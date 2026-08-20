import UIKit

/// Splits a chapter's `[MarkdownBlock]`s (see `MarkdownDocumentParser.swift`) into
/// screen-sized pages for EPUB's `paginatedContent` mode — the block-model
/// counterpart to `TextPaginator`, which only ever laid out a flat `String`.
///
/// `MarkdownReaderContent` (the only other block-based content view) is scroll-only
/// and has never needed this — `TextPaginator.swift`'s own doc comments confirm issue
/// #331's pagination work was scoped to plain-text EPUB/PDF only. This exists because
/// EPUB is moving to the same block model Markdown already renders (#352), and that
/// model needs its own paginator: an image's height depends on its pixel aspect
/// ratio, which a character-counting/glyph-based paginator like `TextPaginator` has
/// no notion of.
///
/// Pagination is at top-level-block granularity: each page is a contiguous run of
/// whole top-level blocks (mirroring `MarkdownReaderContent`'s own top-level
/// `ForEach(blocks)`), not a mid-block split the way `TextPaginator` splits within a
/// single string. A block's own nested structure (list items, block-quote contents,
/// table rows) always stays together on one page.
///
/// Pure and stateless, like `TextPaginator`: identical inputs always produce
/// identical output, which is what makes this independently unit-testable without a
/// live view hierarchy — see `BlockPaginatorTests`.
enum BlockPaginator {
    /// Lays `blocks` out into a sequence of `pageSize`-bounded pages, in reading
    /// order, returning each page's blocks.
    ///
    /// - `font`/`lineSpacing` mirror `TextPaginator.paginate`'s inputs and must be
    ///   the same values `paginatedContent` renders a paragraph block with — heading
    ///   sizes are then derived from `font.pointSize` via `MarkdownHeadingStyle`, the
    ///   same scale `MarkdownBlockView` renders headings with.
    /// - `images` resolves `.image` blocks' `url` to their bytes, exactly like
    ///   `BookChapter.images`/`ReaderView.markdownImages` — an unresolved image
    ///   falls back to the same fixed placeholder height `MarkdownBlockView` shows
    ///   for a missing image (icon + optional alt text), since there's no pixel size
    ///   to measure without the bytes.
    ///
    /// Returns `[]` for an empty block list or a non-positive `pageSize`.
    ///
    /// A single block taller than `pageSize.height` (the common case: a huge image)
    /// still gets its own page rather than being dropped or splitting the loop into
    /// an infinite regress — it simply overflows that one page, mirroring how
    /// `TextPaginator` falls back to a single overflowing page for a pageSize too
    /// small to fit one line, rather than hanging.
    static func paginate(
        blocks: [MarkdownBlock],
        images: [String: Data],
        font: UIFont,
        lineSpacing: CGFloat,
        pageSize: CGSize
    ) -> [[MarkdownBlock]] {
        guard !blocks.isEmpty, pageSize.width > 0, pageSize.height > 0 else { return [] }

        var pages: [[MarkdownBlock]] = []
        var currentPage: [MarkdownBlock] = []
        var currentHeight: CGFloat = 0

        for block in blocks {
            let blockHeight = height(of: block, images: images, font: font, lineSpacing: lineSpacing, width: pageSize.width)
            let spacingBeforeBlock: CGFloat = currentPage.isEmpty ? 0 : LibraVaultSpacing.md

            // Only starts a new page once the current one already holds something —
            // an empty page always accepts the next block, even if it alone overflows
            // pageSize.height (the degenerate-oversized-block case above).
            if !currentPage.isEmpty, currentHeight + spacingBeforeBlock + blockHeight > pageSize.height {
                pages.append(currentPage)
                currentPage = [block]
                currentHeight = blockHeight
            } else {
                currentPage.append(block)
                currentHeight += spacingBeforeBlock + blockHeight
            }
        }

        if !currentPage.isEmpty {
            pages.append(currentPage)
        }

        return pages
    }

