import Foundation
#if canImport(UIKit)
import UIKit
#endif

#if canImport(UIKit)
/// Locks every open vault the instant the app leaves the foreground —
/// `UIApplication.willResignActiveNotification`, per the design scoped in
/// #201's split: immediate, not waiting for full backgrounding, so a
/// transient interruption (an incoming call, Control Center, the app
/// switcher) re-locks too, not just a full app-switch. Mirrors Android's
/// `VaultSessionManager` registering a `ProcessLifecycleOwner` `onStop`
/// observer in its own `init`.
///
/// A separate, plain (non-actor) type rather than folding this into
/// `VaultSessionManager` itself: `NotificationCenter` observer registration
/// is a UIKit/notification-delivery concern, not a vault-state concern —
/// keeping it here means `VaultSessionManager`'s own tests never need to
/// simulate app-lifecycle notifications to exercise its actual create/
/// unlock/lock logic, and this type's own test can simulate exactly one
/// notification and assert the real, observable effect.
final class VaultForegroundLockObserver {

    private let sessionManager: VaultSessionManager
    private var token: NSObjectProtocol?

    init(sessionManager: VaultSessionManager, notificationCenter: NotificationCenter = .default) {
        self.sessionManager = sessionManager
        token = notificationCenter.addObserver(
            forName: UIApplication.willResignActiveNotification,
            object: nil,
            queue: nil
        ) { [sessionManager] _ in
            Task { await sessionManager.lockAll() }
        }
    }

    deinit {
        if let token {
            NotificationCenter.default.removeObserver(token)
        }
    }
}
#endif
