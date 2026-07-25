import SwiftUI

struct ReaderView: View {
    let book: BookItem

    @State private var currentChapter = 1
    private let totalChapters = 5

    @State private var readingTheme: ReadingTheme = .dark
    @State private var fontSize: Double = 1.0
    @State private var lineSpacing: Double = 1.4
    @State private var fontDesign: Font.Design = .default
    @State private var mode: ReaderLayoutMode = .paginated

    @State private var showSettingsSheet = false
    @State private var showBookmarksSheet = false
    @State private var isSpeaking = false

    @ObservedObject private var bridge = LibravaultDomainBridge.shared

    private var colors: LibraVaultColorScheme { .forReadingTheme(readingTheme) }
    private var hasBookmarks: Bool { !(bridge.bookmarks[book.id]?.isEmpty ?? true) }

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
                Button(action: cycleTheme) {
                    Image(systemName: themeIcon)
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
                isSpeaking: isSpeaking,
                onToggleSpeaking: toggleSpeaking
            )
        }
        .sheet(isPresented: $showBookmarksSheet) {
            BookmarksSheet(bookId: book.id)
        }
    }

    private var paginatedContent: some View {
        VStack(spacing: 0) {
            ScrollView {
                Text(sampleChapterContent(chapter: currentChapter))
                    .font(.system(size: 16 * fontSize, design: fontDesign))
                    .lineSpacing(8 * lineSpacing)
                    .foregroundStyle(colors.onBackground)
                    .padding(LibraVaultSpacing.lg)
                    .textSelection(.enabled)
            }
            .frame(maxHeight: .infinity)

            HStack(spacing: LibraVaultSpacing.lg) {
                Button(action: { if currentChapter > 1 { currentChapter -= 1; updateProgress() } }) {
                    Image(systemName: "chevron.left")
                }
                .disabled(currentChapter <= 1)

                Text("Page \(currentChapter) of \(totalChapters)")
                    .font(LibraVaultTypography.labelMedium)

                ProgressView(value: Double(currentChapter) / Double(totalChapters))
                    .tint(colors.primary)
                    .frame(maxWidth: .infinity)

                Button(action: { if currentChapter < totalChapters { currentChapter += 1; updateProgress() } }) {
                    Image(systemName: "chevron.right")
                }
                .disabled(currentChapter >= totalChapters)
            }
            .foregroundStyle(colors.onSurfaceVariant)
            .padding(LibraVaultSpacing.lg)
            .background(colors.surface)
        }
    }

    private var scrollingContent: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: LibraVaultSpacing.xl) {
                ForEach(1...totalChapters, id: \.self) { chapter in
                    Text(sampleChapterContent(chapter: chapter))
                        .font(.system(size: 16 * fontSize, design: fontDesign))
                        .lineSpacing(8 * lineSpacing)
                        .foregroundStyle(colors.onBackground)
                }
            }
            .padding(LibraVaultSpacing.lg)
            .textSelection(.enabled)
        }
    }

    private var themeIcon: String {
        switch readingTheme {
        case .dark:  return "moon.fill"
        case .light: return "sun.max.fill"
        case .sepia: return "book.fill"
        }
    }

    private func cycleTheme() {
        switch readingTheme {
        case .dark:  readingTheme = .light
        case .light: readingTheme = .sepia
        case .sepia: readingTheme = .dark
        }
    }

    private func updateProgress() {
        Task {
            let progress = Double(currentChapter) / Double(totalChapters)
            try? await bridge.updateProgress(bookId: book.id, progress: progress)
        }
    }

    private func addBookmark() {
        Task {
            try? await bridge.addBookmark(bookId: book.id, position: "Chapter \(currentChapter)")
        }
    }

    private func toggleSpeaking() {
        if isSpeaking {
            isSpeaking = false
            Task { await bridge.stopSpeaking() }
        } else {
            isSpeaking = true
            Task { try? await bridge.startSpeaking(text: sampleChapterContent(chapter: currentChapter)) }
        }
    }

    private func sampleChapterContent(chapter: Int) -> String {
        let chapters = [
            "Chapter 1: The Beginning\n\nIt was a bright cold day in April, and the clocks were striking thirteen. The city stretched before them, vast and incomprehensible, full of secrets and mysteries waiting to be discovered.",
            "Chapter 2: Into the Depths\n\nThey ventured deeper into the ancient library, their footsteps echoing against stone walls. The air grew colder as they descended, and the books seemed to watch their progress with silent judgment.",
            "Chapter 3: The Discovery\n\nAmong the forgotten volumes, they found it—a manuscript bound in leather, its pages yellowed with age. The words seemed to shimmer, as if alive with their own peculiar power.",
            "Chapter 4: Revelations\n\nAs they read, the truth began to unfold. Every sentence was a thread, weaving together into a tapestry of understanding. What they had thought was lost was merely hidden, waiting for someone brave enough to seek it.",
            "Chapter 5: The Choice\n\nNow came the moment of decision. Would they close the book and return to their ordinary lives, or would they follow the path laid out before them, into territories unknown?",
        ]
        return chapters[(chapter - 1) % chapters.count]
    }
}

#Preview {
    NavigationStack {
        ReaderView(book: BookItem(id: "1", title: "The Great Gatsby", author: "F. Scott Fitzgerald", progress: 0.35))
    }
}
