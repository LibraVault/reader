import XCTest
@testable import LibraVault

@MainActor
final class EncryptedVaultContentsViewModelTests: XCTestCase {

    private func makeUnlockedVault() async throws -> (manager: VaultSessionManager, id: String) {
        let rootDir = FileManager.default.temporaryDirectory.appendingPathComponent("contents-vm-test-\(UUID().uuidString)")
        let manager = VaultSessionManager(rootDir: rootDir, keyWrapFactory: FakeHardwareKeyWrapFactory())
        let result = try await manager.createVault(displayName: "Personal", pin: Array("1234".utf8))
        guard case .success(let id, _) = result else { XCTFail("expected .success"); return (manager, "") }
        return (manager, id)
    }

    func testRefreshOnAnEmptyVaultProducesNoEntriesAndStaysUnlocked() async throws {
        let (manager, id) = try await makeUnlockedVault()
        let vm = EncryptedVaultContentsViewModel(vaultId: id, sessionManager: manager)
        await vm.refresh()
        XCTAssertTrue(vm.entries.isEmpty)
        XCTAssertFalse(vm.isLocked)
        XCTAssertNil(vm.errorMessage)
    }

    func testRefreshOnAnAlreadyLockedVaultSetsIsLockedWithoutThrowing() async throws {
        let (manager, id) = try await makeUnlockedVault()
        await manager.lock(id)

        let vm = EncryptedVaultContentsViewModel(vaultId: id, sessionManager: manager)
        await vm.refresh()

        XCTAssertTrue(vm.isLocked)
        XCTAssertTrue(vm.entries.isEmpty)
    }

    func testLockFlipsIsLockedAndActuallyLocksTheUnderlyingVault() async throws {
        let (manager, id) = try await makeUnlockedVault()
        let vm = EncryptedVaultContentsViewModel(vaultId: id, sessionManager: manager)

        await vm.lock()

        XCTAssertTrue(vm.isLocked)
        let isUnlocked = await manager.isUnlocked(id)
        XCTAssertFalse(isUnlocked)
    }

    /// A real file on disk, not a mock stream — importFiles reads it via a
    /// genuine `InputStream(url:)`, same as picking it through `.fileImporter`
    /// would (minus the actual system picker UI, which can't run in a test).
    private func makeTempFile(named name: String, contents: String = "# Hello\n\nSome real markdown content.") throws -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("import-fixture-\(UUID().uuidString)/\(name)")
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try contents.write(to: url, atomically: true, encoding: .utf8)
        return url
    }

    func testImportFilesRoundTripsARealFileIntoTheVaultManifest() async throws {
        let (manager, id) = try await makeUnlockedVault()
        let vm = EncryptedVaultContentsViewModel(vaultId: id, sessionManager: manager)
        let fileURL = try makeTempFile(named: "notes.md")

        await vm.importFiles(urls: [fileURL])

        XCTAssertEqual(vm.importItems.count, 1)
        XCTAssertEqual(vm.importItems.first?.status, .done)
        XCTAssertEqual(vm.entries.count, 1, "refresh() runs automatically after the batch finishes")
        XCTAssertEqual(vm.entries.first?.title, "notes")
        XCTAssertEqual(vm.entries.first?.format, "markdown")
    }

    func testImportFilesOnALockedVaultSetsIsLockedWithoutAttemptingImport() async throws {
        let (manager, id) = try await makeUnlockedVault()
        await manager.lock(id)
        let vm = EncryptedVaultContentsViewModel(vaultId: id, sessionManager: manager)
        let fileURL = try makeTempFile(named: "notes.md")

        await vm.importFiles(urls: [fileURL])

        XCTAssertTrue(vm.isLocked)
        XCTAssertTrue(vm.importItems.isEmpty, "must not even start a batch against a locked vault")
    }

    func testImportFilesWithAnUnsupportedExtensionMarksThatItemAsErrorButContinuesWithOthers() async throws {
        let (manager, id) = try await makeUnlockedVault()
        let vm = EncryptedVaultContentsViewModel(vaultId: id, sessionManager: manager)
        let goodFile = try makeTempFile(named: "notes.md")
        let badFile = try makeTempFile(named: "unsupported.xyz")

        await vm.importFiles(urls: [badFile, goodFile])

        XCTAssertEqual(vm.importItems.count, 2)
        guard case .error = vm.importItems[0].status else {
            XCTFail("expected the unsupported-extension file to be marked .error, got \(vm.importItems[0].status)")
            return
        }
        XCTAssertEqual(vm.importItems[1].status, .done, "a failure on one item must not abort the rest of the batch")
        XCTAssertEqual(vm.entries.count, 1)
        XCTAssertEqual(vm.entries.first?.title, "notes")
    }

    func testImportFilesWithEmptyArrayIsANoOp() async throws {
        let (manager, id) = try await makeUnlockedVault()
        let vm = EncryptedVaultContentsViewModel(vaultId: id, sessionManager: manager)

        await vm.importFiles(urls: [])

        XCTAssertTrue(vm.importItems.isEmpty)
        XCTAssertFalse(vm.isImporting)
    }

    func testClearImportItemsEmptiesTheList() async throws {
        let (manager, id) = try await makeUnlockedVault()
        let vm = EncryptedVaultContentsViewModel(vaultId: id, sessionManager: manager)
        let fileURL = try makeTempFile(named: "notes.md")
        await vm.importFiles(urls: [fileURL])
        XCTAssertFalse(vm.importItems.isEmpty)

        vm.clearImportItems()

        XCTAssertTrue(vm.importItems.isEmpty)
    }
}
