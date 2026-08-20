import UIKit
import XCTest
@testable import LibraVault

final class BlockPaginatorTests: XCTestCase {

    private let font = UIFont.systemFont(ofSize: 16)
    private let smallPageSize = CGSize(width: 200, height: 300)

    private func run(text: String) -> MarkdownBlock {
        .paragraph(text: [MarkdownInlineRun(text: text, bold: false, italic: false, code: false)])
    }

    /// Renders 1x1-pixel-per-point PNG data for an image of the given pixel size —
    /// enough for `UIImage(data:)` to report a real `size` for aspect-ratio math,
    /// without needing a real asset fixture on disk.
    private func imageData(width: CGFloat, height: CGFloat) -> Data {
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: width, height: height))
        let image = renderer.image { context in
            UIColor.red.setFill()
            context.fill(CGRect(x: 0, y: 0, width: width, height: height))
        }
        return image.pngData()!
    }

    // MARK: - paginate(blocks:images:font:lineSpacing:pageSize:)

    func testEmptyBlocksProducesNoPages() {
        let pages = BlockPaginator.paginate(blocks: [], images: [:], font: font, lineSpacing: 4, pageSize: smallPageSize)
        XCTAssertTrue(pages.isEmpty)
    }

    func testZeroSizePageSizeProducesNoPages() {
        let pages = BlockPaginator.paginate(blocks: [run(text: "Hello")], images: [:], font: font, lineSpacing: 4, pageSize: .zero)
        XCTAssertTrue(pages.isEmpty)
    }

    func testSingleShortParagraphProducesExactlyOnePage() {
        let pages = BlockPaginator.paginate(blocks: [run(text: "Hello, world.")], images: [:], font: font, lineSpacing: 4, pageSize: smallPageSize)
        XCTAssertEqual(pages.count, 1)
        XCTAssertEqual(pages.first?.count, 1)
    }

    func testManyParagraphsAtSmallPageSizeProduceMultiplePages() {
        let longText = Array(repeating: "The quick brown fox jumps over the lazy dog.", count: 60)
        let blocks = longText.map { run(text: $0) }

        let pages = BlockPaginator.paginate(blocks: blocks, images: [:], font: font, lineSpacing: 4, pageSize: smallPageSize)

        XCTAssertGreaterThan(pages.count, 1)
    }

    /// Every block from the input must appear exactly once, in order, across the
    /// returned pages — pagination redistributes blocks onto pages, it never drops,
    /// duplicates, or reorders them.
    func testPagesContainEveryBlockExactlyOnceInReadingOrder() {
        let blocks = (0..<40).map { run(text: "Paragraph number \($0), with enough words to take up real space on the page.") }

        let pages = BlockPaginator.paginate(blocks: blocks, images: [:], font: font, lineSpacing: 4, pageSize: smallPageSize)

        XCTAssertGreaterThan(pages.count, 1, "test needs multiple pages to be meaningful")
        XCTAssertEqual(pages.flatMap { $0 }, blocks)
    }

    /// A bigger pageSize should never produce *more* pages than a smaller one for the
    /// same blocks — more room to lay blocks out can only reduce or hold the page
    /// count steady. Mirrors TextPaginatorTests' identically-named property.
    func testLargerPageSizeProducesFewerOrEqualPages() {
        let blocks = (0..<30).map { run(text: "More room should mean fewer pages, never more, block \($0).") }

        let smallPages = BlockPaginator.paginate(blocks: blocks, images: [:], font: font, lineSpacing: 4, pageSize: CGSize(width: 200, height: 150))
        let largePages = BlockPaginator.paginate(blocks: blocks, images: [:], font: font, lineSpacing: 4, pageSize: CGSize(width: 400, height: 900))

        XCTAssertLessThanOrEqual(largePages.count, smallPages.count)
    }

    // MARK: - Image blocks (aspect-ratio-fit height)

    /// A tall (roughly-square, rendered at full page width) image occupies most of a
    /// narrow page on its own — two of them plus inter-block spacing must not fit on
    /// one page together, proving pagination is driven by the image's actual measured
    /// height, not by some fixed "N images per page" assumption.
    func testTallImagesEachTakeTheirOwnPageWhenTwoDoNotFitTogether() {
        let squareImage = imageData(width: 100, height: 100) // renders at width:height = 200:200
        let blocks: [MarkdownBlock] = [
            .image(url: "a.png", altText: "A"),
            .image(url: "b.png", altText: "B"),
        ]
        let images = ["a.png": squareImage, "b.png": squareImage]

        let pages = BlockPaginator.paginate(blocks: blocks, images: images, font: font, lineSpacing: 4, pageSize: smallPageSize)

        XCTAssertEqual(pages.count, 2, "two ~200pt-tall images plus spacing can't fit in a 300pt-tall page together")
        XCTAssertEqual(pages[0], [blocks[0]])
        XCTAssertEqual(pages[1], [blocks[1]])
    }

    /// Small (short-aspect-ratio) images comfortably fit together on one page.
    func testShortImagesFitTogetherOnOnePage() {
        let shortImage = imageData(width: 200, height: 20) // renders at width:height = 200:20
        let blocks: [MarkdownBlock] = [
            .image(url: "a.png", altText: "A"),
            .image(url: "b.png", altText: "B"),
        ]
        let images = ["a.png": shortImage, "b.png": shortImage]

        let pages = BlockPaginator.paginate(blocks: blocks, images: images, font: font, lineSpacing: 4, pageSize: smallPageSize)

        XCTAssertEqual(pages.count, 1)
        XCTAssertEqual(pages.first?.count, 2)
    }

    /// An unresolved image reference (no matching bytes in `images`) must fall back to
    /// a fixed placeholder height rather than crashing or being skipped.
    func testUnresolvedImageDoesNotCrashAndStillProducesAPage() {
        let blocks: [MarkdownBlock] = [.image(url: "missing.png", altText: "Missing")]

        let pages = BlockPaginator.paginate(blocks: blocks, images: [:], font: font, lineSpacing: 4, pageSize: smallPageSize)

        XCTAssertEqual(pages, [blocks])
    }

    // MARK: - Degenerate oversized block (issue #359 acceptance criterion)

    /// A single block taller than pageSize (a huge image) must still produce a page —
    /// not hang or infinite-loop — mirroring `TextPaginatorTests`'
    /// `testDegeneratelySmallPageSizeFallsBackToOnePageOfEverything`. Test completion
    /// itself is part of the assertion: a looping implementation would time out here.
    func testOversizedSingleImageProducesItsOwnPageRatherThanHanging() {
        let hugeImage = imageData(width: 10, height: 10_000) // renders far taller than any reasonable page
        let blocks: [MarkdownBlock] = [
            run(text: "Before."),
            .image(url: "huge.png", altText: "Huge"),
            run(text: "After."),
        ]
        let images = ["huge.png": hugeImage]

        let pages = BlockPaginator.paginate(blocks: blocks, images: images, font: font, lineSpacing: 4, pageSize: smallPageSize)

        XCTAssertEqual(pages.flatMap { $0 }, blocks, "no block should be dropped even though the image overflows its page")
        guard let imagePageIndex = pages.firstIndex(where: { $0.contains(blocks[1]) }) else {
            return XCTFail("expected the huge image to land on some page")
        }
        XCTAssertEqual(pages[imagePageIndex], [blocks[1]], "an oversized block gets a page to itself, not squeezed alongside neighbours")
    }

    // MARK: - pageIndex(containingBlockIndex:in:) / firstBlockIndex(ofPage:in:)

    func testPageIndexOnEmptyPaginationReturnsZero() {
        XCTAssertEqual(BlockPaginator.pageIndex(containingBlockIndex: 0, in: []), 0)
    }

    func testPageIndexFindsThePageContainingAKnownBlockIndex() {
        let blocks = (0..<40).map { run(text: "Paragraph number \($0), with enough words to take up real space on the page.") }
        let pages = BlockPaginator.paginate(blocks: blocks, images: [:], font: font, lineSpacing: 4, pageSize: smallPageSize)
        XCTAssertGreaterThan(pages.count, 1, "test needs multiple pages to be meaningful")

        let targetPageIndex = 1
        let blockIndex = BlockPaginator.firstBlockIndex(ofPage: targetPageIndex, in: pages)
        XCTAssertEqual(BlockPaginator.pageIndex(containingBlockIndex: blockIndex, in: pages), targetPageIndex)
    }

    func testPageIndexClampsToTheLastPageWhenBlockIndexIsPastTheEnd() {
        let blocks = (0..<40).map { run(text: "Paragraph number \($0), with enough words to take up real space on the page.") }
        let pages = BlockPaginator.paginate(blocks: blocks, images: [:], font: font, lineSpacing: 4, pageSize: smallPageSize)
        let pastEndIndex = blocks.count + 1000

        XCTAssertEqual(BlockPaginator.pageIndex(containingBlockIndex: pastEndIndex, in: pages), pages.count - 1)
    }

    func testFirstBlockIndexOfFirstPageIsZero() {
        let blocks = (0..<10).map { run(text: "Paragraph \($0).") }
        let pages = BlockPaginator.paginate(blocks: blocks, images: [:], font: font, lineSpacing: 4, pageSize: smallPageSize)

        XCTAssertEqual(BlockPaginator.firstBlockIndex(ofPage: 0, in: pages), 0)
    }

    func testFirstBlockIndexOutOfBoundsReturnsZero() {
        let blocks = [run(text: "Only paragraph.")]
        let pages = BlockPaginator.paginate(blocks: blocks, images: [:], font: font, lineSpacing: 4, pageSize: smallPageSize)

        XCTAssertEqual(BlockPaginator.firstBlockIndex(ofPage: -1, in: pages), 0)
        XCTAssertEqual(BlockPaginator.firstBlockIndex(ofPage: pages.count + 5, in: pages), 0)
    }

    /// paginate at one size/font, capture the flat block index a page starts at (what
    /// `ReaderView.addBookmark` stores and `repaginate(for:)` anchors on before a
    /// repagination), repaginate the *same blocks* at a different size/font (as
    /// happens when Reading Settings change or the device rotates), and confirm the
    /// located page in the new pagination still contains that block — this is what
    /// keeps the reader's visible position stable across a repagination. Reuses the
    /// exact text/size/font values `TextPaginatorTests.testLocatorRoundTripsAcross-
    /// RepaginationAtADifferentSizeAndFont` already proved produce multiple, differing
    /// page counts, one paragraph per block instead of one flat string.
    func testBlockAnchorRoundTripsAcrossRepaginationAtADifferentSizeAndFont() {
        let blocks = (0..<400).map { _ in run(text: "The reader's position must survive a repagination. ") }

        let originalPages = BlockPaginator.paginate(
            blocks: blocks, images: [:], font: UIFont.systemFont(ofSize: 16), lineSpacing: 4,
            pageSize: CGSize(width: 320, height: 480)
        )
        XCTAssertGreaterThan(originalPages.count, 3, "test needs multiple original pages to be meaningful")

        let anchorPageIndex = 2
        let anchorBlockIndex = BlockPaginator.firstBlockIndex(ofPage: anchorPageIndex, in: originalPages)

        // A bigger font in a smaller container — both axes of what can trigger a
        // repagination in the real reader (a Reading Settings change + a resize).
        let repaginatedPages = BlockPaginator.paginate(
            blocks: blocks, images: [:], font: UIFont.systemFont(ofSize: 22), lineSpacing: 8,
            pageSize: CGSize(width: 240, height: 360)
        )
        XCTAssertNotEqual(
            repaginatedPages.count, originalPages.count,
            "test needs the page count to actually shift for this to be meaningful"
        )

        let locatedPageIndex = BlockPaginator.pageIndex(containingBlockIndex: anchorBlockIndex, in: repaginatedPages)
        let locatedBlocks = repaginatedPages[locatedPageIndex]
        let locatedStart = BlockPaginator.firstBlockIndex(ofPage: locatedPageIndex, in: repaginatedPages)
        let locatedEnd = locatedStart + locatedBlocks.count

        XCTAssertTrue(
            (locatedStart..<locatedEnd).contains(anchorBlockIndex),
            "located page [\(locatedStart), \(locatedEnd)) must contain the anchor block index \(anchorBlockIndex)"
        )
    }

    // MARK: - Every MarkdownBlock case is handled (smoke coverage)

    /// Every case of the (non-image/paragraph) block model must be measurable without
    /// crashing — this is as much a compile-time exhaustiveness check (a new
    /// `MarkdownBlock` case would fail to build `BlockPaginator`'s switch) as a
    /// runtime one.
    func testEveryBlockKindPaginatesWithoutCrashing() {
        let blocks: [MarkdownBlock] = [
            .heading(level: 1, text: [MarkdownInlineRun(text: "Title", bold: false, italic: false, code: false)]),
            run(text: "A paragraph."),
            .codeBlock(code: "let x = 1", language: "swift"),
            .blockQuote(blocks: [run(text: "Quoted.")]),
            .unorderedList(items: [[run(text: "Item one")], [run(text: "Item two")]]),
            .orderedList(items: [[run(text: "First")], [run(text: "Second")]], start: 1),
            .thematicBreak,
            .table(
                headers: [[MarkdownInlineRun(text: "H1", bold: false, italic: false, code: false)]],
                rows: [[[MarkdownInlineRun(text: "R1", bold: false, italic: false, code: false)]]]
            ),
            .mermaidDiagram(source: "graph TD; A-->B;"),
        ]

        let pages = BlockPaginator.paginate(blocks: blocks, images: [:], font: font, lineSpacing: 4, pageSize: smallPageSize)

        XCTAssertEqual(pages.flatMap { $0 }, blocks)
    }
}
