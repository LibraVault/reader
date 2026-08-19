import AVFoundation
import Foundation
import NaturalLanguage

// MARK: - KMP Domain Bridge
// Provides Swift-friendly wrappers around what was meant to become Kotlin
// Multiplatform domain code. No KMP framework is actually linked into the iOS app
// (see docs/iOS-TESTFLIGHT-RELEASE-PROCESS.md) — bookmark/highlight/progress state
// below is genuine Swift-native logic (now persisted, see initialize()), not a KMP
// call. Library scanning is real: AppState.loadLibrary() always sources `books` from
// LibraryFileScanner against the user's real folders — there is no demo/fallback
// library here anymore.
//
// KMP Modules referenced by the original Phase D plan (never built):
// - core:domain      → LibravaultDomainKmp (UseCases for library/reading)
// - core:tts         → TtsEngineKmp (text-to-speech)
// - core:logger      → LibravaultLoggerKmp (diagnostic logging)
// - core:storage     → StorageManagerKmp (file access)
// - core:licensing   → ProGateKmp (license/pro features)
//
// Still open:
// 1. Real audio-file playback for audiobooks (AVFoundation) — TTS (below) now
//    genuinely speaks, but audio-format books aren't routed to a real player yet.

@MainActor
class LibravaultDomainBridge: ObservableObject {
    static let shared = LibravaultDomainBridge()

    @Published var highlights: [String: [Highlight]] = [:]
    @Published var bookmarks: [String: [Bookmark]] = [:]
    @Published var progress: [String: Double] = [:]

    private var logger: LoggerBridge?
    private var ttsEngine: TTSEngineProtocol?
    private var ttsEngineType: TTSEngineType = .system
    private var isInitialized = false
    private let persistence: ReadingDataPersistence

    init(persistence: ReadingDataPersistence = ReadingDataPersistence()) {
        self.persistence = persistence
    }

    // MARK: - Initialization
    func initialize() async throws {
        guard !isInitialized else { return }

        logger = LoggerBridge()
        await logger?.initialize()
        logger?.d(tag: "Bridge", message: "Logger initialized")

        // Guarded rather than unconditional: AppState's ttsEngineType didSet can
        // call switchTTSEngine(to:) from its own init before this method's Task
        // has run (both are @MainActor but scheduling order between separate
        // Task { } blocks isn't guaranteed) - don't clobber an engine switch
        // that already landed.
        if ttsEngine == nil {
            ttsEngine = TTSEngineBridge()
            try await ttsEngine?.initialize()
            logger?.d(tag: "Bridge", message: "TTS engine initialized (system)")
        }

        bookmarks = persistence.loadBookmarks()
        highlights = persistence.loadHighlights()
        progress = persistence.loadProgress()

        isInitialized = true
        logger?.d(tag: "Bridge", message: "Domain bridge fully initialized")
    }

    /// Swaps the active TTS engine, matching Android's `TtsEngineProvider.switchEngineSync`
    /// (core/tts/TtsEngineProvider.kt): always starts on the system engine (fast,
    /// reliable, zero setup) via `initialize()` above, then AppState calls this once
    /// it's loaded the user's saved preference. A no-op if already on the requested
    /// engine. On failure (e.g. Pocket TTS's model isn't bundled - see
    /// PocketModelManager), the previous engine is left in place rather than leaving
    /// speech broken.
    func switchTTSEngine(to type: TTSEngineType) async {
        guard type != ttsEngineType || ttsEngine == nil else { return }

        let newEngine: TTSEngineProtocol = type == .pocket ? PocketTTSEngine() : TTSEngineBridge()
        do {
            try await newEngine.initialize()
            await ttsEngine?.stop()
            ttsEngine = newEngine
            ttsEngineType = type
            logger?.d(tag: "TTS", message: "Switched TTS engine to \(type.rawValue)")
        } catch {
            logger?.e(tag: "TTS", message: "Failed to switch TTS engine to \(type.rawValue)", error: error)
        }
    }

    // MARK: - Reading Operations
    func updateProgress(bookId: String, progress: Double) async throws {
        guard isInitialized else { throw DomainError.notInitialized }

        self.progress[bookId] = progress
        persistence.save(progress: self.progress)
        logger?.d(tag: "Progress", message: "Updated \(bookId) to \(Int(progress * 100))%")
    }

