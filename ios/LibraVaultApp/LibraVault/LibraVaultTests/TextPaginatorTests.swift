import XCTest
@testable import LibraVault

final class TextPaginatorTests: XCTestCase {

    private let font = UIFont.systemFont(ofSize: 16)
    private let smallPageSize = CGSize(width: 200, height: 100)

    // MARK: - paginate(text:font:lineSpacing:pageSize:)

    func testEmptyTextProducesNoPages() {
        let pages = TextPaginator.paginate(text: "", font: font, lineSpacing: 4, pageSize: smallPageSize)
        XCTAssertTrue(pages.isEmpty)
    }

    func testShortTextProducesExactlyOnePage() {
        let pages = TextPaginator.paginate(text: "Hello, world.", font: font, lineSpacing: 4, pageSize: smallPageSize)
        XCTAssertEqual(pages.count, 1)
    }

    func testLongTextAtSmallPageSizeProducesMultiplePages() {
        let longText = Array(repeating: "The quick brown fox jumps over the lazy dog. ", count: 200).joined()
        let pages = TextPaginator.paginate(text: longText, font: font, lineSpacing: 4, pageSize: smallPageSize)
        XCTAssertGreaterThan(pages.count, 1)
    }

    func testZeroSizePageSizeProducesNoPages() {
        let pages = TextPaginator.paginate(text: "Some real text.", font: font, lineSpacing: 4, pageSize: .zero)
        XCTAssertTrue(pages.isEmpty)
    }

    /// A pageSize too small to fit even one line must fall back to a single page
    /// covering everything, not spin forever adding empty containers — see
    /// paginate(text:font:lineSpacing:pageSize:)'s zero-glyph-container guard.
    func testDegeneratelySmallPageSizeFallsBackToOnePageOfEverything() {
        let text = "Some text that cannot possibly fit in a one-point-tall container."
        let pages = TextPaginator.paginate(text: text, font: font, lineSpacing: 4, pageSize: CGSize(width: 1, height: 1))

        guard let onlyPage = pages.first else {
            return XCTFail("expected a single fallback page, got none")
        }
        XCTAssertEqual(pages.count, 1)
        XCTAssertEqual(onlyPage.lowerBound, text.startIndex)
        XCTAssertEqual(onlyPage.upperBound, text.endIndex)
    }

    /// A container tall enough for one real line, but not for that line plus a
    /// full `lineSpacing` gap, must still paginate normally rather than falling
    /// back to a single page holding the whole remaining chapter — see the
    /// degenerate-pageSize guard's comment in `paginate(text:font:lineSpacing:pageSize:)`.
    /// This is the exact narrow window issue #337 tightened the guard for.
    func testPageSizeBetweenLineHeightAndLineHeightPlusSpacingPaginatesNormally() {
        let lineSpacing: CGFloat = 4
        let pageHeight = font.lineHeight + (lineSpacing / 2)
        let text = Array(repeating: "Word ", count: 200).joined()

        let pages = TextPaginator.paginate(
            text: text, font: font, lineSpacing: lineSpacing,
            pageSize: CGSize(width: 200, height: pageHeight)
        )

        XCTAssertGreaterThan(
            pages.count, 1,
            "a container tall enough for one line should paginate normally, not fall back to a single page"
        )
        XCTAssertLessThan(
            pages[0].upperBound, text.endIndex,
            "first page should hold only what actually fits one line, not the whole remaining text"
        )
    }

    /// Page ranges must exactly cover the source text once each — no gaps, no
    /// overlaps, no dropped characters — regardless of how many pages that took.
    func testPagesCoverTheEntireTextContiguouslyWithNoGapsOrOverlaps() {
        let longText = Array(repeating: "Pagination should not drop or duplicate any text. ", count: 100).joined()
        let pages = TextPaginator.paginate(text: longText, font: font, lineSpacing: 4, pageSize: smallPageSize)

        guard let first = pages.first, let last = pages.last else {
            return XCTFail("expected multiple pages, got none")
        }
        XCTAssertGreaterThan(pages.count, 1, "test needs multiple pages to be meaningful")
        XCTAssertEqual(first.lowerBound, longText.startIndex)
        XCTAssertEqual(last.upperBound, longText.endIndex)
        for (previous, next) in zip(pages, pages.dropFirst()) {
            XCTAssertEqual(previous.upperBound, next.lowerBound, "adjacent pages must share a boundary exactly")
        }
    }

    /// A bigger pageSize should never produce *more* pages than a smaller one for
    /// the same text/font — more room to lay text out can only reduce or hold the
    /// page count steady.
    func testLargerPageSizeProducesFewerOrEqualPages() {
        let text = Array(repeating: "More room should mean fewer pages, never more. ", count: 100).joined()
        let smallPages = TextPaginator.paginate(text: text, font: font, lineSpacing: 4, pageSize: CGSize(width: 200, height: 150))
        let largePages = TextPaginator.paginate(text: text, font: font, lineSpacing: 4, pageSize: CGSize(width: 400, height: 600))

        XCTAssertLessThanOrEqual(largePages.count, smallPages.count)
    }

