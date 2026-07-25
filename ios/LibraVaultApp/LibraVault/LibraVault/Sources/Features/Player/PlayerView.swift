import SwiftUI

/// Mirrors Android's PlayerScreen.kt — the full-screen audio surface that iOS had no
/// equivalent for at all before this (TTS was one line in the Reader's overflow menu,
/// pre-Phase 3). Cover, title/author, "Full Book" subtitle, scrub bar, 5-button
/// transport, speed control, and sheets for chapters/bookmarks/sleep timer.
struct PlayerView: View {
    @EnvironmentObject var appState: AppState

    @State private var showChaptersSheet = false
    @State private var showBookmarksSheet = false
    @State private var showSleepTimerSheet = false
    @State private var showSpeedSheet = false

    private var book: BookItem? { appState.nowPlayingBook }

    private var elapsedBinding: Binding<Double> {
        Binding(get: { appState.elapsedSeconds }, set: { appState.seek(to: $0) })
    }

    var body: some View {
        VStack(spacing: LibraVaultSpacing.xl) {
            Spacer()

            if let book {
                RoundedRectangle(cornerRadius: LibraVaultRadius.card)
                    .fill(generatedCoverGradient(for: book))
                    .frame(width: 220, height: 220)
                    .shadow(radius: 12)

                VStack(spacing: LibraVaultSpacing.xs) {
                    Text(book.title)
                        .font(LibraVaultTypography.headlineSmall)
                        .foregroundStyle(LibraVaultColor.onBackground)
                        .multilineTextAlignment(.center)
                    Text(book.author)
                        .font(LibraVaultTypography.bodyMedium)
                        .foregroundStyle(LibraVaultColor.onSurfaceVariant)
                    Text("Full Book")
                        .font(LibraVaultTypography.labelMedium)
                        .foregroundStyle(LibraVaultColor.primary)
                }

                VStack(spacing: LibraVaultSpacing.xs) {
                    Slider(value: elapsedBinding, in: 0...max(appState.totalEstimatedSeconds, 1))
                        .tint(LibraVaultColor.primary)
                    HStack {
                        Text(formatted(appState.elapsedSeconds))
                        Spacer()
                        Text(formatted(appState.totalEstimatedSeconds))
                    }
                    .font(LibraVaultTypography.labelSmall)
                    .foregroundStyle(LibraVaultColor.onSurfaceVariant)
                }
                .padding(.horizontal, LibraVaultSpacing.xl)

                HStack(spacing: LibraVaultSpacing.xl) {
                    Button(action: { appState.skipToChapter(appState.nowPlayingChapter - 1) }) {
                        Image(systemName: "backward.end.fill")
                    }
                    .disabled(appState.nowPlayingChapter <= 1)

                    Button(action: { appState.skipBackward(seconds: appState.skipDurationSeconds) }) {
                        // SF Symbols ships exact variants for 10/15/30/45/60 — the same
                        // 5 presets Settings' "Skip duration" chips offer, so this never
                        // needs a fallback for an unsupported number.
                        Image(systemName: "gobackward.\(Int(appState.skipDurationSeconds))")
                    }

                    Button(action: appState.togglePlayback) {
                        Image(systemName: appState.isPlaying ? "pause.circle.fill" : "play.circle.fill")
                            .font(.system(size: 56))
                    }
                    .accessibilityIdentifier("player.playPauseButton")

                    Button(action: { appState.skipForward(seconds: appState.skipDurationSeconds) }) {
                        Image(systemName: "goforward.\(Int(appState.skipDurationSeconds))")
                    }

                    Button(action: { appState.skipToChapter(appState.nowPlayingChapter + 1) }) {
                        Image(systemName: "forward.end.fill")
                    }
                    .disabled(appState.nowPlayingChapter >= MockChapterContent.count)
                }
                .font(.system(size: 28))
                .foregroundStyle(LibraVaultColor.onBackground)

                Button(action: { showSpeedSheet = true }) {
                    Text("\(String(format: "%.2g", appState.playbackSpeed))×")
                        .font(LibraVaultTypography.labelLarge)
                        .foregroundStyle(LibraVaultColor.primary)
                }
                .accessibilityIdentifier("player.speedButton")
            } else {
                Text("Nothing playing")
                    .font(LibraVaultTypography.bodyLarge)
                    .foregroundStyle(LibraVaultColor.onSurfaceVariant)
            }

            Spacer()
        }
        .padding(LibraVaultSpacing.lg)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(LibraVaultColor.background)
        .navigationTitle("Now Playing")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Button(action: { showBookmarksSheet = true }) {
                    Image(systemName: "bookmark")
                }
            }
            ToolbarItem(placement: .navigationBarTrailing) {
                Button(action: { showChaptersSheet = true }) {
                    Image(systemName: "list.bullet")
                }
                .accessibilityIdentifier("player.chaptersButton")
            }
            ToolbarItem(placement: .navigationBarTrailing) {
                Button(action: { showSleepTimerSheet = true }) {
                    Image(systemName: appState.sleepTimerRemainingSeconds != nil ? "moon.zzz.fill" : "moon.zzz")
                }
            }
        }
        .onAppear { appState.isPlayerScreenActive = true }
        .onDisappear { appState.isPlayerScreenActive = false }
        .sheet(isPresented: $showBookmarksSheet) {
            if let book {
                BookmarksSheet(bookId: book.id)
            }
        }
        .sheet(isPresented: $showChaptersSheet) {
            ChapterListSheet(currentChapter: appState.nowPlayingChapter, onSelect: {
                appState.skipToChapter($0)
                showChaptersSheet = false
            })
        }
        .sheet(isPresented: $showSleepTimerSheet) {
            SleepTimerSheet(
                remainingSeconds: appState.sleepTimerRemainingSeconds,
                onSelect: {
                    appState.scheduleSleepTimer(minutes: $0)
                    showSleepTimerSheet = false
                },
                onCancel: {
                    appState.cancelSleepTimer()
                    showSleepTimerSheet = false
                }
            )
        }
        .sheet(isPresented: $showSpeedSheet) {
            SpeedPickerSheet(speed: $appState.playbackSpeed)
        }
    }

    private func formatted(_ seconds: Double) -> String {
        let minutes = Int(seconds) / 60
        let secs = Int(seconds) % 60
        return String(format: "%d:%02d", minutes, secs)
    }
}

#Preview {
    let state = AppState()
    state.startPlayback(book: BookItem(id: "1", title: "Love and Friendship", author: "Jane Austen"))
    return NavigationStack {
        PlayerView()
    }
    .environmentObject(state)
}
