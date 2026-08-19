import XCTest
import UIKit
@testable import LibraVault

/// Exercises the actual notification-driven mechanism (not just
/// `VaultSessionManager.lockAll()` in isolation) — posts the real
/// `UIApplication.willResignActiveNotification` through a private
/// `NotificationCenter` instance (not `.default`, so this can't interfere
/// with or be interfered with by anything else observing app-lifecycle
/// notifications during the test run) and asserts the vault actually
/// re-locks.
final class VaultForegroundLockObserverTests: XCTestCase {

    private func pin(_ s: String) -> [UInt8] { Array(s.utf8) }

    func testWillResignActiveLocksAllOpenVaults() async throws {
        let rootDir = FileManager.default.temporaryDirectory.appendingPathComponent("vaultobserver-test-\(UUID().uuidString)")
        let manager = VaultSessionManager(rootDir: rootDir, keyWrapFactory: FakeHardwareKeyWrapFactory())
        let result = try await manager.createVault(displayName: "Personal", pin: pin("1234"))
        guard case .success(let id, _) = result else {
            XCTFail("expected .success")
            return
        }
        let unlockedBefore = await manager.isUnlocked(id)
        XCTAssertTrue(unlockedBefore)

        let notificationCenter = NotificationCenter()
        let observer = VaultForegroundLockObserver(sessionManager: manager, notificationCenter: notificationCenter)

        let didLock = expectation(description: "vault locked after willResignActive")
        Task {
            // Poll rather than a fixed sleep — the observer's handler hops
            // onto the actor via `Task { await sessionManager.lockAll() }`,
            // so locking happens asynchronously relative to the notification
            // post. A fixed delay would be either flaky (too short) or slow
            // (too long) for no benefit.
            for _ in 0..<50 {
                let stillUnlocked = await manager.isUnlocked(id)
                if !stillUnlocked {
                    didLock.fulfill()
                    return
                }
                try? await Task.sleep(nanoseconds: 20_000_000) // 20ms
            }
        }

        notificationCenter.post(name: UIApplication.willResignActiveNotification, object: nil)
        wait(for: [didLock], timeout: 2.0)

        withExtendedLifetime(observer) {} // keep the observer (and its NotificationCenter registration) alive for the duration of the test
    }
}
