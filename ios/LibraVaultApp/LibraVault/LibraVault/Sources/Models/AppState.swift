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
    /// Mirrors Android's Settings > Text-to-Speech engine picker
    /// (TtsSettingsSection.kt). Defaults to `.system` in `bridge.initialize()`
    /// regardless of this saved value - only switched to Pocket TTS here,
    /// after load, so a corrupt/missing bundled model (see
    /// PocketModelManager) degrades to system voice instead of leaving
    /// speech silently broken on launch.
    @Published var ttsEngineType: TTSEngineType = .system {
        didSet {
            guard ttsEngineType != oldValue else { return }
            userPreferencesPersistence.save(ttsEngineType: ttsEngineType)
            Task { await bridge.switchTTSEngine(to: ttsEngineType) }
        }
    }
    /// Whether MiniPlayerBar collapses to a small hint strip after a few seconds
    /// idle. Defaults to enabled — see UserPreferencesPersistence.loadMiniPlayerAutoHideEnabled.
    @Published var miniPlayerAutoHideEnabled: Bool = true {
        didSet { userPreferencesPersistence.save(miniPlayerAutoHideEnabled: miniPlayerAutoHideEnabled) }
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

    private let audioEngine: AudioPlaybackEngineProtocol
    /// The vault whose security-scoped bookmark is held open for as long as an
    /// audiobook is playing — unlike BookContentProvider's EPUB/PDF reads (which read
    /// the whole file upfront and can release scope immediately), AVAudioPlayer reads
    /// from the file for the duration of playback, so scope has to stay open until
    /// playback actually stops.
    private var activeAudioVaultURL: URL?

    /// Real chapters for the book currently loaded into the player, when its format
    /// has a parser (EPUB/PDF) and parsing succeeded. Loaded once per book in
    /// startPlayback, not on every chapter skip within the same book. Never populated
    /// for audio books — see nowPlayingChapterCount/Titles below. Stays nil (0
    /// chapters, no text) if loading failed — in practice unreachable in normal usage
    /// since ReaderView won't offer "Read Aloud" for a book it couldn't itself load
    /// (see its own unavailableContent state), but chapterText/nowPlayingChapterCount
    /// degrade safely rather than crash if ever reached with nothing loaded.
    private var nowPlayingChapters: [BookChapter]?

    /// Audiobooks are always 1 "chapter" for now (see AudioPlaybackEngine's doc
    /// comment on why real embedded chapter markers aren't extracted yet) — real
    /// skip-forward/backward seeking is the primary navigation for those regardless.
    var nowPlayingChapterCount: Int {
        guard let nowPlayingBook else { return 0 }
        if nowPlayingBook.format.isAudio { return 1 }
        return nowPlayingChapters?.count ?? 0
    }

    /// Titles for the chapters sheet — real chapter titles for text books, the book's
    /// own title for a (single-chapter) audiobook.
    var nowPlayingChapterTitles: [String] {
        guard let nowPlayingBook else { return [] }
        if nowPlayingBook.format.isAudio { return [nowPlayingBook.title] }
        return nowPlayingChapters?.map(\.title) ?? []
    }

    private func chapterText(for chapter: Int) -> String {
        guard let nowPlayingChapters, !nowPlayingChapters.isEmpty else { return "" }
        return nowPlayingChapters[(chapter - 1) % nowPlayingChapters.count].text
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
        userPreferencesPersistence: UserPreferencesPersistence = UserPreferencesPersistence(),
        audioEngine: AudioPlaybackEngineProtocol = AudioPlaybackEngine()
    ) {
        self.vaultPersistence = vaultPersistence
        self.userPreferencesPersistence = userPreferencesPersistence
        self.audioEngine = audioEngine
        #if DEBUG
        UITestFixtures.ensureVault(persistence: vaultPersistence)
        #endif
        vaults = vaultPersistence.loadVaults()
        defaultReadingTheme = userPreferencesPersistence.loadReadingTheme()
        defaultPlaybackSpeed = userPreferencesPersistence.loadPlaybackSpeed()
        skipDurationSeconds = userPreferencesPersistence.loadSkipDurationSeconds()
        ttsEngineType = userPreferencesPersistence.loadTTSEngineType()
        miniPlayerAutoHideEnabled = userPreferencesPersistence.loadMiniPlayerAutoHideEnabled()

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

        let scanned = scanVaults()
        books = scanned.map { BookItem(from: $0) }
        bridge.log("Loaded \(books.count) books from library", tag: "Library")

        enrichCoverArt(for: scanned)
    }

    /// Phase 2 of the two-phase scan LibraryFileScanner.swift's header comment already
    /// describes as the intended shape: phase 1 (above) returns instantly from
    /// filenames alone so the Library grid never blocks on I/O, then this walks the
    /// same scan results in the background (zip reads for EPUB, PDF page rendering,
    /// AVAsset metadata loading — see CoverArtExtractor) and patches real cover art
    /// into `books` as each extraction finishes, replacing that book's placeholder
    /// gradient. Detached rather than structured under `loadLibrary`'s own async
    /// context so a rescan (addVault/removeVault) doesn't leave a stale enrichment
    /// pass racing a newer one — each call gets its own detached task, and a stale
    /// pass's writes for since-removed books are simply no-ops below (index lookup
    /// by id fails once a book is gone from `books`).
    private func enrichCoverArt(for scanned: [BookData]) {
        let cache = CoverArtCache()
        Task.detached(priority: .utility) { [weak self] in
            for bookData in scanned {
                guard let coverURL = await CoverArtExtractor.extractCoverPath(for: bookData, cache: cache) else { continue }
                await MainActor.run {
                    guard let self else { return }
                    if let index = self.books.firstIndex(where: { $0.id == bookData.id }) {
                        self.books[index].coverUrl = coverURL.path
                    }
                    // nowPlayingBook is a value-type snapshot taken once at
                    // startPlayback — if playback started on this book before its
                    // extraction above finished (a real race for audiobooks, whose
                    // AVAsset metadata load can be slow on a large file), the
                    // snapshot's coverUrl stays nil forever even after `books` gets
                    // patched, since nothing re-reads it from there. Patch the
                    // now-playing copy directly so the mini-player/PlayerView pick
                    // up the art the moment it's ready (reported in the field:
                    // audiobook cover art blank in the mini-player/Now Playing).
                    if self.nowPlayingBook?.id == bookData.id {
                        self.nowPlayingBook?.coverUrl = coverURL.path
                    }
                }
            }
        }
    }

    /// Adds a folder picked via Settings' `.fileImporter` as a new vault, persists it,
    /// and immediately rescans so its contents show up in the Library grid without
    /// requiring a manual refresh.
    ///
    /// Dedupes by resolved path — picking the same folder twice (easy to do, since
    /// it's the natural place to browse back to) would otherwise double up every file
    /// in it in the Library grid, since each vault gets its own UUID and scans
    /// independently. Mirrors AddVaultFolderUseCase's URI dedup on the Android side.
    ///
    /// `allowedContentTypes: [.folder]` on the `.fileImporter` call is meant to keep
    /// `pickedURL` a directory, but that constraint is enforced by whichever
    /// document-provider extension is browsing, not the OS — some third-party
    /// providers (Google Drive being the most commonly reported one) don't honor a
    /// folder-only UTType filter and can hand back a plain file. Without this check,
    /// that file becomes a "vault" that looks completely normal in Settings but
    /// silently scans to 0 books forever, since `LibraryFileScanner.scan` walks it as
    /// a directory (see issue #185's field report — a tester with 3 such vaults and a
    /// permanently empty Library).
    func addVault(pickedURL: URL) {
        let didStartAccessing = pickedURL.startAccessingSecurityScopedResource()
        let isDirectory = (try? pickedURL.resourceValues(forKeys: [.isDirectoryKey]))?.isDirectory == true
        if didStartAccessing { pickedURL.stopAccessingSecurityScopedResource() }
        guard isDirectory else {
            error = .invalidVaultSelection
            return
        }

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

    /// Mirrors Android's `RemoveVaultFolderUseCase` (core:domain UseCases.kt): that
    /// use case's two steps are `libraryRepository.deleteByVault(vaultId)` — which,
    /// via Room's `ON DELETE CASCADE` foreign keys, also drops any highlights/
    /// bookmarks/progress rows tied to that vault's library items — then
    /// `vaultRepository.removeVault(vaultId)`. iOS has no on-device database with FK
    /// cascades to lean on (see DomainBridge.swift's header comment — no KMP
    /// framework is actually linked in), so the same two steps are done explicitly
    /// here instead: `bridge.removeVault(bookIds:)` is the Swift-native counterpart
    /// to the cascading delete (without it, a removed vault's bookmarks/highlights/
    /// reading progress would silently linger forever in UserDefaults, keyed by book
    /// ids that no longer resolve to anything), then the vault entry itself is
    /// dropped from `vaultPersistence`, same as before.
    ///
    /// `async`, unlike `addVault` (which fires its rescan off as an internal,
    /// un-awaited `Task`) — the bridge cleanup here has no other synchronization
    /// point a caller (or a test) can hook into to know it's finished, so callers
    /// await this directly (see `SettingsView`'s confirm-alert action) instead of
    /// racing a detached `Task`.
    func removeVault(_ vault: Vault) async {
        let orphanedBookIds = books.filter { $0.vaultId == vault.id }.map(\.id)
        do {
            // Cascade-clean bookmarks/highlights/progress FIRST, matching Android's
            // RemoveVaultFolderUseCase (deleteByVault before removeVault). If this
            // throws, the vault stays in persistence and its books' data survives —
            // orphaned-but-recoverable is the safe failure mode. Doing this after
            // dropping the vault would risk the opposite: a vault silently gone while
            // its books' bookmarks/highlights/progress live on forever with no owner.
            try await bridge.removeVault(bookIds: orphanedBookIds)
        } catch {
            bridge.logError("Couldn't remove vault \"\(vault.name)\"", tag: "Vault", error: error)
            self.error = AppError.vaultRemovalFailed
            return
        }
        vaults.removeAll { $0.id == vault.id }
        vaultPersistence.save(vaults)
        await loadLibrary()
    }

    /// Entry point for a file the OS hands LibraVault via "Open In"/"Copy to
    /// LibraVault" from another app's share sheet (see LibraVaultApp.swift's
    /// `.onOpenURL` and Info.plist's CFBundleDocumentTypes). Unlike `addVault`, which
    /// grants access to a whole folder the user picked, this receives a single file —
    /// so instead of bookmarking it in place, it's copied into the permanent
    /// `VaultPersistence.importedVault()` folder and the library is rescanned, the same
    /// as if it had always lived in a vault.
    ///
    /// Reports failure via `error` rather than crashing — RootView surfaces it as an
    /// alert (see its `errorAlertBinding`) — on an extension LibraryFileScanner doesn't
    /// recognize, or on any failure to create/copy into the Imported vault. Every
    /// failure path also leaves a trail in LibraVaultLogStore via `bridge.logError` —
    /// `error` alone tells the user something went wrong, but not *why*, which matters
    /// for a "sharing a book doesn't work" field report with no other diagnostics.
    ///
    /// `async` (called from LibraVaultApp's `.onOpenURL` inside a `Task { }`) rather
    /// than fire-and-forget: the actual file copy runs off the main actor in a detached
    /// task below and this awaits it, so a large/slow shared file (a multi-hundred-MB
    /// audiobook, or one iCloud hasn't finished downloading) can't freeze the UI or trip
    /// the main-thread hang watchdog just because import was triggered synchronously.
    func importSharedFile(url: URL) async {
        guard LibraryFileScanner.extensionFormats[url.pathExtension.lowercased()] != nil else {
            error = AppError.unsupportedFileType
            return
        }

        let vault: Vault
        let destinationFolder: URL
        do {
            vault = try vaultPersistence.importedVault()
            guard let resolved = vaultPersistence.resolvedURL(for: vault) else {
                throw AppError.fileImportFailed
            }
            destinationFolder = resolved
        } catch {
            bridge.logError("Couldn't prepare the Imported vault", tag: "Import", error: error)
            self.error = AppError.fileImportFailed
            return
        }

        if !vaults.contains(where: { $0.id == vault.id }) {
            vaults.append(vault)
            vaultPersistence.save(vaults)
        }

        // `Error` isn't guaranteed Sendable, so the detached task hands back a plain
        // String (or nil for success) rather than the thrown error itself.
        let filename = url.lastPathComponent
        let copyFailureDescription: String? = await Task.detached(priority: .userInitiated) {
            // The incoming URL (from another app's document provider, e.g. Files/
            // iCloud Drive) may itself be security-scoped even though the destination
            // isn't — mirrors the start/stop pairing already used in
            // makeVault/LibraryFileScanner.
            let didStartAccessing = url.startAccessingSecurityScopedResource()
            defer { if didStartAccessing { url.stopAccessingSecurityScopedResource() } }

            let destinationURL = Self.uniqueDestinationURL(for: filename, in: destinationFolder)
            do {
                try FileManager.default.copyItem(at: url, to: destinationURL)
                return nil
            } catch {
                return String(describing: error)
            }
        }.value

        if let copyFailureDescription {
            bridge.logError("Couldn't copy shared file \"\(filename)\": \(copyFailureDescription)", tag: "Import")
            self.error = AppError.fileImportFailed
        } else {
            await loadLibrary()
        }
    }

    /// Appends " 2", " 3", … before the extension until the name is free, so importing
    /// two different files that happen to share a filename (e.g. re-sharing what looks
    /// like "the same" book from a different source) never silently overwrites the
    /// earlier one.
    ///
    /// `nonisolated`: static members of an `@MainActor` type are MainActor-isolated by
    /// default, but `importSharedFile` calls this from inside its `Task.detached` copy
    /// step specifically to keep it off the main actor — this touches no actor-isolated
    /// state (pure FileManager/String work), so it's safe to opt out.
    private nonisolated static func uniqueDestinationURL(for filename: String, in folder: URL) -> URL {
        var candidate = folder.appendingPathComponent(filename)
        guard FileManager.default.fileExists(atPath: candidate.path) else { return candidate }

        let ext = (filename as NSString).pathExtension
        let base = (filename as NSString).deletingPathExtension
        var counter = 2
        repeat {
            let newName = ext.isEmpty ? "\(base) \(counter)" : "\(base) \(counter).\(ext)"
            candidate = folder.appendingPathComponent(newName)
            counter += 1
        } while FileManager.default.fileExists(atPath: candidate.path)
        return candidate
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
        // A text format with no chapter parser has nothing to narrate — mobi/cbz never
        // reach BookContentProvider.chapters' switch (Markdown does, since #124). Bail
        // out first, before any teardown or state assignment: continuing would speak an
        // empty string, run the wall-clock timer against a 0-second estimate, and leave
        // an idle mini-player pinned to a book that can never play — while also having
        // torn down whatever session was legitimately playing. Gated on format rather
        // than on `nowPlayingChapters == nil` because a parseable format whose file just
        // isn't reachable (no vault fixture, as in most of the playback tests) should
        // still enter a playing state, as it always has. Callers gate too (see
        // ReaderSettingsSheet.showReadAloud); this is the backstop.
        guard book.format.isAudio || BookContentProvider.supportsChapterParsing(book.format) else { return }

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

            // A Markdown file that parses successfully but has nothing speakable (an
            // image-only document, or one made entirely of code blocks/tables/thematic
            // breaks — see MarkdownDocumentParser.narrationText) would otherwise reach
            // the exact phantom-player state the format-gate above prevents for "no
            // parser at all": empty text, isPlaying = true, a 0-second estimate. EPUB/PDF
            // don't need this check — a near-zero-content book in those formats isn't a
            // realistic case the way an image-only Markdown note is, and several
            // playback tests rely on an *unreachable* EPUB file (chapters is nil, not
            // empty) still entering a playing state. Checking specifically for an empty
            // (not nil) array preserves that: parsing failure still proceeds as before,
            // this only catches "parsed fine, genuinely nothing to say".
            if book.format == .markdown, nowPlayingChapters?.isEmpty == true {
                return
            }
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
    /// Absolute path to a cached cover image (see CoverArtCache), or nil if none has
    /// been extracted yet/exists for this book. Not `let`: AppState's phase-2 library
    /// enrichment (loadLibrary's `enrichCoverArt`) patches this in after the initial
    /// filename-only scan, once CoverArtExtractor finishes for this book.
    var coverUrl: String?
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
    case unsupportedFileType
    case fileImportFailed
    case invalidVaultSelection
    case vaultRemovalFailed

    var errorDescription: String? {
        switch self {
        case .libraryLoadFailed(let reason):
            return "Failed to load library: \(reason)"
        case .bookNotFound:
            return "Book not found"
        case .storageAccessDenied:
            return "Storage access denied"
        case .unsupportedFileType:
            return "This file type isn't supported"
        case .invalidVaultSelection:
            return "Please select a folder, not a file — pick the folder that contains your books."
        case .fileImportFailed:
            return "Couldn't import that file"
        case .vaultRemovalFailed:
            return "Couldn't remove that vault. Please try again."
        }
    }
}
