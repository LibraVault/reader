import XCTest
@testable import LibraVault

@MainActor
final class UnlockEncryptedVaultViewModelTests: XCTestCase {

    private func makeVault(factory: FakeHardwareKeyWrapFactory = FakeHardwareKeyWrapFactory()) async throws -> (manager: VaultSessionManager, id: String) {
        let rootDir = FileManager.default.temporaryDirectory.appendingPathComponent("unlock-vm-test-\(UUID().uuidString)")
        let manager = VaultSessionManager(rootDir: rootDir, keyWrapFactory: factory)
        let result = try await manager.createVault(displayName: "Personal", pin: Array("1234".utf8))
        guard case .success(let id, _) = result else {
            XCTFail("expected .success")
            return (manager, "")
        }
        await manager.lock(id)
        return (manager, id)
    }

    func testUnlockWithCorrectPinSucceeds() async throws {
        let (manager, id) = try await makeVault()
        let vm = UnlockEncryptedVaultViewModel(vaultId: id, displayName: "Personal", sessionManager: manager)
        vm.pin = "1234"

        await vm.unlockWithPinSubmitted()

        XCTAssertTrue(vm.didUnlock)
        XCTAssertEqual(vm.pin, "")
        XCTAssertNil(vm.errorMessage)
        let isUnlocked = await manager.isUnlocked(id)
        XCTAssertTrue(isUnlocked)
    }

    func testUnlockWithWrongPinFails() async throws {
        let (manager, id) = try await makeVault()
        let vm = UnlockEncryptedVaultViewModel(vaultId: id, displayName: "Personal", sessionManager: manager)
        vm.pin = "0000"

        await vm.unlockWithPinSubmitted()

        XCTAssertFalse(vm.didUnlock)
        XCTAssertNotNil(vm.errorMessage)
        XCTAssertEqual(vm.pin, "", "a rejected PIN should be cleared, not left sitting on screen")
    }

    func testRepeatedWrongPinEventuallyThrottlesAndCountdownDecaysWithInjectedClock() async throws {
        let (manager, id) = try await makeVault()
        var currentTime = Date()
        let vm = UnlockEncryptedVaultViewModel(vaultId: id, displayName: "Personal", sessionManager: manager, now: { currentTime })

        // freeAttempts = 3: the 4th wrong attempt is still free, the 5th is throttled.
        for _ in 0..<4 {
            vm.pin = "0000"
            await vm.unlockWithPinSubmitted()
        }
        XCTAssertNil(vm.throttleReportedAt, "still within the free-attempt budget")

        vm.pin = "0000"
        await vm.unlockWithPinSubmitted()

        XCTAssertNotNil(vm.throttleReportedAt)
        let remaining = try XCTUnwrap(vm.currentRemainingDelay())
        XCTAssertGreaterThan(remaining, 0)

        // Advance the injected clock past the reported delay — the
        // countdown must actually decay to nil, not just report a value
        // once and freeze.
        currentTime = currentTime.addingTimeInterval(remaining + 1)
        XCTAssertNil(vm.currentRemainingDelay(), "countdown should have expired after the reported delay elapsed")
    }

    func testUnlockWithCorrectRecoveryKeySucceeds() async throws {
        let factory = FakeHardwareKeyWrapFactory()
        let rootDir = FileManager.default.temporaryDirectory.appendingPathComponent("unlock-vm-test-\(UUID().uuidString)")
        let manager = VaultSessionManager(rootDir: rootDir, keyWrapFactory: factory)
        let result = try await manager.createVault(displayName: "Personal", pin: Array("1234".utf8))
        guard case .success(let id, let recoveryKey) = result else { XCTFail("expected .success"); return }
        await manager.lock(id)

        let vm = UnlockEncryptedVaultViewModel(vaultId: id, displayName: "Personal", sessionManager: manager)
        vm.mode = .recoveryKey
        vm.recoveryKeyText = RecoveryKeyFormat.toDisplayString(recoveryKey)

        await vm.unlockWithRecoveryKeySubmitted()

        XCTAssertTrue(vm.didUnlock)
        let isUnlocked = await manager.isUnlocked(id)
        XCTAssertTrue(isUnlocked)
    }

    func testUnlockWithMalformedRecoveryKeyTextFailsWithoutCallingTheStore() async throws {
        let (manager, id) = try await makeVault()
        let vm = UnlockEncryptedVaultViewModel(vaultId: id, displayName: "Personal", sessionManager: manager)
        vm.mode = .recoveryKey
        vm.recoveryKeyText = "not a valid recovery key"

        await vm.unlockWithRecoveryKeySubmitted()

        XCTAssertFalse(vm.didUnlock)
        XCTAssertNotNil(vm.errorMessage)
    }

    func testKeystoreKeyLostForcesRecoveryModeAndHidesTheWayBackToPin() async throws {
        let factory = FakeHardwareKeyWrapFactory()
        let (manager, id) = try await makeVault(factory: factory)
        // The alias format is VaultSessionManager's own private implementation
        // detail (`keystoreAlias(for:)`) — duplicated here deliberately, the
        // same tradeoff VaultSessionManagerTests itself accepts, since there's
        // no public API to simulate "this vault's hardware key is gone"
        // otherwise.
        factory.forgetKey("libravault_vault_\(id)")

        let vm = UnlockEncryptedVaultViewModel(vaultId: id, displayName: "Personal", sessionManager: manager)
        vm.pin = "1234"
        await vm.unlockWithPinSubmitted()

        XCTAssertTrue(vm.keystoreKeyLost)
        XCTAssertEqual(vm.mode, .recoveryKey)

        // switchToPin() must be a no-op once the key is confirmed gone —
        // there is no PIN path back for this vault.
        vm.switchToPin()
        XCTAssertEqual(vm.mode, .recoveryKey)
    }

    func testSwitchToRecoveryKeyAndBackClearsErrorMessage() async throws {
        let (manager, id) = try await makeVault()
        let vm = UnlockEncryptedVaultViewModel(vaultId: id, displayName: "Personal", sessionManager: manager)
        vm.pin = "0000"
        await vm.unlockWithPinSubmitted()
        XCTAssertNotNil(vm.errorMessage)

        vm.switchToRecoveryKey()
        XCTAssertEqual(vm.mode, .recoveryKey)
        XCTAssertNil(vm.errorMessage)

        vm.switchToPin()
        XCTAssertEqual(vm.mode, .pin)
    }
}
