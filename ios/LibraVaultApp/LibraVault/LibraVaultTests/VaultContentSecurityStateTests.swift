import XCTest
@testable import LibraVault

/// Exercises `VaultContentSecurityState` directly — the plain, UIKit-free
/// combining logic behind `vaultContentSecurity()`'s blank overlay (#204).
/// The actual `NotificationCenter`/`ScreenCaptureMonitor` wiring lives in
/// `VaultContentSecurityModifier`, a `ViewModifier` with no injectable
/// constructor to test against directly — same untested-by-design split as
/// `SecureScreenModifier`/`ScreenCaptureMonitor` (see `ScreenCaptureMonitorTests`'s
/// own doc comment).
final class VaultContentSecurityStateTests: XCTestCase {

    func testInitialStateIsNotBlanked() {
        let state = VaultContentSecurityState()
        XCTAssertFalse(state.isBlanked)
    }

    func testCaptureAloneBlanks() {
        var state = VaultContentSecurityState()
        _ = state.updateCaptured(true)
        XCTAssertTrue(state.isBlanked)
    }

    func testBackgroundingAloneBlanks() {
        var state = VaultContentSecurityState()
        state.updateBackgrounding(true)
        XCTAssertTrue(state.isBlanked)
    }

    func testClearingOneSignalWhileTheOtherIsActiveStaysBlanked() {
        var state = VaultContentSecurityState()
        _ = state.updateCaptured(true)
        state.updateBackgrounding(true)
        _ = state.updateCaptured(false)
        XCTAssertTrue(state.isBlanked, "still backgrounding, so still blanked")
    }

    func testClearingBothSignalsUnblanks() {
        var state = VaultContentSecurityState()
        _ = state.updateCaptured(true)
        state.updateBackgrounding(true)
        _ = state.updateCaptured(false)
        state.updateBackgrounding(false)
        XCTAssertFalse(state.isBlanked)
    }

    /// The one signal meant to drive an auto-lock reaction (#204's
    /// acceptance criteria) — must fire exactly on the off→on transition,
    /// never while already captured.
    func testUpdateCapturedReturnsTrueOnlyOnTheOffToOnTransition() {
        var state = VaultContentSecurityState()
        XCTAssertTrue(state.updateCaptured(true), "off -> on must report a capture start")
        XCTAssertFalse(state.updateCaptured(true), "already captured; not a new start")
        XCTAssertFalse(state.updateCaptured(false), "on -> off is not a capture start")
        XCTAssertTrue(state.updateCaptured(true), "off -> on again must report a capture start")
    }

    func testUpdateBackgroundingNeverReportsACaptureStart() {
        // updateBackgrounding has no return value to assert on directly —
        // this instead proves backgrounding never flips isCaptured, which
        // is what would make a later updateCaptured(true) wrongly report
        // "not a new start" if the two signals were accidentally conflated.
        var state = VaultContentSecurityState()
        state.updateBackgrounding(true)
        XCTAssertTrue(state.updateCaptured(true), "backgrounding must not suppress a real capture-start report")
    }
}
