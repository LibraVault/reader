import XCTest
@testable import LibraVault

@MainActor
final class CreateEncryptedVaultViewModelTests: XCTestCase {

    private func makeViewModel(factory: FakeHardwareKeyWrapFactory = FakeHardwareKeyWrapFactory()) -> CreateEncryptedVaultViewModel {
        let rootDir = FileManager.default.temporaryDirectory.appendingPathComponent("create-vm-test-\(UUID().uuidString)")
        let manager = VaultSessionManager(rootDir: rootDir, keyWrapFactory: factory)
        return CreateEncryptedVaultViewModel(sessionManager: manager)
    }

    func testProceedFromNameRejectsBlankName() {
        let vm = makeViewModel()
        vm.displayName = "   "
        vm.proceedFromName()
        XCTAssertEqual(vm.step, .name)
        XCTAssertNotNil(vm.errorMessage)
    }

    func testProceedFromNameTrimsAndAdvances() {
        let vm = makeViewModel()
        vm.displayName = "  Personal  "
        vm.proceedFromName()
        XCTAssertEqual(vm.step, .pin)
        XCTAssertEqual(vm.displayName, "Personal")
    }

    func testProceedFromPinRejectsTooShort() {
        let vm = makeViewModel()
        vm.displayName = "Personal"
        vm.proceedFromName() // real wizard flow: step must reach .pin first
        vm.pin = "12"
        vm.proceedFromPin()
        XCTAssertEqual(vm.step, .pin, "a rejected PIN must not advance the step")
        XCTAssertNotNil(vm.errorMessage)
    }

    func testProceedFromPinAdvancesOnValidLength() {
        let vm = makeViewModel()
        vm.displayName = "Personal"
        vm.proceedFromName()
        vm.pin = "1234"
        vm.proceedFromPin()
        XCTAssertEqual(vm.step, .confirmPin)
    }

    func testProceedFromConfirmPinRejectsMismatch() async {
        let vm = makeViewModel()
        vm.displayName = "Personal"
        vm.proceedFromName()
        vm.pin = "1234"
        vm.proceedFromPin()
        vm.confirmPin = "5678"
        await vm.proceedFromConfirmPin()
        XCTAssertEqual(vm.step, .confirmPin, "a mismatched confirmation must not advance the step")
        XCTAssertNotNil(vm.errorMessage)
        XCTAssertEqual(vm.confirmPin, "", "mismatched confirmation should be cleared for retry")
    }

    func testProceedFromConfirmPinCreatesVaultAndShowsRecoveryKeyExactlyOnce() async {
        let vm = makeViewModel()
        vm.displayName = "Personal"
        vm.pin = "1234"
        vm.confirmPin = "1234"
        await vm.proceedFromConfirmPin()

        XCTAssertEqual(vm.step, .recoveryKey)
        XCTAssertNotNil(vm.createdVaultId)
        let recoveryKeyDisplay = try? XCTUnwrap(vm.recoveryKeyDisplay)
        XCTAssertNotNil(recoveryKeyDisplay)
        // PIN fields are cleared once the vault is created — never lingers
        // in memory (best-effort) or on screen past this point.
        XCTAssertEqual(vm.pin, "")
        XCTAssertEqual(vm.confirmPin, "")
    }

    func testFinishReturnsIdAndClearsRecoveryKeyPermanently() async {
        let vm = makeViewModel()
        vm.displayName = "Personal"
        vm.pin = "1234"
        vm.confirmPin = "1234"
        await vm.proceedFromConfirmPin()

        let id = vm.finish()
        XCTAssertNotNil(id)
        XCTAssertNil(vm.recoveryKeyDisplay, "recovery key must never be re-displayable after finish()")
    }

    func testHardwareUnavailableStepsBackToPinWithBumpedMinimumLength() async {
        let factory = FakeHardwareKeyWrapFactory()
        factory.simulateHardwareUnavailable = true
        let vm = makeViewModel(factory: factory)
        vm.displayName = "Personal"
        vm.pin = "1234"
        vm.confirmPin = "1234"
        await vm.proceedFromConfirmPin()

        XCTAssertEqual(vm.step, .pin)
        XCTAssertNil(vm.createdVaultId)
        XCTAssertEqual(vm.minCredentialLength, CreateEncryptedVaultViewModel.hardwareUnavailableMinLength)
        XCTAssertNotNil(vm.errorMessage)

        // The old short PIN must actually be rejected now, not just cosmetically.
        vm.pin = "1234"
        vm.proceedFromPin()
        XCTAssertEqual(vm.step, .pin, "a 4-character PIN must not pass the bumped 8-character minimum")
    }
}
