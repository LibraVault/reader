import XCTest
@testable import LibraVault

@MainActor
final class AppStateVaultTests: XCTestCase {

    override func setUp() async throws {
        // The domain bridge is a shared singleton scanned by loadLibrary(); make sure
        // it's ready regardless of which order test classes run in (mirrors
        // DomainBridgeTests.setUp).
        try await LibravaultDomainBridge.shared.initialize()
    }

    private func makeIsolatedPersistence() -> VaultPersistence {
        VaultPersistence(defaults: UserDefaults(suiteName: "AppStateVaultTests.\(UUID().uuidString)")!)
    }

    private func makeTempVaultFolder() throws -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("AppStateVaultTests-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    func testNewAppStateStartsWithNoVaults() {
        XCTAssertTrue(AppState(vaultPersistence: makeIsolatedPersistence()).vaults.isEmpty)
    }

    func testAddVaultAppendsToVaultsList() throws {
        let state = AppState(vaultPersistence: makeIsolatedPersistence())
        let folder = try makeTempVaultFolder()
        defer { try? FileManager.default.removeItem(at: folder) }

        state.addVault(pickedURL: folder)

        XCTAssertEqual(state.vaults.count, 1)
        XCTAssertEqual(state.vaults.first?.displayName, folder.lastPathComponent)
    }

    /// Regression guard: picking the same folder twice must not double-add it — the
    /// natural place to browse to for a second "Add Vault" tap is the same folder you
    /// just picked, and without this, every file in it would show up twice in the grid.
    func testAddingTheSameFolderTwiceDoesNotDuplicateTheVault() throws {
        let state = AppState(vaultPersistence: makeIsolatedPersistence())
        let folder = try makeTempVaultFolder()
        defer { try? FileManager.default.removeItem(at: folder) }

        state.addVault(pickedURL: folder)
        state.addVault(pickedURL: folder)

        XCTAssertEqual(state.vaults.count, 1)
    }

    /// Regression guard for issue #185: a document-provider extension (Google Drive
    /// being the commonly reported offender) can hand `.fileImporter` a plain file
    /// even though `allowedContentTypes: [.folder]` asked for a folder. Previously
    /// this silently became a "vault" that scanned to 0 books forever — now it's
    /// rejected up front with an actionable error instead.
    func testAddVaultRejectsAPlainFileInsteadOfSilentlyCreatingADeadVault() throws {
        let state = AppState(vaultPersistence: makeIsolatedPersistence())
        let folder = try makeTempVaultFolder()
        defer { try? FileManager.default.removeItem(at: folder) }
        let fileURL = folder.appendingPathComponent("SomeBook.pdf")
        try Data().write(to: fileURL)

        state.addVault(pickedURL: fileURL)

        XCTAssertTrue(state.vaults.isEmpty)
        XCTAssertEqual(state.error?.errorDescription, AppError.invalidVaultSelection.errorDescription)
    }

    func testRemoveVaultRemovesItFromTheList() throws {
        let state = AppState(vaultPersistence: makeIsolatedPersistence())
        let folder = try makeTempVaultFolder()
        defer { try? FileManager.default.removeItem(at: folder) }
        state.addVault(pickedURL: folder)
        let vault = try XCTUnwrap(state.vaults.first)

        state.removeVault(vault)

        XCTAssertTrue(state.vaults.isEmpty)
    }

    /// Regression guard for the actual bug being fixed: previously "Add Vault" was an
    /// empty `Button(action: {})` that didn't even persist a folder, let alone survive
    /// relaunch. A fresh AppState backed by the same persistence must see what a prior
    /// instance added.
    func testVaultsPersistAcrossAppStateInstances() throws {
        let persistence = makeIsolatedPersistence()
        let folder = try makeTempVaultFolder()
        defer { try? FileManager.default.removeItem(at: folder) }

        AppState(vaultPersistence: persistence).addVault(pickedURL: folder)

        let reloaded = AppState(vaultPersistence: persistence)
        XCTAssertEqual(reloaded.vaults.count, 1)
        XCTAssertEqual(reloaded.vaults.first?.displayName, folder.lastPathComponent)
    }

    /// Regression guard for the second reported bug: audiobooks (and any real file in
    /// a user-added vault) must actually show up in `books`.
    func testLoadLibraryIncludesRealFilesFromAnAddedVault() async throws {
        let persistence = makeIsolatedPersistence()
        let folder = try makeTempVaultFolder()
        defer { try? FileManager.default.removeItem(at: folder) }
        try Data().write(to: folder.appendingPathComponent("MyAudiobook.mp3"))

        let state = AppState(vaultPersistence: persistence)
        state.addVault(pickedURL: folder)
        await state.loadLibrary()

        XCTAssertTrue(state.books.contains { $0.title == "MyAudiobook" && $0.format == .mp3 })
    }

