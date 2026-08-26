import Foundation
import PDFKit

/// Formats `VaultReaderViewModel`/`VaultPlayerViewModel` route to, matching
/// `VaultManifestEntry.format`'s stored value — always a lowercase
/// `MediaFormat` case name (`VaultStore.importFile`'s caller passes
/// `String(describing: format)`; see `EncryptedVaultContentsViewModel
/// .importOne`).
enum VaultContentFormat {
    static let audioFormats: Set<String> = ["mp3", "m4b", "aac", "flac", "ogg", "opus"]

    static func isAudio(_ format: String) -> Bool { audioFormats.contains(format) }
}

enum VaultReaderState: Equatable {
    case loading
    case error(String)
    case epubReady(title: String)
    case pdfReady(title: String)
    /// `text` is the whole decoded document — v1 renders it as a single
    /// scrollable document via `MarkdownReaderContent`, no TOC/mermaid/
    /// relative-image parity with the non-vault reader yet (issue #442).
    case markdownReady(title: String, text: String)
    /// Not a reading format — the caller should have routed audio to
    /// `VaultPlayerView` instead. Surfaced only as a defensive fallback if
    /// that dispatch is ever wrong, mirroring Android's identical
    /// `VaultReaderState.WrongScreen`.
    case wrongScreen(String)

    static func == (lhs: VaultReaderState, rhs: VaultReaderState) -> Bool {
        switch (lhs, rhs) {
        case (.loading, .loading): return true
        case (.error(let a), .error(let b)): return a == b
        case (.epubReady(let a), .epubReady(let b)): return a == b
        case (.pdfReady(let a), .pdfReady(let b)): return a == b
        case (.markdownReady(let at, let ax), .markdownReady(let bt, let bx)): return at == bt && ax == bx
        case (.wrongScreen(let a), .wrongScreen(let b)): return a == b
        default: return false
        }
    }
}

/// Opens one vault file for reading — EPUB or PDF, decrypted fully into
/// memory via `VaultStore.readFullContent` (never written to a plaintext
/// temp file) and handed to `VaultEPUBParser`/`PDFDocument(data:)`
/// respectively. Bookmarks and highlights persist through
/// `VaultStore.addBookmark`/`addHighlight` in the encrypted manifest — called
/// directly, the same "no shared interface with the non-vault
/// repositories" pattern `VaultStore` itself already establishes (see its
/// own doc comment).
///
/// Reading position is tracked only coarsely — `"chapter:N"` for EPUB,
/// `"page:N"` for PDF — since iOS's existing reader has no CFI-equivalent
/// locator (it renders `BookChapter` text directly, unlike Android's
/// Readium-backed navigator). Reading settings (theme/font/line-spacing)
/// and reading position are intentionally not persisted here — see #203's
/// acceptance-criteria note: Android doesn't persist those for vault
/// content either, only bookmarks/highlights, so iOS matches that rather
/// than over-delivering.
@MainActor
final class VaultReaderViewModel: ObservableObject {

    let vaultId: String
    let fileId: Data

    @Published private(set) var state: VaultReaderState = .loading
    @Published private(set) var chapters: [BookChapter] = []
    @Published private(set) var pdfDocument: PDFDocument?
    @Published private(set) var markdownBlocks: [MarkdownBlock] = []
    @Published private(set) var bookmarks: [VaultBookmark] = []
    @Published private(set) var highlights: [VaultHighlight] = []
    @Published var currentChapterIndex: Int = 0
    @Published var currentPageIndex: Int = 0
    @Published var errorMessage: String?
    /// Flips when the vault locks while this screen is open — either
    /// noticed passively via `checkStillUnlocked()` (#526) or triggered
    /// actively via `lock()` (#668). `VaultReaderView` observes this and
    /// pops back to the unlock flow; nothing here does that itself, mirroring
    /// `EncryptedVaultContentsViewModel.isLocked`'s split between model and
    /// view.
    @Published private(set) var wasLocked = false

    private let sessionManager: VaultSessionManager
    private var store: VaultStore?

    init(vaultId: String, fileId: Data, sessionManager: VaultSessionManager) {
        self.vaultId = vaultId
        self.fileId = fileId
        self.sessionManager = sessionManager
    }

    func load() async {
        guard await sessionManager.isUnlocked(vaultId) else {
            state = .error("Vault is locked")
            return
        }
        let s = await sessionManager.requireUnlocked(vaultId)
        store = s

        do {
            guard let entry = try s.listEntries().first(where: { $0.fileId == fileId }) else {
                state = .error("File not found in this vault")
                return
            }
            bookmarks = entry.bookmarks
            highlights = entry.highlights

            if VaultContentFormat.isAudio(entry.format) {
                state = .wrongScreen("This is an audio file — open it from the player instead")
                return
            }

            let content = try s.readFullContent(fileId: fileId)
            switch entry.format {
            case "epub":
                chapters = try VaultEPUBParser.parse(data: content)
                state = .epubReady(title: entry.title)
            case "pdf":
                guard let document = PDFDocument(data: content) else {
                    state = .error("Could not open PDF")
                    return
                }
                guard document.pageCount > 0 else {
                    state = .error("PDF has no pages")
                    return
                }
                pdfDocument = document
                state = .pdfReady(title: entry.title)
            case "markdown":
                let text = String(decoding: content, as: UTF8.self)
                markdownBlocks = MarkdownDocumentParser.parse(text)
                state = .markdownReady(title: entry.title, text: text)
            default:
                state = .error("Unsupported format: \(entry.format)")
            }
        } catch {
            state = .error(error.localizedDescription)
        }
    }

