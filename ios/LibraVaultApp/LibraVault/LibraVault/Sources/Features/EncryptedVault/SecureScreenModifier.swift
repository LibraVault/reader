import SwiftUI
#if canImport(UIKit)
import UIKit
#endif

/// What kind of sensitive material a `.secureVaultScreen()` screen is
/// protecting — selects the screenshot-detected alert's wording, since
/// "recovery key" is only accurate on the two recovery-key screens and is
/// misleading (falsely reassuring) on the vault content reader/player, where
/// a screenshot might instead have captured a passage, filename, or other
/// vault metadata.
enum SecureScreenContentKind {
    case recoveryKey
    case vaultContent

    var screenshotAlertMessage: String {
        switch self {
        case .recoveryKey:
            return "A screenshot was just taken. If it captured your recovery key, delete it from Photos."
        case .vaultContent:
            return "A screenshot was just taken. If it captured something sensitive, delete it from Photos."
        }
    }
}

#if canImport(UIKit)
/// Tracks screen-recording/AirPlay-mirroring state and completed-screenshot
/// events for `SecureScreenModifier`. A separate, plain `ObservableObject`
/// rather than logic inlined into the modifier itself, specifically so it's
/// testable against an injected `NotificationCenter` + `isCapturedProvider`
/// without needing to trigger a real screen recording — impossible in CI/the
/// Simulator — same reasoning as `VaultForegroundLockObserver`'s own
/// injectable `NotificationCenter`.
///
/// **What this can and can't do, and why**: iOS has no API to *prevent* a
/// still screenshot — `userDidTakeScreenshotNotification` only fires after
/// the image is already saved to Photos. The one thing iOS *can* prevent is
/// content leaking into a screen **recording** or **AirPlay mirror** — that's
/// what `UIScreen.isCaptured` reports, and it's what `isBlanked` drives.
/// Deliberately blanking content for the *live* viewer too while
/// `isCaptured` is true (not just in the recorded output) — a full,
/// pixel-level "invisible in the recording but visible live" split render
/// needs undocumented view-hierarchy tricks (hosting content inside a secure
/// `UITextField`'s backing layer) that are fragile across iOS versions and
/// hard to verify without a real device. Blanking for everyone is the
/// robust, honest tradeoff: whoever is recording never captures the secret,
/// and the legitimate user just stops recording to see it again.
@MainActor
final class ScreenCaptureMonitor: ObservableObject {

    @Published private(set) var isBlanked: Bool
    @Published private(set) var didDetectScreenshot = false

    private var tokens: [NSObjectProtocol] = []
    private let notificationCenter: NotificationCenter

    /// - Parameters:
    ///   - notificationCenter: defaults to `.default` (where the real
    ///     `UIScreen`/`UIApplication` notifications actually post) —
    ///     injectable so tests can post synthetic notifications on an
    ///     isolated center without touching real app-wide observers.
    ///   - isCapturedProvider: defaults to the real `UIScreen.main.isCaptured`
    ///     — injectable since nothing can make the Simulator actually report
    ///     `true` here.
    init(
        notificationCenter: NotificationCenter = .default,
        isCapturedProvider: @escaping () -> Bool = { UIScreen.main.isCaptured }
    ) {
        self.notificationCenter = notificationCenter
        self.isBlanked = isCapturedProvider()

        let capturedToken = notificationCenter.addObserver(
            forName: UIScreen.capturedDidChangeNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            self?.isBlanked = isCapturedProvider()
        }
        let screenshotToken = notificationCenter.addObserver(
            forName: UIApplication.userDidTakeScreenshotNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            self?.didDetectScreenshot = true
        }
        tokens = [capturedToken, screenshotToken]
    }

    /// Dismisses the one-time screenshot warning banner. Idempotent.
    func acknowledgeScreenshotWarning() {
        didDetectScreenshot = false
    }

    deinit {
        tokens.forEach { notificationCenter.removeObserver($0) }
    }
}

@MainActor
private struct SecureScreenModifier: ViewModifier {
    // No injectable init: nothing in this codebase constructs
    // `SecureScreenModifier` with a custom monitor (tests exercise
    // `ScreenCaptureMonitor` directly, not through this modifier), so
    // there's no reason to carry an `@autoclosure` default-parameter init
    // here — that shape ran into a real CI build failure twice ("call to
    // main actor-isolated initializer ... in a synchronous nonisolated
    // context"), since an `@autoclosure` parameter's own inferred
    // isolation does NOT follow the enclosing `@MainActor` type/init the
    // way a plain stored-property default initializer does. A synthesized
    // `init()` with a `@MainActor`-isolated default property value is the
    // simpler, actually-correct shape.
    let contentKind: SecureScreenContentKind
    @StateObject private var monitor = ScreenCaptureMonitor()

    func body(content: Content) -> some View {
        content
            .overlay {
                if monitor.isBlanked {
                    ZStack {
                        LibraVaultColor.background.ignoresSafeArea()
                        VStack(spacing: LibraVaultSpacing.md) {
                            Image(systemName: "eye.slash.fill")
                                .font(.system(size: 40))
                                .foregroundStyle(LibraVaultColor.onSurfaceVariant)
                            Text("Hidden during screen recording")
                                .font(LibraVaultTypography.titleMedium)
                                .foregroundStyle(LibraVaultColor.onSurfaceVariant)
                        }
                    }
                    .transition(.opacity)
                }
            }
            .animation(.easeInOut(duration: 0.15), value: monitor.isBlanked)
            .alert("Screenshot Detected", isPresented: Binding(
                get: { monitor.didDetectScreenshot },
                set: { isPresented in if !isPresented { monitor.acknowledgeScreenshotWarning() } }
            )) {
                Button("OK", role: .cancel) { monitor.acknowledgeScreenshotWarning() }
            } message: {
                Text(contentKind.screenshotAlertMessage)
            }
    }
}

extension View {
    /// Unconditional recording-blank + screenshot-detection for a screen
    /// showing sensitive material — applied on `CreateEncryptedVaultView`'s
    /// recovery-key step, `UnlockEncryptedVaultView`'s recovery-key entry
    /// step, and `VaultReaderView`/`VaultPlayerView`'s vault content,
    /// independent of #204's general (toggle-gated) Screen Security spike.
    /// `contentKind` selects the screenshot alert's wording — see
    /// `SecureScreenContentKind`. See `ScreenCaptureMonitor`'s doc comment
    /// for exactly what this does and doesn't prevent.
    ///
    /// `@MainActor` explicitly: `SecureScreenModifier` is `@MainActor`
    /// (needed so its `@StateObject` default, `ScreenCaptureMonitor()`, can
    /// call a `@MainActor`-isolated init), and constructing it here is a
    /// synchronous call that must itself be MainActor-isolated for the same
    /// reason — plain `View` extension methods aren't implicitly
    /// MainActor-isolated just because every real caller happens to be one.
    @MainActor
    func secureVaultScreen(contentKind: SecureScreenContentKind) -> some View {
        modifier(SecureScreenModifier(contentKind: contentKind))
    }
}
#else
extension View {
    func secureVaultScreen(contentKind: SecureScreenContentKind) -> some View { self }
}
#endif