    /// Finds the index into `pages` whose contiguous run of blocks contains flat block
    /// index `blockIndex` (an offset into the chapter's original `blocks` array this
    /// paginator was given), or the last page if `blockIndex` falls beyond every page —
    /// the block-model counterpart to `TextPaginator.pageIndex(containing:in:text:)`,
    /// used by `ReaderView` to re-locate the reader's visible position after a
    /// repagination and to resolve a saved `"Locator:<chapterIndex>:<blockIndex>"`
    /// bookmark. Blocks never split across pages (see `paginate`'s doc comment), so
    /// unlike a character offset this doesn't need range math — just counting.
    static func pageIndex(containingBlockIndex blockIndex: Int, in pages: [[MarkdownBlock]]) -> Int {
        guard !pages.isEmpty else { return 0 }
        var cumulative = 0
        for (index, page) in pages.enumerated() {
            cumulative += page.count
            if blockIndex < cumulative { return index }
        }
        return pages.count - 1
    }

    /// Flat block index (into the chapter's original `blocks` array) of the first
    /// block on `pages[pageIndex]` — the inverse of `pageIndex(containingBlockIndex:in:)`
    /// above, used to capture a stable anchor for the page currently on screen before a
    /// repagination replaces `pages` wholesale.
    static func firstBlockIndex(ofPage pageIndex: Int, in pages: [[MarkdownBlock]]) -> Int {
        guard pageIndex > 0, pageIndex <= pages.count else { return 0 }
        return pages.prefix(pageIndex).reduce(0) { $0 + $1.count }
    }

    /// Fixed height for a `.image` block whose bytes couldn't be resolved from
    /// `images` — matches `MarkdownBlockView`'s placeholder (a photo icon, plus a
    /// line of alt text when present) closely enough to keep pagination stable
    /// without needing a live view hierarchy to measure it exactly.
    private static let unresolvedImagePlaceholderHeight: CGFloat = 32 + LibraVaultSpacing.xs

    /// Mermaid diagrams render via an async `WKWebView` JS evaluation
    /// (`MermaidDiagramView`) — their real height isn't knowable synchronously, let
    /// alone from a pure function with no live web view. Falls back to the same
    /// `minHeight` the loading placeholder itself reserves.
    private static let mermaidPlaceholderHeight: CGFloat = 120

    /// Divider() renders as a hairline; no extra vertical padding is added around it
    /// in MarkdownBlockView beyond the outer VStack's own inter-block spacing.
    private static let thematicBreakHeight: CGFloat = 1

    private static func height(
        of block: MarkdownBlock,
        images: [String: Data],
        font: UIFont,
        lineSpacing: CGFloat,
        width: CGFloat
    ) -> CGFloat {
        switch block {
        case let .heading(level, runs):
            return textHeight(for: plainText(runs), font: headingFont(level: level, bodyFont: font), lineSpacing: lineSpacing, width: width)

        case let .paragraph(runs):
            return textHeight(for: plainText(runs), font: font, lineSpacing: lineSpacing, width: width)

        case let .codeBlock(code, _):
            let codeFont = UIFont.monospacedSystemFont(ofSize: 14 * fontSizeMultiplier(of: font), weight: .regular)
            let innerWidth = max(width - 2 * LibraVaultSpacing.sm, 0)
            return textHeight(for: code, font: codeFont, lineSpacing: 0, width: innerWidth) + 2 * LibraVaultSpacing.sm

        case let .blockQuote(nested):
            // Leading colour bar + its own spacing, matching the HStack in
            // MarkdownBlockView's `.blockQuote` case.
            let innerWidth = max(width - LibraVaultSpacing.sm - 3, 0)
            return stackedHeight(of: nested, images: images, font: font, lineSpacing: lineSpacing, width: innerWidth)

        case let .unorderedList(items), let .orderedList(items, _):
            // listRow's marker column ("•"/"1.") plus its own HStack spacing.
            let markerWidth: CGFloat = 24
            let innerWidth = max(width - markerWidth - LibraVaultSpacing.sm, 0)
            var total: CGFloat = 0
            for (index, item) in items.enumerated() {
                if index > 0 { total += LibraVaultSpacing.sm }
                total += stackedHeight(of: item, images: images, font: font, lineSpacing: lineSpacing, width: innerWidth)
            }
            return total

        case .thematicBreak:
            return thematicBreakHeight

        case let .table(headers, rows):
            // The table renders in its own horizontal ScrollView (MarkdownBlockView's
            // `.table` case), so its Grid columns size to content width rather than
            // wrapping to `width` — a row is therefore always one line tall in
            // practice, not a TextKit-wrapped block like the other cases here.
            let rowCount = (headers.isEmpty ? 0 : 1) + rows.count
            guard rowCount > 0 else { return 0 }
            let rowsHeight = CGFloat(rowCount) * font.lineHeight
            let rowSpacing = CGFloat(max(rowCount - 1, 0)) * LibraVaultSpacing.sm
            let dividerHeight: CGFloat = headers.isEmpty ? 0 : 1 + LibraVaultSpacing.sm
            return rowsHeight + rowSpacing + dividerHeight + 2 * LibraVaultSpacing.sm

        case let .image(url, altText):
            guard let data = images[url], let uiImage = UIImage(data: data), uiImage.size.width > 0 else {
                return unresolvedImagePlaceholderHeight + (altText.isEmpty ? 0 : font.lineHeight)
            }
            // Mirrors MarkdownBlockView's `.resizable().aspectRatio(contentMode: .fit)
            // .frame(maxWidth: .infinity)` — full available width, height scaled to
            // keep the image's own aspect ratio.
            let aspectRatio = uiImage.size.height / uiImage.size.width
            return width * aspectRatio

        case .mermaidDiagram:
            return mermaidPlaceholderHeight
        }
    }