    func addHighlight(bookId: String, position: String, text: String, color: String = "FFFF00") async throws {
        guard isInitialized else { throw DomainError.notInitialized }

        let highlight = Highlight(
            id: UUID().uuidString,
            position: position,
            text: text,
            colorHex: color,
            note: nil,
            createdAt: Date()
        )

        if highlights[bookId] == nil {
            highlights[bookId] = []
        }
        highlights[bookId]?.append(highlight)
        persistence.save(highlights: highlights)

        logger?.d(tag: "Highlights", message: "Added highlight to \(bookId)")
    }

    func addBookmark(bookId: String, position: String) async throws {
        guard isInitialized else { throw DomainError.notInitialized }

        let bookmark = Bookmark(
            id: UUID().uuidString,
            position: position,
            note: nil,
            createdAt: Date()
        )

        if bookmarks[bookId] == nil {
            bookmarks[bookId] = []
        }
        bookmarks[bookId]?.append(bookmark)
        persistence.save(bookmarks: bookmarks)

        logger?.d(tag: "Bookmarks", message: "Added bookmark to \(bookId)")
    }

    func updateBookmarkNote(bookId: String, bookmarkId: String, note: String) async throws {
        guard isInitialized else { throw DomainError.notInitialized }

        guard let index = bookmarks[bookId]?.firstIndex(where: { $0.id == bookmarkId }) else {
            throw DomainError.bookNotFound(bookmarkId)
        }
        bookmarks[bookId]?[index].note = note.isEmpty ? nil : note
        persistence.save(bookmarks: bookmarks)

        logger?.d(tag: "Bookmarks", message: "Updated note on bookmark \(bookmarkId)")
    }

    func deleteBookmark(bookId: String, bookmarkId: String) async throws {
        guard isInitialized else { throw DomainError.notInitialized }

        guard let index = bookmarks[bookId]?.firstIndex(where: { $0.id == bookmarkId }) else {
            throw DomainError.bookNotFound(bookmarkId)
        }
        bookmarks[bookId]?.remove(at: index)
        persistence.save(bookmarks: bookmarks)

        logger?.d(tag: "Bookmarks", message: "Deleted bookmark \(bookmarkId)")
    }

    // MARK: - Folder Operations

    /// Swift-native counterpart to core:domain's `RemoveVaultFolderUseCase`
    /// (`libraryRepository.deleteByVault(vaultId)` half only — the second half,
    /// `vaultRepository.removeVault(vaultId)`, is `FolderPersistence`'s job on iOS,
    /// same as `addFolder`'s counterpart `AddVaultFolderUseCase` already is). Called
    /// by `AppState.removeFolder(_:)` with the ids of every book that belonged to the
    /// folder being removed, resolved *before* the folder entry itself is dropped.
    ///
    /// No KMP framework is actually linked into this app (see this file's header
    /// comment) — Android's use case relies on Room's `ON DELETE CASCADE` foreign
    /// keys to drop a deleted library item's highlights/bookmarks/progress rows for
    /// free; there's no on-device database here to do that automatically, so this
    /// walks the three dictionaries explicitly instead. Without it, a removed
    /// folder's reading data would silently linger forever in UserDefaults, keyed by
    /// book ids nothing can ever resolve back to a real book again.
    func removeFolder(bookIds: [String]) async throws {
        guard isInitialized else { throw DomainError.notInitialized }
        guard !bookIds.isEmpty else { return }

        for bookId in bookIds {
            bookmarks.removeValue(forKey: bookId)
            highlights.removeValue(forKey: bookId)
            progress.removeValue(forKey: bookId)
        }
        persistence.save(bookmarks: bookmarks)
        persistence.save(highlights: highlights)
        persistence.save(progress: progress)

        logger?.d(tag: "Folders", message: "Removed reading data for \(bookIds.count) book(s) from a deleted folder")
    }

    // MARK: - Logger Integration
    func log(_ message: String, tag: String = "LibraVault") {
        logger?.d(tag: tag, message: message)
    }

