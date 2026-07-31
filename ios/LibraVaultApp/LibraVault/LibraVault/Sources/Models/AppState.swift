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
            guard let book = nowPlayingBook else { return }
            if book.format.isAudio {
                // Real audio: the engine's own currentTime/duration already reflect
                // reality, nothing to recompute — just apply the new rate live.
                audioEngine.setRate(Float(playbackSpeed))
                return
            }
            // TTS/text: totalEstimatedSeconds was computed for the old speed at
            // startPlayback/skipToChapter time — without this, changing speed
            // mid-chapter leaves it stale, so the scrub bar's "total" stops matching
            // the estimated rate and the chapter advances earlier or later than it
            // estimates. Rescale it (and elapsedSeconds proportionally, to preserve
            // how far through the chapter the listener actually is) to the new speed.
            guard totalEstimatedSeconds > 0 else { return }
            let progressFraction = elapsedSeconds / totalEstimatedSeconds
            totalEstimatedSeconds = Self.estimateDuration(
                for: chapterText(for: nowPlayingChapter),
                speed: playbackSpeed
            )
            elapsedSeconds = progressFraction * totalEstimatedSeconds
        }
    }
    @Published private(set) var elapsedSeconds: Double = 0
    @Published private(set) var totalEstimatedSeconds: Double = 0
    @Published private(set) var sleepTimerRemainingSeconds: Double?

    private let audioEngine = AudioPlaybackEngine()
    /// The vault whose security-scoped bookmark is held open for as long as an
    /// audiobook is playing — unlike BookContentProvider's EPUB/PDF reads (which read
    /// the whole file upfront and can release scope immediately), AVAudioPlayer reads
    /// from the file for the duration of playback, so scope has to stay open until
    /// playback actually stops.
    private var activeAudioVaultURL: URL?

    /// Real chapters for the book currently loaded into the player, when its format
    /// has a parser (EPUB/PDF) and parsing succeeded — nil falls back to
    /// MockChapterContent, same "not available" (not "not loaded yet") meaning as
    /// ReaderView's realChapters. Loaded once per book in startPlayback, not on every
    /// chapter skip within the same book. Never populated for audio books — see
    /// nowPlayingChapterCount/Titles below.
    private var nowPlayingChapters: [BookChapter]?

    /// Audiobooks are always 1 "chapter" for now (see AudioPlaybackEngine's doc
    /// comment on why real embedded chapter markers aren't extracted yet) — real
    /// skip-forward/backward seeking is the primary navigation for those regardless.
    var nowPlayingChapterCount: Int {
        guard let nowPlayingBook else { return MockChapterContent.count }
        if nowPlayingBook.format.isAudio { return 1 }
        return nowPlayingChapters?.count ?? MockChapterContent.count
    }

    /// Titles for the chapters sheet — real chapter titles when available, the book's
    /// own title for a (single-chapter) audiobook, else MockChapterContent's.
    var nowPlayingChapterTitles: [String] {
        guard let nowPlayingBook else {
            return (1...MockChapterContent.count).map { MockChapterContent.title(for: $0) }
        }
        if nowPlayingBook.format.isAudio { return [nowPlayingBook.title] }
        if let nowPlayingChapters { return nowPlayingChapters.map(\.title) }
        return (1...MockChapterContent.count).map { MockChapterContent.title(for: $0) }
    }

    private func chapterText(for chapter: Int) -> String {
        if let nowPlayingChapters, !nowPlayingChapters.isEmpty {
            return nowPlayingChapters[(chapter - 1) % nowPlayingChapters.count].text
        }
        return MockChapterContent.text(for: chapter)
    }

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
    // Genuinely scanned for book/audiobook files (LibraryFileScanner). `books` below
    // is always real vault content — an empty `vaults` list means an empty library,
    // not a fallback/demo one.
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
        #if DEBUG
        UITestFixtures.ensureVault(persistence: vaultPersistence)
        #endif
        vaults = vaultPersistence.loadVaults()
        defaultReadingTheme = userPreferencesPersistence.loadReadingTheme()
        defaultPlaybackSpeed = userPreferencesPersistence.loadPlaybackSpeed()
        skipDurationSeconds = userPreferencesPersistence.loadSkipDurationSeconds()

        audioEngine.onProgress = { [weak self] elapsed, duration in
            Task { @MainActor [weak self] in
                self?.elapsedSeconds = elapsed
                self?.totalEstimatedSeconds = duration
            }
        }
        audioEngine.onFinished = { [weak self] in
            Task { @MainActor [weak self] in
                self?.stopPlayback()
            }
        }

        Task {
            try? await bridge.initialize()
            await loadLibrary()
        }
    }

    func loadLibrary() async {
        isLoading = true
        defer { isLoading = false }

        books = scanVaults().map { BookItem(from: $0) }
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
    // Two real backends, chosen by book.format.isAudio: audio-format books play their
    // real file through AudioPlaybackEngine (AVAudioPlayer); text-format books are
    // narrated through real TTS (AVSpeechSynthesizer, see DomainBridge.swift's
    // TTSEngineBridge) against chapterText(for:), with elapsedSeconds/
    // totalEstimatedSeconds driven by a wall-clock timer and a word-count estimate,
    // since there's no real audio stream to measure against for synthesized speech.

    func startPlayback(book: BookItem, chapter: Int = 1) {
        let isNewSession = nowPlayingBook?.id != book.id
        if isNewSession {
            // Only reset to the preference / tear down the previous session's engine
            // when this is genuinely a new listening session (a different book) —
            // skipToChapter also routes through here to advance chapters of the
            // *same* book, and shouldn't stomp a speed the listener just adjusted
            // mid-session back to the default, restart audio from 0, or re-parse the
            // book's file on every single chapter change.
            playbackSpeed = defaultPlaybackSpeed
            stopTimer()
            audioEngine.stop()
            releaseActiveAudioVaultAccess()
            nowPlayingChapters = book.format.isAudio
                ? nil
                : try? BookContentProvider.chapters(for: book, vaultPersistence: vaultPersistence)
        }
        nowPlayingBook = book
        nowPlayingChapter = book.format.isAudio ? 1 : chapter
        isPlaying = true

        if book.format.isAudio {
            if isNewSession {
                startAudioPlayback(book: book)
            } else {
                audioEngine.resume()
            }
        } else {
            let text = chapterText(for: nowPlayingChapter)
            totalEstimatedSeconds = Self.estimateDuration(for: text, speed: playbackSpeed)
            elapsedSeconds = 0
            Task { try? await bridge.startSpeaking(text: text, rate: playbackSpeed) }
            startTimer()
        }
    }

    /// Resolves the book's vault, opens the security-scoped bookmark for the
    /// duration of playback (see activeAudioVaultURL's doc comment), and starts the
    /// engine. Silently gives up on any failure (missing file reference, unresolvable
    /// vault, unplayable file) — there's no real error-reporting path for playback
    /// failures yet, so this at least doesn't leave `isPlaying` lying about state.
    private func startAudioPlayback(book: BookItem) {
        guard let fileURL = book.fileURL, let vaultId = book.vaultId,
              let vault = vaultPersistence.loadVaults().first(where: { $0.id == vaultId }),
              let resolvedVaultURL = vaultPersistence.resolvedURL(for: vault) else {
            isPlaying = false
            nowPlayingBook = nil
            return
        }

        let didStartAccessing = resolvedVaultURL.startAccessingSecurityScopedResource()
        activeAudioVaultURL = didStartAccessing ? resolvedVaultURL : nil

        do {
            try audioEngine.play(fileURL: fileURL, rate: Float(playbackSpeed))
            elapsedSeconds = 0
            totalEstimatedSeconds = audioEngine.duration
        } catch {
            isPlaying = false
            nowPlayingBook = nil
            releaseActiveAudioVaultAccess()
        }
    }

    private func releaseActiveAudioVaultAccess() {
        activeAudioVaultURL?.stopAccessingSecurityScopedResource()
        activeAudioVaultURL = nil
    }

    func togglePlayback() {
        guard let book = nowPlayingBook else { return }
        isPlaying.toggle()
        if book.format.isAudio {
            if isPlaying { audioEngine.resume() } else { audioEngine.pause() }
        } else if isPlaying {
            startTimer()
            Task { await bridge.resumeSpeaking() }
        } else {
            stopTimer()
            Task { await bridge.pauseSpeaking() }
        }
    }

    func skipToChapter(_ chapter: Int) {
        guard let book = nowPlayingBook else { return }
        let clamped = max(1, min(chapter, nowPlayingChapterCount))
        startPlayback(book: book, chapter: clamped)
    }

    /// Scrub-bar seeking. For audio books this is a genuine seek within the real
    /// file; for TTS/text books there's still no real audio stream to seek within,
    /// but the wall-clock elapsed timer honors a manual position the same way a real
    /// player would.
    func seek(to seconds: Double) {
        guard let book = nowPlayingBook else { return }
        let clamped = max(0, min(seconds, totalEstimatedSeconds))
        elapsedSeconds = clamped
        if book.format.isAudio {
            audioEngine.elapsed = clamped
        }
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
        audioEngine.stop()
        releaseActiveAudioVaultAccess()
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
            if nowPlayingChapter < nowPlayingChapterCount {
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
    /// The real file this book was scanned from, and the vault it belongs to — needed
    /// to reopen the book for content parsing/playback. Nil for books not backed by a
    /// real vault scan (e.g. constructed directly in tests/previews).
    let fileURL: URL?
    let vaultId: String?

    init(id: String, title: String, author: String, format: MediaFormat = .epub, coverUrl: String? = nil, progress: Double = 0.0, fileURL: URL? = nil, vaultId: String? = nil) {
        self.id = id
        self.title = title
        self.author = author
        self.format = format
        self.coverUrl = coverUrl
        self.progress = progress
        self.fileURL = fileURL
        self.vaultId = vaultId
    }

    init(from bookData: BookData) {
        self.id = bookData.id
        self.title = bookData.title
        self.author = bookData.author
        self.format = bookData.format
        self.coverUrl = nil
        self.progress = bookData.progress
        self.fileURL = bookData.fileURL
        self.vaultId = bookData.vaultId
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
