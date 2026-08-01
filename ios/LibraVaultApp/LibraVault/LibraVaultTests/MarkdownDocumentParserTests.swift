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

    func testTableRendersAsPlaceholderParagraph() {
        let blocks = MarkdownDocumentParser.parse("| A | B |\n|---|---|\n| 1 | 2 |")

        guard case let .paragraph(runs)? = blocks.first else {
            return XCTFail("expected a placeholder paragraph for the unsupported table")
        }
        XCTAssertTrue(runs.first?.text.contains("Table") ?? false)
    }

    func testEmptyDocumentReturnsNoBlocks() {
        XCTAssertEqual(MarkdownDocumentParser.parse(""), [])
    }
}
