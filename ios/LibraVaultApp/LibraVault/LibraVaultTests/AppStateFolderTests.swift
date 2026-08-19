import XCTest
@testable import LibraVault

@MainActor
final class AppStateFolderTests: XCTestCase {

    override func setUp() async throws {
        // The domain bridge is a shared singleton scanned by loadLibrary(); make sure
        // it's ready regardless of which order test classes run in (mirrors
        // DomainBridgeTests.setUp).
        try await LibravaultDomainBridge.shared.initialize()
    }

    private func makeIsolatedPersistence() -> FolderPersistence {
        FolderPersistence(defaults: UserDefaults(suiteName: "AppStateFolderTests.\(UUID().uuidString)")!)
    }

    private func makeTempFolder() throws -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("AppStateFolderTests-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    func testNewAppStateStartsWithNoFolders() {
        XCTAssertTrue(AppState(folderPersistence: makeIsolatedPersistence()).folders.isEmpty)
    }

    func testAddFolderAppendsToFoldersList() throws {
        let state = AppState(folderPersistence: makeIsolatedPersistence())
        let folder = try makeTempFolder()
        defer { try? FileManager.default.removeItem(at: folder) }

        state.addFolder(pickedURL: folder)

        XCTAssertEqual(state.folders.count, 1)
        XCTAssertEqual(state.folders.first?.displayName, folder.lastPathComponent)
    }

    /// Regression guard: picking the same folder twice must not double-add it — the
    /// natural place to browse to for a second "Add Folder" tap is the same folder you
    /// just picked, and without this, every file in it would show up twice in the grid.
    func testAddingTheSameFolderTwiceDoesNotDuplicateTheFolder() throws {
        let state = AppState(folderPersistence: makeIsolatedPersistence())
        let folder = try makeTempFolder()
        defer { try? FileManager.default.removeItem(at: folder) }

        state.addFolder(pickedURL: folder)
        state.addFolder(pickedURL: folder)

        XCTAssertEqual(state.folders.count, 1)
    }

    /// Regression guard for issue #185: a document-provider extension (Google Drive
    /// being the commonly reported offender) can hand `.fileImporter` a plain file
    /// even though `allowedContentTypes: [.folder]` asked for a folder. Previously
    /// this silently became a "folder" that scanned to 0 books forever — now it's
    /// rejected up front with an actionable error instead.
    func testAddFolderRejectsAPlainFileInsteadOfSilentlyCreatingADeadFolder() throws {
        let state = AppState(folderPersistence: makeIsolatedPersistence())
        let folder = try makeTempFolder()
        defer { try? FileManager.default.removeItem(at: folder) }
        let fileURL = folder.appendingPathComponent("SomeBook.pdf")
        try Data().write(to: fileURL)

        state.addFolder(pickedURL: fileURL)

        XCTAssertTrue(state.folders.isEmpty)
        XCTAssertEqual(state.error?.errorDescription, AppError.invalidFolderSelection.errorDescription)
    }

    func testRemoveFolderRemovesItFromTheList() async throws {
        let state = AppState(folderPersistence: makeIsolatedPersistence())
        let folder = try makeTempFolder()
        defer { try? FileManager.default.removeItem(at: folder) }
        state.addFolder(pickedURL: folder)
        let addedFolder = try XCTUnwrap(state.folders.first)

        await state.removeFolder(addedFolder)

        XCTAssertTrue(state.folders.isEmpty)
    }

    /// Regression guard for the actual bug being fixed: previously "Add Folder" was an
    /// empty `Button(action: {})` that didn't even persist a folder, let alone survive
    /// relaunch. A fresh AppState backed by the same persistence must see what a prior
    /// instance added.
    func testFoldersPersistAcrossAppStateInstances() throws {
        let persistence = makeIsolatedPersistence()
        let folder = try makeTempFolder()
        defer { try? FileManager.default.removeItem(at: folder) }

        AppState(folderPersistence: persistence).addFolder(pickedURL: folder)

        let reloaded = AppState(folderPersistence: persistence)
        XCTAssertEqual(reloaded.folders.count, 1)
        XCTAssertEqual(reloaded.folders.first?.displayName, folder.lastPathComponent)
    }