    // MARK: - pageIndex(containing:in:text:)

    func testPageIndexOnEmptyPaginationReturnsZero() {
        XCTAssertEqual(TextPaginator.pageIndex(containing: 0, in: [], text: ""), 0)
    }

    func testPageIndexFindsThePageContainingAKnownOffset() {
        let text = Array(repeating: "Word ", count: 500).joined()
        let pages = TextPaginator.paginate(text: text, font: font, lineSpacing: 4, pageSize: smallPageSize)
        XCTAssertGreaterThan(pages.count, 2, "test needs multiple pages to be meaningful")

        let targetPageIndex = 1
        let offset = text.distance(from: text.startIndex, to: pages[targetPageIndex].lowerBound)
        XCTAssertEqual(TextPaginator.pageIndex(containing: offset, in: pages, text: text), targetPageIndex)
    }

    func testPageIndexClampsToTheLastPageWhenOffsetIsPastTheEnd() {
        let text = Array(repeating: "Word ", count: 500).joined()
        let pages = TextPaginator.paginate(text: text, font: font, lineSpacing: 4, pageSize: smallPageSize)
        let pastEndOffset = text.count + 1000

        XCTAssertEqual(TextPaginator.pageIndex(containing: pastEndOffset, in: pages, text: text), pages.count - 1)
    }

    // MARK: - Locator round-trip (issue #331 acceptance criteria)

    /// paginate at one size/font, capture the character offset a page starts at
    /// (what ReaderView.addBookmark stores, and what repaginate(for:) anchors on
    /// before a repagination), repaginate the *same text* at a different size and
    /// font (as happens when Reading Settings change or the device rotates), and
    /// confirm the located page in the new pagination still contains that offset —
    /// this is what keeps the reader's visible position stable across a
    /// repagination instead of jumping to a random spot in the chapter.
    func testLocatorRoundTripsAcrossRepaginationAtADifferentSizeAndFont() {
        let text = Array(repeating: "The reader's position must survive a repagination. ", count: 400).joined()

        let originalPages = TextPaginator.paginate(
            text: text,
            font: UIFont.systemFont(ofSize: 16),
            lineSpacing: 4,
            pageSize: CGSize(width: 320, height: 480)
        )
        XCTAssertGreaterThan(originalPages.count, 3, "test needs multiple original pages to be meaningful")

        let anchorPageIndex = 2
        let anchorOffset = text.distance(from: text.startIndex, to: originalPages[anchorPageIndex].lowerBound)

        // A bigger font in a smaller container — both axes of what can trigger a
        // repagination in the real reader (a Reading Settings change + a resize).
        let repaginatedPages = TextPaginator.paginate(
            text: text,
            font: UIFont.systemFont(ofSize: 22),
            lineSpacing: 8,
            pageSize: CGSize(width: 240, height: 360)
        )
        XCTAssertNotEqual(
            repaginatedPages.count, originalPages.count,
            "test needs the page count to actually shift for this to be meaningful"
        )

        let locatedPageIndex = TextPaginator.pageIndex(containing: anchorOffset, in: repaginatedPages, text: text)
        let locatedRange = repaginatedPages[locatedPageIndex]
        let locatedLowerOffset = text.distance(from: text.startIndex, to: locatedRange.lowerBound)
        let locatedUpperOffset = text.distance(from: text.startIndex, to: locatedRange.upperBound)

        XCTAssertTrue(
            (locatedLowerOffset..<locatedUpperOffset).contains(anchorOffset),
            "located page [\(locatedLowerOffset), \(locatedUpperOffset)) must contain the anchor offset \(anchorOffset)"
        )
    }

    /// Same round-trip, but anchored on the very first page (offset 0) — a boundary
    /// case the general loop in pageIndex(containing:in:text:) must also get right,
    /// since it's the offset every chapter's very first page-turn will anchor on.
    func testLocatorRoundTripsWhenAnchoredOnTheFirstPage() {
        let text = Array(repeating: "Beginning of the chapter stays put across settings changes. ", count: 300).joined()

        let originalPages = TextPaginator.paginate(
            text: text, font: UIFont.systemFont(ofSize: 14), lineSpacing: 2,
            pageSize: CGSize(width: 300, height: 500)
        )
        XCTAssertFalse(originalPages.isEmpty)
        let anchorOffset = text.distance(from: text.startIndex, to: originalPages[0].lowerBound)
        XCTAssertEqual(anchorOffset, 0)

        let repaginatedPages = TextPaginator.paginate(
            text: text, font: UIFont.systemFont(ofSize: 20), lineSpacing: 6,
            pageSize: CGSize(width: 200, height: 300)
        )

        XCTAssertEqual(TextPaginator.pageIndex(containing: anchorOffset, in: repaginatedPages, text: text), 0)
    }
}
