import XCTest
@testable import LibraVault

final class FolderPersistenceTests: XCTestCase {

    private func makeIsolatedDefaults() -> UserDefaults {
        let suiteName = "FolderPersistenceTests.\(UUID().uuidString)"
        return UserDefaults(suiteName: suiteName)!
    }

    private func makeTempFolder() throws -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("FolderPersistenceTests-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    // MARK: - load/save round trip

    func testLoadFoldersReturnsEmptyWhenNothingSaved() {
        let persistence = FolderPersistence(defaults: makeIsolatedDefaults())
        XCTAssertEqual(persistence.loadFolders(), [])
    }

    func testSaveThenLoadRoundTripsFolders() {
        let defaults = makeIsolatedDefaults()
        let folder = Folder(id: "1", displayName: "Books", bookmarkData: Data([1, 2, 3]))

        FolderPersistence(defaults: defaults).save([folder])

        XCTAssertEqual(FolderPersistence(defaults: defaults).loadFolders(), [folder])
    }

    func testSaveOverwritesThePreviouslySavedList() {
        let defaults = makeIsolatedDefaults()
        let persistence = FolderPersistence(defaults: defaults)
        let first = Folder(id: "1", displayName: "Books", bookmarkData: Data([1]))
        let second = Folder(id: "2", displayName: "Audiobooks", bookmarkData: Data([2]))

        persistence.save([first])
        persistence.save([first, second])

        XCTAssertEqual(persistence.loadFolders(), [first, second])
    }

    // MARK: - makeFolder / resolvedURL

    func testMakeFolderCapturesDisplayNameFromURL() throws {
        let folder = try makeTempFolder()
        defer { try? FileManager.default.removeItem(at: folder) }

        let created = try FolderPersistence().makeFolder(from: folder)

        XCTAssertEqual(created.displayName, folder.lastPathComponent)
        XCTAssertFalse(created.bookmarkData.isEmpty)
    }

    func testResolvedURLRoundTripsBackToTheSamePath() throws {
        let folder = try makeTempFolder()
        defer { try? FileManager.default.removeItem(at: folder) }
        let persistence = FolderPersistence()

        let created = try persistence.makeFolder(from: folder)
        let resolved = persistence.resolvedURL(for: created)

        XCTAssertEqual(resolved?.path, folder.path)
    }

    func testResolvedURLReturnsNilForGarbageBookmarkData() {
        let folder = Folder(id: "1", displayName: "Bad", bookmarkData: Data([0xFF, 0x00, 0x01]))
        XCTAssertNil(FolderPersistence().resolvedURL(for: folder))
    }

    // MARK: - importedFolder

    func testImportedFolderCreatesItsBackingFolder() throws {
        let base = try makeTempFolder()
        defer { try? FileManager.default.removeItem(at: base) }

        let folder = try FolderPersistence(importedFolderBaseDirectory: base).importedFolder()

        XCTAssertEqual(folder.displayName, "Imported")
        var isDirectory: ObjCBool = false
        let exists = FileManager.default.fileExists(
            atPath: base.appendingPathComponent("Imported").path,
            isDirectory: &isDirectory
        )
        XCTAssertTrue(exists)
        XCTAssertTrue(isDirectory.boolValue)
    }

    /// Its id is fixed rather than a fresh UUID specifically so a caller that re-derives
    /// it (as AppState.importSharedFile does on every share) always recognizes an
    /// already-persisted one instead of minting a duplicate — see
    /// AppStateFileImportTests for the AppState-level guard on that.
    func testImportedFolderIdIsStableAcrossSeparatePersistenceInstances() throws {
        let base = try makeTempFolder()
        defer { try? FileManager.default.removeItem(at: base) }

        let first = try FolderPersistence(importedFolderBaseDirectory: base).importedFolder()
        let second = try FolderPersistence(importedFolderBaseDirectory: base).importedFolder()

        XCTAssertEqual(first.id, second.id)
    }

    func testImportedFolderReturnsThePersistedFolderOnceOneHasBeenSaved() throws {
        let base = try makeTempFolder()
        defer { try? FileManager.default.removeItem(at: base) }
        let defaults = makeIsolatedDefaults()
        let persistence = FolderPersistence(defaults: defaults, importedFolderBaseDirectory: base)
        let created = try persistence.importedFolder()
        persistence.save([created])

        let reused = try persistence.importedFolder()

        XCTAssertEqual(reused, created)
    }
}
