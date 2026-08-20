import XCTest
@testable import LibraVault

final class EPUBLocatorTests: XCTestCase {

    private func run(_ text: String) -> MarkdownInlineRun {
        MarkdownInlineRun(text: text, bold: false, italic: false, code: false)
    }

    private func paragraph(_ text: String) -> MarkdownBlock {
        .paragraph(text: [run(text)])
    }

    // MARK: - blockIndex(forCharOffset:in:)

    func testEmptyBlocksResolvesToZero() {
        XCTAssertEqual(EPUBLocator.blockIndex(forCharOffset: 0, in: []), 0)
        XCTAssertEqual(EPUBLocator.blockIndex(forCharOffset: 999, in: []), 0)
    }

    func testOffsetInFirstBlockResolvesToBlockZero() {
        let blocks = [paragraph("Once upon a time."), paragraph("The end.")]
        XCTAssertEqual(EPUBLocator.blockIndex(forCharOffset: 0, in: blocks), 0)
        XCTAssertEqual(EPUBLocator.blockIndex(forCharOffset: 5, in: blocks), 0)
    }

    /// The exact case a "not just chapter-start" acceptance criterion is guarding
    /// against — an offset inside a *later* paragraph must resolve to that paragraph's
    /// block, not fall back to 0.
    func testOffsetInSecondBlockResolvesToBlockOne() {
        let first = "Once upon a time."
        let blocks = [paragraph(first), paragraph("The end.")]
        let offsetIntoSecondBlock = first.count + 2
        XCTAssertEqual(EPUBLocator.blockIndex(forCharOffset: offsetIntoSecondBlock, in: blocks), 1)
    }

    func testOffsetExactlyAtBlockBoundaryLandsInTheNextBlock() {
        let first = "Once upon a time."
        let blocks = [paragraph(first), paragraph("The end.")]
        XCTAssertEqual(EPUBLocator.blockIndex(forCharOffset: first.count, in: blocks), 1)
    }

    /// No exact block-model equivalent for an offset past every block's accumulated
    /// length (e.g. a bookmark from a chapter that has since re-parsed shorter) — must
    /// clamp to the last block rather than crash or go out of bounds.
    func testOffsetPastTheEndClampsToTheLastBlock() {
        let blocks = [paragraph("Short."), paragraph("Also short.")]
        XCTAssertEqual(EPUBLocator.blockIndex(forCharOffset: 10_000, in: blocks), blocks.count - 1)
    }

    func testNegativeOffsetDoesNotCrashAndResolvesToFirstBlock() {
        let blocks = [paragraph("Once upon a time."), paragraph("The end.")]
        XCTAssertEqual(EPUBLocator.blockIndex(forCharOffset: -5, in: blocks), 0)
    }

    func testHeadingContributesItsOwnTextLength() {
        let heading = MarkdownBlock.heading(level: 1, text: [run("Chapter One")])
        let blocks = [heading, paragraph("Body text goes here.")]
        let offsetIntoBody = "Chapter One".count + 3
        XCTAssertEqual(EPUBLocator.blockIndex(forCharOffset: offsetIntoBody, in: blocks), 1)
    }

    /// An offset landing inside nested list content must resolve to the list's own
    /// block index, not skip past it — a top-level-only length count would treat every
    /// list as zero-length and misattribute offsets that actually fall inside one.
    func testOffsetInsideListItemResolvesToTheListBlock() {
        let intro = paragraph("Ingredients:")
        let list = MarkdownBlock.unorderedList(items: [
            [paragraph("Flour")],
            [paragraph("Sugar")],
        ])
        let outro = paragraph("Method follows.")
        let blocks = [intro, list, outro]

        let offsetIntoList = "Ingredients:".count + 2
        XCTAssertEqual(EPUBLocator.blockIndex(forCharOffset: offsetIntoList, in: blocks), 1)
    }

    /// Same shape, for `blockQuote` — its nested blocks must count toward its own
    /// length rather than being ignored (which would misattribute offsets to whatever
    /// block happens to follow it).
    func testOffsetInsideBlockQuoteResolvesToTheQuoteBlock() {
        let intro = paragraph("As they say:")
        let quote = MarkdownBlock.blockQuote(blocks: [paragraph("Fortune favors the bold.")])
        let outro = paragraph("Words to live by.")
        let blocks = [intro, quote, outro]

        let offsetIntoQuote = "As they say:".count + 2
        XCTAssertEqual(EPUBLocator.blockIndex(forCharOffset: offsetIntoQuote, in: blocks), 1)
    }

    /// An `.image` block with no text of its own must not be skipped over silently —
    /// zero length is correct (there's nothing to land inside), but the block after it
    /// must still resolve correctly once its own length is accounted for.
    func testImageBlockContributesAltTextLength() {
        let blocks: [MarkdownBlock] = [
            .image(url: "cover.png", altText: "Cover"),
            paragraph("Story begins."),
        ]
        XCTAssertEqual(EPUBLocator.blockIndex(forCharOffset: 0, in: blocks), 0)
        let offsetIntoSecond = "Cover".count + 1
        XCTAssertEqual(EPUBLocator.blockIndex(forCharOffset: offsetIntoSecond, in: blocks), 1)
    }

    /// A `.thematicBreak` contributes no text, so an offset that would otherwise land
    /// exactly on it instead falls through to the next real block.
    func testThematicBreakContributesNoLengthAndIsSkippedOver() {
        let blocks: [MarkdownBlock] = [paragraph("Before."), .thematicBreak, paragraph("After.")]
        let offsetAtBreak = "Before.".count
        XCTAssertEqual(EPUBLocator.blockIndex(forCharOffset: offsetAtBreak, in: blocks), 2)
    }
}
