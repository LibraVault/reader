/// Pure state machine behind `vaultContentSecurity()`'s blank overlay
/// (issue #204) — combines two independent "blank this vault content
/// screen" signals into one `isBlanked` flag:
///
///  - "captured": screen-recording or AirPlay-mirroring is active right
///    now, reported by `ScreenCaptureMonitor.isBlanked`.
///  - "backgrounding": the app is about to background — set on
///    `willResignActiveNotification` (which fires *before* the OS takes the
///    app-switcher snapshot on `didEnterBackground`) and cleared on
///    `didBecomeActiveNotification` — so the blank overlay is already in
///    place by the time the snapshot is actually captured.
///
/// A plain struct with no Combine/UIKit/NotificationCenter dependency of its
/// own, unlike `ScreenCaptureMonitor` (an `ObservableObject` that owns real
/// notification observation) — this only combines two already-observed
/// booleans, so it's directly testable with no notification-posting
/// ceremony, injected `NotificationCenter`, or `@MainActor` isolation at
/// all.
struct VaultContentSecurityState: Equatable {
    private(set) var isCaptured: Bool
    private(set) var isBackgrounding: Bool

    var isBlanked: Bool { isCaptured || isBackgrounding }

    init(isCaptured: Bool = false, isBackgrounding: Bool = false) {
        self.isCaptured = isCaptured
        self.isBackgrounding = isBackgrounding
    }

    /// Updates capture state and reports whether *this call* is the moment
    /// capture transitioned from off to on — the one instant that should
    /// trigger an auto-lock reaction (see #204's acceptance criteria: iOS
    /// has no API to block a capture outright, so detect-and-react is the
    /// achievable ceiling), not every recomputation while still captured,
    /// and not the moment capture stops.
    mutating func updateCaptured(_ isCaptured: Bool) -> Bool {
        let didStartCapture = isCaptured && !self.isCaptured
        self.isCaptured = isCaptured
        return didStartCapture
    }

    mutating func updateBackgrounding(_ isBackgrounding: Bool) {
        self.isBackgrounding = isBackgrounding
    }
}
