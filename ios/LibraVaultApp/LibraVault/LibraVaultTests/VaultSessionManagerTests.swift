import XCTest
@testable import LibraVault

final class VaultSessionManagerTests: XCTestCase {

    private func newManager() -> VaultSessionManager {
        let rootDir = FileManager.default.temporaryDirectory.appendingPathComponent("vaultsession-test-\(UUID().uuidString)")
        return VaultSessionManager(rootDir: rootDir, keyWrapFactory: FakeHardwareKeyWrapFactory())
    }

    private func pin(_ s: String) -> [UInt8] { Array(s.utf8) }

    func testCreateVaultRegistersItAndLeavesItUnlocked() async throws {
        let manager = newManager()

        let result = try await manager.createVault(displayName: "Personal", pin: pin("1234"))
        guard case .success(let id, let recoveryKey) = result else {
            XCTFail("expected .success, got \(result)")
            return
        }
        XCTAssertEqual(recoveryKey.count, 32)
        let unlocked = await manager.isUnlocked(id)
        XCTAssertTrue(unlocked)

        let listed = await manager.listVaults()
        XCTAssertEqual(listed.map(\.id), [id])
        XCTAssertEqual(listed.first?.displayName, "Personal")
    }

    func testCreateVaultReportsHardwareUnavailableAndDoesNotRegister() async throws {
        let rootDir = FileManager.default.temporaryDirectory.appendingPathComponent("vaultsession-test-\(UUID().uuidString)")
        let factory = FakeHardwareKeyWrapFactory()
        factory.simulateHardwareUnavailable = true
        let manager = VaultSessionManager(rootDir: rootDir, keyWrapFactory: factory)

        let result = try await manager.createVault(displayName: "Personal", pin: pin("1234"))
        XCTAssertEqual(result, .hardwareUnavailable)

        let listed = await manager.listVaults()
        XCTAssertTrue(listed.isEmpty, "a failed create must not register a vault")
    }

    func testUnlockWithPinAndLock() async throws {
        let manager = newManager()
        let result = try await manager.createVault(displayName: "Personal", pin: pin("1234"))
        guard case .success(let id, _) = result else { XCTFail("expected .success"); return }

        await manager.lock(id)
        let lockedNow = await manager.isUnlocked(id)
        XCTAssertFalse(lockedNow)

        let outcome = try await manager.unlockWithPin(id: id, pin: pin("1234"))
        XCTAssertEqual(outcome, .success)
        let unlockedNow = await manager.isUnlocked(id)
        XCTAssertTrue(unlockedNow)
    }

    func testUnlockWithRecoveryKey() async throws {
        let manager = newManager()
        let result = try await manager.createVault(displayName: "Personal", pin: pin("1234"))
        guard case .success(let id, let recoveryKey) = result else { XCTFail("expected .success"); return }

        await manager.lock(id)
        let outcome = try await manager.unlockWithRecoveryKey(id: id, recoveryKey: recoveryKey)
        XCTAssertEqual(outcome, .success)
    }

    func testLockAllLocksEveryTouchedVault() async throws {
        let manager = newManager()
        let r1 = try await manager.createVault(displayName: "One", pin: pin("1234"))
        let r2 = try await manager.createVault(displayName: "Two", pin: pin("5678"))
        guard case .success(let id1, _) = r1, case .success(let id2, _) = r2 else {
            XCTFail("expected both creates to succeed")
            return
        }

        await manager.lockAll()

        let unlocked1 = await manager.isUnlocked(id1)
        let unlocked2 = await manager.isUnlocked(id2)
        XCTAssertFalse(unlocked1)
        XCTAssertFalse(unlocked2)
    }

    func testRequireUnlockedReturnsTheStoreOnlyWhenActuallyUnlocked() async throws {
        let manager = newManager()
        let result = try await manager.createVault(displayName: "Personal", pin: pin("1234"))
        guard case .success(let id, _) = result else { XCTFail("expected .success"); return }

        let store = await manager.requireUnlocked(id)
        XCTAssertTrue(store.isUnlocked)
    }
}