    /// Error-level counterpart to `log(_:tag:)` — routes to `LoggerBridge.e` instead of
    /// `.d`, so a failure a caller can't otherwise surface (e.g. AppState.importSharedFile's
    /// swallowed `try?`/catch paths) leaves a real, level-filterable trail in
    /// LibraVaultLogStore for field debugging, the same way switchTTSEngine's catch
    /// above already does internally.
    func logError(_ message: String, tag: String = "LibraVault", error: Error? = nil) {
        logger?.e(tag: tag, message: message, error: error)
    }

    // MARK: - TTS Integration
    func startSpeaking(text: String, rate: Double = 1.0) async throws {
        guard ttsEngine != nil else { throw DomainError.notInitialized }
        logger?.d(tag: "TTS", message: "Starting speech: \(text.prefix(50))...")
        await ttsEngine?.speak(text: text, rate: rate)
    }

    func stopSpeaking() async {
        await ttsEngine?.stop()
    }

    func pauseSpeaking() async {
        await ttsEngine?.pause()
    }

    func resumeSpeaking() async {
        await ttsEngine?.resume()
    }
}

// MARK: - Swift-friendly Models (mapped from Kotlin)

struct BookData: Identifiable {
    let id: String
    let title: String
    let author: String
    let format: MediaFormat
    var progress: Double = 0.0
    var highlights: [Highlight] = []
    var bookmarks: [Bookmark] = []
    /// The real file this book was scanned from, and the folder it belongs to.
    /// Populated for every real folder scan (see LibraryFileScanner) — optional only
    /// because a handful of tests/previews construct a `BookData` without one.
    var fileURL: URL? = nil
    var folderId: String? = nil
}

// Hashable (which implies Equatable): needed transitively by BookItem's own Hashable
// conformance (#294, see BookItem's doc comment in AppState.swift) — a plain
// no-associated-values enum, so this costs nothing beyond what Equatable already did.
enum MediaFormat: Hashable {
    case pdf
    case epub
    case markdown
    case mobi
    case cbz
    case mp3
    case m4b
    case aac
    case flac
    case ogg
    case opus

    /// Mirrors core:domain's `MediaFormat.isAudio()` (Models.kt) — used to split the
    /// Library grid into books vs. audiobooks and to detect audio files during a
    /// folder scan (see LibraryFileScanner).
    var isAudio: Bool {
        switch self {
        case .mp3, .m4b, .aac, .flac, .ogg, .opus: return true
        case .pdf, .epub, .markdown, .mobi, .cbz: return false
        }
    }
}

struct Highlight: Identifiable, Codable, Equatable {
    let id: String
    let position: String
    let text: String
    let colorHex: String
    var note: String?
    let createdAt: Date
}

struct Bookmark: Identifiable, Codable, Equatable {
    let id: String
    let position: String
    var note: String?
    let createdAt: Date
}

// MARK: - Bridge helper classes

class LoggerBridge {
    private let store: LibraVaultLogStore

    init(store: LibraVaultLogStore = LibraVaultLogStore()) {
        self.store = store
    }

    func initialize() async {}

    func d(tag: String, message: String) {
        print("[\(tag)] \(message)")
        store.write(level: "D", tag: tag, message: message)
    }

    func e(tag: String, message: String, error: Error? = nil) {
        let fullMessage = error.map { "\(message) - \($0)" } ?? message
        print("[\(tag)] ERROR: \(fullMessage)")
        store.write(level: "E", tag: tag, message: fullMessage)
    }
}

// Real text-to-speech via AVSpeechSynthesizer (core:tts is still blocked on the KMP
// chain — see the header comment above). startSpeaking/stopSpeaking/etc. reach this
// class from AppState.swift's playback controls.
class TTSEngineBridge: TTSEngineProtocol {
    private let synthesizer = AVSpeechSynthesizer()

    /// `xcodebuild test`'s CI Simulator has no real audio hardware, and
    /// AVAudioSession activation / AVSpeechSynthesizer there was confirmed (two
    /// consecutive ~30-minute CI timeouts) to hang indefinitely — almost certainly the
    /// debugger `xcodebuild test` attaches getting stuck on an XPC call to the
    /// Simulator's audio daemon. Real apps never set this env var; only the XCTest
    /// test host process does, so this only short-circuits automated test runs, not
    /// real usage (manual Simulator/device runs, TestFlight, App Store).
    private static var isRunningUnderXCTest: Bool {
        ProcessInfo.processInfo.environment["XCTestConfigurationFilePath"] != nil
    }

