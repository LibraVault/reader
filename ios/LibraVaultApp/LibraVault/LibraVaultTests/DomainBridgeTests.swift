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

    func testDeleteBookmarkRemovesBookmark() async throws {
        try await bridge.addBookmark(bookId: "1", position: "chapter-6")
        let bookmarkId = try XCTUnwrap(bridge.bookmarks["1"]?.last?.id)
        let before = bridge.bookmarks["1"]?.count ?? 0

        try await bridge.deleteBookmark(bookId: "1", bookmarkId: bookmarkId)

        XCTAssertEqual(bridge.bookmarks["1"]?.count, before - 1)
        XCTAssertFalse(bridge.bookmarks["1"]?.contains { $0.id == bookmarkId } ?? false)
    }

    func testDeleteBookmarkThrowsForUnknownBookmark() async {
        do {
            try await bridge.deleteBookmark(bookId: "1", bookmarkId: "does-not-exist")
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

    // MARK: - removeVault

    /// Swift-native counterpart to core:domain's `RemoveVaultFolderUseCase` — see the
    /// method's own doc comment. This is the "delete library items for this vault"
    /// half; a removed book's bookmarks, highlights, and reading progress must all go.
    func testRemoveVaultClearsBookmarksHighlightsAndProgressForGivenBookIds() async throws {
        try await bridge.addBookmark(bookId: "removed-1", position: "chapter-1")
        try await bridge.addHighlight(bookId: "removed-1", position: "para-1", text: "quote")
        try await bridge.updateProgress(bookId: "removed-1", progress: 0.5)

        try await bridge.removeVault(bookIds: ["removed-1"])

        XCTAssertNil(bridge.bookmarks["removed-1"])
        XCTAssertNil(bridge.highlights["removed-1"])
        XCTAssertNil(bridge.progress["removed-1"])
    }

    /// Regression guard: only the books that actually belonged to the removed vault
    /// should be affected — a naive "clear everything" implementation would silently
    /// wipe an unrelated, still-present book's reading data too. Asserts a delta
    /// (like the `addBookmark`/`deleteBookmark` tests above), not an absolute count —
    /// `bridge` is the shared singleton, so an earlier test in the same run may have
    /// already left bookmarks behind under the same id.
    func testRemoveVaultLeavesOtherBooksReadingDataUntouched() async throws {
        let before = bridge.bookmarks["kept-untouched"]?.count ?? 0
        try await bridge.addBookmark(bookId: "kept-untouched", position: "chapter-2")
        try await bridge.updateProgress(bookId: "kept-untouched", progress: 0.3)
        try await bridge.addBookmark(bookId: "removed-1", position: "chapter-1")

        try await bridge.removeVault(bookIds: ["removed-1"])

        XCTAssertEqual(bridge.bookmarks["kept-untouched"]?.count, before + 1)
        XCTAssertEqual(bridge.progress["kept-untouched"], 0.3)
    }

    func testRemoveVaultWithNoBookIdsIsANoOp() async throws {
        let before = bridge.bookmarks["kept-noop"]?.count ?? 0
        try await bridge.addBookmark(bookId: "kept-noop", position: "chapter-2")

        try await bridge.removeVault(bookIds: [])

        XCTAssertEqual(bridge.bookmarks["kept-noop"]?.count, before + 1)
    }

    func testRemoveVaultThrowsWhenBridgeNotInitialized() async {
        let uninitialized = LibravaultDomainBridge(
            persistence: ReadingDataPersistence(defaults: UserDefaults(suiteName: "DomainBridgeTests.\(UUID().uuidString)")!)
        )

        do {
            try await uninitialized.removeVault(bookIds: ["1"])
            XCTFail("Expected DomainError.notInitialized to be thrown")
        } catch DomainError.notInitialized {
            // expected
        } catch {
            XCTFail("Expected DomainError.notInitialized, got \(error)")
        }
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

    /// Regression guard: `removeVault`'s cleanup must actually persist, not just
    /// mutate the in-memory dictionaries — a relaunch (a fresh bridge instance
    /// reading the same UserDefaults) must not see the removed book's data reappear.
    func testRemoveVaultCleanupPersistsAcrossBridgeInstances() async throws {
        let persistence = makeIsolatedPersistence()
        let first = LibravaultDomainBridge(persistence: persistence)
        try await first.initialize()
        try await first.addBookmark(bookId: "removed-1", position: "chapter-1")
        try await first.updateProgress(bookId: "removed-1", progress: 0.5)

        try await first.removeVault(bookIds: ["removed-1"])

        let reloaded = LibravaultDomainBridge(persistence: persistence)
        try await reloaded.initialize()
        XCTAssertNil(reloaded.bookmarks["removed-1"])
        XCTAssertNil(reloaded.progress["removed-1"])
    }
}
