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

    // MARK: - resolve(_:chapters:)

    private func chapter(_ blocks: [MarkdownBlock]) -> BookChapter {
        BookChapter(title: "Chapter", text: "", blocks: blocks)
    }

    /// The regression QA caught on PR #369: a pre-#360 bookmark's second component is
    /// a flat-text character offset, not a block index. Resolving it must land on the
    /// block that offset actually falls in — not clamp to the chapter's last block,
    /// which is what happens if the offset is mistaken for a (much smaller) block
    /// index and handed straight to `BlockPaginator.pageIndex(containingBlockIndex:in:)`.
    func testResolveLegacyCharOffsetFormatMapsToItsContainingBlockNotChapterEnd() {
        let blocks = [
            paragraph("Once upon a time,"),
            paragraph("there lived a lighthouse keeper"),
            paragraph("who kept a very tidy log."),
            paragraph("Years passed quietly."),
            paragraph("The end."),
        ]
        let chapters = [chapter(blocks)]
        let offsetIntoThirdBlock = blocks[0...1].reduce(0) { $0 + charLength($1) } + 3

        let resolved = EPUBLocator.resolve("Locator:0:\(offsetIntoThirdBlock)", chapters: chapters)

        XCTAssertEqual(resolved?.chapterIndex, 0)
        XCTAssertEqual(resolved?.blockIndex, 2)
        XCTAssertNotEqual(resolved?.blockIndex, blocks.count - 1)
    }

    private func charLength(_ block: MarkdownBlock) -> Int {
        guard case let .paragraph(text) = block else { return 0 }
        return text.reduce(0) { $0 + $1.text.count }
    }

    /// A bookmark saved by #360-onward `ReaderView.addBookmark()` carries the trailing
    /// `":block"` marker and stores a block index directly — it must be used verbatim,
    /// not run back through the char-offset mapping (which would badly misplace it: a
    /// small block index like `3` read as a character offset would resolve to block 0
    /// for any chapter whose early blocks are longer than 3 characters).
    func testResolveBlockIndexFormatUsesTheValueDirectly() {
        let blocks = [
            paragraph("A much longer opening paragraph than three characters."),
            paragraph("Second."),
            paragraph("Third."),
            paragraph("Fourth — the intended target."),
        ]
        let chapters = [chapter(blocks)]

        let resolved = EPUBLocator.resolve("Locator:0:3:block", chapters: chapters)

        XCTAssertEqual(resolved?.chapterIndex, 0)
        XCTAssertEqual(resolved?.blockIndex, 3)
    }

    func testResolveUsesTheChapterIndexComponentRegardlessOfFormat() {
        let chapters = [chapter([paragraph("Chapter zero.")]), chapter([paragraph("Chapter one."), paragraph("Second block.")])]

        let legacy = EPUBLocator.resolve("Locator:1:0", chapters: chapters)
        let blockIndexed = EPUBLocator.resolve("Locator:1:1:block", chapters: chapters)

        XCTAssertEqual(legacy?.chapterIndex, 1)
        XCTAssertEqual(blockIndexed?.chapterIndex, 1)
        XCTAssertEqual(blockIndexed?.blockIndex, 1)
    }

    func testResolveReturnsNilForNonLocatorString() {
        XCTAssertNil(EPUBLocator.resolve("scroll:0.5", chapters: [chapter([paragraph("Text.")])]))
    }

    func testResolveReturnsNilForOutOfRangeChapterIndex() {
        let chapters = [chapter([paragraph("Only chapter.")])]
        XCTAssertNil(EPUBLocator.resolve("Locator:5:0", chapters: chapters))
    }

    func testResolveReturnsNilForMalformedComponents() {
        let chapters = [chapter([paragraph("Only chapter.")])]
        XCTAssertNil(EPUBLocator.resolve("Locator:not-a-number:0", chapters: chapters))
        XCTAssertNil(EPUBLocator.resolve("Locator:0", chapters: chapters))
    }
}
