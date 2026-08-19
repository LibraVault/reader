import SwiftUI
import PDFKit
import UIKit

/// A page's position expressed as a character offset within its chapter rather than a
/// page index — indices shift whenever font/line-spacing/screen-size changes trigger a
/// repagination, but an offset into the chapter's extracted text does not. See
/// `TextPaginator.startOffset(of:in:)`/`pageIndex(containingOffset:in:pages:)`, and
/// issue #331.
private struct ReaderPositionAnchor {
    let chapterIndex: Int
    let charOffset: Int
}

/// The inputs that determine `TextPaginator.paginate`'s output for every chapter.
/// `ReaderView.repaginate(for:)` compares this against the last value it paginated at
/// and no-ops when nothing has actually changed — `GeometryReader` reports on every
/// body evaluation, and laying out a whole chapter with TextKit isn't free.
private struct ReaderPaginationSignature: Equatable {
    let pageSize: CGSize
    let fontSize: Double
    let lineSpacing: Double
    let fontDesign: Font.Design
}

struct ReaderView: View {
    let book: BookItem

    @EnvironmentObject var appState: AppState
    /// Real per-screen pages for the current EPUB, one array per chapter — populated by
    /// `repaginate(for:)`. nil until the first layout pass has a size to paginate at.
    /// See issue #331: this used to not exist at all, and "page" meant "chapter".
    @State private var pagination: [[Range<String.Index>]]?
    @State private var lastPaginationSignature: ReaderPaginationSignature?
    @State private var currentChapterIndex = 0
    @State private var currentPageInChapter = 0
    /// Real chapters for formats with a parser wired up (EPUB only — PDF used to
    /// share this reflowed-text path too, see PDFReaderContent's doc comment for why
    /// that changed). nil until `loadContent()` resolves — briefly renders
    /// `loadingContent` — then stays nil permanently if loading failed, in which case
    /// `unavailableReason` explains why.
    @State private var chapters: [BookChapter]?
    /// Real PDF document for on-screen page rendering — populated by loadContent()
    /// instead of `chapters` when book.format == .pdf. See PDFReaderContent.
    @State private var pdfDocument: PDFDocument?
    /// Releases the vault security scope BookContentProvider.openPDFDocument started
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
    /// happen lazily during rendering (needs the vault's security-scoped access,
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