    /// `fileURL`/`vaultId` are what later reopen the real file for content parsing
    /// and playback — without them, the scan is discovery-only and nothing can
    /// actually read the book back.
    func testLoadLibraryPopulatesFileURLAndVaultId() async throws {
        let persistence = makeIsolatedPersistence()
        let folder = try makeTempVaultFolder()
        defer { try? FileManager.default.removeItem(at: folder) }
        let fileURL = folder.appendingPathComponent("MyAudiobook.mp3")
        try Data().write(to: fileURL)

        let state = AppState(vaultPersistence: persistence)
        state.addVault(pickedURL: folder)
        await state.loadLibrary()
        let vault = try XCTUnwrap(state.vaults.first)

        let book = try XCTUnwrap(state.books.first { $0.title == "MyAudiobook" })
        XCTAssertEqual(book.vaultId, vault.id)
        XCTAssertEqual(book.fileURL?.path, fileURL.path)
    }

    /// Regression guard: `loadLibrary()` must only ever reflect real vault contents —
    /// no hardcoded/demo titles mixed in alongside real files.
    func testLoadLibraryNeverMixesMockDemoBooksIntoARealVault() async throws {
        let persistence = makeIsolatedPersistence()
        let folder = try makeTempVaultFolder()
        defer { try? FileManager.default.removeItem(at: folder) }
        try Data().write(to: folder.appendingPathComponent("MyAudiobook.mp3"))

        let state = AppState(vaultPersistence: persistence)
        state.addVault(pickedURL: folder)
        await state.loadLibrary()

        XCTAssertEqual(state.books.count, 1)
    }

    /// There is no demo/fallback library — with no vaults configured, the library is
    /// genuinely empty until the user adds one.
    func testLoadLibraryIsEmptyWhenNoVaultsConfigured() async throws {
        let state = AppState(vaultPersistence: makeIsolatedPersistence())
        await state.loadLibrary()
        XCTAssertTrue(state.books.isEmpty)
    }

    func testRemovingAVaultDropsItsBooksFromTheLibrary() async throws {
        let persistence = makeIsolatedPersistence()
        let folder = try makeTempVaultFolder()
        defer { try? FileManager.default.removeItem(at: folder) }
        try Data().write(to: folder.appendingPathComponent("MyAudiobook.mp3"))

        let state = AppState(vaultPersistence: persistence)
        state.addVault(pickedURL: folder)
        await state.loadLibrary()
        let vault = try XCTUnwrap(state.vaults.first)

        state.removeVault(vault)
        await state.loadLibrary()

        XCTAssertFalse(state.books.contains { $0.title == "MyAudiobook" })
    }

    /// Regression guard for issue #223: removing a vault must also drop the
    /// bookmarks/highlights/progress recorded against its books — mirrors Android's
    /// `RemoveVaultFolderUseCase`, whose `libraryRepository.deleteByVault(vaultId)`
    /// cascades to those rows via Room foreign keys. iOS has no such cascade to lean
    /// on, so `AppState.removeVault` must explicitly route through
    /// `DomainBridge.removeVault(bookIds:)` for this to actually happen — see that
    /// method's own tests in DomainBridgeTests for the cleanup logic itself.
    func testRemovingAVaultClearsBookmarksForItsBooks() async throws {
        try await LibravaultDomainBridge.shared.initialize()
        let persistence = makeIsolatedPersistence()
        let folder = try makeTempVaultFolder()
        defer { try? FileManager.default.removeItem(at: folder) }
        try Data().write(to: folder.appendingPathComponent("MyAudiobook.mp3"))

        let state = AppState(vaultPersistence: persistence)
        state.addVault(pickedURL: folder)
        await state.loadLibrary()
        let vault = try XCTUnwrap(state.vaults.first)
        let book = try XCTUnwrap(state.books.first { $0.vaultId == vault.id })
        try await LibravaultDomainBridge.shared.addBookmark(bookId: book.id, position: "0:00")

        state.removeVault(vault)
        // removeVault kicks off its bridge cleanup + rescan in an internal
        // fire-and-forget Task; awaiting another call on the same MainActor
        // afterwards (same idiom as testRemovingAVaultDropsItsBooksFromTheLibrary
        // above) lets that already-scheduled Task run to completion first.
        await state.loadLibrary()

        XCTAssertNil(LibravaultDomainBridge.shared.bookmarks[book.id])
    }
}
