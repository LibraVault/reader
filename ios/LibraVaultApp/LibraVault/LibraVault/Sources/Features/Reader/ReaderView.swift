import SwiftUI
import PDFKit

struct ReaderView: View {
    let book: BookItem

    @EnvironmentObject var appState: AppState
    /// Resolves `readingTheme == .system` to a concrete light/dark choice — see
    /// `ReadingTheme.resolved(for:)`. Live: SwiftUI re-invalidates `body` on a
    /// trait-collection change, so `colors`/`effectiveReadingTheme` below pick up
    /// an OS appearance change automatically while the reader is open, not just on
    /// next resume.
    @Environment(\.colorScheme) private var systemColorScheme
    /// 0-based chapter index, paired with `currentPageInChapter` below to locate the
    /// reader's exact on-screen page. Replaces the old 1-based `currentChapter`,
    /// which conflated "chapter" (one EPUB spine item) with "page" — see issue #331.
    @State private var currentChapterIndex = 0
    /// 0-based index into `blockPagination[currentChapterIndex]`.
    @State private var currentPageInChapter = 0
    /// Per-chapter screen-sized pages-of-blocks from `BlockPaginator`, indexed to
    /// match `chapters` — nil until the first `repaginate(for:)` call, which needs a
    /// real `GeometryReader`-measured size to lay blocks out against. See
    /// BlockPaginator.swift.
    @State private var blockPagination: [[[MarkdownBlock]]]?
    /// The inputs `blockPagination` was last computed from, so `repaginate(for:)` can
    /// skip re-running TextKit layout over every chapter when `GeometryReader`
    /// re-evaluates its closure without the measured size (or font settings) actually
    /// changing — which happens on every body pass, not just real resizes/rotations.
    @State private var lastPaginationSize: CGSize?
    @State private var lastPaginationFontSize: Double?
    @State private var lastPaginationLineSpacing: Double?
    @State private var lastPaginationFontDesign: Font.Design?
    /// A saved reading-progress fraction waiting to be resolved into a (chapter, page)
    /// once the first `repaginate(for:)` call has real page boundaries to resolve it
    /// against — mirrors PDF's `restoredIndex` in `loadContent()`, which can restore
    /// immediately since `PDFDocument.pageCount` doesn't depend on a measured view
    /// size the way `BlockPaginator`'s page count does. Cleared once consumed.
    @State private var pendingRestoreFraction: Double?
    /// Real chapters for formats with a parser wired up (EPUB only — PDF used to
    /// share this reflowed-text path too, see PDFReaderContent's doc comment for why
    /// that changed). nil until `loadContent()` resolves — briefly renders
    /// `loadingContent` — then stays nil permanently if loading failed, in which case
    /// `unavailableReason` explains why.
    @State private var chapters: [BookChapter]?
    /// Real PDF document for on-screen page rendering — populated by loadContent()
    /// instead of `chapters` when book.format == .pdf. See PDFReaderContent.
    @State private var pdfDocument: PDFDocument?
    /// Releases the folder security scope BookContentProvider.openPDFDocument started
    /// — must run exactly once, in onDisappear, since PDFKit reads lazily from disk
    /// for as long as pdfDocument is being displayed (see that function's doc
    /// comment).
    @State private var pdfEndAccess: (() -> Void)?
    /// 0-based, matching PDFKit's own page indexing (PDFDocument.page(at:)).
    @State private var pdfCurrentPageIndex = 0
    /// Parsed Markdown blocks — populated by loadContent() instead of `chapters`
    /// when book.format == .markdown. See MarkdownDocumentParser.
    @State private var markdownBlocks: [MarkdownBlock]?
    /// Resolved image bytes for this Markdown file's `.image` blocks, keyed by the
    /// raw (unresolved) reference string as written in the source. Loaded eagerly in
    /// loadContent() — see BookContentProvider.markdownAssetData for why this can't
    /// happen lazily during rendering (needs the folder's security-scoped access,
    /// which is only held open for the duration of that one call).
    @State private var markdownImages: [String: Data] = [:]
    @State private var unavailableReason: UnavailableReason?

    private enum UnavailableReason {
        case unsupportedFormat
        case loadFailed
    }

    @State private var readingTheme: ReadingTheme = .dark
    @State private var fontSize: Double = 1.0
    @State private var lineSpacing: Double = 1.4
    @State private var fontDesign: Font.Design = .default
    @State private var mode: ReaderLayoutMode = .paginated

    @State private var showSettingsSheet = false
    @State private var showBookmarksSheet = false
    @State private var showTocSheet = false
    /// Toggled by center-third taps in EPUB/PDF's paginated & scrolling content, and
    /// (#125) Markdown's own centre-tap gesture in MarkdownReaderContent — mirrors
    /// Android's ReaderViewModel.onCentreTap, which hides/shows the whole toolbar as
    /// an immersive-reading toggle, across all three formats there too.
    @State private var showToolbar = true
    /// One-shot scroll target set when the user taps a TOC entry — consumed by
    /// MarkdownReaderContent's onBlockScrollConsumed.
    @State private var pendingTocBlockIndex: Int?

