import XCTest
@testable import LibraVault

/// Covers `AppState.importSharedFile`, the entry point LibraVaultApp.swift's
/// `.onOpenURL` calls (inside a `Task { }`, since importSharedFile is `async`) when the
/// OS hands LibraVault a file via "Open In"/"Copy to LibraVault" (see Info.plist's
/// CFBundleDocumentTypes). Sibling to AppStateFolderTests, which covers the folder
/// ("Add Folder") flow this is deliberately kept separate from.
@MainActor
final class AppStateFileImportTests: XCTestCase {

    override func setUp() async throws {
        // The domain bridge is a shared singleton scanned by loadLibrary(); make sure
        // it's ready regardless of which order test classes run in (mirrors
        // AppStateFolderTests.setUp).
        try await LibravaultDomainBridge.shared.initialize()
    }

    private func makeTempFolder() throws -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("AppStateFileImportTests-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    /// Isolated the same way AppStateFolderTests' persistence is (a fresh UserDefaults
    /// suite), plus a fresh `importedFolderBaseDirectory` so the "Imported" folder these
    /// tests create never lands in the real Documents directory the test host uses.
    private func makeIsolatedPersistence() throws -> FolderPersistence {
        FolderPersistence(
            defaults: UserDefaults(suiteName: "AppStateFileImportTests.\(UUID().uuidString)")!,
            importedFolderBaseDirectory: try makeTempFolder()
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

        let state = AppState(folderPersistence: persistence)
        await state.importSharedFile(url: fileURL)

        XCTAssertTrue(state.books.contains { $0.title == "MyBook" && $0.format == .epub })
    }

    func testImportSharedFileRegistersTheImportedFolder() async throws {
        let persistence = try makeIsolatedPersistence()
        let sourceFolder = try makeTempFolder()
        defer { try? FileManager.default.removeItem(at: sourceFolder) }
        let fileURL = try makeFixtureFile(named: "MyBook.epub", in: sourceFolder)

        let state = AppState(folderPersistence: persistence)
        await state.importSharedFile(url: fileURL)

        XCTAssertEqual(state.folders.count, 1)
        XCTAssertEqual(state.folders.first?.displayName, "Imported")
    }

    /// Regression guard mirroring AppStateFolderTests'
    /// testAddingTheSameFolderTwiceDoesNotDuplicateTheFolder: importing a second file
    /// must reuse the same Imported folder, not append a second one every time.
    func testImportingASecondFileReusesTheSameImportedFolder() async throws {
        let persistence = try makeIsolatedPersistence()
        let sourceFolder = try makeTempFolder()
        defer { try? FileManager.default.removeItem(at: sourceFolder) }
        let first = try makeFixtureFile(named: "First.epub", in: sourceFolder)
        let second = try makeFixtureFile(named: "Second.epub", in: sourceFolder)

        let state = AppState(folderPersistence: persistence)
        await state.importSharedFile(url: first)
        await state.importSharedFile(url: second)

        XCTAssertEqual(state.folders.count, 1)
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

        let state = AppState(folderPersistence: persistence)
        await state.importSharedFile(url: first)
        await state.importSharedFile(url: second)

        XCTAssertEqual(state.books.filter { $0.title.hasPrefix("Book") }.count, 2)
    }

    /// (#294) Field feedback on build 35 (Dutch): "de redirect is er nu.. maar die
    /// open libra.. niet de md zelf" — the OS handoff launched LibraVault but left the
    /// user on the Library screen instead of the shared document itself. RootView
    /// pushes ReaderView off `pendingImportedBook` (see LibraVaultApp.swift's
    /// `.navigationDestination(item:)`); this covers the AppState half of that: the
    /// property must actually point at the book that was just imported.
    func testImportSharedFileSetsPendingImportedBookToTheNewBook() async throws {
        let persistence = try makeIsolatedPersistence()
        let sourceFolder = try makeTempFolder()
        defer { try? FileManager.default.removeItem(at: sourceFolder) }
        let fileURL = try makeFixtureFile(named: "Notes.md", in: sourceFolder)

        let state = AppState(folderPersistence: persistence)
        await state.importSharedFile(url: fileURL)

        XCTAssertEqual(state.pendingImportedBook?.title, "Notes")
        XCTAssertEqual(state.pendingImportedBook?.format, .markdown)
    }

    /// Mirrors testImportingAFileWithADuplicateNameDoesNotOverwriteTheEarlierOne:
    /// uniqueDestinationURL renames the second import on disk ("Book 2.epub"), so
    /// pendingImportedBook must follow the same renamed file, not the original name.
    func testImportSharedFileFollowsTheRenamedFileWhenNameCollides() async throws {
        let persistence = try makeIsolatedPersistence()
        let firstSourceFolder = try makeTempFolder()
        defer { try? FileManager.default.removeItem(at: firstSourceFolder) }
        let secondSourceFolder = try makeTempFolder()
        defer { try? FileManager.default.removeItem(at: secondSourceFolder) }
        let first = try makeFixtureFile(named: "Book.epub", in: firstSourceFolder, contents: Data("first".utf8))
        let second = try makeFixtureFile(named: "Book.epub", in: secondSourceFolder, contents: Data("second".utf8))

        let state = AppState(folderPersistence: persistence)
        await state.importSharedFile(url: first)
        await state.importSharedFile(url: second)

        XCTAssertEqual(state.pendingImportedBook?.title, "Book 2")
    }

    /// A failed copy must not leave a stale pendingImportedBook pointing at a book
    /// from an earlier, successful import — RootView would push the wrong Reader.
    func testImportSharedFileDoesNotSetPendingImportedBookOnFailure() async throws {
        let persistence = try makeIsolatedPersistence()
        let missingURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("AppStateFileImportTests-missing-\(UUID().uuidString).epub")

        let state = AppState(folderPersistence: persistence)
        await state.importSharedFile(url: missingURL)

        XCTAssertNil(state.pendingImportedBook)
    }

    func testImportingAnUnsupportedFileTypeSetsAnErrorAndAddsNothing() async throws {
        let persistence = try makeIsolatedPersistence()
        let sourceFolder = try makeTempFolder()
        defer { try? FileManager.default.removeItem(at: sourceFolder) }
        let fileURL = try makeFixtureFile(named: "Random.xyz", in: sourceFolder)

        let state = AppState(folderPersistence: persistence)
        await state.importSharedFile(url: fileURL)

        XCTAssertEqual(state.error?.errorDescription, AppError.unsupportedFileType.errorDescription)
        XCTAssertTrue(state.folders.isEmpty)
    }

    /// The copy itself is what fails here (the source doesn't exist) — distinct from
    /// the `.unsupportedFileType` guard above, and deliberately not `.storageAccessDenied`
    /// either (that's reserved for addFolder's folder-bookmark failures): a failed copy
    /// isn't a storage-access denial, so it gets its own message.
    func testImportingAMissingSourceFileSetsFileImportFailedError() async throws {
        let persistence = try makeIsolatedPersistence()
        let missingURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("AppStateFileImportTests-missing-\(UUID().uuidString).epub")

        let state = AppState(folderPersistence: persistence)
        await state.importSharedFile(url: missingURL)

        XCTAssertEqual(state.error?.errorDescription, AppError.fileImportFailed.errorDescription)
    }
}
