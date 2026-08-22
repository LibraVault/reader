import XCTest
@testable import LibraVault

/// Exercises `SecureScreenContentKind`'s alert-wording selection directly —
/// the plain, UIKit-free logic behind `SecureScreenModifier`'s screenshot
/// alert (#446). The modifier itself has no injectable constructor to test
/// against directly, same untested-by-design split as
/// `SecureScreenModifier`/`ScreenCaptureMonitor` (see `ScreenCaptureMonitorTests`'s
/// own doc comment) — but pulling the message selection into this enum makes
/// that one piece directly testable.
final class SecureScreenContentKindTests: XCTestCase {

    func testRecoveryKeyMessageNamesTheRecoveryKey() {
        let message = SecureScreenContentKind.recoveryKey.screenshotAlertMessage
        XCTAssertTrue(message.contains("recovery key"), "recovery-key screens must name the recovery key explicitly")
    }

    func testVaultContentMessageDoesNotNameTheRecoveryKey() {
        let message = SecureScreenContentKind.vaultContent.screenshotAlertMessage
        XCTAssertFalse(message.contains("recovery key"), "reader/player screens never show a recovery key, so the alert must not claim they might have captured one")
    }

    func testTheTwoContentKindsProduceDifferentMessages() {
        XCTAssertNotEqual(
            SecureScreenContentKind.recoveryKey.screenshotAlertMessage,
            SecureScreenContentKind.vaultContent.screenshotAlertMessage
        )
    }
}
