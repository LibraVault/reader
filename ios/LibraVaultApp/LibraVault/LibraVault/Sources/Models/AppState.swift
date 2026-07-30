import SwiftUI
import Foundation

@MainActor
final class AppState: ObservableObject {
    @Published var books: [BookItem] = []
    @Published var isLoading = false
    @Published var error: AppError?
    // TODO: Wire to core:licensing once a Pro/Supporter state bridge exists (see
    // DomainBridge.swift's Phase D TODOs) — always false until then, matching reality
    // rather than showing a badge no license check backs.
    @Published var isSupporter = false

    // MARK: - Settings (Reading / Playback defaults)

    /// Applied when ReaderView opens a book — see ReaderView's `.task` modifier.
    /// Persisted via userPreferencesPersistence — see the didSet below.
    @Published var defaultReadingTheme: ReadingTheme = .dark {
        didSet { userPreferencesPersistence.save(readingTheme: defaultReadingTheme) }
    }
    /// Read by PlayerView's skip-back/skip-forward buttons instead of a hardcoded 30.
    @Published var skipDurationSeconds: Double = 30 {
        didSet { userPreferencesPersistence.save(skipDurationSeconds: skipDurationSeconds) }
    }
    /// The Settings-configured preference a *new* listening session starts at —
    /// deliberately not the same property as the live `playbackSpeed` below. Android
    /// keeps these separate (prefs.defaultPlaybackSpeed vs. the live SpeedPickerSheet
    /// control); collapsing them into one shared value would mean dragging "Default
    /// speed" in Settings visibly changes the pace of whatever's already playing in
    /// the background mini-player, which isn't what a "default for next time" setting
    /// should do. See startPlayback's use of this.
    @Published var defaultPlaybackSpeed: Double = 1.0 {
        didSet { userPreferencesPersistence.save(playbackSpeed: defaultPlaybackSpeed) }
    }

    // MARK: - Playback (mini-player / Player screen)

    @Published private(set) var nowPlayingBook: BookItem?
    @Published private(set) var nowPlayingChapter = 1
    @Published private(set) var isPlaying = false
    @Published var playbackSpeed: Double = 1.0 {
        didSet {
            // totalEstimatedSeconds was computed for the old speed at startPlayback/
            // skipToChapter time — without this, changing speed mid-chapter leaves it
            // stale, so the scrub bar's "total" stops matching the actual playback
            // rate and the chapter advances earlier or later than it estimates.
            // Rescale it (and elapsedSeconds proportionally, to preserve how far
            // through the chapter the listener actually is) to the new speed.
            guard nowPlayingBook != nil, totalEstimatedSeconds > 0 else { return }
            let progressFraction = elapsedSeconds / totalEstimatedSeconds
            totalEstimatedSeconds = Self.estimateDuration(
                for: MockChapterContent.text(for: nowPlayingChapter),
                speed: playbackSpeed
            )
            elapsedSeconds = progressFraction * totalEstimatedSeconds
        }
    }
    @Published private(set) var elapsedSeconds: Double = 0
    @Published private(set) var totalEstimatedSeconds: Double = 0
    @Published private(set) var sleepTimerRemainingSeconds: Double?

    /// Set by PlayerView's onAppear/onDisappear so the global mini-player can hide
    /// itself while the full Player screen is already showing the same controls.
    @Published var isPlayerScreenActive = false

    /// Drives RootView's `.navigationDestination(isPresented:)` for PlayerView.
    /// Lives here, not as local @State on RootView, because ReaderView — a pushed
    /// destination several levels deep in the same stack — needs to trigger the same
    /// push when "Read Aloud" starts playback, and it has no direct access to a
    /// sibling view's local state.
    @Published var shouldNavigateToPlayer = false

    // MARK: - Vaults
    //
    // Folder locations the user has granted access to via Settings' "Add Vault"
    // picker — the iOS counterpart to Android's Storage Access Framework vault list.
    // Persisted across launches through a security-scoped bookmark; see Vault.swift.
    // Genuinely scanned for book/audiobook files (LibraryFileScanner). Once any vault
    // exists, `books` below is real vault content ONLY — DomainBridge's small demo
    // library (see its header comment) is shown solely as a first-launch preview
    // before a vault has ever been added, never mixed in alongside real books.
    @Published private(set) var vaults: [Vault] = []

