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
    /// A GFM table (#120) — `headers` is the single head row, `rows` the body rows,
    /// each cell already flattened to inline runs via the same [inlineRuns(for:)]
    /// walk headings/paragraphs use, since a `Table.Cell` is a `BasicInlineContainer`
    /// just like they are. Column alignment (`Table.columnAlignments`) isn't carried
    /// through — MarkdownReaderContent renders every column left-aligned, matching
    /// Android's renderer default (see feature/reader/build.gradle.kts for the
    /// version this table support was added alongside).
    case table(headers: [[MarkdownInlineRun]], rows: [[[MarkdownInlineRun]]])
    /// A ```` ```mermaid ```` fenced code block (#121) — `source` is the fence body
    /// exactly as written, unmodified. Split out from [codeBlock] rather than
    /// special-cased at render time so MarkdownReaderContent's `switch` stays
    /// exhaustive over "is this actually a diagram", matching how [image] is split
    /// out of [paragraph] for the same reason.
    case mermaidDiagram(source: String)
}

/// Parses raw Markdown text into a flat list of [MarkdownBlock] using
/// swiftlang/swift-markdown's CommonMark/GFM AST (cmark-gfm under the hood).
///
/// v1 scope: headings, paragraphs (with bold/italic/inline-code runs), code blocks,
/// block quotes, ordered/unordered lists, thematic breaks, GFM tables (#120).
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

/// Read Aloud support for Markdown (#124) — converts parsed [MarkdownBlock]s into
/// [BookChapter]s (see BookContentProvider.swift), the same currency AppState's
/// playback pipeline already narrates EPUB/PDF through via AVSpeechSynthesizer, so no
/// change to that pipeline itself is needed — only a source of chapters for it.
///
/// One chapter per top-level heading section, matching the granularity
/// MarkdownReaderContent's own TOC/scroll-restore logic already uses (see
/// sectionIndexForFraction's Android counterpart and this file's extractToc) — headings
/// are a document's only real structural unit, and narrating "the whole file" as a
/// single chapter would make skip-to-next-chapter useless for anything but the
/// shortest notes. A document with no headings at all becomes one "Untitled" chapter
/// containing everything, rather than zero chapters — a heading-less note is still a
/// real, narratable document.
extension MarkdownDocumentParser {
    static func chaptersForNarration(from blocks: [MarkdownBlock]) -> [BookChapter] {
        var chapters: [BookChapter] = []
        var currentTitle: String?
        var currentParts: [String] = []

        func flush() {
            let text = currentParts.joined(separator: "\n\n")
            if !text.isEmpty {
                chapters.append(BookChapter(title: currentTitle ?? "Untitled", text: text))
            }
            currentParts = []
        }

        for block in blocks {
            if case let .heading(_, runs) = block {
                flush()
                currentTitle = runs.map(\.text).joined()
            }
            if let text = narrationText(for: block) {
                currentParts.append(text)
            }
        }
        flush()
        return chapters
    }

    /// Plain speakable text for one block, or nil for a block with nothing to say.
    /// Deliberately silent (not "[table omitted]"-style placeholder speech) for
    /// content that would be actively unhelpful read aloud:
    ///  - code blocks: hearing source code spoken character-by-character isn't useful
    ///  - tables: reading cell values in isolation, with no visual grid for context,
    ///    conveys little; a real fix (announcing row/column headers) is a fast-follow,
    ///    not a v1 blocker
    ///  - thematic breaks: a visual divider, no textual content
    /// Image alt text IS spoken, unlike the above — it's already written specifically
    /// to describe the image in words, the one media type where that's true.
    ///
    /// NOTE: MarkdownBlock gains a `.mermaidDiagram` case in #121 (Mermaid rendering),
    /// developed in parallel on a separate branch and not yet merged as of this
    /// writing — inherently visual, nothing to narrate, so once that case exists this
    /// switch needs `.mermaidDiagram` added alongside `.codeBlock`/`.thematicBreak`/
    /// `.table` below, or it will fail to compile as non-exhaustive.
    private static func narrationText(for block: MarkdownBlock) -> String? {
        switch block {
        case let .heading(_, runs), let .paragraph(runs):
            let text = runs.map(\.text).joined()
            return text.isEmpty ? nil : text
        case let .blockQuote(nested):
            return joinedNarration(of: nested)
        case let .unorderedList(items), let .orderedList(items, _):
            let spoken = items.compactMap { joinedNarration(of: $0) }
            return spoken.isEmpty ? nil : spoken.joined(separator: ". ")
        case let .image(_, altText):
            return altText.isEmpty ? nil : altText
        case .codeBlock, .thematicBreak, .table:
            return nil
        }
    }

    private static func joinedNarration(of blocks: [MarkdownBlock]) -> String? {
        let parts = blocks.compactMap { narrationText(for: $0) }
        return parts.isEmpty ? nil : parts.joined(separator: " ")
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
        // Case-sensitive, matching GFM's own info-string convention — ```Mermaid``` is
        // an ordinary, unrecognized-language code block, not a diagram (see Android's
        // identical MermaidBlockDetector.mermaidSourceOrNull for the same rule).
        if codeBlock.language == "mermaid" {
            return [.mermaidDiagram(source: codeBlock.code)]
        }
        return [.codeBlock(code: codeBlock.code, language: codeBlock.language)]
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
        // Table.Head and Table.Row both conform to TableCellContainer (`.cells`);
        // Table.Cell conforms to BasicInlineContainer, so it walks through the same
        // inlineRuns(for:) as any other inline container — headings, paragraphs.
        //
        // Plain for-loops rather than `.cells.map { inlineRuns(for: $0) }`: `.cells`/
        // `.rows` are LazyMapSequence, and a closure passed to a *lazy* sequence's
        // `.map` is stored for deferred evaluation rather than called immediately —
        // which makes it an escaping closure. Swift doesn't allow an escaping closure
        // to capture `self` from inside a `mutating func` (self is effectively `inout`
        // there), so `inlineRuns(for:)` — an instance method, implicitly `self.`-bound
        // — can't be referenced that way here. visitBlockQuote/visitUnorderedList's
        // `.flatMap { visit($0) }` calls look similar but are fine: their `.children`
        // is a plain (non-lazy) Sequence, whose `.flatMap` runs the closure eagerly
        // and doesn't retain it, so it's non-escaping.
        var headers: [[MarkdownInlineRun]] = []
        for cell in table.head.cells {
            headers.append(inlineRuns(for: cell))
        }
        var rows: [[[MarkdownInlineRun]]] = []
        for row in table.body.rows {
            var rowCells: [[MarkdownInlineRun]] = []
            for cell in row.cells {
                rowCells.append(inlineRuns(for: cell))
            }
            rows.append(rowCells)
        }
        return [.table(headers: headers, rows: rows)]
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
