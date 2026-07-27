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
    /// a user-added vault) must actually show up in `books`, not just the mock library.
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
}
