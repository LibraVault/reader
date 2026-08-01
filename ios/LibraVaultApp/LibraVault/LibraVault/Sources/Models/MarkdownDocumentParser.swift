import Foundation
import Markdown

/// One inline text run with its accumulated styling — the building block for
/// rendering a paragraph/heading as a single SwiftUI `Text` via concatenation.
struct MarkdownInlineRun: Equatable {
    let text: String
    let bold: Bool
    let italic: Bool
    let code: Bool
}

/// A block-level element in a parsed Markdown document. Deliberately a plain data
/// model (not a View) so it can be unit-tested without SwiftUI, and rendered by
/// MarkdownReaderContent.
enum MarkdownBlock: Equatable {
    case heading(level: Int, text: [MarkdownInlineRun])
    case paragraph(text: [MarkdownInlineRun])
    case codeBlock(code: String, language: String?)
    case blockQuote(blocks: [MarkdownBlock])
    case unorderedList(items: [[MarkdownBlock]])
    case orderedList(items: [[MarkdownBlock]], start: Int)
    case thematicBreak
    /// A paragraph containing only a single image (`![alt](./img.png)` on its own
    /// line) — the common case for a relative image reference. `url` is the raw,
    /// unresolved reference exactly as written in the source; resolving it against
    /// the file's location and loading the bytes happens separately (see
    /// BookContentProvider.markdownAssetData and ReaderView.loadContent), since that
    /// needs the vault's security-scoped access, which this pure parsing layer has no
    /// knowledge of. An image referenced inline alongside other text in a paragraph
    /// is not extracted specially — it falls through to plain alt-text like any other
    /// unhandled inline node (see BlockBuilder.walk's `default` case).
    case image(url: String, altText: String)
}

/// Parses raw Markdown text into a flat list of [MarkdownBlock] using
/// swiftlang/swift-markdown's CommonMark/GFM AST (cmark-gfm under the hood).
///
/// v1 scope: headings, paragraphs (with bold/italic/inline-code runs), code blocks,
/// block quotes, ordered/unordered lists, thematic breaks. GFM tables are recognized
/// by the parser but rendered as a placeholder — real table rendering is a deliberate
/// fast-follow on both platforms (Android's renderer is likewise pinned to a
/// pre-table release for Kotlin-version-compatibility reasons), not a v1 blocker.
enum MarkdownDocumentParser {
    static func parse(_ source: String) -> [MarkdownBlock] {
        let document = Document(parsing: source)
        var builder = BlockBuilder()
        return builder.visit(document)
    }
}

/// One heading found in a document, plus the index of the top-level [MarkdownBlock]
/// it corresponds to — mirrors Android's `TocEntry`/`sectionIndex` shape, but here the
/// index is directly a `blocks` array position rather than a synthesized section
/// index, since MarkdownReaderContent already renders one block per array element.
struct MarkdownTocEntry: Equatable, Identifiable {
    let level: Int
    let title: String
    let blockIndex: Int

    var id: Int { blockIndex }
}

extension MarkdownDocumentParser {
    static func extractToc(from blocks: [MarkdownBlock]) -> [MarkdownTocEntry] {
        blocks.enumerated().compactMap { index, block in
            guard case let .heading(level, runs) = block else { return nil }
            let title = runs.map(\.text).joined()
            return MarkdownTocEntry(level: level, title: title, blockIndex: index)
        }
    }
}

private struct BlockBuilder: MarkupVisitor {
    typealias Result = [MarkdownBlock]

    mutating func defaultVisit(_ markup: Markup) -> [MarkdownBlock] {
        markup.children.flatMap { visit($0) }
    }

    mutating func visitHeading(_ heading: Heading) -> [MarkdownBlock] {
        [.heading(level: heading.level, text: inlineRuns(for: heading))]
    }

    mutating func visitParagraph(_ paragraph: Paragraph) -> [MarkdownBlock] {
        let children = Array(paragraph.children)
        if children.count == 1, let image = children[0] as? Markdown.Image {
            let altText = image.children.compactMap { ($0 as? Markdown.Text)?.string }.joined()
            return [.image(url: image.source ?? "", altText: altText)]
        }
        return [.paragraph(text: inlineRuns(for: paragraph))]
    }

    mutating func visitCodeBlock(_ codeBlock: CodeBlock) -> [MarkdownBlock] {
        [.codeBlock(code: codeBlock.code, language: codeBlock.language)]
    }

    mutating func visitBlockQuote(_ blockQuote: BlockQuote) -> [MarkdownBlock] {
        [.blockQuote(blocks: blockQuote.children.flatMap { visit($0) })]
    }

    mutating func visitUnorderedList(_ unorderedList: UnorderedList) -> [MarkdownBlock] {
        [.unorderedList(items: unorderedList.listItems.map { item in
            item.children.flatMap { visit($0) }
        })]
    }

    mutating func visitOrderedList(_ orderedList: OrderedList) -> [MarkdownBlock] {
        [.orderedList(
            items: orderedList.listItems.map { item in item.children.flatMap { visit($0) } },
            start: Int(orderedList.startIndex)
        )]
    }

    mutating func visitThematicBreak(_ thematicBreak: ThematicBreak) -> [MarkdownBlock] {
        [.thematicBreak]
    }

    mutating func visitTable(_ table: Table) -> [MarkdownBlock] {
        [.paragraph(text: [
            MarkdownInlineRun(text: "[Table omitted — not yet supported in this viewer]", bold: false, italic: true, code: false),
        ])]
    }

    /// Walks a block's inline children (Text/Strong/Emphasis/InlineCode/Link/breaks),
    /// flattening nested emphasis into a run-per-leaf list that a caller can render
    /// as one concatenated `Text` value.
    private func inlineRuns(for markup: Markup) -> [MarkdownInlineRun] {
        var runs: [MarkdownInlineRun] = []
        for child in markup.children {
            walk(child, bold: false, italic: false, code: false, into: &runs)
        }
        return runs
    }

    private func walk(_ node: Markup, bold: Bool, italic: Bool, code: Bool, into runs: inout [MarkdownInlineRun]) {
        switch node {
        case let text as Markdown.Text:
            runs.append(MarkdownInlineRun(text: text.string, bold: bold, italic: italic, code: code))
        case let inlineCode as InlineCode:
            runs.append(MarkdownInlineRun(text: inlineCode.code, bold: bold, italic: italic, code: true))
        case let strong as Strong:
            for child in strong.children { walk(child, bold: true, italic: italic, code: code, into: &runs) }
        case let emphasis as Emphasis:
            for child in emphasis.children { walk(child, bold: bold, italic: true, code: code, into: &runs) }
        case let link as Link:
            for child in link.children { walk(child, bold: bold, italic: italic, code: code, into: &runs) }
        case is LineBreak:
            runs.append(MarkdownInlineRun(text: "\n", bold: bold, italic: italic, code: code))
        case is SoftBreak:
            runs.append(MarkdownInlineRun(text: " ", bold: bold, italic: italic, code: code))
        default:
            for child in node.children { walk(child, bold: bold, italic: italic, code: code, into: &runs) }
        }
    }
}
