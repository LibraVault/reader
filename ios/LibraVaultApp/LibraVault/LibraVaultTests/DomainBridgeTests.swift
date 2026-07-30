import XCTest
@testable import LibraVault

@MainActor
final class DomainBridgeTests: XCTestCase {

    private var bridge: LibravaultDomainBridge {
        LibravaultDomainBridge.shared
    }

    override func setUp() async throws {
        try await bridge.initialize()
    }

    func testInitializeLoadsMockLibrary() {
        XCTAssertEqual(bridge.allBooks.count, 7)
        XCTAssertTrue(bridge.allBooks.contains { $0.title == "The Great Gatsby" })
    }

    func testMockLibraryIncludesAudiobooks() {
        let audiobooks = bridge.allBooks.filter { $0.format.isAudio }
        XCTAssertEqual(audiobooks.count, 2)
        XCTAssertTrue(audiobooks.contains { $0.title == "The Hobbit" && $0.format == .m4b })
        XCTAssertTrue(audiobooks.contains { $0.title == "Sapiens: A Brief History of Humankind" && $0.format == .mp3 })
    }

    func testLoadBookReturnsMatchingBook() async throws {
        let book = try await bridge.loadBook(id: "2")
        XCTAssertEqual(book.title, "1984")
        XCTAssertEqual(book.author, "George Orwell")
    }

    func testScanLibraryReturnsAllBooks() async throws {
        let scanned = try await bridge.scanLibrary(vaultPath: "/Documents")
        XCTAssertEqual(scanned.count, bridge.allBooks.count)
        XCTAssertEqual(Set(scanned.map(\.id)), Set(bridge.allBooks.map(\.id)))
    }

    func testLoadBookThrowsForUnknownId() async {
        do {
            _ = try await bridge.loadBook(id: "does-not-exist")
            XCTFail("Expected DomainError.bookNotFound to be thrown")
        } catch DomainError.bookNotFound(let id) {
            XCTAssertEqual(id, "does-not-exist")
        } catch {
            XCTFail("Expected DomainError.bookNotFound, got \(error)")
        }
    }

    func testAddBookmarkAppendsBookmark() async throws {
        let before = bridge.bookmarks["1"]?.count ?? 0
        try await bridge.addBookmark(bookId: "1", position: "chapter-3")
        let after = bridge.bookmarks["1"]?.count ?? 0
        XCTAssertEqual(after, before + 1)
        XCTAssertEqual(bridge.bookmarks["1"]?.last?.position, "chapter-3")
    }

    func testUpdateBookmarkNoteSetsNote() async throws {
        try await bridge.addBookmark(bookId: "1", position: "chapter-4")
        let bookmarkId = try XCTUnwrap(bridge.bookmarks["1"]?.last?.id)

        try await bridge.updateBookmarkNote(bookId: "1", bookmarkId: bookmarkId, note: "Great turn of phrase here")

        XCTAssertEqual(bridge.bookmarks["1"]?.last?.note, "Great turn of phrase here")
    }

    func testUpdateBookmarkNoteWithEmptyStringClearsNote() async throws {
        try await bridge.addBookmark(bookId: "1", position: "chapter-5")
        let bookmarkId = try XCTUnwrap(bridge.bookmarks["1"]?.last?.id)
        try await bridge.updateBookmarkNote(bookId: "1", bookmarkId: bookmarkId, note: "temp")

        try await bridge.updateBookmarkNote(bookId: "1", bookmarkId: bookmarkId, note: "")

        XCTAssertNil(bridge.bookmarks["1"]?.last?.note)
    }

    func testUpdateBookmarkNoteThrowsForUnknownBookmark() async {
        do {
            try await bridge.updateBookmarkNote(bookId: "1", bookmarkId: "does-not-exist", note: "x")
            XCTFail("Expected DomainError.bookNotFound to be thrown")
        } catch DomainError.bookNotFound(let id) {
            XCTAssertEqual(id, "does-not-exist")
        } catch {
            XCTFail("Expected DomainError.bookNotFound, got \(error)")
        }
    }

    func testAddHighlightAppendsHighlight() async throws {
        let before = bridge.highlights["1"]?.count ?? 0
        try await bridge.addHighlight(bookId: "1", position: "para-2", text: "notable quote")
        let after = bridge.highlights["1"]?.count ?? 0
        XCTAssertEqual(after, before + 1)
        XCTAssertEqual(bridge.highlights["1"]?.last?.text, "notable quote")
    }

    func testUpdateProgressStoresValue() async throws {
        try await bridge.updateProgress(bookId: "3", progress: 0.75)
        XCTAssertEqual(bridge.progress["3"], 0.75)
    }
}

/// Regression coverage for the actual bug being fixed: bookmarks/highlights/progress
/// used to be pure in-memory state on the shared singleton — genuinely lost on every
/// relaunch even though the UI presents them as saved. Uses isolated
/// ReadingDataPersistence (not the shared singleton's real UserDefaults.standard) so a
/// fresh LibravaultDomainBridge instance stands in for "the app relaunched."
@MainActor
final class DomainBridgePersistenceTests: XCTestCase {

    private func makeIsolatedPersistence() -> ReadingDataPersistence {
        ReadingDataPersistence(defaults: UserDefaults(suiteName: "DomainBridgePersistenceTests.\(UUID().uuidString)")!)
    }

    func testBookmarksPersistAcrossBridgeInstances() async throws {
        let persistence = makeIsolatedPersistence()
        let first = LibravaultDomainBridge(persistence: persistence)
        try await first.initialize()
        try await first.addBookmark(bookId: "1", position: "chapter-3")

        let reloaded = LibravaultDomainBridge(persistence: persistence)
        try await reloaded.initialize()

        XCTAssertEqual(reloaded.bookmarks["1"]?.count, 1)
        XCTAssertEqual(reloaded.bookmarks["1"]?.first?.position, "chapter-3")
    }

    func testHighlightsPersistAcrossBridgeInstances() async throws {
        let persistence = makeIsolatedPersistence()
        let first = LibravaultDomainBridge(persistence: persistence)
        try await first.initialize()
        try await first.addHighlight(bookId: "1", position: "para-2", text: "notable quote")

        let reloaded = LibravaultDomainBridge(persistence: persistence)
        try await reloaded.initialize()

        XCTAssertEqual(reloaded.highlights["1"]?.first?.text, "notable quote")
    }

    func testProgressPersistsAcrossBridgeInstances() async throws {
        let persistence = makeIsolatedPersistence()
        let first = LibravaultDomainBridge(persistence: persistence)
        try await first.initialize()
        try await first.updateProgress(bookId: "3", progress: 0.75)

        let reloaded = LibravaultDomainBridge(persistence: persistence)
        try await reloaded.initialize()

        XCTAssertEqual(reloaded.progress["3"], 0.75)
    }
}
