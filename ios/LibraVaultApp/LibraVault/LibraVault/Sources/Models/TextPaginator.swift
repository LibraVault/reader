import UIKit

/// Splits a chapter's extracted text into screen-sized pages using TextKit, so page
/// boundaries match what `ReaderView.paginatedContent` actually draws — same font,
/// line spacing, and page size the view renders with. See issue #331: before this,
/// EPUB's "Page X of Y" was `chapters?.count` (the number of spine items), not a real
/// page count, and next/previous jumped a whole chapter at a time instead of a screen.
enum TextPaginator {
    /// Lays out `text` at `font`/`lineSpacing` inside repeated `pageSize`-bounded text
    /// containers on one `NSLayoutManager`, consuming glyphs container by container —
    /// the standard TextKit pagination technique. Returns one character range per page,
    /// in reading order. Empty text returns zero pages (there is nothing to show, not
    /// one blank page).
    static func paginate(text: String, font: UIFont, lineSpacing: CGFloat, pageSize: CGSize) -> [Range<String.Index>] {
        guard !text.isEmpty, pageSize.width > 0, pageSize.height > 0 else { return [] }

        let paragraphStyle = NSMutableParagraphStyle()
        paragraphStyle.lineSpacing = lineSpacing

        let textStorage = NSTextStorage(
            string: text,
            attributes: [.font: font, .paragraphStyle: paragraphStyle]
        )
        let layoutManager = NSLayoutManager()
        textStorage.addLayoutManager(layoutManager)

        var pages: [Range<String.Index>] = []
        let totalGlyphs = layoutManager.numberOfGlyphs
        var glyphIndex = 0

        while glyphIndex < totalGlyphs {
            let textContainer = NSTextContainer(size: pageSize)
            textContainer.lineFragmentPadding = 0
            layoutManager.addTextContainer(textContainer)

            let glyphRange = layoutManager.glyphRange(for: textContainer)
            guard glyphRange.length > 0 else {
                // pageSize is too small to fit even a single glyph at this font size —
                // bail out with one page holding everything left, rather than adding
                // zero-length containers forever.
                let remainingGlyphRange = NSRange(location: glyphIndex, length: totalGlyphs - glyphIndex)
                let remainingCharRange = layoutManager.characterRange(forGlyphRange: remainingGlyphRange, actualGlyphRange: nil)
                if let range = Range(remainingCharRange, in: text) {
                    pages.append(range)
                }
                break
            }

            let charRange = layoutManager.characterRange(forGlyphRange: glyphRange, actualGlyphRange: nil)
            if let range = Range(charRange, in: text) {
                pages.append(range)
            }
            glyphIndex = NSMaxRange(glyphRange)
        }

        return pages
    }

    /// Character offset (from the start of `text`) that `page` starts at — the stable,
    /// repagination-independent quantity `ReaderView` persists (bookmarks, position
    /// restore) instead of a raw page index, since page indices shift whenever type
    /// settings or screen size change.
    static func startOffset(of page: Range<String.Index>, in text: String) -> Int {
        text.distance(from: text.startIndex, to: page.lowerBound)
    }

    /// Inverse of `startOffset(of:in:)` — finds the page containing a previously
    /// captured character offset in a (possibly freshly recomputed) `pages` array.
    /// Falls back to the last page if `offset` is beyond the end of `text`, so a stale
    /// offset from a longer prior edition of the text still lands somewhere sane.
    static func pageIndex(containingOffset offset: Int, in text: String, pages: [Range<String.Index>]) -> Int? {
        guard !pages.isEmpty else { return nil }
        guard let target = text.index(text.startIndex, offsetBy: offset, limitedBy: text.endIndex) else {
            return pages.count - 1
        }
        return pages.firstIndex { $0.contains(target) } ?? pages.count - 1
    }
}
