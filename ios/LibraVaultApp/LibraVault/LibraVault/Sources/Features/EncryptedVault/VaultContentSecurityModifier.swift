import SwiftUI
import Combine
#if canImport(UIKit)
import UIKit
#endif

#if canImport(UIKit)
/// General, toggle-gated Screen Security for vault content screens (#204) —
/// distinct from `SecureScreenModifier`'s *unconditional* use on the
/// recovery-key display/entry steps (see that type's own doc comment for
/// why those two are deliberately separate gates, not shared). Reuses
/// `ScreenCaptureMonitor` for the recording/AirPlay-mirroring half of the
/// picture; the app-switcher-snapshot half has no equivalent existing
/// signal to reuse, so it's observed directly here (see
/// `VaultContentSecurityState`'s doc comment for why the two need combining
/// at all). Combining logic itself lives in `VaultContentSecurityState`, a
/// plain, directly-testable struct — this modifier is thin glue over real
/// `NotificationCenter`/`ScreenCaptureMonitor` observation, the same split
/// `SecureScreenModifier`/`ScreenCaptureMonitor` already use.
@MainActor
private struct VaultContentSecurityModifier: ViewModifier {
    let enabled: Bool
    let onCaptureDetected: () -> Void

    @StateObject private var captureMonitor = ScreenCaptureMonitor()
    @State private var state = VaultContentSecurityState()

    func body(content: Content) -> some View {
        content
            .overlay {
                if enabled && state.isBlanked {
                    ZStack {
                        LibraVaultColor.background.ignoresSafeArea()
                        VStack(spacing: LibraVaultSpacing.md) {
                            Image(systemName: "eye.slash.fill")
                                .font(.system(size: 40))
                                .foregroundStyle(LibraVaultColor.onSurfaceVariant)
                            Text("Hidden for privacy")
                                .font(LibraVaultTypography.titleMedium)
                                .foregroundStyle(LibraVaultColor.onSurfaceVariant)
                        }
                    }
                    .transition(.opacity)
                }
            }
            .animation(.easeInOut(duration: 0.15), value: state.isBlanked)
            // `.onChange` never fires for the value already in place on
            // first appearance (only for later *changes*) — without this,
            // opening a vault content screen while already mid-recording
            // would neither blank the content nor auto-lock until whatever
            // triggered the *next* capture-state change, which might never
            // come. Seeding `state` from `captureMonitor`'s current value
            // here catches exactly that case.
            .onAppear {
                reportCaptureStart(state.updateCaptured(captureMonitor.isBlanked))
            }
            .onChange(of: captureMonitor.isBlanked) { _, isCaptured in
                reportCaptureStart(state.updateCaptured(isCaptured))
            }
            .onReceive(NotificationCenter.default.publisher(for: UIApplication.willResignActiveNotification)) { _ in
                state.updateBackgrounding(true)
            }
            .onReceive(NotificationCenter.default.publisher(for: UIApplication.didBecomeActiveNotification)) { _ in
                state.updateBackgrounding(false)
            }
    }

    private func reportCaptureStart(_ didStartCapture: Bool) {
        if enabled && didStartCapture { onCaptureDetected() }
    }
}

extension View {
    /// General, toggle-gated Screen Security for vault content screens —
    /// blanks content while screen-recorded/AirPlay-mirrored (calling
    /// `onCaptureDetected`, meant to trigger an auto-lock reaction — see
    /// #204's acceptance criteria) or while the app is about to background
    /// (protecting the app-switcher snapshot). A no-op, both visually and
    /// for `onCaptureDetected`, when `enabled` is false.
    @MainActor
    func vaultContentSecurity(enabled: Bool, onCaptureDetected: @escaping () -> Void) -> some View {
        modifier(VaultContentSecurityModifier(enabled: enabled, onCaptureDetected: onCaptureDetected))
    }
}
#else
extension View {
    func vaultContentSecurity(enabled: Bool, onCaptureDetected: @escaping () -> Void) -> some View { self }
}
#endif
