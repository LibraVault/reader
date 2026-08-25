import XCTest
@testable import LibraVault

final class MarkdownDocumentParserTests: XCTestCase {

    func testParsesHeadingLevelsAndText() {
        let blocks = MarkdownDocumentParser.parse("# Title\n## Subtitle\n###### Deepest")

        XCTAssertEqual(blocks.count, 3)
        XCTAssertEqual(blocks[0], .heading(level: 1, text: [MarkdownInlineRun(text: "Title", bold: false, italic: false, code: false)]))
        XCTAssertEqual(blocks[1], .heading(level: 2, text: [MarkdownInlineRun(text: "Subtitle", bold: false, italic: false, code: false)]))
        XCTAssertEqual(blocks[2], .heading(level: 6, text: [MarkdownInlineRun(text: "Deepest", bold: false, italic: false, code: false)]))
    }

    func testParsesPlainParagraph() {
        let blocks = MarkdownDocumentParser.parse("Just a paragraph.")

        XCTAssertEqual(blocks, [.paragraph(text: [MarkdownInlineRun(text: "Just a paragraph.", bold: false, italic: false, code: false)])])
    }

    func testParsesBoldAndItalicRuns() {
        let blocks = MarkdownDocumentParser.parse("Some **bold** and *italic* and `code`.")

        guard case let .paragraph(runs)? = blocks.first else {
            return XCTFail("expected a paragraph block")
        }
        XCTAssertTrue(runs.contains(MarkdownInlineRun(text: "bold", bold: true, italic: false, code: false)))
        XCTAssertTrue(runs.contains(MarkdownInlineRun(text: "italic", bold: false, italic: true, code: false)))
        XCTAssertTrue(runs.contains(MarkdownInlineRun(text: "code", bold: false, italic: false, code: true)))
    }

    func testNestedBoldItalicCombinesBothStyles() {
        let blocks = MarkdownDocumentParser.parse("***both***")

        guard case let .paragraph(runs)? = blocks.first else {
            return XCTFail("expected a paragraph block")
        }
        XCTAssertEqual(runs, [MarkdownInlineRun(text: "both", bold: true, italic: true, code: false)])
    }

    func testParsesCodeBlockWithLanguage() {
        let blocks = MarkdownDocumentParser.parse("```swift\nlet x = 1\n```")

        XCTAssertEqual(blocks, [.codeBlock(code: "let x = 1\n", language: "swift")])
    }

    func testParsesCodeBlockWithoutLanguage() {
        let blocks = MarkdownDocumentParser.parse("```\nplain\n```")

        guard case let .codeBlock(_, language)? = blocks.first else {
            return XCTFail("expected a code block")
        }
        XCTAssertNil(language)
    }

    func testParsesBlockQuoteAsNestedBlocks() {
        let blocks = MarkdownDocumentParser.parse("> Quoted text")

        XCTAssertEqual(
            blocks,
            [.blockQuote(blocks: [.paragraph(text: [MarkdownInlineRun(text: "Quoted text", bold: false, italic: false, code: false)])])]
        )
    }

    func testParsesUnorderedListItems() {
        let blocks = MarkdownDocumentParser.parse("- One\n- Two")

        guard case let .unorderedList(items)? = blocks.first else {
            return XCTFail("expected an unordered list")
        }
        XCTAssertEqual(items.count, 2)
        XCTAssertEqual(items[0], [.paragraph(text: [MarkdownInlineRun(text: "One", bold: false, italic: false, code: false)])])
        XCTAssertEqual(items[1], [.paragraph(text: [MarkdownInlineRun(text: "Two", bold: false, italic: false, code: false)])])
    }

    func testParsesOrderedListRespectingStartIndex() {
        let blocks = MarkdownDocumentParser.parse("3. Third\n4. Fourth")

        guard case let .orderedList(items, start)? = blocks.first else {
            return XCTFail("expected an ordered list")
        }
        XCTAssertEqual(start, 3)
        XCTAssertEqual(items.count, 2)
    }

    func testParsesThematicBreak() {
        let blocks = MarkdownDocumentParser.parse("Above\n\n---\n\nBelow")

        XCTAssertTrue(blocks.contains(.thematicBreak))
    }

    // MARK: - Mermaid diagrams (#121)

