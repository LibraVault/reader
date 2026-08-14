import XCTest
@testable import LibraVault

/// Covers `AppState.importSharedFile`, the entry point LibraVaultApp.swift's
/// `.onOpenURL` calls when the OS hands LibraVault a file via "Open In"/"Copy to
/// LibraVault" (see Info.plist's CFBundleDocumentTypes). Sibling to
/// AppStateVaultTests, which covers the folder-vault ("Add Vault") flow this is
/// deliberately kept separate from.
@MainActor
final class AppStateFileImportTests: XCTestCase {

    override func setUp() async throws {
        // The domain bridge is a shared singleton scanned by loadLibrary(); make sure
        // it's ready regardless of which order test classes run in (mirrors
        // AppStateVaultTests.setUp).
        try await LibravaultDomainBridge.shared.initialize()
    }

    private func makeTempFolder() throws -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("AppStateFileImportTests-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    /// Isolated the same way AppStateVaultTests' persistence is (a fresh UserDefaults
    /// suite), plus a fresh `importedVaultBaseDirectory` so the "Imported" folder these
    /// tests create never lands in the real Documents directory the test host uses.
    private func makeIsolatedPersistence() throws -> VaultPersistence {
        VaultPersistence(
            defaults: UserDefaults(suiteName: "AppStateFileImportTests.\(UUID().uuidString)")!,
            importedVaultBaseDirectory: try makeTempFolder()
        )
    }

    @discardableResult
    private func makeFixtureFile(named name: String, in folder: URL, contents: Data = Data("fixture".utf8)) throws -> URL {
        let url = folder.appendingPathComponent(name)
        try contents.write(to: url)
        return url
    }

    func testImportSharedFileAddsBookToLibrary() async throws {
        let persistence = try makeIsolatedPersistence()
        let sourceFolder = try makeTempFolder()
        defer { try? FileManager.default.removeItem(at: sourceFolder) }
        let fileURL = try makeFixtureFile(named: "MyBook.epub", in: sourceFolder)

        let state = AppState(vaultPersistence: persistence)
        state.importSharedFile(url: fileURL)
        await state.loadLibrary()

        XCTAssertTrue(state.books.contains { $0.title == "MyBook" && $0.format == .epub })
    }

    func testImportSharedFileRegistersTheImportedVault() throws {
        let persistence = try makeIsolatedPersistence()
        let sourceFolder = try makeTempFolder()
        defer { try? FileManager.default.removeItem(at: sourceFolder) }
        let fileURL = try makeFixtureFile(named: "MyBook.epub", in: sourceFolder)

        let state = AppState(vaultPersistence: persistence)
        state.importSharedFile(url: fileURL)

        XCTAssertEqual(state.vaults.count, 1)
        XCTAssertEqual(state.vaults.first?.displayName, "Imported")
    }

    /// Regression guard mirroring AppStateVaultTests'
    /// testAddingTheSameFolderTwiceDoesNotDuplicateTheVault: importing a second file
    /// must reuse the same Imported vault, not append a second one every time.
    func testImportingASecondFileReusesTheSameImportedVault() throws {
        let persistence = try makeIsolatedPersistence()
        let sourceFolder = try makeTempFolder()
        defer { try? FileManager.default.removeItem(at: sourceFolder) }
        let first = try makeFixtureFile(named: "First.epub", in: sourceFolder)
        let second = try makeFixtureFile(named: "Second.epub", in: sourceFolder)

        let state = AppState(vaultPersistence: persistence)
        state.importSharedFile(url: first)
        state.importSharedFile(url: second)

        XCTAssertEqual(state.vaults.count, 1)
    }

    /// Two files sharing a filename (a real case — re-downloading/re-sharing "the same"
    /// book from a different source) must both survive, not have the second overwrite
    /// the first.
    func testImportingAFileWithADuplicateNameDoesNotOverwriteTheEarlierOne() async throws {
        let persistence = try makeIsolatedPersistence()
        let firstSourceFolder = try makeTempFolder()
        defer { try? FileManager.default.removeItem(at: firstSourceFolder) }
        let secondSourceFolder = try makeTempFolder()
        defer { try? FileManager.default.removeItem(at: secondSourceFolder) }
        let first = try makeFixtureFile(named: "Book.epub", in: firstSourceFolder, contents: Data("first".utf8))
        let second = try makeFixtureFile(named: "Book.epub", in: secondSourceFolder, contents: Data("second".utf8))

        let state = AppState(vaultPersistence: persistence)
        state.importSharedFile(url: first)
        state.importSharedFile(url: second)
        await state.loadLibrary()

        XCTAssertEqual(state.books.filter { $0.title.hasPrefix("Book") }.count, 2)
    }

    func testImportingAnUnsupportedFileTypeSetsAnErrorAndAddsNothing() throws {
        let persistence = try makeIsolatedPersistence()
        let sourceFolder = try makeTempFolder()
        defer { try? FileManager.default.removeItem(at: sourceFolder) }
        let fileURL = try makeFixtureFile(named: "Random.xyz", in: sourceFolder)

        let state = AppState(vaultPersistence: persistence)
        state.importSharedFile(url: fileURL)

        XCTAssertEqual(state.error?.errorDescription, AppError.unsupportedFileType.errorDescription)
        XCTAssertTrue(state.vaults.isEmpty)
    }

    /// The copy itself is what fails here (the source doesn't exist) — distinct from
    /// the `.unsupportedFileType` guard above, and deliberately not `.storageAccessDenied`
    /// either (that's reserved for addVault's folder-bookmark failures): a failed copy
    /// isn't a storage-access denial, so it gets its own message.
    func testImportingAMissingSourceFileSetsFileImportFailedError() throws {
        let persistence = try makeIsolatedPersistence()
        let missingURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("AppStateFileImportTests-missing-\(UUID().uuidString).epub")

        let state = AppState(vaultPersistence: persistence)
        state.importSharedFile(url: missingURL)

        XCTAssertEqual(state.error?.errorDescription, AppError.fileImportFailed.errorDescription)
    }
}
