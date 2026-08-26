import XCTest
@testable import LibraVault

/// `NarrationSegmenter.segments(for:baseKind:)`'s per-block behaviour is already
/// covered indirectly via `MarkdownDocumentParserTests` (through
/// `chaptersForNarration`, which now delegates to this type — see #635's PR
/// description for why the logic moved here). These tests target
/// `segments(forBlocks:)` directly — the new entry point `EPUBParser`/
/// `VaultEPUBParser` use, which has no chapter-splitting behaviour of its own.
final class NarrationSegmenterTests: XCTestCase {

    func testSegmentsForBlocksPreservesEmphasisAndInsertsParagraphPauseBetweenBlocks() {
        let blocks: [MarkdownBlock] = [
            .heading(level: 1, text: [MarkdownInlineRun(text: "Title", bold: false, italic: false, code: false)]),
            .paragraph(text: [
                MarkdownInlineRun(text: "Plain ", bold: false, italic: false, code: false),
                MarkdownInlineRun(text: "bold", bold: true, italic: false, code: false),
                MarkdownInlineRun(text: " text.", bold: false, italic: false, code: false),
            ]),
        ]

        let segments = NarrationSegmenter.segments(forBlocks: blocks)

        XCTAssertEqual(segments, [
            NarrationSegment(text: "Title", kind: .heading, pauseBefore: .paragraph),
            NarrationSegment(text: "Plain ", kind: .plain, pauseBefore: .paragraph),
            NarrationSegment(text: "bold", kind: .emphasis, pauseBefore: .none),
            NarrationSegment(text: " text.", kind: .plain, pauseBefore: .none),
        ])
    }

    /// A `.thematicBreak` carries no text of its own — its scene-break pause must
    /// attach to whichever segment comes next, not be silently dropped the way a
    /// naive per-block walk (with no pending-pause carry-over) would.
    func testSegmentsForBlocksAttachesSceneBreakPauseToNextSegment() {
        let blocks: [MarkdownBlock] = [
            .paragraph(text: [MarkdownInlineRun(text: "Before.", bold: false, italic: false, code: false)]),
            .thematicBreak,
            .paragraph(text: [MarkdownInlineRun(text: "After.", bold: false, italic: false, code: false)]),
        ]

        let segments = NarrationSegmenter.segments(forBlocks: blocks)

        XCTAssertEqual(segments, [
            NarrationSegment(text: "Before.", kind: .plain, pauseBefore: .none),
            NarrationSegment(text: "After.", kind: .plain, pauseBefore: .sceneBreak),
        ])
    }

    func testSegmentsForBlocksMarksBlockQuoteContentAsQuote() {
        let blocks: [MarkdownBlock] = [
            .blockQuote(blocks: [
                .paragraph(text: [MarkdownInlineRun(text: "Quoted words.", bold: false, italic: false, code: false)]),
            ]),
        ]

        let segments = NarrationSegmenter.segments(forBlocks: blocks)

        XCTAssertEqual(segments, [
            NarrationSegment(text: "Quoted words.", kind: .quote, pauseBefore: .paragraph),
        ])
    }

    func testSegmentsForBlocksSpeaksImageAltTextAndSkipsSilentBlockTypes() {
        let blocks: [MarkdownBlock] = [
            .image(url: "cover.png", altText: "A cover"),
            .codeBlock(code: "let x = 1", language: "swift"),
            .table(headers: [], rows: []),
        ]

        let segments = NarrationSegmenter.segments(forBlocks: blocks)

        XCTAssertEqual(segments, [NarrationSegment(text: "A cover", kind: .plain, pauseBefore: .none)])
    }

    func testSegmentsForBlocksReturnsEmptyForEmptyInput() {
        XCTAssertEqual(NarrationSegmenter.segments(forBlocks: []), [])
    }
}
