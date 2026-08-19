import XCTest
@testable import LibraVault

final class TextPaginatorTests: XCTestCase {

    private let font = UIFont.systemFont(ofSize: 16)

    // MARK: - paginate

    func testEmptyStringProducesZeroPages() {
        let pages = TextPaginator.paginate(text: "", font: font, lineSpacing: 4, pageSize: CGSize(width: 300, height: 500))
        XCTAssertEqual(pages.count, 0)
    }

    func testShortStringAtLargePageSizeProducesExactlyOnePage() {
        let text = "A short paragraph that easily fits on one page."
        let pages = TextPaginator.paginate(text: text, font: font, lineSpacing: 4, pageSize: CGSize(width: 800, height: 1200))
        XCTAssertEqual(pages.count, 1)
        XCTAssertEqual(pages.first, text.startIndex..<text.endIndex)
    }

    func testLongTextAtSmallPageSizeProducesMultiplePages() {
        let text = String(repeating: "The quick brown fox jumps over the lazy dog. ", count: 200)
        let pages = TextPaginator.paginate(text: text, font: font, lineSpacing: 4, pageSize: CGSize(width: 300, height: 120))
        XCTAssertGreaterThan(pages.count, 1)
    }

    func testPagesAreContiguousAndCoverTheWholeText() {
        let text = String(repeating: "The quick brown fox jumps over the lazy dog. ", count: 200)
        let pages = TextPaginator.paginate(text: text, font: font, lineSpacing: 4, pageSize: CGSize(width: 300, height: 120))

        XCTAssertEqual(pages.first?.lowerBound, text.startIndex)
        XCTAssertEqual(pages.last?.upperBound, text.endIndex)
        for (previous, next) in zip(pages, pages.dropFirst()) {
            XCTAssertEqual(previous.upperBound, next.lowerBound, "pages must be back-to-back with no gap or overlap")
        }

        let reassembled = pages.map { String(text[$0]) }.joined()
        XCTAssertEqual(reassembled, text)
    }

    // A pageSize far smaller than a single glyph at this font size cannot fit any
    // content — TextPaginator must fall back to one page holding everything left
    // rather than looping forever adding zero-length containers.
    func testTinyPageSizeFallsBackToOnePageInsteadOfLooping() {
        let text = "Some text that cannot fit inside a one-point-square page container."
        let pages = TextPaginator.paginate(text: text, font: font, lineSpacing: 4, pageSize: CGSize(width: 1, height: 1))
        XCTAssertEqual(pages.count, 1)
        XCTAssertEqual(pages.first, text.startIndex..<text.endIndex)
    }

    // MARK: - startOffset / pageIndex(containingOffset:) round trip

    func testOffsetRoundTripSurvivesRepaginationAtADifferentSizeAndFont() {
        let text = String(repeating: "The quick brown fox jumps over the lazy dog. ", count: 200)
        let originalPages = TextPaginator.paginate(text: text, font: font, lineSpacing: 4, pageSize: CGSize(width: 300, height: 120))
        XCTAssertGreaterThan(originalPages.count, 2, "need a middle page for this test to be meaningful")

        let anchorPage = originalPages[1]
        let offset = TextPaginator.startOffset(of: anchorPage, in: text)
        let anchorStartIndex = text.index(text.startIndex, offsetBy: offset)

        // A different font size, line spacing, and page size than the original —
        // simulating a font-size slider drag or device rotation.
        let biggerFont = UIFont.systemFont(ofSize: 22)
        let resizedPages = TextPaginator.paginate(text: text, font: biggerFont, lineSpacing: 6, pageSize: CGSize(width: 340, height: 200))

        guard let locatedIndex = TextPaginator.pageIndex(containingOffset: offset, in: text, pages: resizedPages) else {
            return XCTFail("expected a page containing the captured offset")
        }
        XCTAssertTrue(resizedPages[locatedIndex].contains(anchorStartIndex))
    }

    func testStartOffsetOfFirstPageIsZero() {
        let text = String(repeating: "Some prose. ", count: 50)
        let pages = TextPaginator.paginate(text: text, font: font, lineSpacing: 4, pageSize: CGSize(width: 300, height: 120))
        XCTAssertEqual(TextPaginator.startOffset(of: pages[0], in: text), 0)
    }

    func testPageIndexContainingOffsetReturnsNilForEmptyPages() {
        XCTAssertNil(TextPaginator.pageIndex(containingOffset: 0, in: "text", pages: []))
    }

    func testPageIndexContainingOffsetBeyondTextFallsBackToLastPage() {
        let text = "short text"
        let pages = TextPaginator.paginate(text: text, font: font, lineSpacing: 4, pageSize: CGSize(width: 800, height: 1200))
        let index = TextPaginator.pageIndex(containingOffset: 9_999, in: text, pages: pages)
        XCTAssertEqual(index, pages.count - 1)
    }
}
