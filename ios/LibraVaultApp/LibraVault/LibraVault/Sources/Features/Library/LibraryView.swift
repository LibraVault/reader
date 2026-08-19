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
            Text(emptyLibraryHeadline(hasVaults: !appState.vaults.isEmpty))
                .font(LibraVaultTypography.headlineSmall)
                .foregroundStyle(LibraVaultColor.onBackground)
            Text(emptyLibraryMessage(hasVaults: !appState.vaults.isEmpty))
                .font(LibraVaultTypography.bodyMedium)
                .foregroundStyle(LibraVaultColor.onSurfaceVariant)
                .multilineTextAlignment(.center)
                .padding(.horizontal, LibraVaultSpacing.xl)
            if appState.vaults.isEmpty {
                NavigationLink(destination: SettingsView()) {
                    Text("Go to Settings")
                }
                .buttonStyle(.borderedProminent)
            }
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

/// Split out from LibraryView.emptyState so the copy choice — whether the user has any
/// vaults configured at all vs. their vaults are just empty/still scanning — is a plain
/// testable function. A first-launch user with zero vaults has no other way to discover
/// that folders live in Settings, so that case gets explicit guidance; see issue #75.
func emptyLibraryHeadline(hasVaults: Bool) -> String {
    hasVaults ? "No Books Found" : "Start Your Library"
}

func emptyLibraryMessage(hasVaults: Bool) -> String {
    hasVaults
        ? "Add books to your library to get started"
        : "Tap Settings > Add Vault to choose a folder where your books and audiobooks are stored."
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

/// Uppercase initials — first letters of the first two words, or the first two
/// letters of a single-word title. Mirrors Android's `initialsFor` in
/// `core/ui/components/GeneratedCover.kt` (same rule, same "?" empty-title fallback).
func initials(for title: String) -> String {
    let trimmed = title.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !trimmed.isEmpty else { return "?" }
    let words = trimmed.components(separatedBy: .whitespacesAndNewlines).filter { !$0.isEmpty }
    if words.count >= 2, let first = words[0].first, let second = words[1].first {
        return String([first, second]).uppercased()
    }
    return String(trimmed.prefix(2)).uppercased()
}

/// A [CoverArtView] placeholder's caption-band identity (issue #308) — mirrors
/// Android's `core:ui` `CoverFormatBadge`, independently derived here from
/// `MediaFormat` directly (iOS has no cross-module boundary to route around the way
/// `core:ui`/`core:domain` do on Android). `nil` (mobi/cbz — recognized by the
/// scanner but not yet readable, see `BookContentProvider`) keeps `CoverArtView`'s
/// original generic "no cover art" treatment.
enum CoverFormatBadge {
    case epub, pdf, markdown, audio

    init?(format: MediaFormat) {
        switch format {
        case .epub: self = .epub
        case .pdf: self = .pdf
        case .markdown: self = .markdown
        case .mp3, .m4b, .aac, .flac, .ogg, .opus: self = .audio
        case .mobi, .cbz: return nil
        }
    }

    var symbolName: String {
        switch self {
        case .epub: return "book.closed"
        case .pdf: return "doc.richtext"
        case .markdown: return "doc.plaintext"
        case .audio: return "headphones"
        }
    }

    /// Matches `LibraryFormatFilter`'s own chip labels above ("EPUB"/"PDF"/"MD"/"Audio").
    var label: String {
        switch self {
        case .epub: return "EPUB"
        case .pdf: return "PDF"
        case .markdown: return "MD"
        case .audio: return "Audio"
        }
    }
}

/// Caption text/description shared by the generic and format-specific cases — kept as
/// a free function (not a `CoverFormatBadge` case) since the generic "no format"
/// treatment isn't itself a badge variant.
private let noCoverArtDescription = "No cover art"

/// Below this width the caption band shows the icon alone — the label doesn't fit
/// legibly at, say, MiniPlayerBar's 40pt thumbnail. Matches Android's
/// `MIN_WIDTH_FOR_LABEL` in `core:ui`'s `GeneratedCover.kt`.
private let minWidthForCaptionLabel: CGFloat = 72

/// Renders `book`'s real cover art (extracted by CoverArtExtractor, cached to disk by
/// CoverArtCache) when one exists, falling back to a deterministic gradient + initials
/// + a bottom caption band otherwise — the single place every cover-shaped surface
/// (Library grid, Continue row, mini-player, full player) goes through, so "no real
/// cover yet" degrades the same way everywhere.
///
/// The caption band (#308, mirroring Android's `GeneratedCover`/issue #168) exists so
/// the gradient+initials alone don't read as a real, deliberately-designed cover —
/// it shows a format-specific icon/label (book/PDF/doc/headphones + "EPUB"/"PDF"/
/// "MD"/"Audio") when `book.format` maps to one, or the original generic
/// "No cover art" treatment otherwise.
///
/// Reads the cached JPEG synchronously via `UIImage(contentsOfFile:)` rather than
/// `AsyncImage` — covers are local cache files already downsampled to a small fixed size
/// (see CoverArtCache), not network fetches, so there's no loading state worth the extra
/// machinery of async image loading.
struct CoverArtView: View {
    let book: BookItem
    var cornerRadius: CGFloat = LibraVaultRadius.cover

    private var badge: CoverFormatBadge? { CoverFormatBadge(format: book.format) }

    var body: some View {
        Group {
            if let coverPath = book.coverUrl, let uiImage = UIImage(contentsOfFile: coverPath) {
                Image(uiImage: uiImage)
                    .resizable()
                    .aspectRatio(contentMode: .fill)
            } else {
                placeholderContent
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: cornerRadius))
    }

    private var placeholderContent: some View {
        GeometryReader { geometry in
            ZStack {
                generatedCoverGradient(for: book)

                Text(initials(for: book.title))
                    .font(LibraVaultTypography.headlineSmall)
                    .fontWeight(.bold)
                    .foregroundStyle(LibraVaultPalette.leatherLight.opacity(0.92))
                    .lineLimit(1)

                VStack {
                    Spacer()
                    HStack(spacing: 4) {
                        Image(systemName: badge?.symbolName ?? "photo")
                            .font(.system(size: 12))
                            .foregroundStyle(LibraVaultPalette.leatherLight.opacity(0.9))
                        if geometry.size.width >= minWidthForCaptionLabel {
                            Text(badge?.label ?? noCoverArtDescription)
                                .font(LibraVaultTypography.labelSmall)
                                .foregroundStyle(LibraVaultPalette.leatherLight.opacity(0.9))
                                .lineLimit(1)
                        }
                    }
                    .padding(.vertical, 3)
                    .frame(maxWidth: .infinity)
                    .background(Color.black.opacity(0.38))
                }
            }
            // The band's meaning (and, when known, which format) is on this one
            // accessibility element — mirrors Android's outer `.semantics { }` on
            // GeneratedCover, rather than the icon/text inside separately narrating.
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(badge.map { "\(noCoverArtDescription) — \($0.label)" } ?? noCoverArtDescription)
        }
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