    func testMermaidFenceParsesAsAMermaidDiagramBlock() {
        let blocks = MarkdownDocumentParser.parse("```mermaid\ngraph TD\n  A --> B\n```")

        XCTAssertEqual(blocks, [.mermaidDiagram(source: "graph TD\n  A --> B\n")])
    }

    func testMermaidLanguageCheckIsCaseSensitive() {
        // Matches GFM's own info-string convention — ```Mermaid``` is an ordinary,
        // unrecognized-language code block, not a diagram (same rule Android's
        // MermaidBlockDetector.mermaidSourceOrNull applies).
        let blocks = MarkdownDocumentParser.parse("```Mermaid\ngraph TD\n```")

        guard case .codeBlock? = blocks.first else {
            return XCTFail("expected an ordinary code block, not a mermaid diagram, for a differently-cased language tag")
        }
    }

    func testOtherLanguagesStillParseAsOrdinaryCodeBlocks() {
        let blocks = MarkdownDocumentParser.parse("```kotlin\nval x = 1\n```")

        XCTAssertEqual(blocks, [.codeBlock(code: "val x = 1\n", language: "kotlin")])
    }

    // MARK: - Tables (#120)

    func testTableParsesHeaderAndBodyRowsAsSeparateCells() {
        let blocks = MarkdownDocumentParser.parse("| A | B |\n|---|---|\n| 1 | 2 |\n| 3 | 4 |")

        guard case let .table(headers, rows)? = blocks.first else {
            return XCTFail("expected a table block")
        }
        XCTAssertEqual(headers.map { $0.map(\.text).joined() }, ["A", "B"])
        XCTAssertEqual(rows.count, 2)
        XCTAssertEqual(rows[0].map { $0.map(\.text).joined() }, ["1", "2"])
        XCTAssertEqual(rows[1].map { $0.map(\.text).joined() }, ["3", "4"])
    }

    func testTableWithNoBodyRowsHasAnEmptyRowsArray() {
        let blocks = MarkdownDocumentParser.parse("| A | B |\n|---|---|")

        guard case let .table(headers, rows)? = blocks.first else {
            return XCTFail("expected a table block")
        }
        XCTAssertEqual(headers.map { $0.map(\.text).joined() }, ["A", "B"])
        XCTAssertTrue(rows.isEmpty)
    }

    func testTableCellSupportsInlineFormattingLikeAnyOtherInlineContainer() {
        let blocks = MarkdownDocumentParser.parse("| Field |\n|---|\n| **Bold** and `code` |")

        guard case let .table(_, rows)? = blocks.first, let firstCell = rows.first?.first else {
            return XCTFail("expected a table block with at least one cell")
        }
        XCTAssertTrue(firstCell.contains(MarkdownInlineRun(text: "Bold", bold: true, italic: false, code: false)))
        XCTAssertTrue(firstCell.contains(MarkdownInlineRun(text: "code", bold: false, italic: false, code: true)))
    }

    /// Direct regression coverage for the bug fixed alongside #120: `.table` is a real
    /// block type MarkdownReaderContent switches over exhaustively, so an unhandled
    /// `.table` case (like the old placeholder-paragraph approach effectively was)
    /// would now fail to compile rather than silently rendering wrong content —
    /// this test exists to keep a plain "no placeholder text anywhere" assertion
    /// alongside that compiler guarantee, in case the case is ever handled by falling
    /// back to `.paragraph` again instead.
    func testTableNeverProducesThePreviousPlaceholderText() {
        let blocks = MarkdownDocumentParser.parse("| A | B |\n|---|---|\n| 1 | 2 |")

        for block in blocks {
            if case let .paragraph(runs) = block {
                XCTAssertFalse(runs.contains { $0.text.contains("Table omitted") })
            }
        }
    }

    func testEmptyDocumentReturnsNoBlocks() {
        XCTAssertEqual(MarkdownDocumentParser.parse(""), [])
    }

    // MARK: - Images

    func testStandaloneImageParsesAsAnImageBlock() {
        let blocks = MarkdownDocumentParser.parse("![a cat](./cat.png)")

        XCTAssertEqual(blocks, [.image(url: "./cat.png", altText: "a cat")])
    }

    func testStandaloneImageWithNoAltTextHasAnEmptyAltText() {
        let blocks = MarkdownDocumentParser.parse("![](./cat.png)")

        XCTAssertEqual(blocks, [.image(url: "./cat.png", altText: "")])
    }

