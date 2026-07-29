import SwiftUI

struct ReaderView: View {
    let book: BookItem

    @EnvironmentObject var appState: AppState
    @State private var currentChapter = 1

    @State private var readingTheme: ReadingTheme = .dark
    @State private var fontSize: Double = 1.0
    @State private var lineSpacing: Double = 1.4
    @State private var fontDesign: Font.Design = .default
    @State private var mode: ReaderLayoutMode = .paginated

    @State private var showSettingsSheet = false
    @State private var showBookmarksSheet = false

    @ObservedObject private var bridge = LibravaultDomainBridge.shared

    private var colors: LibraVaultColorScheme { .forReadingTheme(readingTheme) }
    private var hasBookmarks: Bool { !(bridge.bookmarks[book.id]?.isEmpty ?? true) }
    private var isCurrentlyPlayingThisBook: Bool { appState.nowPlayingBook?.id == book.id }

    var body: some View {
        Group {
            switch mode {
            case .paginated: paginatedContent
            case .scrolling: scrollingContent
            }
        }
        .background(colors.background)
        .navigationTitle(book.title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Button(action: { readingTheme = readingTheme.next }) {
                    Image(systemName: readingTheme.systemImageName)
                        .foregroundStyle(colors.onBackground)
                }
                .accessibilityIdentifier("reader.themeButton")
            }
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
                Button(action: { showSettingsSheet = true }) {
                    Image(systemName: "textformat.size")
                        .foregroundStyle(colors.onBackground)
                }
                .accessibilityIdentifier("reader.settingsButton")
            }
        }
        .sheet(isPresented: $showSettingsSheet) {
            ReaderSettingsSheet(
                theme: $readingTheme,
                fontSize: $fontSize,
                lineSpacing: $lineSpacing,
                fontDesign: $fontDesign,
                mode: $mode,
                isSpeaking: isCurrentlyPlayingThisBook,
                onToggleSpeaking: toggleReadAloud
            )
        }
        .sheet(isPresented: $showBookmarksSheet) {
            BookmarksSheet(bookId: book.id)
        }
        // @EnvironmentObject isn't available at init time, so the Settings-configured
        // default can't be this @State var's initial value directly — apply it once
        // the view actually appears instead. Runs once per view identity (not on
        // every appearance), so cycling the theme in-session via the toolbar isn't
        // clobbered by revisits.
        .task {
            readingTheme = appState.defaultReadingTheme
        }
    }

    private var paginatedContent: some View {
        VStack(spacing: 0) {
            ScrollViewReader { scrollProxy in
                ScrollView {
                    Text(MockChapterContent.text(for: currentChapter))
                        .font(.system(size: 16 * fontSize, design: fontDesign))
                        .lineSpacing(8 * lineSpacing)
                        .foregroundStyle(colors.onBackground)
                        .padding(LibraVaultSpacing.lg)
                        .textSelection(.enabled)
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
            }

            HStack(spacing: LibraVaultSpacing.lg) {
                Button(action: { if currentChapter > 1 { currentChapter -= 1; updateProgress() } }) {
                    Image(systemName: "chevron.left")
                }
                .disabled(currentChapter <= 1)

                Text("Page \(currentChapter) of \(MockChapterContent.count)")
                    .font(LibraVaultTypography.labelMedium)

                ProgressView(value: Double(currentChapter) / Double(MockChapterContent.count))
                    .tint(colors.primary)
                    .frame(maxWidth: .infinity)

                Button(action: { if currentChapter < MockChapterContent.count { currentChapter += 1; updateProgress() } }) {
                    Image(systemName: "chevron.right")
                }
                .disabled(currentChapter >= MockChapterContent.count)
            }
            .foregroundStyle(colors.onSurfaceVariant)
            .padding(LibraVaultSpacing.lg)
            .background(colors.surface)
        }
    }

    private var scrollingContent: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: LibraVaultSpacing.xl) {
                ForEach(1...MockChapterContent.count, id: \.self) { chapter in
                    Text(MockChapterContent.text(for: chapter))
                        .font(.system(size: 16 * fontSize, design: fontDesign))
                        .lineSpacing(8 * lineSpacing)
                        .foregroundStyle(colors.onBackground)
                }
            }
            .padding(LibraVaultSpacing.lg)
            .textSelection(.enabled)
        }
    }

    private func updateProgress() {
        Task {
            let progress = Double(currentChapter) / Double(MockChapterContent.count)
            try? await bridge.updateProgress(bookId: book.id, progress: progress)
        }
    }

    private func addBookmark() {
        Task {
            try? await bridge.addBookmark(bookId: book.id, position: "Chapter \(currentChapter)")
        }
    }

    /// "Read Aloud" now hands off to the real Player screen (Phase 4) instead of
    /// toggling TTS in place — starts shared playback state and asks RootView to
    /// push PlayerView, the same trigger the mini-player's tap uses.
    private func toggleReadAloud() {
        showSettingsSheet = false
        if isCurrentlyPlayingThisBook {
            appState.stopPlayback()
        } else {
            appState.startPlayback(book: book, chapter: currentChapter)
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
