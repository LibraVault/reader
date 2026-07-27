import Foundation

// MARK: - KMP Domain Bridge
// Provides Swift-friendly wrappers around Kotlin Multiplatform domain code
//
// Phase B: Mock data implementation (100% functional UI)
// Phase D: Real KMP framework integration point
//
// KMP Modules (build with: ./gradlew -f kmp-ios-build.gradle.kts :buildIosFrameworks):
// - core:domain      → LibravaultDomainKmp (UseCases for library/reading)
// - core:tts         → TtsEngineKmp (text-to-speech)
// - core:logger      → LibravaultLoggerKmp (diagnostic logging)
// - core:storage     → StorageManagerKmp (file access)
// - core:licensing   → ProGateKmp (license/pro features)
//
// Phase D TODO:
// 1. Link XCFrameworks from build/XCFrameworks/ in Package.swift
// 2. Replace MockLibrary with actual GetLibraryUseCase calls
// 3. Wire TTS to core:tts framework
// 4. Connect logging to core:logger framework

@MainActor
class LibravaultDomainBridge: ObservableObject {
    static let shared = LibravaultDomainBridge()

    @Published var allBooks: [BookData] = []
    @Published var highlights: [String: [Highlight]] = [:]
    @Published var bookmarks: [String: [Bookmark]] = [:]
    @Published var progress: [String: Double] = [:]

    private var logger: LoggerBridge?
    private var ttsEngine: TTSEngineBridge?
    private var isInitialized = false

    // Phase D: KMP framework holders (will be populated when frameworks linked)
    // private var kmpDomain: LibravaultDomainKmp?
    // private var kmpTts: TtsEngineKmp?
    // private var kmpLogger: LibravaultLoggerKmp?

    // MARK: - Initialization
    func initialize() async throws {
        guard !isInitialized else { return }

        logger = LoggerBridge()
        await logger?.initialize()
        logger?.d(tag: "Bridge", message: "Logger initialized")

        ttsEngine = TTSEngineBridge()
        try await ttsEngine?.initialize()
        logger?.d(tag: "Bridge", message: "TTS engine initialized")

        // Phase D: Initialize KMP frameworks here
        // try initializeKmpFrameworks()

        // Phase B: Load mock library data (remove in Phase D when using real KMP)
        loadMockLibrary()

        isInitialized = true
        logger?.d(tag: "Bridge", message: "Domain bridge fully initialized")
    }

    // Phase D: Will initialize actual KMP frameworks
    // private func initializeKmpFrameworks() throws {
    //     // Import KMP frameworks from build/XCFrameworks/
    //     // Initialize domain, logging, TTS engines
    //     // Load actual library via GetLibraryUseCase
    // }

    // MARK: - Library Operations
    func scanLibrary(vaultPath: String) async throws -> [BookData] {
        guard isInitialized else { throw DomainError.notInitialized }
        logger?.d(tag: "Library", message: "Scanning vault: \(vaultPath)")

        // Phase B: Return cached mock library
        // Phase C: Call core:domain ScanVaultUseCase and cache result
        return allBooks
    }

    func loadBook(id: String) async throws -> BookData {
        guard isInitialized else { throw DomainError.notInitialized }

        guard let book = allBooks.first(where: { $0.id == id }) else {
            throw DomainError.bookNotFound(id)
        }

        logger?.d(tag: "Reader", message: "Loading book: \(book.title)")

        // Phase C: Call core:domain GetLibraryItemUseCase
        return book
    }

    // MARK: - Reading Operations
    func updateProgress(bookId: String, progress: Double) async throws {
        guard isInitialized else { throw DomainError.notInitialized }

        self.progress[bookId] = progress
        logger?.d(tag: "Progress", message: "Updated \(bookId) to \(Int(progress * 100))%")

        // Phase C: Call core:domain SaveReadingProgressUseCase
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

        logger?.d(tag: "Highlights", message: "Added highlight to \(bookId)")

        // Phase C: Call core:domain AddHighlightUseCase
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

        logger?.d(tag: "Bookmarks", message: "Added bookmark to \(bookId)")

        // Phase C: Call core:domain AddBookmarkUseCase
    }

    func updateBookmarkNote(bookId: String, bookmarkId: String, note: String) async throws {
        guard isInitialized else { throw DomainError.notInitialized }

        guard let index = bookmarks[bookId]?.firstIndex(where: { $0.id == bookmarkId }) else {
            throw DomainError.bookNotFound(bookmarkId)
        }
        bookmarks[bookId]?[index].note = note.isEmpty ? nil : note

        logger?.d(tag: "Bookmarks", message: "Updated note on bookmark \(bookmarkId)")

        // Phase C: Call core:domain UpdateBookmarkUseCase
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

    // MARK: - Mock Data (Phase B)
    private func loadMockLibrary() {
        allBooks = [
            BookData(
                id: "1",
                title: "The Great Gatsby",
                author: "F. Scott Fitzgerald",
                format: .epub,
                progress: 0.35,
                highlights: [],
                bookmarks: []
            ),
            BookData(
                id: "2",
                title: "1984",
                author: "George Orwell",
                format: .pdf,
                progress: 0.67,
                highlights: [],
                bookmarks: []
            ),
            BookData(
                id: "3",
                title: "To Kill a Mockingbird",
                author: "Harper Lee",
                format: .epub,
                progress: 0.0,
                highlights: [],
                bookmarks: []
            ),
            BookData(
                id: "4",
                title: "Pride and Prejudice",
                author: "Jane Austen",
                format: .epub,
                progress: 0.42,
                highlights: [],
                bookmarks: []
            ),
            BookData(
                id: "5",
                title: "Brave New World",
                author: "Aldous Huxley",
                format: .pdf,
                progress: 0.28,
                highlights: [],
                bookmarks: []
            ),
            BookData(
                id: "6",
                title: "The Hobbit",
                author: "J.R.R. Tolkien",
                format: .m4b,
                progress: 0.12,
                highlights: [],
                bookmarks: []
            ),
            BookData(
                id: "7",
                title: "Sapiens: A Brief History of Humankind",
                author: "Yuval Noah Harari",
                format: .mp3,
                progress: 0.0,
                highlights: [],
                bookmarks: []
            ),
        ]
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

struct Highlight: Identifiable {
    let id: String
    let position: String
    let text: String
    let colorHex: String
    let note: String?
    let createdAt: Date
}

struct Bookmark: Identifiable {
    let id: String
    let position: String
    var note: String?
    let createdAt: Date
}

// MARK: - KMP Wrapper Classes

class DomainUseCases {
    // Holds references to Kotlin use cases
    // TODO: Initialize with core:domain KMP classes
}

class LoggerBridge {
    // Wraps core:logger expect/actual implementation
    func initialize() async {
        // TODO: Initialize core:logger
    }

    func d(tag: String, message: String) {
        // TODO: Call core:logger.d()
        print("[\(tag)] \(message)")
    }

    func e(tag: String, message: String, error: Error? = nil) {
        // TODO: Call core:logger.e()
        if let error = error {
            print("[\(tag)] ERROR: \(message) - \(error)")
        } else {
            print("[\(tag)] ERROR: \(message)")
        }
    }
}

class TTSEngineBridge {
    // Wraps core:tts expect/actual implementation
    func initialize() async throws {
        // TODO: Initialize core:tts engine
    }

    func speak(text: String) async {
        // TODO: Call core:tts.speak()
    }

    func stop() async {
        // TODO: Call core:tts.stop()
    }

    func pause() async {
        // TODO: Call core:tts.pause()
    }

    func resume() async {
        // TODO: Call core:tts.resume()
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
