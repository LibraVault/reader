import XCTest
@testable import LibraVault

final class VaultPersistenceTests: XCTestCase {

    private func makeIsolatedDefaults() -> UserDefaults {
        let suiteName = "VaultPersistenceTests.\(UUID().uuidString)"
        return UserDefaults(suiteName: suiteName)!
    }

    private func makeTempFolder() throws -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("VaultPersistenceTests-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    // MARK: - load/save round trip

    func testLoadVaultsReturnsEmptyWhenNothingSaved() {
        let persistence = VaultPersistence(defaults: makeIsolatedDefaults())
        XCTAssertEqual(persistence.loadVaults(), [])
    }

    func testSaveThenLoadRoundTripsVaults() {
        let defaults = makeIsolatedDefaults()
        let vault = Vault(id: "1", displayName: "Books", bookmarkData: Data([1, 2, 3]))

        VaultPersistence(defaults: defaults).save([vault])

        XCTAssertEqual(VaultPersistence(defaults: defaults).loadVaults(), [vault])
    }

    func testSaveOverwritesThePreviouslySavedList() {
        let defaults = makeIsolatedDefaults()
        let persistence = VaultPersistence(defaults: defaults)
        let first = Vault(id: "1", displayName: "Books", bookmarkData: Data([1]))
        let second = Vault(id: "2", displayName: "Audiobooks", bookmarkData: Data([2]))

        persistence.save([first])
        persistence.save([first, second])

        XCTAssertEqual(persistence.loadVaults(), [first, second])
    }

    // MARK: - makeVault / resolvedURL

    func testMakeVaultCapturesDisplayNameFromURL() throws {
        let folder = try makeTempFolder()
        defer { try? FileManager.default.removeItem(at: folder) }

        let vault = try VaultPersistence().makeVault(from: folder)

        XCTAssertEqual(vault.displayName, folder.lastPathComponent)
        XCTAssertFalse(vault.bookmarkData.isEmpty)
    }

    func testResolvedURLRoundTripsBackToTheSamePath() throws {
        let folder = try makeTempFolder()
        defer { try? FileManager.default.removeItem(at: folder) }
        let persistence = VaultPersistence()

        let vault = try persistence.makeVault(from: folder)
        let resolved = persistence.resolvedURL(for: vault)

        XCTAssertEqual(resolved?.path, folder.path)
    }

    func testResolvedURLReturnsNilForGarbageBookmarkData() {
        let vault = Vault(id: "1", displayName: "Bad", bookmarkData: Data([0xFF, 0x00, 0x01]))
        XCTAssertNil(VaultPersistence().resolvedURL(for: vault))
    }
}
