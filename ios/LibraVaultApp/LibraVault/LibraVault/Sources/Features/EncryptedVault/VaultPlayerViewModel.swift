import Foundation

private let vaultPlayerSkipSeconds: Double = 30

/// Plays one vault audio file — decrypted fully into memory via
/// `VaultStore.readFullContent` (never written to a plaintext temp file) and
/// handed to `VaultAudioPlaybackEngine`. No background playback: unlike the
/// main library's player (a shared, `MediaSession`-backed singleton), this
/// view model owns a screen-scoped engine that stops the moment the screen
/// is dismissed — mirrors Android's `VaultPlayerViewModel`'s identical,
/// deliberate scope cut (see its own doc comment) rather than wiring vault
/// audio into `AppState`'s lock-screen-aware playback pipeline.
///
/// Bookmarks use the same `"ms:<Int64 milliseconds>"` `positionRef`
/// convention as Android's `VaultPlayerViewModel`/`core.domain.model
/// .Bookmark`.
@MainActor
final class VaultPlayerViewModel: ObservableObject {

    let vaultId: String
    let fileId: Data

    @Published private(set) var title: String = ""
    @Published private(set) var isLoading = true
    @Published var errorMessage: String?
    @Published private(set) var isPlaying = false
    @Published private(set) var elapsed: Double = 0
    @Published private(set) var duration: Double = 0
    @Published private(set) var bookmarks: [VaultBookmark] = []

    private let sessionManager: VaultSessionManager
    private let engine: VaultAudioPlaybackEngine
    private var store: VaultStore?

    init(vaultId: String, fileId: Data, sessionManager: VaultSessionManager, engine: VaultAudioPlaybackEngine = VaultAudioPlaybackEngine()) {
        self.vaultId = vaultId
        self.fileId = fileId
        self.sessionManager = sessionManager
        self.engine = engine
        engine.onProgress = { [weak self] elapsed, duration in
            self?.elapsed = elapsed
            self?.duration = duration
        }
        engine.onFinished = { [weak self] in
            self?.isPlaying = false
        }
    }

    func load() async {
        guard await sessionManager.isUnlocked(vaultId) else {
            isLoading = false
            errorMessage = "Vault is locked"
            return
        }
        let s = await sessionManager.requireUnlocked(vaultId)
        store = s

        do {
            guard let entry = try s.listEntries().first(where: { $0.fileId == fileId }) else {
                isLoading = false
                errorMessage = "File not found in this vault"
                return
            }
            guard VaultContentFormat.isAudio(entry.format) else {
                isLoading = false
                errorMessage = "Not an audio file"
                return
            }
            title = entry.title
            bookmarks = entry.bookmarks

            let content = try s.readFullContent(fileId: fileId)
            // `load`, not `play`: opens paused (mirrors `AudioPlaybackEngine
            // .load(fileURL:rate:)`'s own split from `play`) — the screen
            // shows title/duration/bookmarks immediately and the user starts
            // playback explicitly via `onPlayPause()`. Also keeps this method
            // testable without ever calling `AVAudioPlayer.play()`, which
            // `AudioPlaybackEngineTests`' own doc comment notes is unsafe to
            // trigger unattended in the CI Simulator.
            try engine.load(data: content, rate: 1.0)
            duration = engine.duration
            isPlaying = engine.isPlaying
            isLoading = false
        } catch {
            isLoading = false
            errorMessage = error.localizedDescription
        }
    }

    func onPlayPause() {
        if engine.isPlaying {
            engine.pause()
        } else {
            engine.resume()
        }
        isPlaying = engine.isPlaying
    }

    func onSeek(to seconds: Double) {
        engine.elapsed = seconds
        elapsed = engine.elapsed
    }

    func onSkipBack() {
        onSeek(to: engine.elapsed - vaultPlayerSkipSeconds)
    }

    func onSkipForward() {
        onSeek(to: engine.elapsed + vaultPlayerSkipSeconds)
    }

    func stop() {
        engine.stop()
    }

    // MARK: - Bookmarks

    func addBookmark(label: String? = nil) async {
        guard let store else { return }
        let positionMs = Int64((engine.elapsed * 1000).rounded())
        do {
            let bookmark = try store.addBookmark(fileId: fileId, positionRef: "ms:\(positionMs)", label: label)
            bookmarks.append(bookmark)
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func removeBookmark(id: Int64) async {
        guard let store else { return }
        do {
            try store.removeBookmark(fileId: fileId, bookmarkId: id)
            bookmarks.removeAll { $0.id == id }
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func updateBookmarkNote(id: Int64, note: String?) async {
        guard let store else { return }
        do {
            try store.updateBookmarkNote(fileId: fileId, bookmarkId: id, note: note)
            if let index = bookmarks.firstIndex(where: { $0.id == id }) {
                bookmarks[index].note = note
            }
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func seekToBookmark(_ bookmark: VaultBookmark) {
        guard bookmark.positionRef.hasPrefix("ms:"),
              let ms = Int64(bookmark.positionRef.dropFirst("ms:".count)) else { return }
        onSeek(to: Double(ms) / 1000)
        if !engine.isPlaying {
            engine.resume()
            isPlaying = engine.isPlaying
        }
    }
}
