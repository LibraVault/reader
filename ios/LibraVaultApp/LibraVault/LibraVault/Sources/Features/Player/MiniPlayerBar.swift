import SwiftUI

/// Mirrors Android's MiniPlayerBar (feature/library, shown at the bottom of the
/// Scaffold) — lives at the app root here (see RootView), not inside LibraryView,
/// so it persists across every pushed screen the same way Android's does, not just
/// the Library screen. Hidden while PlayerView itself is on screen (redundant
/// transport controls stacked on the same information otherwise).
///
/// Unlike Android's (which stays pinned regardless of interaction — see
/// ReaderScreen.kt's doc comment), this auto-collapses to a small hint strip after
/// a few seconds idle, per Settings > Playback > "Auto-hide mini-player" (field
/// feedback: the bar permanently covering the bottom of the reading area while
/// reading was unwanted screen real estate). Opt-out via
/// appState.miniPlayerAutoHideEnabled, since there's no existing Android behavior
/// to match either way.
struct MiniPlayerBar: View {
    @EnvironmentObject var appState: AppState
    let onTap: () -> Void
    /// Overridable so tests don't have to wait out the real delay — see
    /// scheduleAutoHide's doc comment.
    var autoHideDelaySeconds: TimeInterval = 3

    @State private var isCollapsed = false
    @State private var hideTask: Task<Void, Never>?

    var body: some View {
        if let book = appState.nowPlayingBook, !appState.isPlayerScreenActive {
            Group {
                if isCollapsed {
                    collapsedHint
                } else {
                    expandedBar(book: book)
                }
            }
            .onAppear { scheduleAutoHide() }
            .onDisappear { hideTask?.cancel() }
            .onChange(of: book.id) { _, _ in
                // A different book started playing (Prev/Next, or picking a new
                // book while one was already playing) — always re-show fully
                // rather than staying collapsed from the previous session.
                isCollapsed = false
                scheduleAutoHide()
            }
            .onChange(of: appState.miniPlayerAutoHideEnabled) { _, enabled in
                hideTask?.cancel()
                if enabled {
                    scheduleAutoHide()
                } else {
                    isCollapsed = false
                }
            }
        }
    }

    private func expandedBar(book: BookItem) -> some View {
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

            Button(action: {
                appState.togglePlayback()
                scheduleAutoHide()
            }) {
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
        .onTapGesture {
            scheduleAutoHide()
            onTap()
        }
        .accessibilityIdentifier("miniPlayer.bar")
        .transition(.move(edge: .bottom))
    }

    /// Small tap target left behind once the bar auto-hides — a short "handle"
    /// rather than reusing the full bar's chrome, so it clearly reads as "tucked
    /// away, tap to bring back" instead of a second, smaller player.
    private var collapsedHint: some View {
        Capsule()
            .fill(LibraVaultColor.onSurfaceVariant.opacity(0.4))
            .frame(width: 36, height: 4)
            .frame(maxWidth: .infinity)
            .frame(height: LibraVaultSpacing.miniBarHintHeight)
            .background(LibraVaultColor.surface)
            .contentShape(Rectangle())
            .onTapGesture {
                withAnimation(.easeInOut(duration: 0.2)) {
                    isCollapsed = false
                }
                scheduleAutoHide()
            }
            .accessibilityIdentifier("miniPlayer.hint")
            .accessibilityLabel("Show mini-player")
            .transition(.move(edge: .bottom))
    }

    /// Collapses the bar after `autoHideDelaySeconds` of no interaction, per
    /// Settings > Playback > "Auto-hide mini-player" (defaults on). Any interaction
    /// (play/pause, tapping through to the full player, re-expanding the hint)
    /// cancels the in-flight task and reschedules a fresh one, so the bar only
    /// collapses after a genuine idle period rather than a fixed time-since-shown.
    /// No-ops (and cancels any pending hide) when the setting is off.
    private func scheduleAutoHide() {
        hideTask?.cancel()
        guard appState.miniPlayerAutoHideEnabled else { return }
        hideTask = Task {
            try? await Task.sleep(nanoseconds: UInt64(autoHideDelaySeconds * 1_000_000_000))
            guard !Task.isCancelled else { return }
            withAnimation(.easeInOut(duration: 0.2)) {
                isCollapsed = true
            }
        }
    }
}

#Preview {
    let state = AppState()
    state.startPlayback(book: BookItem(id: "1", title: "Love and Friendship", author: "Jane Austen"))
    return MiniPlayerBar(onTap: {})
        .environmentObject(state)
}