    private var colors: LibraVaultColorScheme { .forReadingTheme(readingTheme) }
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
                // file, vault no longer resolvable, empty/corrupt document).
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
        } catch BookContentProvider.ContentError.unsupportedFormat {
            unavailableReason = .unsupportedFormat
        } catch {
            // A format with a real parser (EPUB) failed for some other reason —
            // malformed file, vault no longer resolvable, etc.
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

    private var chapterCount: Int { chapters?.count ?? 0 }

    private func chapterText(for index: Int) -> String {
        guard let chapters, !chapters.isEmpty else { return "" }
        return chapters[(index - 1) % chapters.count].text
    }

    /// The real, screen-sized page total across every chapter — what "Page X of Y" and
    /// the progress bar read from, replacing the old `chapterCount` (spine-item count).
    private var totalPageCount: Int {
        pagination?.reduce(0) { $0 + $1.count } ?? 0
    }

    /// 1-based position of the current page across the whole book. Clamped to
    /// `totalPageCount` so a text-free trailing chapter (0 pages of its own) can't push
    /// this past the total it's being displayed against.
    private var globalPageNumber: Int {
        guard let pagination, currentChapterIndex < pagination.count else { return 0 }
        let precedingPages = pagination[..<currentChapterIndex].reduce(0) { $0 + $1.count }
        return min(precedingPages + currentPageInChapter + 1, max(totalPageCount, 1))
    }

    private var currentPageText: String {
        guard let chapters, let pagination,
              currentChapterIndex < chapters.count,
              currentChapterIndex < pagination.count,
              currentPageInChapter < pagination[currentChapterIndex].count
        else { return "" }
        let text = chapters[currentChapterIndex].text
        return String(text[pagination[currentChapterIndex][currentPageInChapter]])
    }

    private var isAtFirstPage: Bool {
        currentChapterIndex == 0 && currentPageInChapter == 0
    }

    private var isAtLastPage: Bool {
        guard let pagination, !pagination.isEmpty else { return true }
        let lastChapterIndex = pagination.count - 1
        let lastPageIndex = max(pagination[lastChapterIndex].count - 1, 0)
        return currentChapterIndex >= lastChapterIndex && currentPageInChapter >= lastPageIndex
    }

    /// Maps `Font.Design` (SwiftUI, used for `.font(.system(size:design:))` below) onto
    /// the `UIFontDescriptor.SystemDesign` TextKit needs, so `TextPaginator` lays out
    /// text with the same font the view actually renders it with.
    private func paginationFont(size: CGFloat, design: Font.Design) -> UIFont {
        let systemDesign: UIFontDescriptor.SystemDesign
        switch design {
        case .serif: systemDesign = .serif
        case .monospaced: systemDesign = .monospaced
        case .rounded: systemDesign = .rounded
        default: systemDesign = .default
        }
        let base = UIFont.systemFont(ofSize: size)
        guard let descriptor = base.fontDescriptor.withDesign(systemDesign) else { return base }
        return UIFont(descriptor: descriptor, size: size)
    }

    /// Captures the current page's position as a (chapter, character-offset) pair —
    /// stable across a repagination, unlike `currentPageInChapter`'s raw index.
    private func currentPositionAnchor() -> ReaderPositionAnchor? {
        guard let chapters, let pagination,
              currentChapterIndex < chapters.count,
              currentChapterIndex < pagination.count,
              currentPageInChapter < pagination[currentChapterIndex].count
        else { return nil }
        let text = chapters[currentChapterIndex].text
        let offset = TextPaginator.startOffset(of: pagination[currentChapterIndex][currentPageInChapter], in: text)
        return ReaderPositionAnchor(chapterIndex: currentChapterIndex, charOffset: offset)
    }

    /// Re-locates `anchor` inside a freshly computed `pagination` and updates
    /// `currentChapterIndex`/`currentPageInChapter` to match, so changing type
    /// settings or rotating the device keeps the visible position instead of jumping
    /// to a random page.
    private func restorePosition(_ anchor: ReaderPositionAnchor?, in pagination: [[Range<String.Index>]]) {
        guard let anchor, let chapters,
              anchor.chapterIndex < chapters.count,
              anchor.chapterIndex < pagination.count
        else {
            currentChapterIndex = 0
            currentPageInChapter = 0
            return
        }
        let text = chapters[anchor.chapterIndex].text
        let pages = pagination[anchor.chapterIndex]
        currentChapterIndex = anchor.chapterIndex
        currentPageInChapter = TextPaginator.pageIndex(containingOffset: anchor.charOffset, in: text, pages: pages) ?? 0
    }

    /// Recomputes `pagination` for every chapter at the given viewport size, using the
    /// current font size/line spacing/font design — the same inputs `paginatedContent`
    /// renders with, so page boundaries match what's actually drawn. A no-op unless
    /// those inputs actually changed since the last call (see `ReaderPaginationSignature`).
    private func repaginate(for viewportSize: CGSize) {
        guard let chapters, !chapters.isEmpty else { return }
        let pageSize = CGSize(
            width: viewportSize.width - 2 * LibraVaultSpacing.lg,
            height: viewportSize.height - 2 * LibraVaultSpacing.lg
        )
        guard pageSize.width > 0, pageSize.height > 0 else { return }

        let signature = ReaderPaginationSignature(
            pageSize: pageSize, fontSize: fontSize, lineSpacing: lineSpacing, fontDesign: fontDesign
        )
        guard signature != lastPaginationSignature else { return }

        let anchor = currentPositionAnchor()
        let font = paginationFont(size: CGFloat(16 * fontSize), design: fontDesign)
        let scaledLineSpacing = CGFloat(8 * lineSpacing)

        let newPagination = chapters.map {
            TextPaginator.paginate(text: $0.text, font: font, lineSpacing: scaledLineSpacing, pageSize: pageSize)
        }
        pagination = newPagination
        lastPaginationSignature = signature
        restorePosition(anchor, in: newPagination)
    }

    private func goToNextPage() {
        guard let pagination, currentChapterIndex < pagination.count else { return }
        let pagesInChapter = pagination[currentChapterIndex].count
        if currentPageInChapter < pagesInChapter - 1 {
            currentPageInChapter += 1
            updateProgress()
        } else if currentChapterIndex < pagination.count - 1 {
            currentChapterIndex += 1
            currentPageInChapter = 0
            updateProgress()
        }
    }

    private func goToPreviousPage() {
        guard let pagination, currentChapterIndex < pagination.count else { return }
        if currentPageInChapter > 0 {
            currentPageInChapter -= 1
            updateProgress()
        } else if currentChapterIndex > 0 {
            currentChapterIndex -= 1
            currentPageInChapter = max(pagination[currentChapterIndex].count - 1, 0)
            updateProgress()
        }
    }

    private var paginatedContent: some View {
        VStack(spacing: 0) {
            GeometryReader { geometry in
                ScrollViewReader { scrollProxy in
                    ScrollView {
                        Group {
                            if pagination == nil {
                                loadingContent
                            } else if currentPageText.isEmpty {
                                emptyPageNotice
                            } else {
                                Text(currentPageText)
                                    .font(.system(size: 16 * fontSize, design: fontDesign))
                                    .lineSpacing(8 * lineSpacing)
                                    .foregroundStyle(colors.onBackground)
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
                    .onAppear { repaginate(for: geometry.size) }
                    .onChange(of: geometry.size) { _, newSize in repaginate(for: newSize) }
                    .onChange(of: fontSize) { _, _ in repaginate(for: geometry.size) }
                    .onChange(of: lineSpacing) { _, _ in repaginate(for: geometry.size) }
                    .onChange(of: fontDesign) { _, _ in repaginate(for: geometry.size) }
                    // Left/right/center tap zones, mirroring Android's Readium
                    // DirectionalNavigationAdapter (edge taps flip pages) + centre-tap
                    // toolbar toggle. .simultaneous so long-press text selection above
                    // still works alongside plain taps.
                    .simultaneousGesture(
                        SpatialTapGesture(coordinateSpace: .local).onEnded { value in
                            handlePaginatedTap(x: value.location.x, width: geometry.size.width)
                        }
                    )
                }
            }

            if showToolbar {
                HStack(spacing: LibraVaultSpacing.lg) {
                    Button(action: goToPreviousPage) {
                        Image(systemName: "chevron.left")
                    }
                    .disabled(isAtFirstPage)

                    Text("Page \(globalPageNumber) of \(totalPageCount)")
                        .font(LibraVaultTypography.labelMedium)

                    ProgressView(value: Double(globalPageNumber), total: Double(max(totalPageCount, 1)))
                        .tint(colors.primary)
                        .frame(maxWidth: .infinity)

                    Button(action: goToNextPage) {
                        Image(systemName: "chevron.right")
                    }
                    .disabled(isAtLastPage)
                }
                .foregroundStyle(colors.onSurfaceVariant)
                .padding(LibraVaultSpacing.lg)
                .background(colors.surface)
            }
        }
    }

    private var scrollingContent: some View {
        GeometryReader { geometry in
            ScrollView {
                VStack(alignment: .leading, spacing: LibraVaultSpacing.xl) {
                    ForEach(1...chapterCount, id: \.self) { chapter in
                        Text(chapterText(for: chapter))
                            .font(.system(size: 16 * fontSize, design: fontDesign))
                            .lineSpacing(8 * lineSpacing)
                            .foregroundStyle(colors.onBackground)
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
            readingTheme: readingTheme,
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

    /// `"Locator:<chapterIndex>:<charOffset>"` — chapter index and character offset are
    /// both stable across a fresh parse of the same file and across repagination,
    /// unlike a page index. Replaces the old `"Chapter N"` format (see
    /// `navigateToBookmark` below for the backward-compatible read side).
    private func currentLocatorPosition() -> String {
        guard let chapters, currentChapterIndex < chapters.count else {
            return "Locator:\(currentChapterIndex):0"
        }
        let offset = currentPositionAnchor()?.charOffset ?? 0
        return "Locator:\(currentChapterIndex):\(offset)"
    }

    private func addBookmark() {
        Task {
            let position: String
            switch book.format {
            case .markdown: position = "scroll:\(markdownScrollFraction)"
            case .pdf: position = "Page \(pdfCurrentPageIndex + 1)"
            default: position = currentLocatorPosition()
            }
            try? await bridge.addBookmark(bookId: book.id, position: position)
        }
    }

    /// Moves to the chapter/character-offset pair a locator bookmark points at,
    /// re-locating it against the current pagination the same way `restorePosition`
    /// does after a repagination.
    private func navigateToLocator(chapterIndex: Int, charOffset: Int) {
        guard let chapters, !chapters.isEmpty else { return }
        let clampedChapterIndex = max(0, min(chapterIndex, chapters.count - 1))
        currentChapterIndex = clampedChapterIndex
        guard let pagination, clampedChapterIndex < pagination.count else {
            currentPageInChapter = 0
            return
        }
        let text = chapters[clampedChapterIndex].text
        let pages = pagination[clampedChapterIndex]
        currentPageInChapter = TextPaginator.pageIndex(containingOffset: charOffset, in: text, pages: pages) ?? 0
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
            let parts = bookmark.position.dropFirst("Locator:".count).split(separator: ":")
            if parts.count == 2, let chapterIndex = Int(parts[0]), let charOffset = Int(parts[1]) {
                navigateToLocator(chapterIndex: chapterIndex, charOffset: charOffset)
            }
        } else if bookmark.position.hasPrefix("Chapter "), let chapter = Int(bookmark.position.dropFirst("Chapter ".count)) {
            // Backward compatibility: bookmarks saved before #331 stored a 1-based
            // chapter number with no page granularity — land on that chapter's first
            // page rather than failing to navigate at all.
            navigateToLocator(chapterIndex: chapter - 1, charOffset: 0)
        }
        showBookmarksSheet = false
    }

    /// The toolbar Play button hands off to the real Player screen (Phase 4) instead
    /// of toggling TTS in place — starts shared playback state and asks RootView to
    /// push PlayerView, the same trigger the mini-player's tap uses. For PDF,
    /// AppState.startPlayback's `chapter` parameter means "PDF page number" (it loads
    /// one BookChapter per page via PDFParser under the hood), so it takes
    /// pdfCurrentPageIndex there instead of the EPUB-only `currentChapterIndex` state.
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