    func initialize() async throws {
        guard !Self.isRunningUnderXCTest else { return }
        // .spokenAudio is the mode Apple documents for narration/TTS apps — lets
        // speech play even with the silent switch on, which a plain default session
        // wouldn't guarantee. try? because failing to configure the session shouldn't
        // block the rest of app initialization over a non-critical audio nicety.
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .spokenAudio)
        try? AVAudioSession.sharedInstance().setActive(true)
    }

    func speak(text: String, rate: Double) async {
        guard !Self.isRunningUnderXCTest, !text.isEmpty else { return }
        let utterance = AVSpeechUtterance(string: text)
        utterance.rate = Self.scaledRate(for: rate)
        utterance.voice = Self.voice(for: text)
        synthesizer.stopSpeaking(at: .immediate)
        synthesizer.speak(utterance)
    }

    /// Left unset, `AVSpeechUtterance.voice` defaults to whatever voice matches the
    /// *device's* system language (Settings > General > Language & Region) —
    /// nothing to do with the language of the book actually being read. Reported in
    /// the field as an English PDF/EPUB being read in a Dutch voice on an iPad set
    /// to Dutch. Detects the dominant language of the text being spoken instead and
    /// asks for a voice in that language.
    static func voice(for text: String) -> AVSpeechSynthesisVoice? {
        guard let languageCode = detectedLanguageCode(for: text) else { return nil }
        // AVSpeechSynthesisVoice(language:) does its own fuzzy region matching (an
        // "en" hit resolves to whichever en-* voice is installed) and returns nil
        // if nothing matches at all — speak() then falls back to
        // AVSpeechUtterance's own default (today's device-language behaviour),
        // rather than throwing, for a language with no installed voice.
        return AVSpeechSynthesisVoice(language: languageCode)
    }

    /// Split out from `voice(for:)` as its own pure function so it's unit-testable
    /// without depending on the real device/Simulator voice catalog, which varies
    /// by installed language packs and isn't something CI controls.
    static func detectedLanguageCode(for text: String) -> String? {
        let recognizer = NLLanguageRecognizer()
        // Capping the sample keeps this fast for a full chapter's worth of text —
        // NLLanguageRecognizer's accuracy plateaus well before that on ordinary
        // prose, so there's nothing gained from feeding it more.
        recognizer.processString(String(text.prefix(1_000)))
        return recognizer.dominantLanguage?.rawValue
    }

    func stop() async {
        guard !Self.isRunningUnderXCTest else { return }
        synthesizer.stopSpeaking(at: .immediate)
    }

    func pause() async {
        guard !Self.isRunningUnderXCTest else { return }
        synthesizer.pauseSpeaking(at: .word)
    }

    func resume() async {
        guard !Self.isRunningUnderXCTest else { return }
        synthesizer.continueSpeaking()
    }

    /// `AVSpeechUtterance.rate` is a 0...1 fraction of a fixed maximum, not a
    /// multiplier — anchor `speed == 1.0` (LibraVault's "normal" speed) at Apple's
    /// documented default rate and scale from there, so 2x/0.5x still feel
    /// proportionally faster/slower instead of landing on an arbitrary fixed rate.
    static func scaledRate(for speed: Double) -> Float {
        let scaled = AVSpeechUtteranceDefaultSpeechRate * Float(speed)
        return min(max(scaled, AVSpeechUtteranceMinimumSpeechRate), AVSpeechUtteranceMaximumSpeechRate)
    }
}

// MARK: - Errors

enum DomainError: LocalizedError {
    case notInitialized
    case libraryLoadFailed(String)
    case bookNotFound(String)
    case storageAccessDenied

    var errorDescription: String? {
        switch self {
        case .notInitialized:
            return "Domain layer not initialized"
        case .libraryLoadFailed(let reason):
            return "Failed to load library: \(reason)"
        case .bookNotFound(let id):
            return "Book not found: \(id)"
        case .storageAccessDenied:
            return "Storage access denied"
        }
    }
}
