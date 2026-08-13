import SwiftUI
import PDFKit

struct ReaderView: View {
    let book: BookItem

    @EnvironmentObject var appState: AppState
    @State private var currentChapter = 1
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
            // Only Add/View Bookmark stay as direct top-level icons — with a book
            // title competing for space in .inline display mode, 4-5 separate
            // navigationBarTrailing items reliably fit on CI's Simulator (a large
            // modern iPhone) but silently get dropped (not shown in an overflow
            // menu, just missing) on smaller real devices. Reported in the field as
            // "there's no + button to add a bookmark" — the button was always there
            // in code, it just didn't fit. Everything lower-frequency now lives
            // behind a single overflow Menu, which iOS always renders as one icon
            // regardless of how many actions are inside it.
            ToolbarItem(placement: .navigationBarTrailing) {
                Button(action: addBookmark) {
                    Image(systemName: "bookmark.badge.plus")
                        .foregroundStyle(colors.onBackground)
                }
                .accessibilityIdentifier("reader.addBookmarkButton")
            }
            ToolbarItem(placement: .navigationBarTrailing) {
                Button(action: { showBookmarksSheet = true }) {
                    Image(systemName: hasBookmarks ? "bookmark.fill" : "bookmark")
                        .foregroundStyle(colors.onBackground)
                }
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
                isSpeaking: isCurrentlyPlayingThisBook,
                onToggleSpeaking: toggleReadAloud,
                showFontControls: ReaderSettingsAvailability.showFontControls(for: book.format),
                showReadAloud: ReaderSettingsAvailability.showReadAloud(for: book.format),
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

    private var paginatedContent: some View {
        VStack(spacing: 0) {
            GeometryReader { geometry in
                ScrollViewReader { scrollProxy in
                    ScrollView {
                        Group {
                            if chapterText(for: currentChapter).isEmpty {
                                emptyPageNotice
                            } else {
                                Text(chapterText(for: currentChapter))
                                    .font(.system(size: 16 * fontSize, design: fontDesign))
                                    .lineSpacing(8 * lineSpacing)
                                    .foregroundStyle(colors.onBackground)
                                    .textSelection(.enabled)
                            }
                        }
                        .padding(LibraVaultSpacing.lg)
                        .id(currentChapter)
                    }
                    .frame(maxHeight: .infinity)
                    // The ScrollView keeps its offset across content swaps, so without this
                    // the < > buttons land the next chapter's text at the previous scroll
                    // position instead of the top — reported as janky page-to-page scrolling
                    // on Mac (Catalyst), where the trackpad makes the leftover offset obvious.
                    .onChange(of: currentChapter) { _, newChapter in
                        withAnimation {
                            scrollProxy.scrollTo(newChapter, anchor: .top)
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
                }
            }

            if showToolbar {
                HStack(spacing: LibraVaultSpacing.lg) {
                    Button(action: { if currentChapter > 1 { currentChapter -= 1; updateProgress() } }) {
                        Image(systemName: "chevron.left")
                    }
                    .disabled(currentChapter <= 1)

                    Text("Page \(currentChapter) of \(chapterCount)")
                        .font(LibraVaultTypography.labelMedium)

                    ProgressView(value: Double(currentChapter) / Double(chapterCount))
                        .tint(colors.primary)
                        .frame(maxWidth: .infinity)

                    Button(action: { if currentChapter < chapterCount { currentChapter += 1; updateProgress() } }) {
                        Image(systemName: "chevron.right")
                    }
                    .disabled(currentChapter >= chapterCount)
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
            if currentChapter > 1 { currentChapter -= 1; updateProgress() }
        case .next:
            if currentChapter < chapterCount { currentChapter += 1; updateProgress() }
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
        Task {
            let progress = Double(currentChapter) / Double(chapterCount)
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
            default: position = "Chapter \(currentChapter)"
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
        } else if bookmark.position.hasPrefix("Chapter "), let chapter = Int(bookmark.position.dropFirst("Chapter ".count)) {
            currentChapter = max(1, min(chapter, max(chapterCount, 1)))
        }
        showBookmarksSheet = false
    }

    /// "Read Aloud" now hands off to the real Player screen (Phase 4) instead of
    /// toggling TTS in place — starts shared playback state and asks RootView to
    /// push PlayerView, the same trigger the mini-player's tap uses. For PDF,
    /// AppState.startPlayback's `chapter` parameter means "PDF page number" (it loads
    /// one BookChapter per page via PDFParser under the hood), so it takes
    /// pdfCurrentPageIndex there instead of the EPUB-only `currentChapter` state.
    private func toggleReadAloud() {
        showSettingsSheet = false
        if isCurrentlyPlayingThisBook {
            appState.stopPlayback()
        } else {
            let chapter = book.format == .pdf ? pdfCurrentPageIndex + 1 : currentChapter
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
