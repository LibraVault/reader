import SwiftUI
import UIKit

enum LibraryFormatFilter: String, CaseIterable {
    case all = "All"
    case epub = "EPUB"
    case pdf = "PDF"
    case markdown = "MD"
    case audio = "Audio"

    func matches(_ format: MediaFormat) -> Bool {
        switch self {
        case .all:      return true
        case .epub:     return format == .epub
        case .pdf:      return format == .pdf
        case .markdown: return format == .markdown
        case .audio:    return format.isAudio
        }
    }
}

struct LibraryView: View {
    @EnvironmentObject var appState: AppState
    @State private var searchText = ""
    @State private var formatFilter: LibraryFormatFilter = .all

    private var continueBooks: [BookItem] {
        appState.books.filter { $0.progress > 0 && $0.progress < 1 }
    }

    private var filteredBooks: [BookItem] {
        let byFormat = appState.books.filter { formatFilter.matches($0.format) }
        guard !searchText.isEmpty else { return byFormat }
        return byFormat.filter { book in
            book.title.localizedCaseInsensitiveContains(searchText) ||
            book.author.localizedCaseInsensitiveContains(searchText)
        }
    }

    var body: some View {
        Group {
            if appState.isLoading {
                ProgressView()
            } else if filteredBooks.isEmpty {
                emptyState
            } else {
                ScrollView {
                    VStack(alignment: .leading, spacing: LibraVaultSpacing.xl) {
                        if !continueBooks.isEmpty {
                            continueSection
                        }
                        formatFilterChips
                        librarySection
                    }
                    .padding(LibraVaultSpacing.lg)
                }
            }
        }
        .background(LibraVaultColor.background)
        .navigationTitle("Library")
        .navigationBarTitleDisplayMode(.inline)
        .searchable(text: $searchText, prompt: "Search title, author")
        .toolbar {
            ToolbarItem(placement: .principal) {
                HStack(spacing: LibraVaultSpacing.sm) {
                    Text("LibraVault")
                        .font(LibraVaultTypography.headlineSmall)
                        .foregroundStyle(LibraVaultColor.primary)
                    if appState.isSupporter {
                        SupporterBadge()
                    }
                }
            }
            ToolbarItem(placement: .navigationBarTrailing) {
                Button(action: { Task { await appState.loadLibrary() } }) {
                    Image(systemName: "arrow.clockwise")
                }
            }
            ToolbarItem(placement: .navigationBarTrailing) {
                NavigationLink(destination: SettingsView()) {
                    Image(systemName: "gear")
                }
                .accessibilityIdentifier("libraryToolbar.settingsButton")
            }
        }
        .onAppear {
            Task {
                await appState.loadLibrary()
            }
        }
    }

