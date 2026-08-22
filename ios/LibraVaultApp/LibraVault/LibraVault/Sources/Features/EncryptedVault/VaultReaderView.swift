import SwiftUI

/// Reads one vault file — EPUB or PDF, dispatched by `VaultReaderViewModel
/// .state`. EPUB renders `BookChapter` text directly (iOS's existing reader
/// has no Readium-style navigator to plug into — see the view model's own
/// doc comment); PDF reuses `PDFReaderContent` unmodified, passing it a
/// `PDFDocument` built straight from `VaultStore.readFullContent`'s decrypted
/// `Data` instead of a file URL.
///
/// `.secureVaultScreen()` blanks this screen during screen recording/AirPlay
/// mirroring — the same protection `CreateEncryptedVaultView`/
/// `UnlockEncryptedVaultView` apply to recovery-key material, extended here
/// since vault *content* is exactly what Encrypted Vaults exists to keep
/// off-screen-recording too.
struct VaultReaderView: View {
    @StateObject private var viewModel: VaultReaderViewModel
    @Environment(\.dismiss) private var dismiss
    @Environment(\.colorScheme) private var colorScheme
    @State private var showBookmarksSheet = false

    init(vaultId: String, fileId: Data, sessionManager: VaultSessionManager) {
        _viewModel = StateObject(wrappedValue: VaultReaderViewModel(vaultId: vaultId, fileId: fileId, sessionManager: sessionManager))
    }

    var body: some View {
        content
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItemGroup(placement: .navigationBarTrailing) {
                    Button {
                        Task { await viewModel.addHighlight() }
                    } label: {
                        Image(systemName: "highlighter")
                    }
                    .accessibilityLabel("Highlight this page")
                    .disabled(!isReady)

                    Button {
                        Task { await viewModel.addBookmark() }
                    } label: {
                        Image(systemName: "bookmark")
                    }
                    .accessibilityLabel("Add bookmark")
                    .disabled(!isReady)

                    Button {
                        showBookmarksSheet = true
                    } label: {
                        Image(systemName: "list.bullet")
                    }
                    .accessibilityLabel("Bookmarks")
                }
            }
            .task { await viewModel.load() }
            .onChange(of: viewModel.state) { _, state in
                if case .wrongScreen = state { dismiss() }
            }
            .sheet(isPresented: $showBookmarksSheet) {
                VaultBookmarksSheet(
                    bookmarks: viewModel.bookmarks,
                    onNavigate: { bookmark in
                        viewModel.navigate(to: bookmark)
                        showBookmarksSheet = false
                    },
                    onDelete: { bookmark in Task { await viewModel.removeBookmark(id: bookmark.id) } },
                    onEditNote: { bookmark, note in Task { await viewModel.updateBookmarkNote(id: bookmark.id, note: note) } }
                )
            }
            .secureVaultScreen()
    }

    private var isReady: Bool {
        switch viewModel.state {
        case .epubReady, .pdfReady: return true
        default: return false
        }
    }

    private var title: String {
        switch viewModel.state {
        case .epubReady(let title), .pdfReady(let title): return title
        case .markdownReady(let title, _): return title
        default: return "Vault"
        }
    }

    @ViewBuilder
    private var content: some View {
        switch viewModel.state {
        case .loading, .wrongScreen:
            ProgressView()
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        case .error(let message):
            VStack(spacing: LibraVaultSpacing.md) {
                Image(systemName: "exclamationmark.triangle")
                    .font(.system(size: 32))
                    .foregroundStyle(LibraVaultColor.onSurfaceVariant)
                Text("Couldn't open this file")
                    .font(LibraVaultTypography.titleMedium)
                    .foregroundStyle(LibraVaultColor.onSurface)
                Text(message)
                    .font(LibraVaultTypography.bodySmall)
                    .foregroundStyle(LibraVaultColor.onSurfaceVariant)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, LibraVaultSpacing.xl)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        case .epubReady:
            epubContent
        case .pdfReady:
            pdfContent
        case .markdownReady:
            markdownContent
        }
    }

    private var epubContent: some View {
        VStack(spacing: 0) {
            ScrollView {
                if viewModel.currentChapterIndex < viewModel.chapters.count {
                    Text(viewModel.chapters[viewModel.currentChapterIndex].text)
                        .font(LibraVaultTypography.bodyLarge)
                        .foregroundStyle(LibraVaultColor.onSurface)
                        .padding(LibraVaultSpacing.lg)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            chapterNavigationBar
        }
    }

    private var chapterNavigationBar: some View {
        HStack {
            Button {
                viewModel.currentChapterIndex = max(0, viewModel.currentChapterIndex - 1)
            } label: {
                Image(systemName: "chevron.left")
            }
            .disabled(viewModel.currentChapterIndex <= 0)

            Spacer()
            Text("Chapter \(viewModel.currentChapterIndex + 1) of \(max(viewModel.chapters.count, 1))")
                .font(LibraVaultTypography.bodySmall)
                .foregroundStyle(LibraVaultColor.onSurfaceVariant)
            Spacer()

            Button {
                viewModel.currentChapterIndex = min(viewModel.chapters.count - 1, viewModel.currentChapterIndex + 1)
            } label: {
                Image(systemName: "chevron.right")
            }
            .disabled(viewModel.currentChapterIndex >= viewModel.chapters.count - 1)
        }
        .padding(LibraVaultSpacing.md)
        .background(LibraVaultColor.surface)
    }

    private var pdfContent: some View {
        Group {
            if let document = viewModel.pdfDocument {
                PDFReaderContent(
                    document: document,
                    mode: .paginated,
                    currentPageIndex: $viewModel.currentPageIndex,
                    backgroundColor: .systemBackground,
                    onCenterTap: {}
                )
            }
        }
    }

    /// v1-scoped, matching what's documented as out of scope on #442: no
    /// per-user theme/font-size/line-spacing controls exist for the Vault
    /// reader at all yet (unlike Android's `VaultReaderSettingsSheet`) — this
    /// resolves colors from the system color scheme the same way `epubContent`
    /// implicitly does via `LibraVaultColor.onSurface`, and uses fixed
    /// typography defaults matching `ReaderView`'s own (fontSize: 1.0,
    /// lineSpacing: 1.4, fontDesign: .default). No image loading (`images: [:]`
    /// — no vault equivalent of the security-scoped folder access
    /// `ReaderView.markdownImages` resolves relative references against), no
    /// TOC navigation, and reading position isn't persisted (`initialScrollFraction:
    /// 0`, `onScrollFractionChanged` a no-op) — bookmarks/highlights stay
    /// disabled for this state (see `isReady`) for the same reason.
    private var markdownContent: some View {
        let theme: ConcreteReadingTheme = colorScheme == .dark ? .dark : .light
        return MarkdownReaderContent(
            blocks: viewModel.markdownBlocks,
            images: [:],
            colors: LibraVaultColorScheme.forReadingTheme(theme),
            fontSize: 1.0,
            lineSpacing: 1.4,
            fontDesign: .default,
            readingTheme: theme,
            initialScrollFraction: 0,
            onScrollFractionChanged: { _ in }
        )
    }
}