    private var markdownToc: [MarkdownTocEntry] {
        guard let markdownBlocks else { return [] }
        return MarkdownDocumentParser.extractToc(from: markdownBlocks)
    }

    /// Live scroll fraction for Markdown, updated on every scroll tick (cheap,
    /// no I/O) but only persisted via updateMarkdownProgress() on `.onDisappear` —
    /// writing to UserDefaults on every scroll pixel would be wasteful, and EPUB/PDF
    /// elsewhere in this reader only persist at similarly discrete checkpoints
    /// (page-turn taps), not continuously.
    @State private var markdownScrollFraction: Double = 0

    @ObservedObject private var bridge = LibravaultDomainBridge.shared

    private var effectiveReadingTheme: ConcreteReadingTheme { readingTheme.resolved(for: systemColorScheme) }
    private var colors: LibraVaultColorScheme { .forReadingTheme(effectiveReadingTheme) }
    private var hasBookmarks: Bool { !(bridge.bookmarks[book.id]?.isEmpty ?? true) }
    private var isCurrentlyPlayingThisBook: Bool { appState.nowPlayingBook?.id == book.id }

    var body: some View {
        Group {
            if let unavailableReason {
                unavailableContent(reason: unavailableReason)
            } else if book.format == .markdown {
                if let markdownBlocks {
                    markdownContent(markdownBlocks)
                } else {
                    loadingContent
                }
            } else if book.format == .pdf {
                if let pdfDocument {
                    pdfContent(pdfDocument)
                } else {
                    loadingContent
                }
            } else if let chapters, !chapters.isEmpty {
                switch mode {
                case .paginated: paginatedContent
                case .scrolling: scrollingContent
                }
            } else {
                loadingContent
            }
        }
        .background(colors.background)
        .navigationTitle(book.title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            // Only Play/Bookmark stay as direct top-level icons — with a book title
            // competing for space in .inline display mode, 4-5 separate
            // navigationBarTrailing items reliably fit on CI's Simulator (a large
            // modern iPhone) but silently get dropped (not shown in an overflow
            // menu, just missing) on smaller real devices. Reported in the field as
            // "there's no + button to add a bookmark" — the button was always there
            // in code, it just didn't fit. That's also why Add/View Bookmark are one
            // combined tap/long-press icon below rather than two: adding a Play
            // button while keeping both separate would put the count right back at
            // 4. Everything lower-frequency lives behind a single overflow Menu,
            // which iOS always renders as one icon regardless of how many actions
            // are inside it.
            if ReaderSettingsAvailability.showReadAloud(for: book.format) {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button(action: toggleReadAloud) {
                        Image(systemName: isCurrentlyPlayingThisBook ? "pause.fill" : "play.fill")
                            .foregroundStyle(colors.onBackground)
                    }
                    .accessibilityIdentifier("reader.playButton")
                }
            }
            ToolbarItem(placement: .navigationBarTrailing) {
                // One combined control rather than separate add/view icons: a tap
                // opens the bookmarks sheet (view/edit/delete); holding past the
                // long-press threshold additionally adds a bookmark at the current
                // position first (with a haptic to confirm), and the same release
                // still opens the sheet afterward — so a long-press shows you the
                // bookmark it just added instead of leaving you to trust the icon
                // switching to its filled state alone.
                //
                // This stays a real Button (with the long-press layered on via
                // .simultaneousGesture) rather than a bare Image with
                // .onTapGesture/.onLongPressGesture — the latter computed an
                // invalid {-1, -1} hit point once hosted inside a nav-bar
                // ToolbarItem, which CI caught as a real, reproducible
                // testBookmarksSheetShowsAddedBookmark failure, not flakiness.
                Button(action: { showBookmarksSheet = true }) {
                    Image(systemName: hasBookmarks ? "bookmark.fill" : "bookmark")
                        .foregroundStyle(colors.onBackground)
                }
                .simultaneousGesture(
                    LongPressGesture(minimumDuration: 0.5).onEnded { _ in
                        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                        addBookmark()
                    }
                )
                .accessibilityIdentifier("reader.bookmarksButton")
            }
            ToolbarItem(placement: .navigationBarTrailing) {
                Menu {
                    Button(action: { readingTheme = readingTheme.next }) {
                        Label(readingTheme.label, systemImage: readingTheme.systemImageName)
                    }
                    .accessibilityIdentifier("reader.themeButton")

                    if book.format == .markdown {
                        Button(action: { showTocSheet = true }) {
                            Label("Table of Contents", systemImage: "list.bullet")
                        }
                        .accessibilityIdentifier("reader.tocButton")
                    }

                    Button(action: { showSettingsSheet = true }) {
                        Label("Reading Settings", systemImage: "textformat.size")
                    }
                    .accessibilityIdentifier("reader.settingsButton")
                } label: {
                    Image(systemName: "ellipsis.circle")
                        .foregroundStyle(colors.onBackground)
                }
                .accessibilityIdentifier("reader.moreMenuButton")
            }
        }
        .toolbar(showToolbar ? .visible : .hidden, for: .navigationBar)
        .sheet(isPresented: $showSettingsSheet) {
            ReaderSettingsSheet(
                theme: $readingTheme,
                fontSize: $fontSize,
                lineSpacing: $lineSpacing,
                fontDesign: $fontDesign,
                mode: $mode,
                showFontControls: ReaderSettingsAvailability.showFontControls(for: book.format),
                showLayoutMode: ReaderSettingsAvailability.showLayoutMode(for: book.format)
            )
        }
        .sheet(isPresented: $showBookmarksSheet) {
            BookmarksSheet(bookId: book.id, onNavigate: { navigateToBookmark($0) })
        }
        .sheet(isPresented: $showTocSheet) {
            MarkdownTocSheet(entries: markdownToc) { entry in
                pendingTocBlockIndex = entry.blockIndex
                showTocSheet = false
            }
        }
        // @EnvironmentObject isn't available at init time, so the Settings-configured
        // default can't be this @State var's initial value directly — apply it once
        // the view actually appears instead. Runs once per view identity (not on
        // every appearance), so cycling the theme in-session via the toolbar isn't
        // clobbered by revisits.
        .task {
            readingTheme = appState.defaultReadingTheme
            loadContent()
        }
        .onDisappear {
            if book.format == .markdown {
                updateMarkdownProgress()
            } else if book.format == .pdf {
                pdfEndAccess?()
                pdfEndAccess = nil
            }
        }
    }

    /// Content is loaded synchronously on the main thread deliberately —
    /// `NSAttributedString`'s HTML parsing (inside EPUBParser) requires it. For
    /// typical book sizes this is fast enough not to need a background hop; a very
    /// large file could cause a brief hitch, which is an acceptable tradeoff over the
    /// complexity of a thread-safe async EPUB pipeline for a first pass.
    private func loadContent() {
        if book.format == .markdown {
            do {
                let source = try BookContentProvider.markdownSource(for: book)
                let blocks = MarkdownDocumentParser.parse(source)
                markdownBlocks = blocks
                loadMarkdownImages(for: blocks)
            } catch BookContentProvider.ContentError.unsupportedFormat {
                unavailableReason = .unsupportedFormat
            } catch {
                unavailableReason = .loadFailed
            }
            return
        }

        if book.format == .pdf {
            do {
                let (document, endAccess) = try BookContentProvider.openPDFDocument(for: book)
                pdfDocument = document
                pdfEndAccess = endAccess
                let savedFraction = bridge.progress[book.id] ?? 0
                let lastPageIndex = document.pageCount - 1
                let restoredIndex = Int((savedFraction * Double(lastPageIndex)).rounded())
                pdfCurrentPageIndex = max(0, min(restoredIndex, lastPageIndex))
            } catch {
                // book.format == .pdf here, so openPDFDocument's own unsupportedFormat
                // guard can't fire — any failure is a real load problem (malformed
                // file, folder no longer resolvable, empty/corrupt document).
                unavailableReason = .loadFailed
            }
            return
        }

        do {
            let loaded = try BookContentProvider.chapters(for: book)
            guard !loaded.isEmpty else {
                unavailableReason = .loadFailed
                return
            }
            chapters = loaded
            // No page boundaries exist yet — the first repaginate(for:) call (driven
            // by paginatedContent's GeometryReader) consumes this to restore the
            // reader to roughly where it left off, the same way PDF's loadContent
            // does with pdfCurrentPageIndex above.
            let savedFraction = bridge.progress[book.id] ?? 0
            if savedFraction > 0 { pendingRestoreFraction = savedFraction }
        } catch BookContentProvider.ContentError.unsupportedFormat {
            unavailableReason = .unsupportedFormat
        } catch {
            // A format with a real parser (EPUB) failed for some other reason —
            // malformed file, folder no longer resolvable, etc.
            unavailableReason = .loadFailed
        }
    }

    /// Resolves and reads every `.image` block's bytes up front — see markdownImages'
    /// doc comment for why this can't happen lazily during rendering. A single image
    /// that fails to resolve (moved/deleted file, malformed reference) is skipped, not
    /// treated as a whole-document load failure — MarkdownBlockView shows a broken-
    /// image placeholder for any url missing from the dictionary.
    private func loadMarkdownImages(for blocks: [MarkdownBlock]) {
        var images: [String: Data] = [:]
        for block in blocks {
            guard case let .image(url, _) = block, images[url] == nil else { continue }
            if let data = try? BookContentProvider.markdownAssetData(for: book, relativePath: url) {
                images[url] = data
            }
        }
        markdownImages = images
    }

    /// Total real page count across every chapter — this, not `chapters?.count`
    /// (the EPUB spine-item count), is what "Page X of Y" and the progress bar read
    /// from. 0 before the first `repaginate(for:)` call has run.
    private var totalPageCount: Int {
        blockPagination?.reduce(0) { $0 + $1.count } ?? 0
    }

    /// 1-based page number for display, counting every page in every chapter before
    /// `currentChapterIndex` plus `currentPageInChapter`.
    private var globalPageNumber: Int {
        guard let blockPagination else { return 1 }
        let pagesBeforeCurrentChapter = blockPagination.prefix(currentChapterIndex).reduce(0) { $0 + $1.count }
        return pagesBeforeCurrentChapter + currentPageInChapter + 1
    }

    /// The blocks of the page currently on screen. Empty either before pagination has
    /// run yet or for a chapter with no blocks at all — a spine item `EPUBParser`
    /// extracted neither text nor images from (see emptyPageNotice). An image-only
    /// spine item is *not* this case since #360: it has real `.image` blocks, so
    /// `BlockPaginator` gives it real pages instead of the empty-page fallback.
    private var currentPageBlocks: [MarkdownBlock] {
        guard let blockPagination,
              currentChapterIndex < blockPagination.count,
              currentPageInChapter < blockPagination[currentChapterIndex].count
        else { return [] }
        return blockPagination[currentChapterIndex][currentPageInChapter]
    }

    /// Resolved image bytes for the chapter currently on screen — passed to
    /// `MarkdownBlockView` alongside `currentPageBlocks`/the chapter's whole `blocks`
    /// (scrolling mode), mirroring `BookChapter.images`' per-chapter keying.
    private var currentChapterImages: [String: Data] {
        guard let chapters, currentChapterIndex < chapters.count else { return [:] }
        return chapters[currentChapterIndex].images
    }

    private var hasPreviousPage: Bool {
        guard let blockPagination, currentChapterIndex < blockPagination.count else { return false }
        if currentPageInChapter > 0 { return true }
        return blockPagination[..<currentChapterIndex].contains { !$0.isEmpty }
    }

    private var hasNextPage: Bool {
        guard let blockPagination, currentChapterIndex < blockPagination.count else { return false }
        if currentPageInChapter + 1 < blockPagination[currentChapterIndex].count { return true }
        return blockPagination[(currentChapterIndex + 1)...].contains { !$0.isEmpty }
    }

    /// Advances one screen-sized page, crossing into the next chapter's first
    /// non-empty page once the current chapter is exhausted (a chapter with no blocks
    /// at all paginates to zero pages and must be skipped over, not landed on).
    private func goToNextPage() {
        guard let blockPagination, currentChapterIndex < blockPagination.count else { return }
        if currentPageInChapter + 1 < blockPagination[currentChapterIndex].count {
            currentPageInChapter += 1
            updateProgress()
            return
        }
        var nextChapterIndex = currentChapterIndex + 1
        while nextChapterIndex < blockPagination.count, blockPagination[nextChapterIndex].isEmpty {
            nextChapterIndex += 1
        }
        guard nextChapterIndex < blockPagination.count else { return }
        currentChapterIndex = nextChapterIndex
        currentPageInChapter = 0
        updateProgress()
    }

    /// Mirror of goToNextPage() — lands on the previous chapter's *last* non-empty
    /// page, since that's the page immediately before the current chapter's first.
    private func goToPreviousPage() {
        guard let blockPagination, currentChapterIndex < blockPagination.count else { return }
        if currentPageInChapter > 0 {
            currentPageInChapter -= 1
            updateProgress()
            return
        }
        var previousChapterIndex = currentChapterIndex - 1
        while previousChapterIndex >= 0, blockPagination[previousChapterIndex].isEmpty {
            previousChapterIndex -= 1
        }
        guard previousChapterIndex >= 0 else { return }
        currentChapterIndex = previousChapterIndex
        currentPageInChapter = max(blockPagination[previousChapterIndex].count - 1, 0)
        updateProgress()
    }

    /// The chapter index + flat block index (into that chapter's `blocks` array) the
    /// reader is currently showing, in the *current* block pagination — captured
    /// before a repagination replaces `blockPagination` wholesale, so the new
    /// pagination can locate the same block again afterward. nil before the first
    /// pagination has run (nothing to anchor to yet).
    private func currentBlockAnchor() -> (chapterIndex: Int, blockIndex: Int)? {
        guard let blockPagination,
              currentChapterIndex < blockPagination.count,
              currentPageInChapter < blockPagination[currentChapterIndex].count
        else { return nil }
        let blockIndex = BlockPaginator.firstBlockIndex(
            ofPage: currentPageInChapter,
            in: blockPagination[currentChapterIndex]
        )
        return (currentChapterIndex, blockIndex)
    }

    private func locate(chapterIndex: Int, blockIndex: Int, in newBlockPagination: [[[MarkdownBlock]]]) {
        guard chapterIndex >= 0, chapterIndex < newBlockPagination.count else { return }
        currentChapterIndex = chapterIndex
        currentPageInChapter = BlockPaginator.pageIndex(containingBlockIndex: blockIndex, in: newBlockPagination[chapterIndex])
    }

    /// Resolves a saved reading-progress fraction (see pendingRestoreFraction) into a
    /// (chapter, page) against a freshly computed pagination — mirrors PDF's
    /// `savedFraction * lastPageIndex` restore math in loadContent(), just expressed
    /// over the two-level chapter/page index instead of PDFKit's flat page count.
    private func locate(globalFraction fraction: Double, in newBlockPagination: [[[MarkdownBlock]]]) {
        let total = newBlockPagination.reduce(0) { $0 + $1.count }
        guard total > 0 else { return }
        var remaining = min(max(Int((fraction * Double(total)).rounded()), 0), total - 1)
        for (chapterIndex, pages) in newBlockPagination.enumerated() {
            if remaining < pages.count {
                currentChapterIndex = chapterIndex
                currentPageInChapter = remaining
                return
            }
            remaining -= pages.count
        }
    }

    /// A `UIFont` matching the `Font.system(size:design:)` `paginatedContent` renders
    /// with — `BlockPaginator` needs a concrete `UIFont` (TextKit predates SwiftUI's
    /// `Font`), and page boundaries only match what's drawn if this mirrors that
    /// rendering call exactly.
    private static func uiFont(size: CGFloat, design: Font.Design) -> UIFont {
        let base = UIFont.systemFont(ofSize: size)
        let systemDesign: UIFontDescriptor.SystemDesign
        switch design {
        case .serif: systemDesign = .serif
        case .rounded: systemDesign = .rounded
        case .monospaced: systemDesign = .monospaced
        default: systemDesign = .default
        }
        guard let descriptor = base.fontDescriptor.withDesign(systemDesign) else { return base }
        return UIFont(descriptor: descriptor, size: size)
    }

    /// Re-derives `blockPagination` for every chapter against `size` (the space
    /// `paginatedContent`'s `GeometryReader` measured, reduced for this view's own
    /// `LibraVaultSpacing.lg` padding) and the current font settings — called from
    /// `paginatedContent` on appear and whenever size/fontSize/lineSpacing/fontDesign
    /// change. A no-op unless one of those actually changed since the last run:
    /// `GeometryReader` re-evaluates its closure on every body pass, not just real
    /// resizes, and TextKit layout over a whole chapter isn't free.
    ///
    /// Preserves the reader's visible position across the repagination — captures the
    /// current page's first block beforehand and re-locates the page containing that
    /// block afterward, rather than reusing the old page *index* blindly (indices
    /// shift; block identity doesn't, since a repagination only ever regroups whole
    /// blocks, never splits one — see BlockPaginator's own doc comment). On the very
    /// first run (no prior pagination to anchor from), resolves `pendingRestoreFraction`
    /// instead, if set.
    private func repaginate(for size: CGSize) {
        guard let chapters, !chapters.isEmpty else { return }
        let pageSize = CGSize(
            width: max(size.width - 2 * LibraVaultSpacing.lg, 0),
            height: max(size.height - 2 * LibraVaultSpacing.lg, 0)
        )
        guard pageSize.width > 0, pageSize.height > 0 else { return }

        let isFirstPagination = blockPagination == nil
        guard isFirstPagination
            || pageSize != lastPaginationSize
            || fontSize != lastPaginationFontSize
            || lineSpacing != lastPaginationLineSpacing
            || fontDesign != lastPaginationFontDesign
        else { return }

        let anchor = isFirstPagination ? nil : currentBlockAnchor()

        let font = Self.uiFont(size: 16 * fontSize, design: fontDesign)
        let newBlockPagination = chapters.map {
            BlockPaginator.paginate(blocks: $0.blocks, images: $0.images, font: font, lineSpacing: 8 * lineSpacing, pageSize: pageSize)
        }

        blockPagination = newBlockPagination
        lastPaginationSize = pageSize
        lastPaginationFontSize = fontSize
        lastPaginationLineSpacing = lineSpacing
        lastPaginationFontDesign = fontDesign

        if let anchor {
            locate(chapterIndex: anchor.chapterIndex, blockIndex: anchor.blockIndex, in: newBlockPagination)
        } else if let fraction = pendingRestoreFraction {
            pendingRestoreFraction = nil
            locate(globalFraction: fraction, in: newBlockPagination)
        } else {
            currentChapterIndex = min(currentChapterIndex, max(newBlockPagination.count - 1, 0))
            currentPageInChapter = 0
        }
    }

    private var paginatedContent: some View {
        VStack(spacing: 0) {
            GeometryReader { geometry in
                ScrollViewReader { scrollProxy in
                    ScrollView {
                        Group {
                            if currentPageBlocks.isEmpty {
                                emptyPageNotice
                            } else {
                                VStack(alignment: .leading, spacing: LibraVaultSpacing.md) {
                                    ForEach(Array(currentPageBlocks.enumerated()), id: \.offset) { _, block in
                                        MarkdownBlockView(
                                            block: block,
                                            images: currentChapterImages,
                                            colors: colors,
                                            fontSize: fontSize,
                                            lineSpacing: lineSpacing,
                                            fontDesign: fontDesign,
                                            readingTheme: readingTheme
                                        )
                                    }
                                }
                                .textSelection(.enabled)
                            }
                        }
                        .padding(LibraVaultSpacing.lg)
                        .id(globalPageNumber)
                    }
                    .frame(maxHeight: .infinity)
                    // The ScrollView keeps its offset across content swaps, so without this
                    // the < > buttons land the next page's text at the previous scroll
                    // position instead of the top — reported as janky page-to-page scrolling
                    // on Mac (Catalyst), where the trackpad makes the leftover offset obvious.
                    .onChange(of: globalPageNumber) { _, newPageNumber in
                        withAnimation {
                            scrollProxy.scrollTo(newPageNumber, anchor: .top)
                        }
                    }
                    // Left/right/center tap zones, mirroring Android's Readium
                    // DirectionalNavigationAdapter (edge taps flip pages) + centre-tap
                    // toolbar toggle. .simultaneous so long-press text selection above
                    // still works alongside plain taps.
                    .simultaneousGesture(
                        SpatialTapGesture(coordinateSpace: .local).onEnded { value in
                            handlePaginatedTap(x: value.location.x, width: geometry.size.width)
                        }
                    )
                    // Swipe-to-turn-page (issue #348 — Android's EpubNavigatorFragment
                    // gets this for free from Readium; iOS's custom TextPaginator-based
                    // pager only had the tap-zone half). .simultaneous for the same
                    // reason as the tap gesture above — ReaderSwipeGesture.classify
                    // requires a mostly-horizontal drag past a distance threshold, so a
                    // vertical text-selection drag doesn't also register as a page turn.
                    .simultaneousGesture(
                        DragGesture(minimumDistance: 20).onEnded { value in
                            handlePaginatedSwipe(translation: value.translation)
                        }
                    )
                }
                // Repaginate whenever the measured layout area changes (first
                // appearance, rotation, Split View / Slide Over resize on iPad) or the
                // type settings that affect layout change — repaginate(for:) itself
                // no-ops if none of those actually moved since the last run.
                .onAppear { repaginate(for: geometry.size) }
                .onChange(of: geometry.size) { _, newSize in repaginate(for: newSize) }
                .onChange(of: fontSize) { _, _ in repaginate(for: geometry.size) }
                .onChange(of: lineSpacing) { _, _ in repaginate(for: geometry.size) }
                .onChange(of: fontDesign) { _, _ in repaginate(for: geometry.size) }
            }

            if showToolbar {
                HStack(spacing: LibraVaultSpacing.lg) {
                    Button(action: goToPreviousPage) {
                        Image(systemName: "chevron.left")
                    }
                    .disabled(!hasPreviousPage)

                    Text("Page \(globalPageNumber) of \(max(totalPageCount, 1))")
                        .font(LibraVaultTypography.labelMedium)

                    ProgressView(value: Double(globalPageNumber), total: Double(max(totalPageCount, 1)))
                        .tint(colors.primary)
                        .frame(maxWidth: .infinity)

                    Button(action: goToNextPage) {
                        Image(systemName: "chevron.right")
                    }
                    .disabled(!hasNextPage)
                }
                .foregroundStyle(colors.onSurfaceVariant)
                .padding(LibraVaultSpacing.lg)
                .background(colors.surface)
            }
        }
    }

    /// Continuous scroll mode — unlike paginatedContent this shows whole chapters
    /// concatenated, so it has no notion of a screen-sized "page" and is untouched by
    /// BlockPaginator (issue #331/#359's pagination work is scoped to paginatedContent
    /// only) — each chapter renders its full `blocks` array directly, the same way
    /// MarkdownReaderContent scrolls a whole document's blocks.
    private var scrollingContent: some View {
        GeometryReader { geometry in
            ScrollView {
                VStack(alignment: .leading, spacing: LibraVaultSpacing.xl) {
                    ForEach(Array((chapters ?? []).enumerated()), id: \.offset) { _, chapter in
                        VStack(alignment: .leading, spacing: LibraVaultSpacing.md) {
                            ForEach(Array(chapter.blocks.enumerated()), id: \.offset) { _, block in
                                MarkdownBlockView(
                                    block: block,
                                    images: chapter.images,
                                    colors: colors,
                                    fontSize: fontSize,
                                    lineSpacing: lineSpacing,
                                    fontDesign: fontDesign,
                                    readingTheme: readingTheme
                                )
                            }
                        }
                    }
                }
                .padding(LibraVaultSpacing.lg)
                .textSelection(.enabled)
            }
            // Center-third only — there's no discrete "page" to flip to while
            // continuously scrolling, matching Android's PdfScrollingView (which is
            // center-tap-only for the same reason).
            .simultaneousGesture(
                SpatialTapGesture(coordinateSpace: .local).onEnded { value in
                    if ReaderTapZone.classify(x: value.location.x, width: geometry.size.width) == .center {
                        withAnimation { showToolbar.toggle() }
                    }
                }
            )
        }
    }

    private func handlePaginatedTap(x: CGFloat, width: CGFloat) {
        switch ReaderTapZone.classify(x: x, width: width) {
        case .previous:
            goToPreviousPage()
        case .next:
            goToNextPage()
        case .center:
            withAnimation { showToolbar.toggle() }
        }
    }

    private func handlePaginatedSwipe(translation: CGSize) {
        switch ReaderSwipeGesture.classify(translation: translation) {
        case .previous:
            goToPreviousPage()
        case .next:
            goToNextPage()
        case .none:
            break
        }
    }

    /// Real PDF page rendering via PDFKit — see PDFReaderContent's doc comment for
    /// why this exists instead of routing PDFs through the same extracted-text
    /// `chapters`/paginatedContent path EPUB uses. The bottom bar mirrors
    /// paginatedContent's, kept visible in both layout modes (unlike EPUB's
    /// scrollingContent, which has none) since PDFKit reports a real current page
    /// even while continuously scrolling.
    private func pdfContent(_ document: PDFDocument) -> some View {
        VStack(spacing: 0) {
            PDFReaderContent(
                document: document,
                mode: mode,
                currentPageIndex: $pdfCurrentPageIndex,
                backgroundColor: UIColor(colors.background),
                onCenterTap: { withAnimation { showToolbar.toggle() } }
            )
            .frame(maxHeight: .infinity)
            .onChange(of: pdfCurrentPageIndex) { _, _ in updatePDFProgress() }

            if showToolbar {
                HStack(spacing: LibraVaultSpacing.lg) {
                    Button(action: { if pdfCurrentPageIndex > 0 { pdfCurrentPageIndex -= 1 } }) {
                        Image(systemName: "chevron.left")
                    }
                    .disabled(pdfCurrentPageIndex <= 0)

                    Text("Page \(pdfCurrentPageIndex + 1) of \(document.pageCount)")
                        .font(LibraVaultTypography.labelMedium)

                    ProgressView(value: Double(pdfCurrentPageIndex), total: Double(max(document.pageCount - 1, 1)))
                        .tint(colors.primary)
                        .frame(maxWidth: .infinity)

                    Button(action: { if pdfCurrentPageIndex < document.pageCount - 1 { pdfCurrentPageIndex += 1 } }) {
                        Image(systemName: "chevron.right")
                    }
                    .disabled(pdfCurrentPageIndex >= document.pageCount - 1)
                }
                .foregroundStyle(colors.onSurfaceVariant)
                .padding(LibraVaultSpacing.lg)
                .background(colors.surface)
            }
        }
    }

    private func markdownContent(_ blocks: [MarkdownBlock]) -> some View {
        MarkdownReaderContent(
            blocks: blocks,
            images: markdownImages,
            colors: colors,
            fontSize: fontSize,
            lineSpacing: lineSpacing,
            fontDesign: fontDesign,
            readingTheme: effectiveReadingTheme,
            initialScrollFraction: bridge.progress[book.id] ?? 0,
            onScrollFractionChanged: { markdownScrollFraction = $0 },
            scrollToBlockIndex: pendingTocBlockIndex,
            onBlockScrollConsumed: { pendingTocBlockIndex = nil },
            onCenterTap: { withAnimation { showToolbar.toggle() } }
        )
    }

    /// A spine item with no extractable text is usually a legitimately text-free page
    /// (a full-bleed image plate, a decorative part divider). It used to render as an
    /// entirely empty screen, which readers reported as the page having failed to load
    /// (issue #108) — saying so explicitly distinguishes "nothing to read here" from
    /// "something broke", and keeps the page-turn controls reachable.
    private var emptyPageNotice: some View {
        VStack(spacing: LibraVaultSpacing.sm) {
            Image(systemName: "doc.plaintext")
                .font(.system(size: 32))
            Text("This page has no text")
                .font(LibraVaultTypography.bodyMedium)
        }
        .foregroundStyle(colors.onSurfaceVariant)
        .frame(maxWidth: .infinity, minHeight: 240)
        .accessibilityIdentifier("reader.emptyPageNotice")
    }

    private var loadingContent: some View {
        ProgressView()
            .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private func unavailableContent(reason: UnavailableReason) -> some View {
        VStack(spacing: LibraVaultSpacing.lg) {
            Image(systemName: reason == .unsupportedFormat ? "doc.questionmark" : "exclamationmark.triangle")
                .font(.system(size: 48))
                .foregroundStyle(colors.onSurfaceVariant)
            Text(reason == .unsupportedFormat ? "Format Not Yet Supported" : "Couldn't Open This Book")
                .font(LibraVaultTypography.headlineSmall)
                .foregroundStyle(colors.onBackground)
            Text(
                reason == .unsupportedFormat
                    ? "This book's format can't be read here yet."
                    : "The file couldn't be read. It may have been moved, deleted, or is corrupted."
            )
            .font(LibraVaultTypography.bodyMedium)
            .foregroundStyle(colors.onSurfaceVariant)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private func updateProgress() {
        // totalPageCount is legitimately 0 before the first pagination has run, and
        // (rarely) for a book whose every chapter has no blocks at all — either way
        // there's no real fraction to persist yet.
        guard totalPageCount > 0 else { return }
        Task {
            let progress = Double(globalPageNumber) / Double(totalPageCount)
            try? await bridge.updateProgress(bookId: book.id, progress: progress)
        }
    }

    private func updateMarkdownProgress() {
        Task {
            try? await bridge.updateProgress(bookId: book.id, progress: markdownScrollFraction)
        }
    }

    private func updatePDFProgress() {
        guard let pdfDocument, pdfDocument.pageCount > 1 else { return }
        Task {
            let progress = Double(pdfCurrentPageIndex) / Double(pdfDocument.pageCount - 1)
            try? await bridge.updateProgress(bookId: book.id, progress: progress)
        }
    }

    private func addBookmark() {
        Task {
            let position: String
            switch book.format {
            case .markdown: position = "scroll:\(markdownScrollFraction)"
            case .pdf: position = "Page \(pdfCurrentPageIndex + 1)"
            // A page *index* isn't stable across a repagination (font size, line
            // spacing, font design, or screen size changing all shift where page
            // boundaries fall), but a block's own identity is — BlockPaginator only
            // ever regroups whole blocks into pages, never splits one (see its doc
            // comment), so a flat block index survives both a fresh app launch's
            // re-parse of the same file and any future repagination. See
            // navigateToLocator below and BlockPaginator.pageIndex(containingBlockIndex:in:),
            // which resolves this back to a page. The trailing ":block" marks this as
            // a block index rather than the pre-#360 character offset the same
            // "Locator:" prefix used to store — see EPUBLocator.resolve(_:chapters:).
            default: position = "Locator:\(currentChapterIndex):\(currentBlockAnchor()?.blockIndex ?? 0):block"
            }
            try? await bridge.addBookmark(bookId: book.id, position: position)
        }
    }

    /// Jumps the reader to a saved bookmark's position and dismisses the sheet —
    /// mirrors Android's ReaderScreen bookmark-tap dispatch (ReaderScreen.kt), parsing
    /// the same position-string prefixes addBookmark() above writes.
    private func navigateToBookmark(_ bookmark: Bookmark) {
        if bookmark.position.hasPrefix("Page "), let page = Int(bookmark.position.dropFirst("Page ".count)) {
            let lastPageIndex = max((pdfDocument?.pageCount ?? 1) - 1, 0)
            pdfCurrentPageIndex = max(0, min(page - 1, lastPageIndex))
        } else if bookmark.position.hasPrefix("scroll:"), let fraction = Double(bookmark.position.dropFirst("scroll:".count)) {
            let blockCount = markdownBlocks?.count ?? 0
            if blockCount > 0 {
                pendingTocBlockIndex = min(max(Int((fraction * Double(blockCount)).rounded()), 0), blockCount - 1)
            }
        } else if bookmark.position.hasPrefix("Locator:") {
            navigateToLocator(bookmark.position)
        } else if bookmark.position.hasPrefix("Chapter "), let chapter = Int(bookmark.position.dropFirst("Chapter ".count)) {
            // Backward compatibility: bookmarks saved before issue #331 introduced the
            // "Locator:" format above have no character offset to resolve, only a
            // 1-based chapter number — land on that chapter's first page rather than
            // failing to navigate at all.
            let lastChapterIndex = max((chapters?.count ?? 1) - 1, 0)
            currentChapterIndex = max(0, min(chapter - 1, lastChapterIndex))
            currentPageInChapter = 0
        }
        showBookmarksSheet = false
    }

    /// Parses `"Locator:<chapterIndex>:<N>"` (see addBookmark) via
    /// `EPUBLocator.resolve(_:chapters:)` and resolves the resulting block index
    /// against the chapter's *current* block pagination — the reader is already open
    /// and laid out by the time bookmarks are navigable, so repaginate(for:) has
    /// already run and this doesn't need its own layout pass.
    ///
    /// `N` used to be a character offset (pre-#360, when EPUB rendered plain text);
    /// `EPUBLocator.resolve` tells that apart from a post-#360 block index using the
    /// locator string's trailing marker and maps a legacy offset onto its nearest
    /// block, so a pre-migration bookmark lands close to its original reading
    /// position instead of clamping to the chapter's last page.
    private func navigateToLocator(_ position: String) {
        guard let chapters, let resolved = EPUBLocator.resolve(position, chapters: chapters) else { return }

        currentChapterIndex = resolved.chapterIndex
        if let blockPagination, resolved.chapterIndex < blockPagination.count, !blockPagination[resolved.chapterIndex].isEmpty {
            currentPageInChapter = BlockPaginator.pageIndex(
                containingBlockIndex: resolved.blockIndex,
                in: blockPagination[resolved.chapterIndex]
            )
        } else {
            currentPageInChapter = 0
        }
    }

    /// The toolbar Play button hands off to the real Player screen (Phase 4) instead
    /// of toggling TTS in place — starts shared playback state and asks RootView to
    /// push PlayerView, the same trigger the mini-player's tap uses. For PDF,
    /// AppState.startPlayback's `chapter` parameter means "PDF page number" (it loads
    /// one BookChapter per page via PDFParser under the hood), so it takes
    /// pdfCurrentPageIndex there instead. Narration is chapter-granular regardless of
    /// on-screen page (BookContentProvider.chapters), so EPUB keeps using
    /// currentChapterIndex (+1 for the existing 1-based `chapter` param) rather than
    /// the finer-grained page position introduced by issue #331.
    private func toggleReadAloud() {
        if isCurrentlyPlayingThisBook {
            appState.stopPlayback()
        } else {
            let chapter = book.format == .pdf ? pdfCurrentPageIndex + 1 : currentChapterIndex + 1
            appState.startPlayback(book: book, chapter: chapter)
            appState.shouldNavigateToPlayer = true
        }
    }
}

#Preview {
    NavigationStack {
        ReaderView(book: BookItem(id: "1", title: "The Great Gatsby", author: "F. Scott Fitzgerald", progress: 0.35))
    }
    .environmentObject(AppState())
}
