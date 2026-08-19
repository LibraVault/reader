import XCTest
import UIKit
@testable import LibraVault

/// Exercises the real notification-driven mechanism (not just flipping a
/// property directly) — posts the real `UIScreen.capturedDidChangeNotification`/
/// `UIApplication.userDidTakeScreenshotNotification` names through a private
/// `NotificationCenter` instance (not `.default`, so this can't interfere
/// with or be interfered with by anything else observing these notifications
/// during the test run), same isolation pattern as
/// `VaultForegroundLockObserverTests`.
@MainActor
final class ScreenCaptureMonitorTests: XCTestCase {

    func testInitialStateReflectsTheInjectedIsCapturedProvider() {
        let center = NotificationCenter()
        let capturedMonitor = ScreenCaptureMonitor(notificationCenter: center, isCapturedProvider: { true })
        XCTAssertTrue(capturedMonitor.isBlanked)

        let notCapturedMonitor = ScreenCaptureMonitor(notificationCenter: center, isCapturedProvider: { false })
        XCTAssertFalse(notCapturedMonitor.isBlanked)
    }

    func testCapturedDidChangeNotificationUpdatesIsBlanked() {
        let center = NotificationCenter()
        var isCaptured = false
        let monitor = ScreenCaptureMonitor(notificationCenter: center, isCapturedProvider: { isCaptured })
        XCTAssertFalse(monitor.isBlanked)

        isCaptured = true
        center.post(name: UIScreen.capturedDidChangeNotification, object: nil)
        XCTAssertTrue(monitor.isBlanked)

        isCaptured = false
        center.post(name: UIScreen.capturedDidChangeNotification, object: nil)
        XCTAssertFalse(monitor.isBlanked)
    }

    func testScreenshotNotificationSetsDidDetectScreenshotUntilAcknowledged() {
        let center = NotificationCenter()
        let monitor = ScreenCaptureMonitor(notificationCenter: center, isCapturedProvider: { false })
        XCTAssertFalse(monitor.didDetectScreenshot)

        center.post(name: UIApplication.userDidTakeScreenshotNotification, object: nil)
        XCTAssertTrue(monitor.didDetectScreenshot)

        monitor.acknowledgeScreenshotWarning()
        XCTAssertFalse(monitor.didDetectScreenshot)
    }

    func testAcknowledgingTheScreenshotWarningDoesNotAffectIsBlanked() {
        let center = NotificationCenter()
        let monitor = ScreenCaptureMonitor(notificationCenter: center, isCapturedProvider: { true })
        center.post(name: UIApplication.userDidTakeScreenshotNotification, object: nil)
        monitor.acknowledgeScreenshotWarning()
        XCTAssertTrue(monitor.isBlanked, "recording-blank state is independent of the screenshot warning")
    }
}
