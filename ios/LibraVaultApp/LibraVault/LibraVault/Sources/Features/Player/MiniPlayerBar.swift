import SwiftUI

/// Mirrors Android's MiniPlayerBar (feature/library, shown at the bottom of the
/// Scaffold) — lives at the app root here (see RootView), not inside LibraryView,
/// so it persists across every pushed screen the same way Android's does, not just
/// the Library screen. Hidden while PlayerView itself is on screen (redundant
/// transport controls stacked on the same information otherwise).
struct MiniPlayerBar: View {
    @EnvironmentObject var appState: AppState
    let onTap: () -> Void

    var body: some View {
        if let book = appState.nowPlayingBook, !appState.isPlayerScreenActive {
            HStack(spacing: LibraVaultSpacing.sm) {
                CoverArtView(book: book)
                    .frame(width: 40, height: 40)

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

                Spacer()

                Button(action: appState.togglePlayback) {
                    Image(systemName: appState.isPlaying ? "pause.fill" : "play.fill")
                        .font(.system(size: 18))
                        .foregroundStyle(LibraVaultColor.primary)
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("miniPlayer.playPauseButton")
            }
            .padding(LibraVaultSpacing.sm)
            .frame(height: LibraVaultSpacing.miniBarHeight)
            .background(LibraVaultColor.surface)
            .overlay(alignment: .top) {
                Rectangle()
                    .fill(LibraVaultColor.outline.opacity(0.3))
                    .frame(height: 1)
            }
            .contentShape(Rectangle())
            .onTapGesture(perform: onTap)
            .accessibilityIdentifier("miniPlayer.bar")
            .transition(.move(edge: .bottom))
        }
    }
}

#Preview {
    let state = AppState()
    state.startPlayback(book: BookItem(id: "1", title: "Love and Friendship", author: "Jane Austen"))
    return MiniPlayerBar(onTap: {})
        .environmentObject(state)
}
