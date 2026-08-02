import UIKit

/// Backs `AppDelegate.application(_:supportedInterfaceOrientationsFor:)` so a single
/// screen — the audiobook player — can restrict rotation to portrait while the rest
/// of the app (reader screens included) stays free to rotate. Mirrors Android's
/// `LockScreenOrientation` in core:ui, which does the same thing per-screen via
/// `Activity.requestedOrientation` instead of a UIKit delegate callback.
enum OrientationManager {
    static var mask: UIInterfaceOrientationMask = .allButUpsideDown

    /// Call from a screen's onAppear/onDisappear to lock or release rotation.
    static func lock(to mask: UIInterfaceOrientationMask) {
        self.mask = mask
        guard let scene = UIApplication.shared.connectedScenes.first as? UIWindowScene else { return }
        scene.windows.first?.rootViewController?.setNeedsUpdateOfSupportedInterfaceOrientations()
        if mask == .portrait {
            scene.requestGeometryUpdate(.iOS(interfaceOrientations: .portrait))
        }
    }
}

final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        supportedInterfaceOrientationsFor window: UIWindow?
    ) -> UIInterfaceOrientationMask {
        OrientationManager.mask
    }
}