    private var emptyState: some View {
        VStack(spacing: LibraVaultSpacing.lg) {
            Image(systemName: "books.vertical")
                .font(.system(size: 48))
                .foregroundStyle(LibraVaultColor.onSurfaceVariant)
            Text("No Books Found")
                .font(LibraVaultTypography.headlineSmall)
                .foregroundStyle(LibraVaultColor.onBackground)
            Text("Add books to your library to get started")
                .font(LibraVaultTypography.bodyMedium)
                .foregroundStyle(LibraVaultColor.onSurfaceVariant)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(LibraVaultColor.background)
    }

    private var continueSection: some View {
        VStack(alignment: .leading, spacing: LibraVaultSpacing.sm) {
            Text("Continue")
                .font(LibraVaultTypography.titleLarge)
                .foregroundStyle(LibraVaultColor.onBackground)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: LibraVaultSpacing.sm) {
                    ForEach(continueBooks) { book in
                        bookTapTarget(for: book) { ContinueCard(book: book) }
                    }
                }
            }
        }
    }

    private var formatFilterChips: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: LibraVaultSpacing.sm) {
                ForEach(LibraryFormatFilter.allCases, id: \.self) { filter in
                    FilterChip(title: filter.rawValue, isSelected: formatFilter == filter) {
                        formatFilter = filter
                    }
                }
            }
        }
    }

    private var librarySection: some View {
        VStack(alignment: .leading, spacing: LibraVaultSpacing.sm) {
            Text("Library")
                .font(LibraVaultTypography.titleLarge)
                .foregroundStyle(LibraVaultColor.onBackground)
            LazyVGrid(
                columns: [GridItem(.adaptive(minimum: LibraVaultSpacing.coverWidth), spacing: LibraVaultSpacing.sm)],
                spacing: LibraVaultSpacing.lg
            ) {
                ForEach(filteredBooks) { book in
                    bookTapTarget(for: book) { BookCoverView(book: book) }
                }
            }
        }
    }

    /// Navigates straight into Reader/Player on tap — matches Android's
    /// LibravaultNavHost, which routes a Library item tap directly to Reader or
    /// Player by format with no intermediate detail screen. iOS used to have one
    /// (BookDetailView, since removed) with big colored "Continue Reading"/"View
    /// Bookmarks"/"View Highlights" buttons that didn't match any Android screen —
    /// "View Highlights" was dead UI besides (nothing anywhere on iOS ever calls
    /// DomainBridge.addHighlight, so the sheet it opened was always empty), and the
    /// extra tap before reading was reported as friction against Android's flow.
    @ViewBuilder
    private func bookTapTarget<Content: View>(for book: BookItem, @ViewBuilder content: () -> Content) -> some View {
        if book.format.isAudio {
            Button(action: {
                appState.startPlayback(book: book)
                appState.shouldNavigateToPlayer = true
            }) {
                content()
            }
            .buttonStyle(.plain)
        } else {
            NavigationLink(destination: ReaderView(book: book)) {
                content()
            }
            .buttonStyle(.plain)
        }
    }
}

/// Deterministic per-book gradient — the fallback CoverArtView below renders when a
/// book has no extractable cover art (CoverArtExtractor found nothing, or extraction
/// hasn't finished yet — see AppState.loadLibrary's phase-2 enrichment), so covers stay
/// visually distinct instead of a single flat placeholder color. Not private:
/// MiniPlayerBar and PlayerView reuse it so a book's cover tint is consistent
/// everywhere it appears, not just in the Library grid.
let generatedCoverPalette: [(Color, Color)] = [
    (LibraVaultPalette.leatherBrown, LibraVaultPalette.leatherDark),
    (LibraVaultPalette.agedBrass, LibraVaultPalette.leatherDark),
    (LibraVaultPalette.warmNeutral400, LibraVaultPalette.warmNeutral700),
    (LibraVaultPalette.leatherLight, LibraVaultPalette.warmNeutral500),
]

/// Split out from generatedCoverGradient(for:) so the actual regression-prone part —
/// hashing a book id into a stable palette slot — is a plain testable function instead
/// of being locked inside a LinearGradient nobody can inspect the contents of.
///
/// book.id.hashValue is reseeded per process launch (Swift randomizes String hashing
/// for hash-flooding resistance), which would make covers reshuffle colors on every
/// relaunch — sum UTF-8 bytes instead for a value that's actually stable across runs.
func generatedCoverPaletteIndex(for book: BookItem) -> Int {
    let stableSeed = book.id.utf8.reduce(0) { $0 + Int($1) }
    return stableSeed % generatedCoverPalette.count
}

func generatedCoverGradient(for book: BookItem) -> LinearGradient {
    let (start, end) = generatedCoverPalette[generatedCoverPaletteIndex(for: book)]
    return LinearGradient(colors: [start, end], startPoint: .topLeading, endPoint: .bottomTrailing)
}

