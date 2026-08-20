import XCTest
@testable import LibraVault

@MainActor
final class EncryptedVaultListViewModelTests: XCTestCase {

    private func newManager() -> VaultSessionManager {
        let rootDir = FileManager.default.temporaryDirectory.appendingPathComponent("list-vm-test-\(UUID().uuidString)")
        return VaultSessionManager(rootDir: rootDir, keyWrapFactory: FakeHardwareKeyWrapFactory())
    }

    func testRefreshOnEmptyRegistryProducesEmptyList() async {
        let manager = newManager()
        let vm = EncryptedVaultListViewModel(sessionManager: manager)
        await vm.refresh()
        XCTAssertTrue(vm.vaults.isEmpty)
    }

    func testRefreshReflectsDisplayNameAndUnlockedState() async throws {
        let manager = newManager()
        let result = try await manager.createVault(displayName: "Personal", pin: Array("1234".utf8))
        guard case .success(let id, _) = result else { XCTFail("expected .success"); return }

        let vm = EncryptedVaultListViewModel(sessionManager: manager)
        await vm.refresh()

        XCTAssertEqual(vm.vaults.count, 1)
        XCTAssertEqual(vm.vaults.first?.id, id)
        XCTAssertEqual(vm.vaults.first?.displayName, "Personal")
        XCTAssertTrue(vm.vaults.first?.isUnlocked ?? false, "createVault leaves the vault unlocked")

        await manager.lock(id)
        await vm.refresh()
        XCTAssertFalse(vm.vaults.first?.isUnlocked ?? true)
    }

    func testRefreshOrdersNewestFirst() async throws {
        let manager = newManager()
        let first = try await manager.createVault(displayName: "First", pin: Array("1234".utf8))
        let second = try await manager.createVault(displayName: "Second", pin: Array("5678".utf8))
        guard case .success(let firstId, _) = first, case .success(let secondId, _) = second else {
            XCTFail("expected both creates to succeed")
            return
        }

        let vm = EncryptedVaultListViewModel(sessionManager: manager)
        await vm.refresh()

        XCTAssertEqual(vm.vaults.map(\.id), [secondId, firstId])
    }

    func testRefreshCalledAgainAfterExternalLockAllReflectsTheChange() async throws {
        // Simulates VaultForegroundLockObserver locking everything while
        // this screen isn't front-most — the scenario onAppear-driven
        // refresh (not just a one-time .task) exists to catch.
        let manager = newManager()
        _ = try await manager.createVault(displayName: "Personal", pin: Array("1234".utf8))

        let vm = EncryptedVaultListViewModel(sessionManager: manager)
        await vm.refresh()
        XCTAssertTrue(vm.vaults.first?.isUnlocked ?? false)

        await manager.lockAll()
        await vm.refresh()
        XCTAssertFalse(vm.vaults.first?.isUnlocked ?? true)
    }
}
