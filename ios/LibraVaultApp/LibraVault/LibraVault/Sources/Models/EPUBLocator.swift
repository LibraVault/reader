import Foundation

/// Maps a flat-text character offset — the currency EPUB bookmarks are stored in
/// (`"Locator:<chapterIndex>:<charOffset>"`, resolved today against `BookChapter.text`
/// via `TextPaginator.pageIndex(containing:in:text:)`) — onto the index of the block in
/// that same chapter's `BookChapter.blocks` (#357) covering roughly the same reading
/// position. This is issue #361's answer to "can a flat-text offset be cleanly mapped
/// onto the block model": not exactly, so this documents and implements the fallback
/// explicitly instead of leaving it unspecified.
///
/// `text` and `blocks` are two independent parses of the same chapter XHTML
/// (`EPUBParser.plainText(fromHTML:)` vs `EPUBParser.parseBlocks(fromHTML:)`) — an
/// `NSAttributedString`/tag-strip pass for the former, an `XMLParser` tree walk for the
/// latter — so their character counts are close but not always identical (differing
/// whitespace collapsing, entity handling). The mapping here is therefore approximate by
/// design: it walks `blocks` in order accumulating each one's own rendered plain-text
/// length and returns the first block whose accumulated range would contain `offset` if
/// the two pipelines agreed exactly. A bookmark saved before the block model existed
/// then lands on the *nearest* block — possibly off by a paragraph or two — rather than
/// failing outright or landing back at chapter start.
///
/// Called from `ReaderView.navigateToLocator` (via `resolve(_:chapters:)` below) to
/// resolve a pre-#360 bookmark once block-based navigation is wired in — #360-onward
/// bookmarks already store a block index directly and don't need this mapping, but
/// still go through `resolve(_:chapters:)` so the two formats share one entry point.
enum EPUBLocator {
    /// Returns an index into `blocks`, clamped to `blocks.indices` — `0` for an empty
    /// `blocks` array (nothing to resolve against; callers should treat this the same as
    /// "chapter start"), and the last index if `offset` falls beyond every block's
    /// accumulated length. Mirrors `TextPaginator.pageIndex`'s same past-the-end
    /// clamping, so an offset with no exact block equivalent (e.g. a bookmark from a
    /// chapter that has since re-parsed shorter) still resolves somewhere sane instead
    /// of crashing or being silently dropped — see this issue's acceptance criteria.
    static func blockIndex(forCharOffset offset: Int, in blocks: [MarkdownBlock]) -> Int {
        guard !blocks.isEmpty else { return 0 }
        var consumed = 0
        for (index, block) in blocks.enumerated() {
            let length = plainTextLength(of: block)
            if offset < consumed + length {
                return index
            }
            consumed += length
        }
        return blocks.count - 1
    }

    /// The character count `blockIndex(forCharOffset:in:)` accumulates per block —
    /// recurses into list items and block quotes, since those contribute no inline runs
    /// of their own at the top level and an offset can legitimately land inside one.
    private static func plainTextLength(of block: MarkdownBlock) -> Int {
        switch block {
        case let .heading(_, text):
            return length(of: text)
        case let .paragraph(text):
            return length(of: text)
        case let .codeBlock(code, _):
            return code.count
        case let .blockQuote(blocks):
            return blocks.reduce(0) { $0 + plainTextLength(of: $1) }
        case let .unorderedList(items):
            return items.reduce(0) { total, item in total + item.reduce(0) { $0 + plainTextLength(of: $1) } }
        case let .orderedList(items, _):
            return items.reduce(0) { total, item in total + item.reduce(0) { $0 + plainTextLength(of: $1) } }
        case .thematicBreak:
            return 0
        case let .image(_, altText):
            return altText.count
        case let .table(headers, rows):
            let headerLength = headers.reduce(0) { $0 + length(of: $1) }
            let rowsLength = rows.reduce(0) { total, row in total + row.reduce(0) { $0 + length(of: $1) } }
            return headerLength + rowsLength
        case let .mermaidDiagram(source):
            return source.count
        }
    }

    private static func length(of runs: [MarkdownInlineRun]) -> Int {
        runs.reduce(0) { $0 + $1.text.count }
    }

    /// Resolves a saved EPUB bookmark's `"Locator:<chapterIndex>:<N>"` string (see
    /// `ReaderView.addBookmark`/`navigateToBookmark`) into a `(chapterIndex,
    /// blockIndex)` pair, ready for `BlockPaginator.pageIndex(containingBlockIndex:in:)`.
    ///
    /// The format has two eras sharing the same `"Locator:"` prefix: pre-#360
    /// bookmarks (EPUB rendered flat text) store `N` as a character offset into
    /// `BookChapter.text`; #360-onward bookmarks store `N` as a block index directly
    /// and append a trailing `":block"` component so the two can be told apart from
    /// the string alone — without it, a small legacy char offset and a small
    /// block-model block index are indistinguishable, and resolving one as the other
    /// silently mis-navigates instead of failing loudly.
    ///
    /// Returns `nil` if `position` isn't a `"Locator:"` string, its components don't
    /// parse as integers, or `chapterIndex` is out of range for `chapters` — callers
    /// should treat that the same as "nothing to navigate to."
    static func resolve(_ position: String, chapters: [BookChapter]) -> (chapterIndex: Int, blockIndex: Int)? {
        guard position.hasPrefix("Locator:") else { return nil }
        let components = position.dropFirst("Locator:".count).split(separator: ":")
        guard components.count >= 2,
              let chapterIndex = Int(components[0]),
              let rawNumber = Int(components[1]),
              chapterIndex >= 0, chapterIndex < chapters.count
        else { return nil }

        let isBlockIndexFormat = components.count >= 3 && components[2] == "block"
        let resolvedBlockIndex = isBlockIndexFormat
            ? rawNumber
            : blockIndex(forCharOffset: rawNumber, in: chapters[chapterIndex].blocks)
        return (chapterIndex, resolvedBlockIndex)
    }
}
