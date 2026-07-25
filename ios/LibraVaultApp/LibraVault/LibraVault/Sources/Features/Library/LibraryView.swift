import SwiftUI

enum LibraryFormatFilter: String, CaseIterable {
    case all = "All"
    case epub = "EPUB"
    case pdf = "PDF"

    func matches(_ format: MediaFormat) -> Bool {
        switch self {
        case .all:  return true
        case .epub: return format == .epub
        case .pdf:  return format == .pdf
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
        NavigationStack {
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
                        NavigationLink(destination: BookDetailView(book: book)) {
                            ContinueCard(book: book)
                        }
                        .buttonStyle(.plain)
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
                    NavigationLink(destination: BookDetailView(book: book)) {
                        BookCoverView(book: book)
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }
}

/// Deterministic per-book gradient so covers without real artwork (all of them, until
/// core:storage cover extraction is wired — see DomainBridge.swift's Phase D TODOs)
/// stay visually distinct instead of a single flat placeholder color.
private let generatedCoverPalette: [(Color, Color)] = [
    (LibraVaultPalette.leatherBrown, LibraVaultPalette.leatherDark),
    (LibraVaultPalette.agedBrass, LibraVaultPalette.leatherDark),
    (LibraVaultPalette.warmNeutral400, LibraVaultPalette.warmNeutral700),
    (LibraVaultPalette.leatherLight, LibraVaultPalette.warmNeutral500),
]

private func generatedCoverGradient(for book: BookItem) -> LinearGradient {
    // book.id.hashValue is reseeded per process launch (Swift randomizes String hashing
    // for hash-flooding resistance), which would make covers reshuffle colors on every
    // relaunch — sum UTF-8 bytes instead for a value that's actually stable across runs.
    let stableSeed = book.id.utf8.reduce(0) { $0 + Int($1) }
    let (start, end) = generatedCoverPalette[stableSeed % generatedCoverPalette.count]
    return LinearGradient(colors: [start, end], startPoint: .topLeading, endPoint: .bottomTrailing)
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
            RoundedRectangle(cornerRadius: LibraVaultRadius.cover)
                .fill(generatedCoverGradient(for: book))
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
            // actual behavior for books with real title metadata (every current mock
            // book): the cover shows artwork with no text, title appears once beneath it.
            RoundedRectangle(cornerRadius: LibraVaultRadius.cover)
                .fill(generatedCoverGradient(for: book))
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

struct BookDetailView: View {
    let book: BookItem
    @State private var isLoading = false

    var body: some View {
        ScrollView {
            VStack(spacing: 20) {
                // Cover area
                VStack {
                    ZStack {
                        Color.blue.opacity(0.3)
                        Image(systemName: "book.fill")
                            .font(.system(size: 80))
                            .foregroundColor(.blue)
                    }
                    .frame(height: 200)
                    .cornerRadius(12)
                }
                .padding()

                VStack(alignment: .leading, spacing: 16) {
                    // Title and author
                    VStack(alignment: .leading, spacing: 4) {
                        Text(book.title)
                            .font(.title2)
                            .fontWeight(.bold)
                        Text(book.author)
                            .font(.subheadline)
                            .foregroundColor(.secondary)
                    }

                    Divider()

                    // Progress
                    VStack(alignment: .leading, spacing: 8) {
                        HStack {
                            Label("Reading Progress", systemImage: "percent")
                            Spacer()
                            Text("\(Int(book.progress * 100))%")
                                .fontWeight(.semibold)
                        }
                        ProgressView(value: book.progress)
                    }

                    // Action buttons
                    VStack(spacing: 12) {
                        NavigationLink(destination: ReaderView(book: book)) {
                            HStack {
                                Image(systemName: "book")
                                Text("Continue Reading")
                                Spacer()
                                Image(systemName: "chevron.right")
                            }
                            .foregroundColor(.white)
                            .padding()
                            .background(Color.blue)
                            .cornerRadius(8)
                        }
                        .accessibilityIdentifier("bookDetail.continueReadingButton")

                        Button(action: {}) {
                            HStack {
                                Image(systemName: "bookmark")
                                Text("View Bookmarks")
                                Spacer()
                                Image(systemName: "chevron.right")
                            }
                            .foregroundColor(.white)
                            .padding()
                            .background(Color.green.opacity(0.7))
                            .cornerRadius(8)
                        }

                        Button(action: {}) {
                            HStack {
                                Image(systemName: "highlighter")
                                Text("View Highlights")
                                Spacer()
                                Image(systemName: "chevron.right")
                            }
                            .foregroundColor(.white)
                            .padding()
                            .background(Color.orange.opacity(0.7))
                            .cornerRadius(8)
                        }
                    }

                    Spacer()
                }
                .padding()
            }
        }
        .navigationBarTitleDisplayMode(.inline)
    }
}

#Preview {
    LibraryView()
        .environmentObject(AppState())
}
