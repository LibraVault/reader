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
}