/// Renders `book`'s real cover art (extracted by CoverArtExtractor, cached to disk by
/// CoverArtCache) when one exists, falling back to `generatedCoverGradient` otherwise —
/// the single place every cover-shaped surface (Library grid, Continue row, mini-player,
/// full player) goes through, so "no real cover yet" degrades the same way everywhere.
///
/// Reads the cached JPEG synchronously via `UIImage(contentsOfFile:)` rather than
/// `AsyncImage` — covers are local cache files already downsampled to a small fixed size
/// (see CoverArtCache), not network fetches, so there's no loading state worth the extra
/// machinery of async image loading.
struct CoverArtView: View {
    let book: BookItem
    var cornerRadius: CGFloat = LibraVaultRadius.cover

    var body: some View {
        Group {
            if let coverPath = book.coverUrl, let uiImage = UIImage(contentsOfFile: coverPath) {
                Image(uiImage: uiImage)
                    .resizable()
                    .aspectRatio(contentMode: .fill)
            } else {
                generatedCoverGradient(for: book)
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: cornerRadius))
    }
}

struct SupporterBadge: View {
    var body: some View {
        HStack(spacing: 4) {
            Image(systemName: "star.fill")
                .font(.system(size: 10))
            Text("Supporter")
                .font(LibraVaultTypography.labelSmall)
        }
        .foregroundStyle(LibraVaultColor.onSecondary)
        .padding(.horizontal, LibraVaultSpacing.sm)
        .padding(.vertical, 4)
        .background(LibraVaultColor.secondary)
        .clipShape(Capsule())
    }
}

struct FilterChip: View {
    let title: String
    let isSelected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(LibraVaultTypography.labelLarge)
                .foregroundStyle(isSelected ? LibraVaultColor.onPrimary : LibraVaultColor.onSurfaceVariant)
                .padding(.horizontal, LibraVaultSpacing.md)
                .padding(.vertical, LibraVaultSpacing.sm)
                .background(isSelected ? LibraVaultColor.primary : Color.clear)
                .overlay(
                    Capsule().stroke(LibraVaultColor.outline, lineWidth: isSelected ? 0 : 1)
                )
                .clipShape(Capsule())
        }
        .buttonStyle(.plain)
    }
}

struct ContinueCard: View {
    let book: BookItem

    var body: some View {
        HStack(spacing: LibraVaultSpacing.sm) {
            CoverArtView(book: book)
                .frame(width: 44, height: 44)
            VStack(alignment: .leading, spacing: 2) {
                Text(book.title)
                    .font(LibraVaultTypography.titleSmall)
                    .foregroundStyle(LibraVaultColor.onSurface)
                    .lineLimit(1)
                Text(book.author)
                    .font(LibraVaultTypography.bodySmall)
                    .foregroundStyle(LibraVaultColor.onSurfaceVariant)
                    .lineLimit(1)
            }
        }
        .padding(LibraVaultSpacing.sm)
        .frame(width: 200, alignment: .leading)
        .background(LibraVaultColor.surface)
        .clipShape(RoundedRectangle(cornerRadius: LibraVaultRadius.card))
    }
}

struct BookCoverView: View {
    let book: BookItem

    var body: some View {
        VStack(alignment: .leading, spacing: LibraVaultSpacing.sm) {
            // Title lives in the caption below, not overlaid here — matches Android's
            // actual behavior for books with real title metadata: the cover shows
            // artwork with no text, title appears once beneath it.
            CoverArtView(book: book)
                .aspectRatio(LibraVaultSpacing.coverAspect, contentMode: .fit)

            VStack(alignment: .leading, spacing: LibraVaultSpacing.xs) {
                Text(book.title)
                    .font(LibraVaultTypography.bodyMedium)
                    .foregroundStyle(LibraVaultColor.onSurface)
                    .lineLimit(2)

                Text(book.author)
                    .font(LibraVaultTypography.bodySmall)
                    .foregroundStyle(LibraVaultColor.onSurfaceVariant)
                    .lineLimit(1)

                if book.progress > 0 {
                    ProgressView(value: book.progress)
                        .tint(LibraVaultColor.primary)
                }
            }
        }
    }
}

#Preview {
    NavigationStack {
        LibraryView()
    }
    .environmentObject(AppState())
}
