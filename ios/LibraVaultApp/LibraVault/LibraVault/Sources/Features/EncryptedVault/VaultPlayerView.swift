import SwiftUI

/// Plays one vault audio file, dispatched here (rather than
/// `VaultReaderView`) once `EncryptedVaultContentsView` sees an audio
/// `VaultManifestEntry.format` — see `VaultContentFormat.isAudio`.
///
/// `.secureVaultScreen()` for the same reason `VaultReaderView` applies it —
/// vault content should stay off a screen recording.
struct VaultPlayerView: View {
    @StateObject private var viewModel: VaultPlayerViewModel
    @State private var showBookmarksSheet = false

    init(vaultId: String, fileId: Data, sessionManager: VaultSessionManager) {
        _viewModel = StateObject(wrappedValue: VaultPlayerViewModel(vaultId: vaultId, fileId: fileId, sessionManager: sessionManager))
    }

    var body: some View {
        content
            .navigationTitle(viewModel.title.isEmpty ? "Vault" : viewModel.title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItemGroup(placement: .navigationBarTrailing) {
                    Button {
                        Task { await viewModel.addBookmark() }
                    } label: {
                        Image(systemName: "bookmark")
                    }
                    .accessibilityLabel("Add bookmark")
                    .disabled(viewModel.isLoading)

                    Button {
                        showBookmarksSheet = true
                    } label: {
                        Image(systemName: "list.bullet")
                    }
                    .accessibilityLabel("Bookmarks")
                }
            }
            .task { await viewModel.load() }
            .onDisappear { viewModel.stop() }
            .sheet(isPresented: $showBookmarksSheet) {
                VaultBookmarksSheet(
                    bookmarks: viewModel.bookmarks,
                    onNavigate: { bookmark in
                        viewModel.seekToBookmark(bookmark)
                        showBookmarksSheet = false
                    },
                    onDelete: { bookmark in Task { await viewModel.removeBookmark(id: bookmark.id) } },
                    onEditNote: { bookmark, note in Task { await viewModel.updateBookmarkNote(id: bookmark.id, note: note) } }
                )
            }
            .secureVaultScreen(contentKind: .vaultContent)
    }

    @ViewBuilder
    private var content: some View {
        if viewModel.isLoading {
            ProgressView()
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if let errorMessage = viewModel.errorMessage {
            VStack(spacing: LibraVaultSpacing.md) {
                Image(systemName: "exclamationmark.triangle")
                    .font(.system(size: 32))
                    .foregroundStyle(LibraVaultColor.onSurfaceVariant)
                Text("Couldn't play this file")
                    .font(LibraVaultTypography.titleMedium)
                    .foregroundStyle(LibraVaultColor.onSurface)
                Text(errorMessage)
                    .font(LibraVaultTypography.bodySmall)
                    .foregroundStyle(LibraVaultColor.onSurfaceVariant)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, LibraVaultSpacing.xl)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else {
            playerControls
        }
    }

    private var playerControls: some View {
        VStack(spacing: LibraVaultSpacing.xl) {
            Spacer()

            Image(systemName: "lock.shield")
                .font(.system(size: 64))
                .foregroundStyle(LibraVaultColor.onSurfaceVariant)

            VStack(spacing: LibraVaultSpacing.sm) {
                Slider(
                    value: Binding(
                        get: { viewModel.elapsed },
                        set: { viewModel.onSeek(to: $0) }
                    ),
                    in: 0...max(viewModel.duration, 1)
                )
                HStack {
                    Text(formatTime(viewModel.elapsed))
                    Spacer()
                    Text(formatTime(viewModel.duration))
                }
                .font(LibraVaultTypography.bodySmall)
                .foregroundStyle(LibraVaultColor.onSurfaceVariant)
            }
            .padding(.horizontal, LibraVaultSpacing.xl)

            HStack(spacing: LibraVaultSpacing.xxl) {
                Button { viewModel.onSkipBack() } label: {
                    Image(systemName: "gobackward.30")
                        .font(.system(size: 28))
                }
                .accessibilityLabel("Skip back 30 seconds")

                Button { viewModel.onPlayPause() } label: {
                    Image(systemName: viewModel.isPlaying ? "pause.circle.fill" : "play.circle.fill")
                        .font(.system(size: 56))
                }
                .accessibilityLabel(viewModel.isPlaying ? "Pause" : "Play")

                Button { viewModel.onSkipForward() } label: {
                    Image(systemName: "goforward.30")
                        .font(.system(size: 28))
                }
                .accessibilityLabel("Skip forward 30 seconds")
            }
            .foregroundStyle(LibraVaultColor.primary)

            Spacer()
        }
    }

    private func formatTime(_ seconds: Double) -> String {
        let total = Int(seconds.rounded())
        let h = total / 3600
        let m = (total % 3600) / 60
        let s = total % 60
        return h > 0 ? String(format: "%d:%02d:%02d", h, m, s) : String(format: "%d:%02d", m, s)
    }
}