    func testImageInlineAlongsideTextIsNotExtractedAsAnImageBlock() {
        // Deliberately not special-cased for v1 — falls through to plain alt text
        // like any other unhandled inline node, rather than an .image block.
        let blocks = MarkdownDocumentParser.parse("Some text ![a cat](./cat.png) more text")

        XCTAssertEqual(blocks.count, 1)
        guard case .paragraph? = blocks.first else {
            return XCTFail("expected a paragraph, not an image block, for a mixed-content line")
        }
    }

    // MARK: - extractToc

    func testExtractTocFindsHeadingsAtTheirTopLevelBlockIndex() {
        let blocks = MarkdownDocumentParser.parse("# One\nbody\n## Two\nbody\n### Three")
        let toc = MarkdownDocumentParser.extractToc(from: blocks)

        XCTAssertEqual(toc.map(\.title), ["One", "Two", "Three"])
        XCTAssertEqual(toc.map(\.level), [1, 2, 3])
        // Each entry's blockIndex must point back at the exact heading block it names —
        // this is what MarkdownReaderContent's ScrollViewReader.scrollTo(blockIndex:)
        // relies on.
        for entry in toc {
            guard case let .heading(level, text)? = blocks[safe: entry.blockIndex] else {
                return XCTFail("blockIndex \(entry.blockIndex) should point at a heading block")
            }
            XCTAssertEqual(level, entry.level)
            XCTAssertEqual(text.map(\.text).joined(), entry.title)
        }
    }

    func testExtractTocIgnoresNonHeadingBlocks() {
        let blocks = MarkdownDocumentParser.parse("Just a paragraph.\n\n- a list item")
        XCTAssertTrue(MarkdownDocumentParser.extractToc(from: blocks).isEmpty)
    }

    func testExtractTocIgnoresTablesButStillFindsHeadingsAroundThem() {
        let blocks = MarkdownDocumentParser.parse("# Before\n| A | B |\n|---|---|\n| 1 | 2 |\n# After")
        let toc = MarkdownDocumentParser.extractToc(from: blocks)

        XCTAssertEqual(toc.map(\.title), ["Before", "After"])
    }

    func testExtractTocOnEmptyDocumentIsEmpty() {
        XCTAssertTrue(MarkdownDocumentParser.extractToc(from: []).isEmpty)
    }

    // MARK: - chaptersForNarration (#124)

    func testOneChapterPerHeadingSection() {
        let blocks = MarkdownDocumentParser.parse("# One\nFirst body.\n# Two\nSecond body.")
        let chapters = MarkdownDocumentParser.chaptersForNarration(from: blocks)

        XCTAssertEqual(chapters.map(\.title), ["One", "Two"])
        XCTAssertEqual(chapters[0].text, "One\n\nFirst body.")
        XCTAssertEqual(chapters[1].text, "Two\n\nSecond body.")
    }

    func testAHeadinglessDocumentBecomesOneUntitledChapter() {
        let blocks = MarkdownDocumentParser.parse("Just a paragraph, no heading at all.")
        let chapters = MarkdownDocumentParser.chaptersForNarration(from: blocks)

        XCTAssertEqual(chapters.map(\.title), ["Untitled"])
        XCTAssertEqual(chapters.first?.text, "Just a paragraph, no heading at all.")
    }

    func testEmptyDocumentProducesNoChapters() {
        XCTAssertTrue(MarkdownDocumentParser.chaptersForNarration(from: []).isEmpty)
    }

    func testCodeBlocksTablesAndThematicBreaksAreNeverSpoken() {
        // A document consisting ONLY of unspeakable content produces zero chapters —
        // this is the case AppState.startPlayback must guard against (see
        // AppStatePlaybackTests), the Markdown-specific version of the phantom-player
        // bug #112 fixed for "no chapter parser at all".
        let blocks = MarkdownDocumentParser.parse("```\nsome code\n```\n\n---\n\n| A | B |\n|---|---|\n| 1 | 2 |")
        XCTAssertTrue(MarkdownDocumentParser.chaptersForNarration(from: blocks).isEmpty)
    }