    private var playbackTimer: Timer?
    private var sleepTimer: Timer?

    private let bridge = LibravaultDomainBridge.shared
    private let vaultPersistence: VaultPersistence
    private let userPreferencesPersistence: UserPreferencesPersistence

    init(
        vaultPersistence: VaultPersistence = VaultPersistence(),
        userPreferencesPersistence: UserPreferencesPersistence = UserPreferencesPersistence()
    ) {
        self.vaultPersistence = vaultPersistence
        self.userPreferencesPersistence = userPreferencesPersistence
        vaults = vaultPersistence.loadVaults()
        defaultReadingTheme = userPreferencesPersistence.loadReadingTheme()
        defaultPlaybackSpeed = userPreferencesPersistence.loadPlaybackSpeed()
        skipDurationSeconds = userPreferencesPersistence.loadSkipDurationSeconds()
        Task {
            try? await bridge.initialize()
            await loadLibrary()
        }
    }

    func loadLibrary() async {
        isLoading = true
        defer { isLoading = false }

        if vaults.isEmpty {
            // No real vault configured yet — show the bridge's small demo library as a
            // first-launch preview rather than a plain empty screen. The moment a vault
            // is added, this branch never runs again: see the `else`.
            books = ((try? await bridge.scanLibrary(vaultPath: "/Documents")) ?? []).map { BookItem(from: $0) }
        } else {
            books = scanVaults().map { BookItem(from: $0) }
        }
        bridge.log("Loaded \(books.count) books from library", tag: "Library")
    }

    /// Adds a folder picked via Settings' `.fileImporter` as a new vault, persists it,
    /// and immediately rescans so its contents show up in the Library grid without
    /// requiring a manual refresh.
    ///
    /// Dedupes by resolved path — picking the same folder twice (easy to do, since
    /// it's the natural place to browse back to) would otherwise double up every file
    /// in it in the Library grid, since each vault gets its own UUID and scans
    /// independently. Mirrors AddVaultFolderUseCase's URI dedup on the Android side.
    func addVault(pickedURL: URL) {
        guard let vault = try? vaultPersistence.makeVault(from: pickedURL) else {
            error = AppError.storageAccessDenied
            return
        }
        guard !vaults.contains(where: { vaultPersistence.resolvedURL(for: $0)?.path == pickedURL.path }) else {
            return
        }
        vaults.append(vault)
        vaultPersistence.save(vaults)
        Task { await loadLibrary() }
    }

    func removeVault(_ vault: Vault) {
        vaults.removeAll { $0.id == vault.id }
        vaultPersistence.save(vaults)
        Task { await loadLibrary() }
    }

    private func scanVaults() -> [BookData] {
        vaults.flatMap { vault -> [BookData] in
            guard let resolvedURL = vaultPersistence.resolvedURL(for: vault) else { return [] }
            return LibraryFileScanner.scan(vault: vault, resolvedURL: resolvedURL)
        }
    }

    func clearError() {
        error = nil
    }

    // MARK: - Playback controls
    //
    // There's no real audio engine or audiobook file behind this (TTSEngineBridge in
    // DomainBridge.swift is still a no-op) — TTS start/stop/pause/resume calls are
    // real, but "elapsed"/"duration" have nothing to measure against. Rather than
    // fabricate static numbers, elapsedSeconds is a real wall-clock timer and
    // totalEstimatedSeconds is computed from the chapter's actual word count, so the
    // scrub bar in PlayerView reflects genuinely changing state instead of a static prop.

    func startPlayback(book: BookItem, chapter: Int = 1) {
        // Only reset to the preference when this is genuinely a new listening session
        // (a different book) — skipToChapter also routes through here to advance
        // chapters of the *same* book, and shouldn't stomp a speed the listener just
        // adjusted mid-session back to the default.
        if nowPlayingBook?.id != book.id {
            playbackSpeed = defaultPlaybackSpeed
        }
        nowPlayingBook = book
        nowPlayingChapter = chapter
        let text = MockChapterContent.text(for: chapter)
        totalEstimatedSeconds = Self.estimateDuration(for: text, speed: playbackSpeed)
        elapsedSeconds = 0
        isPlaying = true
        Task { try? await bridge.startSpeaking(text: text) }
        startTimer()
    }