    /// Regression guard for the second reported bug: audiobooks (and any real file in
    /// a user-added folder) must actually show up in `books`.
    func testLoadLibraryIncludesRealFilesFromAnAddedFolder() async throws {
        let persistence = makeIsolatedPersistence()
        let folder = try makeTempFolder()
        defer { try? FileManager.default.removeItem(at: folder) }
        try Data().write(to: folder.appendingPathComponent("MyAudiobook.mp3"))

        let state = AppState(folderPersistence: persistence)
        state.addFolder(pickedURL: folder)
        await state.loadLibrary()

        XCTAssertTrue(state.books.contains { $0.title == "MyAudiobook" && $0.format == .mp3 })
    }

    /// `fileURL`/`folderId` are what later reopen the real file for content parsing
    /// and playback — without them, the scan is discovery-only and nothing can
    /// actually read the book back.
    func testLoadLibraryPopulatesFileURLAndFolderId() async throws {
        let persistence = makeIsolatedPersistence()
        let folder = try makeTempFolder()
        defer { try? FileManager.default.removeItem(at: folder) }
        let fileURL = folder.appendingPathComponent("MyAudiobook.mp3")
        try Data().write(to: fileURL)

        let state = AppState(folderPersistence: persistence)
        state.addFolder(pickedURL: folder)
        await state.loadLibrary()
        let addedFolder = try XCTUnwrap(state.folders.first)

        let book = try XCTUnwrap(state.books.first { $0.title == "MyAudiobook" })
        XCTAssertEqual(book.folderId, addedFolder.id)
        XCTAssertEqual(book.fileURL?.path, fileURL.path)
    }

    /// Regression guard: `loadLibrary()` must only ever reflect real folder contents —
    /// no hardcoded/demo titles mixed in alongside real files.
    func testLoadLibraryNeverMixesMockDemoBooksIntoARealFolder() async throws {
        let persistence = makeIsolatedPersistence()
        let folder = try makeTempFolder()
        defer { try? FileManager.default.removeItem(at: folder) }
        try Data().write(to: folder.appendingPathComponent("MyAudiobook.mp3"))

        let state = AppState(folderPersistence: persistence)
        state.addFolder(pickedURL: folder)
        await state.loadLibrary()

        XCTAssertEqual(state.books.count, 1)
    }

    /// There is no demo/fallback library — with no folders configured, the library is
    /// genuinely empty until the user adds one.
    func testLoadLibraryIsEmptyWhenNoFoldersConfigured() async throws {
        let state = AppState(folderPersistence: makeIsolatedPersistence())
        await state.loadLibrary()
        XCTAssertTrue(state.books.isEmpty)
    }

    func testRemovingAFolderDropsItsBooksFromTheLibrary() async throws {
        let persistence = makeIsolatedPersistence()
        let folder = try makeTempFolder()
        defer { try? FileManager.default.removeItem(at: folder) }
        try Data().write(to: folder.appendingPathComponent("MyAudiobook.mp3"))

        let state = AppState(folderPersistence: persistence)
        state.addFolder(pickedURL: folder)
        await state.loadLibrary()
        let addedFolder = try XCTUnwrap(state.folders.first)

        await state.removeFolder(addedFolder)

        XCTAssertFalse(state.books.contains { $0.title == "MyAudiobook" })
    }

    /// Regression guard for issue #223: removing a folder must also drop the
    /// bookmarks/highlights/progress recorded against its books — mirrors Android's
    /// `RemoveVaultFolderUseCase`, whose `libraryRepository.deleteByVault(vaultId)`
    /// cascades to those rows via Room foreign keys. iOS has no such cascade to lean
    /// on, so `AppState.removeFolder` must explicitly route through
    /// `DomainBridge.removeFolder(bookIds:)` for this to actually happen — see that
    /// method's own tests in DomainBridgeTests for the cleanup logic itself.
    func testRemovingAFolderClearsBookmarksForItsBooks() async throws {
        try await LibravaultDomainBridge.shared.initialize()
        let persistence = makeIsolatedPersistence()
        let folder = try makeTempFolder()
        defer { try? FileManager.default.removeItem(at: folder) }
        try Data().write(to: folder.appendingPathComponent("MyAudiobook.mp3"))

        let state = AppState(folderPersistence: persistence)
        state.addFolder(pickedURL: folder)
        await state.loadLibrary()
        let addedFolder = try XCTUnwrap(state.folders.first)
        let book = try XCTUnwrap(state.books.first { $0.folderId == addedFolder.id })
        try await LibravaultDomainBridge.shared.addBookmark(bookId: book.id, position: "0:00")

        await state.removeFolder(addedFolder)

        XCTAssertNil(LibravaultDomainBridge.shared.bookmarks[book.id])
    }
}