    /// Regression test: `.mermaidDiagram` (#121) was merged to dev on a separate
    /// branch from `chaptersForNarration` (#124) — the two PRs landed back to back
    /// without either rebasing onto the other, so `narrationText`'s switch went
    /// non-exhaustive and broke the dev build outright (caught via the very next CI
    /// run, not before merge). A diagram is visual, like a code block or table — it
    /// should be silently skipped, not spoken.
    ///
    /// Deliberately headingless, matching testCodeBlocksTablesAndThematicBreaksAreNeverSpoken
    /// exactly — a heading's own title text is legitimately speakable (see
    /// testOneChapterPerHeadingSection), so a document *with* a heading always
    /// produces at least one chapter regardless of what the body contains.
    func testMermaidDiagramsAreNeverSpoken() {
        let blocks = MarkdownDocumentParser.parse("```mermaid\ngraph TD\n  A --> B\n```")
        XCTAssertTrue(MarkdownDocumentParser.chaptersForNarration(from: blocks).isEmpty)
    }

    func testImageAltTextIsSpokenUnlikeOtherMediaBlocks() {
        let blocks = MarkdownDocumentParser.parse("# Photo\n![A sunset over the ocean](./sunset.png)")
        let chapters = MarkdownDocumentParser.chaptersForNarration(from: blocks)

        XCTAssertEqual(chapters.first?.text, "Photo\n\nA sunset over the ocean")
    }

    func testImageWithNoAltTextContributesNothingButDoesNotCrash() {
        let blocks = MarkdownDocumentParser.parse("# Photo\n![](./sunset.png)")
        let chapters = MarkdownDocumentParser.chaptersForNarration(from: blocks)

        // Heading text alone still makes a real, non-empty chapter.
        XCTAssertEqual(chapters.first?.text, "Photo")
    }

    func testListItemsAreJoinedIntoOneSpokenPassage() {
        let blocks = MarkdownDocumentParser.parse("# Steps\n- First\n- Second\n- Third")
        let chapters = MarkdownDocumentParser.chaptersForNarration(from: blocks)

        XCTAssertEqual(chapters.first?.text, "Steps\n\nFirst. Second. Third")
    }

    func testBlockQuoteContentIsSpoken() {
        let blocks = MarkdownDocumentParser.parse("# Quote\n> Wise words here.")
        let chapters = MarkdownDocumentParser.chaptersForNarration(from: blocks)

        XCTAssertEqual(chapters.first?.text, "Quote\n\nWise words here.")
    }

    func testContentBeforeTheFirstHeadingBecomesAPreambleChapter() {
        let blocks = MarkdownDocumentParser.parse("Preamble text.\n# First Heading\nBody.")
        let chapters = MarkdownDocumentParser.chaptersForNarration(from: blocks)

        XCTAssertEqual(chapters.map(\.title), ["Untitled", "First Heading"])
        XCTAssertEqual(chapters[0].text, "Preamble text.")
        XCTAssertEqual(chapters[1].text, "First Heading\n\nBody.")
    }

    // MARK: - Narration segments (#499 v2a Phase A)

    func testHeadingProducesAHeadingKindSegmentWithAParagraphPause() {
        let blocks = MarkdownDocumentParser.parse("# Chapter One")
        let chapters = MarkdownDocumentParser.chaptersForNarration(from: blocks)

        XCTAssertEqual(chapters.first?.segments, [
            NarrationSegment(text: "Chapter One", kind: .heading, pauseBefore: .paragraph),
        ])
    }

    func testEmphasisRunsSplitIntoTheirOwnSegmentDistinctFromSurroundingPlainText() {
        let blocks = MarkdownDocumentParser.parse("# T\n*emph* text and **bold** text")
        let chapters = MarkdownDocumentParser.chaptersForNarration(from: blocks)

        // Heading segment first (its own test above), then the paragraph's
        // runs split at each emphasis-state boundary. The paragraph's own
        // first segment ("emph") picks up a .paragraph pause since it's a
        // new top-level block following the heading — only the *internal*
        // emphasis-state splits within the same paragraph stay .none.
        let bodySegments = chapters.first?.segments.dropFirst() ?? []
        XCTAssertEqual(Array(bodySegments), [
            NarrationSegment(text: "emph", kind: .emphasis, pauseBefore: .paragraph),
            NarrationSegment(text: " text and ", kind: .plain, pauseBefore: .none),
            NarrationSegment(text: "bold", kind: .emphasis, pauseBefore: .none),
            NarrationSegment(text: " text", kind: .plain, pauseBefore: .none),
        ])
    }