    /// Sum of `blocks`' own heights plus the `LibraVaultSpacing.sm` spacing
    /// `MarkdownBlockView` places between nested blocks (list items, block-quote
    /// contents) — the same vertical `VStack(spacing:)` those cases use, distinct
    /// from `LibraVaultSpacing.md` between top-level blocks.
    private static func stackedHeight(
        of blocks: [MarkdownBlock],
        images: [String: Data],
        font: UIFont,
        lineSpacing: CGFloat,
        width: CGFloat
    ) -> CGFloat {
        var total: CGFloat = 0
        for (index, block) in blocks.enumerated() {
            if index > 0 { total += LibraVaultSpacing.sm }
            total += height(of: block, images: images, font: font, lineSpacing: lineSpacing, width: width)
        }
        return total
    }

    private static func plainText(_ runs: [MarkdownInlineRun]) -> String {
        runs.map(\.text).joined()
    }

    /// `font.pointSize` is `16 * fontSize` for body text, by the same convention
    /// `ReaderView.repaginate` builds the `UIFont` it hands `TextPaginator` with —
    /// inverting that gives back the user's `fontSize` multiplier so heading/code
    /// sizes can be derived from it, without this pure model layer needing its own
    /// separate `fontSize: Double` parameter.
    private static func fontSizeMultiplier(of font: UIFont) -> CGFloat {
        font.pointSize / 16
    }

    /// Matches `MarkdownBlockView`'s `MarkdownHeadingStyle.headingSize(for:fontSize:)`
    /// call, deriving the multiplier from `bodyFont` per `fontSizeMultiplier(of:)`
    /// above rather than threading a second font-size input through this type.
    private static func headingFont(level: Int, bodyFont: UIFont) -> UIFont {
        let size = MarkdownHeadingStyle.headingSize(for: level, fontSize: Double(fontSizeMultiplier(of: bodyFont)))
        return bodyFont.withSize(CGFloat(size))
    }

    /// Measures the height `text` occupies when wrapped to `width` with `font`/
    /// `lineSpacing`, via the same TextKit machinery `TextPaginator` uses to lay out
    /// glyphs — an unbounded-height container avoids splitting the text itself here;
    /// this measures a whole block's height, it doesn't paginate within one.
    private static func textHeight(for text: String, font: UIFont, lineSpacing: CGFloat, width: CGFloat) -> CGFloat {
        guard !text.isEmpty, width > 0 else { return 0 }

        let paragraphStyle = NSMutableParagraphStyle()
        paragraphStyle.lineSpacing = lineSpacing

        let textStorage = NSTextStorage(
            string: text,
            attributes: [.font: font, .paragraphStyle: paragraphStyle]
        )
        let layoutManager = NSLayoutManager()
        textStorage.addLayoutManager(layoutManager)

        let textContainer = NSTextContainer(size: CGSize(width: width, height: .greatestFiniteMagnitude))
        textContainer.lineFragmentPadding = 0
        layoutManager.addTextContainer(textContainer)

        // Forces layout of the container so usedRect reflects the actually-wrapped
        // glyphs rather than an un-laid-out zero rect.
        layoutManager.glyphRange(for: textContainer)

        return layoutManager.usedRect(for: textContainer).height
    }
}
