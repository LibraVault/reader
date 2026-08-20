import UIKit

/// Splits a chapter's plain text into real screen-sized pages, using TextKit
/// (`NSLayoutManager` + size-bounded `NSTextContainer`s) to lay out actual glyphs
/// rather than estimating from character counts — the same `font`/`lineSpacing`/
/// `pageSize` inputs `ReaderView.paginatedContent` renders with, so a page boundary
/// here is exactly where the on-screen text actually wraps to a new page.
///
/// This replaces treating one EPUB spine item (one XHTML content document) as one
/// "page" — a book split into 33 chapter files showed "Page 1 of 33" even when a
/// single chapter was many real printed pages long (issue #331).
///
/// Pure and stateless: identical inputs always produce identical output, which is
/// what makes this independently unit-testable without a live view hierarchy — see
/// `TextPaginatorTests`.
enum TextPaginator {
    /// Lays `text` out with `font`/`lineSpacing` into a sequence of `pageSize`-bounded
    /// containers and returns each container's consumed text as a character range,
    /// in reading order.
    ///
    /// Implementation note: this adds successive `NSTextContainer`s to a single
    /// shared `NSLayoutManager`/`NSTextStorage` pair rather than re-laying-out the
    /// whole chapter once per page — `NSLayoutManager` fills containers in the order
    /// they were added, continuing from wherever the previous container's layout left
    /// off, so each `glyphRange(for:)` call after adding a new container yields
    /// exactly the glyphs that landed on that page.
    ///
    /// Returns `[]` for empty text or a non-positive `pageSize` — there is no page to
    /// show in either case (`ReaderView.emptyPageNotice` is how a resulting empty
    /// chapter is presented, not a single blank page here).
    static func paginate(text: String, font: UIFont, lineSpacing: CGFloat, pageSize: CGSize) -> [Range<String.Index>] {
        guard !text.isEmpty, pageSize.width > 0, pageSize.height > 0 else { return [] }

        // A container shorter than a single line of this font can never fit even
        // one full line. TextKit does not surface that as a zero-glyph container
        // the way the loop below assumes — it force-lays out at least one glyph
        // per line rather than leaving a container empty, to avoid a non-advancing
        // layout — so on a real device this produced one one-glyph "page" per
        // character instead of the documented single fallback page. Catch the
        // degenerate case here, before the per-page loop, instead of relying on a
        // zero-glyph signal that never actually fires.
        //
        // Only `font.lineHeight` is required, not `+ lineSpacing`: `lineSpacing`
        // is inserted *between* lines (after each line's own fragment), so a
        // container only needs room for one line's height to receive a non-zero
        // glyph range — it doesn't also need room for the gap that would follow
        // it. Requiring the extra `lineSpacing` here rejected containers that
        // TextKit could actually lay one real line into, falling back to a single
        // giant page unnecessarily for a narrow height window just above
        // `lineHeight`.
        guard pageSize.height >= font.lineHeight else {
            return [text.startIndex..<text.endIndex]
        }

        let paragraphStyle = NSMutableParagraphStyle()
        paragraphStyle.lineSpacing = lineSpacing

        let textStorage = NSTextStorage(
            string: text,
            attributes: [.font: font, .paragraphStyle: paragraphStyle]
        )
        let layoutManager = NSLayoutManager()
        textStorage.addLayoutManager(layoutManager)

        let totalGlyphs = layoutManager.numberOfGlyphs
        var pages: [Range<String.Index>] = []
        var glyphIndex = 0

        while glyphIndex < totalGlyphs {
            let textContainer = NSTextContainer(size: pageSize)
            textContainer.lineFragmentPadding = 0
            layoutManager.addTextContainer(textContainer)

            let glyphRange = layoutManager.glyphRange(for: textContainer)

            // A container that consumed zero glyphs means pageSize can't fit even one
            // line of this font — adding further empty containers would spin forever
            // without ever reaching totalGlyphs. Fall back to a single page holding
            // everything left rather than hanging or silently truncating the chapter.
            guard glyphRange.length > 0 else {
                let remainingGlyphRange = NSRange(location: glyphIndex, length: totalGlyphs - glyphIndex)
                if let range = characterRange(forGlyphRange: remainingGlyphRange, in: layoutManager, text: text) {
                    pages.append(range)
                }
                break
            }

            if let range = characterRange(forGlyphRange: glyphRange, in: layoutManager, text: text) {
                pages.append(range)
            }
            glyphIndex = NSMaxRange(glyphRange)
        }

        return pages
    }

    /// Finds the index into `pages` whose range contains character offset `offset`
    /// (as produced by `text.distance(from: text.startIndex, to:)`), or the last page
    /// if `offset` falls beyond every range — used by `ReaderView.repaginate(for:)` to
    /// re-locate the reader's visible position after a repagination shifts every page
    /// index (font size, line spacing, font design, or screen size changing), and by
    /// `navigateToBookmark` to resolve a saved `Locator:<chapterIndex>:<charOffset>`
    /// bookmark against the chapter's *current* pagination.
    static func pageIndex(containing offset: Int, in pages: [Range<String.Index>], text: String) -> Int {
        guard !pages.isEmpty else { return 0 }
        for (index, range) in pages.enumerated() {
            let lowerOffset = text.distance(from: text.startIndex, to: range.lowerBound)
            let upperOffset = text.distance(from: text.startIndex, to: range.upperBound)
            if offset >= lowerOffset && offset < upperOffset {
                return index
            }
        }
        return pages.count - 1
    }

    /// Bridges TextKit's UTF-16-based `NSRange` back to a Swift `Range<String.Index>`
    /// — `Range(_:in:)` handles that conversion correctly (including surrogate-pair
    /// characters TextKit counts as two UTF-16 units but Swift counts as one
    /// `Character`), which a naive `String.Index(utf16Offset:in:)` pair would not
    /// always do at a boundary that lands mid-grapheme-cluster.
    private static func characterRange(
        forGlyphRange glyphRange: NSRange,
        in layoutManager: NSLayoutManager,
        text: String
    ) -> Range<String.Index>? {
        var actualGlyphRange = NSRange(location: 0, length: 0)
        let nsCharacterRange = layoutManager.characterRange(forGlyphRange: glyphRange, actualGlyphRange: &actualGlyphRange)
        return Range(nsCharacterRange, in: text)
    }
}
