import Foundation

// MARK: - KMP Domain Bridge
// Provides Swift-friendly wrappers around what was meant to become Kotlin
// Multiplatform domain code. No KMP framework is actually linked into the iOS app
// (see docs/iOS-TESTFLIGHT-RELEASE-PROCESS.md) — bookmark/highlight/progress state
// below is genuine Swift-native logic (now persisted, see initialize()), not a KMP
// call. Library scanning is real: AppState.loadLibrary() always sources `books` from
// LibraryFileScanner against the user's real vaults — there is no demo/fallback
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
// 1. Wire TTS to a real engine (AVSpeechSynthesizer as a Swift-native stand-in, or
//    core:tts if/when the KMP chain unblocks)
// 2. Real audio-file playback (AVFoundation)

@MainActor
class LibravaultDomainBridge: ObservableObject {
    static let shared = LibravaultDomainBridge()

    @Published var highlights: [String: [Highlight]] = [:]
    @Published var bookmarks: [String: [Bookmark]] = [:]
    @Published var progress: [String: Double] = [:]

    private var logger: LoggerBridge?
    private var ttsEngine: TTSEngineBridge?
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

        ttsEngine = TTSEngineBridge()
        try await ttsEngine?.initialize()
        logger?.d(tag: "Bridge", message: "TTS engine initialized")

        bookmarks = persistence.loadBookmarks()
        highlights = persistence.loadHighlights()
        progress = persistence.loadProgress()

        isInitialized = true
        logger?.d(tag: "Bridge", message: "Domain bridge fully initialized")
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

    // MARK: - Logger Integration
    func log(_ message: String, tag: String = "LibraVault") {
        logger?.d(tag: tag, message: message)
    }

    // MARK: - TTS Integration
    func startSpeaking(text: String) async throws {
        guard ttsEngine != nil else { throw DomainError.notInitialized }
        logger?.d(tag: "TTS", message: "Starting speech: \(text.prefix(50))...")
        await ttsEngine?.speak(text: text)
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
    /// The real file this book was scanned from, and the vault it belongs to.
    /// Populated for every real vault scan (see LibraryFileScanner) — optional only
    /// because a handful of tests/previews construct a `BookData` without one.
    var fileURL: URL? = nil
    var vaultId: String? = nil
}

enum MediaFormat: Equatable {
    case pdf
    case epub
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
    /// vault scan (see LibraryFileScanner).
    var isAudio: Bool {
        switch self {
        case .mp3, .m4b, .aac, .flac, .ogg, .opus: return true
        case .pdf, .epub, .mobi, .cbz: return false
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

// TTS is currently a no-op: neither core:tts (blocked on the KMP chain) nor a
// Swift-native engine (e.g. AVSpeechSynthesizer) is wired up yet. startSpeaking/
// stopSpeaking/etc. all genuinely reach this class — see AppState.swift's playback
// controls — they just don't produce audio.
class TTSEngineBridge {
    func initialize() async throws {}
    func speak(text: String) async {}
    func stop() async {}
    func pause() async {}
    func resume() async {}
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
