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

    /// (#309, principal-review finding on PR #310) The scrub bar's live drag
    /// position, tracked separately from `appState.elapsedSeconds` so dragging it
    /// doesn't call `appState.seek(to:)` on every frame — `seek(to:)` now also syncs
    /// Now Playing info (a synchronous cover-art disk read plus an IPC round-trip to
    /// `nowplayingd`), which a plain `Slider(value:)` binding would have fired many
    /// times per second for the whole gesture. `isDraggingScrubber` gates
    /// `sliderSection`'s displayed value: the drag's own live position while
    /// dragging, `appState.elapsedSeconds` otherwise (so external changes — another
    /// skip button, a remote command — still show up when not actively dragging).
    @State private var isDraggingScrubber = false
    @State private var scrubberDragValue: Double = 0

    private var book: BookItem? { appState.nowPlayingBook }

    var body: some View {
        Group {
            if let book {
                // This screen used to lock rotation to portrait for as long as it was
                // on screen (via OrientationManager, since removed here) — that made
                // starting Read Aloud from a document already in landscape spin the
                // screen out from under the user. Now it rotates freely, so a
                // GeometryReader picks a layout that actually looks intentional in
                // landscape instead of just squeezing the portrait column sideways.
                GeometryReader { proxy in
                    Group {
                        if isLandscapeOrientation(size: proxy.size) {
                            landscapeContent(book: book)
                        } else {
                            portraitContent(book: book)
                        }
                    }
                    .frame(width: proxy.size.width, height: proxy.size.height)
                }
            } else {
                VStack {
                    Spacer()
                    Text("Nothing playing")
                        .font(LibraVaultTypography.bodyLarge)
                        .foregroundStyle(LibraVaultColor.onSurfaceVariant)
                    Spacer()
                }
            }
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
            ChapterListSheet(
                currentChapter: appState.nowPlayingChapter,
                chapterTitles: appState.nowPlayingChapterTitles,
                onSelect: {
                    appState.skipToChapter($0)
                    showChaptersSheet = false
                }
            )
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

    // MARK: - Portrait layout — single scrolling column, as before

    @ViewBuilder
    private func portraitContent(book: BookItem) -> some View {
        VStack(spacing: LibraVaultSpacing.xl) {
            Spacer()
            coverArt(book: book, side: 220)
            titleBlock(book: book)
            sliderSection
            transportButtons
            speedButton
            Spacer()
        }
    }

    // MARK: - Landscape layout — cover/title on the left, transport on the right

    @ViewBuilder
    private func landscapeContent(book: BookItem) -> some View {
        HStack(spacing: LibraVaultSpacing.xl) {
            VStack(spacing: LibraVaultSpacing.md) {
                Spacer()
                coverArt(book: book, side: 140)
                titleBlock(book: book)
                Spacer()
            }

            VStack(spacing: LibraVaultSpacing.lg) {
                Spacer()
                sliderSection
                transportButtons
                speedButton
                Spacer()
            }
        }
    }

    // MARK: - Shared pieces used by both layouts

    @ViewBuilder
    private func coverArt(book: BookItem, side: CGFloat) -> some View {
        CoverArtView(book: book, cornerRadius: LibraVaultRadius.card)
            .frame(width: side, height: side)
            .shadow(radius: 12)
    }

    @ViewBuilder
    private func titleBlock(book: BookItem) -> some View {
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
    }

    /// The live drag position while the scrub bar is being dragged, otherwise
    /// `appState.elapsedSeconds` — see `isDraggingScrubber`'s doc comment.
    private var scrubberDisplayValue: Double {
        isDraggingScrubber ? scrubberDragValue : appState.elapsedSeconds
    }

    @ViewBuilder
    private var sliderSection: some View {
        VStack(spacing: LibraVaultSpacing.xs) {
            Slider(
                value: Binding(get: { scrubberDisplayValue }, set: { scrubberDragValue = $0 }),
                in: 0...max(appState.totalEstimatedSeconds, 1),
                onEditingChanged: { isEditing in
                    if isEditing {
                        // Drag just started — seed the drag value from wherever
                        // playback actually is right now, not a stale 0.
                        scrubberDragValue = appState.elapsedSeconds
                    } else {
                        // Drag ended — commit once, the only point that should
                        // actually seek/sync Now Playing.
                        appState.seek(to: scrubberDragValue)
                    }
                    isDraggingScrubber = isEditing
                }
            )
            .tint(LibraVaultColor.primary)
            HStack {
                Text(formatPlaybackTime(scrubberDisplayValue))
                Spacer()
                Text(formatPlaybackTime(appState.totalEstimatedSeconds))
            }
            .font(LibraVaultTypography.labelSmall)
            .foregroundStyle(LibraVaultColor.onSurfaceVariant)
        }
        .padding(.horizontal, LibraVaultSpacing.xl)
    }

    @ViewBuilder
    private var transportButtons: some View {
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
            .disabled(appState.nowPlayingChapter >= appState.nowPlayingChapterCount)
        }
        .font(.system(size: 28))
        .foregroundStyle(LibraVaultColor.onBackground)
    }

    @ViewBuilder
    private var speedButton: some View {
        Button(action: { showSpeedSheet = true }) {
            Text(formatPlaybackSpeed(appState.playbackSpeed))
                .font(LibraVaultTypography.labelLarge)
                .foregroundStyle(LibraVaultColor.primary)
        }
        .accessibilityIdentifier("player.speedButton")
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