    func togglePlayback() {
        guard nowPlayingBook != nil else { return }
        isPlaying.toggle()
        if isPlaying {
            startTimer()
            Task { await bridge.resumeSpeaking() }
        } else {
            stopTimer()
            Task { await bridge.pauseSpeaking() }
        }
    }

    func skipToChapter(_ chapter: Int) {
        guard let book = nowPlayingBook else { return }
        let clamped = max(1, min(chapter, MockChapterContent.count))
        startPlayback(book: book, chapter: clamped)
    }

    /// Scrub-bar seeking. There's no real audio stream to seek within, but the
    /// wall-clock elapsed timer can honor a manual position the same way a real
    /// player would — this isn't cosmetic, dragging the slider genuinely moves
    /// elapsedSeconds and the next tick continues from there.
    func seek(to seconds: Double) {
        guard nowPlayingBook != nil else { return }
        elapsedSeconds = max(0, min(seconds, totalEstimatedSeconds))
    }

    func skipForward(seconds: Double = 30) {
        seek(to: elapsedSeconds + seconds)
    }

    func skipBackward(seconds: Double = 30) {
        seek(to: elapsedSeconds - seconds)
    }

    func stopPlayback() {
        isPlaying = false
        nowPlayingBook = nil
        elapsedSeconds = 0
        totalEstimatedSeconds = 0
        stopTimer()
        cancelSleepTimer()
        Task { await bridge.stopSpeaking() }
    }

    func scheduleSleepTimer(minutes: Double) {
        sleepTimer?.invalidate()
        sleepTimerRemainingSeconds = minutes * 60
        sleepTimer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] _ in
            Task { @MainActor [weak self] in
                guard let self, var remaining = self.sleepTimerRemainingSeconds else { return }
                remaining -= 1
                if remaining <= 0 {
                    self.cancelSleepTimer()
                    self.stopPlayback()
                } else {
                    self.sleepTimerRemainingSeconds = remaining
                }
            }
        }
    }

    func cancelSleepTimer() {
        sleepTimer?.invalidate()
        sleepTimer = nil
        sleepTimerRemainingSeconds = nil
    }

    private func startTimer() {
        stopTimer()
        playbackTimer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] _ in
            Task { @MainActor [weak self] in
                self?.tick()
            }
        }
    }

    private func stopTimer() {
        playbackTimer?.invalidate()
        playbackTimer = nil
    }

    private func tick() {
        guard isPlaying else { return }
        elapsedSeconds = min(elapsedSeconds + playbackSpeed, totalEstimatedSeconds)
        if elapsedSeconds >= totalEstimatedSeconds {
            if nowPlayingChapter < MockChapterContent.count {
                skipToChapter(nowPlayingChapter + 1)
            } else {
                stopPlayback()
            }
        }
    }

    /// ~150 wpm is a common average narration/TTS pace; scaled by playbackSpeed so a
    /// 2x speed halves the estimated duration, matching what a real speed control
    /// should do even without a real audio engine behind it.
    static func estimateDuration(for text: String, speed: Double) -> Double {
        let wordCount = Double(text.split(separator: " ").count)
        let effectiveWordsPerMinute = 150.0 * max(speed, 0.1)
        return max((wordCount / effectiveWordsPerMinute) * 60, 1)
    }
}

struct BookItem: Identifiable {
    let id: String
    let title: String
    let author: String
    let format: MediaFormat
    let coverUrl: String?
    var progress: Double

    init(id: String, title: String, author: String, format: MediaFormat = .epub, coverUrl: String? = nil, progress: Double = 0.0) {
        self.id = id
        self.title = title
        self.author = author
        self.format = format
        self.coverUrl = coverUrl
        self.progress = progress
    }

    init(from bookData: BookData) {
        self.id = bookData.id
        self.title = bookData.title
        self.author = bookData.author
        self.format = bookData.format
        self.coverUrl = nil
        self.progress = bookData.progress
    }
}

enum AppError: LocalizedError {
    case libraryLoadFailed(String)
    case bookNotFound
    case storageAccessDenied

    var errorDescription: String? {
        switch self {
        case .libraryLoadFailed(let reason):
            return "Failed to load library: \(reason)"
        case .bookNotFound:
            return "Book not found"
        case .storageAccessDenied:
            return "Storage access denied"
        }
    }
}