    func testBlockQuoteContentIsKindQuoteWithAParagraphPauseOnEntry() {
        let blocks = MarkdownDocumentParser.parse("# T\n> Wise words here.")
        let chapters = MarkdownDocumentParser.chaptersForNarration(from: blocks)

        let quoteSegment = chapters.first?.segments.last
        XCTAssertEqual(quoteSegment, NarrationSegment(text: "Wise words here.", kind: .quote, pauseBefore: .paragraph))
    }

    func testMultiParagraphBlockQuoteGetsAParagraphPauseBetweenItsOwnParagraphsToo() {
        // Regression coverage for the same "block-to-block transition with no
        // pause hint runs the two together" bug the top-level chapter loop
        // guards against — blockQuote's own nested paragraphs go through
        // joinedSegments for exactly this reason.
        let blocks = MarkdownDocumentParser.parse("# T\n> Para one.\n>\n> Para two.")
        let chapters = MarkdownDocumentParser.chaptersForNarration(from: blocks)

        let quoteSegments = chapters.first?.segments.dropFirst() ?? []
        XCTAssertEqual(Array(quoteSegments), [
            NarrationSegment(text: "Para one.", kind: .quote, pauseBefore: .paragraph),
            NarrationSegment(text: "Para two.", kind: .quote, pauseBefore: .paragraph),
        ])
    }

    func testThematicBreakAttachesASceneBreakPauseToTheNextSegmentInsteadOfBeingDropped() {
        // The exact gap #499's design writeup flagged: narrationText(for:)
        // returns nil for .thematicBreak, silently dropping the scene-break
        // signal entirely. narrationSegments (via chaptersForNarration)
        // should instead carry it forward onto whatever comes next.
        let blocks = MarkdownDocumentParser.parse("# T\nBefore rule.\n\n---\n\nAfter rule.")
        let chapters = MarkdownDocumentParser.chaptersForNarration(from: blocks)

        let segments = chapters.first?.segments ?? []
        // "Before rule." is a new top-level paragraph following the heading,
        // so it carries its own .paragraph pause — the scene break's signal
        // is specifically that "After rule." gets the *stronger* .sceneBreak
        // instead of the .paragraph it would otherwise have gotten too.
        XCTAssertTrue(segments.contains(NarrationSegment(text: "Before rule.", kind: .plain, pauseBefore: .paragraph)))
        XCTAssertTrue(segments.contains(NarrationSegment(text: "After rule.", kind: .plain, pauseBefore: .sceneBreak)))
    }

    func testListItemsGetASentencePauseBetweenThemMirroringTheFlatTextJoiner() {
        let blocks = MarkdownDocumentParser.parse("# T\n- First\n- Second")
        let chapters = MarkdownDocumentParser.chaptersForNarration(from: blocks)

        // "First" (the list's own first segment) carries .paragraph — the
        // list itself is a new top-level block following the heading, same
        // as any other top-level block would. "Second" carries the .sentence
        // pause that's specific to being a later item within the same list.
        let listSegments = chapters.first?.segments.dropFirst() ?? []
        XCTAssertEqual(Array(listSegments), [
            NarrationSegment(text: "First", kind: .plain, pauseBefore: .paragraph),
            NarrationSegment(text: "Second", kind: .plain, pauseBefore: .sentence),
        ])
    }

    func testCodeBlocksTablesThematicBreaksProduceNoSegmentsOfTheirOwn() {
        let blocks = MarkdownDocumentParser.parse("# T\n```\nsome code\n```")
        let chapters = MarkdownDocumentParser.chaptersForNarration(from: blocks)

        // Only the heading segment — the code block contributes nothing.
        XCTAssertEqual(chapters.first?.segments.count, 1)
    }

    func testImageAltTextBecomesAPlainSegment() {
        let blocks = MarkdownDocumentParser.parse("# Photo\n![A sunset over the ocean](./sunset.png)")
        let chapters = MarkdownDocumentParser.chaptersForNarration(from: blocks)

        // .paragraph, not .none — it's a new top-level block following the
        // heading, same top-level normalization every other block gets.
        XCTAssertTrue(chapters.first?.segments.contains(
            NarrationSegment(text: "A sunset over the ocean", kind: .plain, pauseBefore: .paragraph)
        ) ?? false)
    }

    func testEmptyDocumentProducesNoSegments() {
        XCTAssertTrue(MarkdownDocumentParser.chaptersForNarration(from: []).isEmpty)
    }
}

private extension Array {
    subscript(safe index: Int) -> Element? {
        indices.contains(index) ? self[index] : nil
    }
}