    // MARK: - Vault lock observation (#526/#668)

    /// Re-checks vault lock state when this screen returns to the
    /// foreground — called from `VaultReaderView`'s `scenePhase` observer,
    /// the SwiftUI equivalent of the `DisposableEffect`+`ON_RESUME` idiom
    /// the unified Android `ReaderScreen` uses for the identical gap (ported
    /// from the deleted `VaultReaderScreen`, see that Kotlin file's own
    /// `checkStillUnlocked()` doc comment). Nothing else here observes
    /// `VaultSessionManager` continuously, so a lock that fired while this
    /// screen was backgrounded (`VaultForegroundLockObserver`) would
    /// otherwise go unnoticed until the user tried and failed to read/mutate
    /// something. No-op while still loading — `load()` itself already
    /// handles the locked case for the initial open.
    func checkStillUnlocked() async {
        guard state != .loading else { return }
        if await !sessionManager.isUnlocked(vaultId) {
            wasLocked = true
        }
    }

    /// Actively locks the vault and notices it via the same `wasLocked` path
    /// `checkStillUnlocked()` uses — called when `.vaultContentSecurity()`
    /// detects a screen recording/AirPlay mirror or an imminent app-switcher
    /// snapshot while vault content is on screen (#668), not just reacting
    /// passively to a lock that already happened elsewhere.
    func lock() async {
        await sessionManager.lock(vaultId)
        wasLocked = true
    }

    // MARK: - Position tracking

    private var currentPositionRef: String {
        switch state {
        case .pdfReady: return "page:\(currentPageIndex)"
        default: return "chapter:\(currentChapterIndex)"
        }
    }

    // MARK: - Bookmarks

    /// Bookmarks the reader's current position. `async` rather than an
    /// internal fire-and-forget `Task` — mirrors
    /// `EncryptedVaultContentsViewModel.lock()`'s convention of pushing the
    /// `Task { await ... }` wrapping out to the caller (a button action),
    /// which is also what lets a test simply `await` the result instead of
    /// polling for it.
    func addBookmark(label: String? = nil) async {
        guard let store else { return }
        let ref = currentPositionRef
        do {
            let bookmark = try store.addBookmark(fileId: fileId, positionRef: ref, label: label)
            bookmarks.append(bookmark)
        } catch VaultStoreError.vaultLocked {
            wasLocked = true
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func removeBookmark(id: Int64) async {
        guard let store else { return }
        do {
            try store.removeBookmark(fileId: fileId, bookmarkId: id)
            bookmarks.removeAll { $0.id == id }
        } catch VaultStoreError.vaultLocked {
            wasLocked = true
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
        } catch VaultStoreError.vaultLocked {
            wasLocked = true
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    /// Jumps the active renderer to `bookmark`'s saved position.
    func navigate(to bookmark: VaultBookmark) {
        if bookmark.positionRef.hasPrefix("page:"),
           let page = Int(bookmark.positionRef.dropFirst("page:".count)) {
            currentPageIndex = page
        } else if bookmark.positionRef.hasPrefix("chapter:"),
                  let chapter = Int(bookmark.positionRef.dropFirst("chapter:".count)) {
            currentChapterIndex = chapter
        }
    }

    // MARK: - Highlights

    /// Highlights the currently displayed text — the whole current chapter
    /// for EPUB, or the whole current page's extracted text for PDF. Coarser
    /// than a real text-selection range (iOS's existing reader has no
    /// selection-to-highlight UI at all yet — see `HighlightsSheet`'s own
    /// doc comment), but still round-trips through the encrypted manifest
    /// exactly like a finer-grained highlight would, which is what #203's
    /// acceptance criteria require.
    func addHighlight(colorHex: String = "#FFE066") async {
        guard let store else { return }
        let (ref, text): (String, String)
        switch state {
        case .pdfReady:
            guard let page = pdfDocument?.page(at: currentPageIndex) else { return }
            ref = "page:\(currentPageIndex)"
            text = page.string ?? ""
        default:
            guard currentChapterIndex < chapters.count else { return }
            ref = "chapter:\(currentChapterIndex)"
            text = chapters[currentChapterIndex].text
        }
        guard !text.isEmpty else { return }

        do {
            let highlight = try store.addHighlight(fileId: fileId, positionRef: ref, highlightedText: text, colorHex: colorHex)
            highlights.append(highlight)
        } catch VaultStoreError.vaultLocked {
            wasLocked = true
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func removeHighlight(id: Int64) async {
        guard let store else { return }
        do {
            try store.removeHighlight(fileId: fileId, highlightId: id)
            highlights.removeAll { $0.id == id }
        } catch VaultStoreError.vaultLocked {
            wasLocked = true
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}
