import Foundation

/// PIN or recovery-key unlock for one vault. Swift port of Android's
/// `UnlockVaultViewModel` — two independent methods
/// (`unlockWithPinSubmitted`/`unlockWithRecoveryKeySubmitted`) rather than
/// one dispatched "submit," deliberately mirroring
/// `VaultSessionManager`'s/`VaultStore`'s own split.
@MainActor
final class UnlockEncryptedVaultViewModel: ObservableObject {

    enum Mode: Equatable {
        case pin
        case recoveryKey
    }

    let vaultId: String
    let displayName: String

    @Published var mode: Mode = .pin
    @Published var pin = ""
    @Published var recoveryKeyText = ""
    @Published var errorMessage: String?
    @Published var isUnlocking = false
    /// Set once by `handle` on `.success` — the view observes this to
    /// navigate on to the vault's contents.
    @Published private(set) var didUnlock = false

    /// The Secure Enclave key for this vault is gone (device restore/factory
    /// reset). Forces `mode = .recoveryKey` and hides the "use PIN instead"
    /// link — a PIN unlock is not coming back for this vault without it.
    @Published private(set) var keystoreKeyLost = false

    /// A snapshot pair, not a running timer: `(reportedAt, remainingAtReport)`
    /// captured the moment `.throttled` comes back from `unlockWithPin`. The
    /// view derives a live countdown from this snapshot (e.g. via
    /// `TimelineView`), rather than this view model ticking a `Timer`
    /// itself — matches Android's `LaunchedEffect` + 250ms `delay()` loop
    /// deriving its countdown from an identical stored snapshot, so both
    /// platforms re-ask the real throttle on the next tap instead of trusting
    /// client-side wall-clock time once it's expired.
    @Published private(set) var throttleReportedAt: Date?
    private(set) var throttleRemainingAtReport: TimeInterval = 0

    private let sessionManager: VaultSessionManager
    private let now: () -> Date

    init(vaultId: String, displayName: String, sessionManager: VaultSessionManager, now: @escaping () -> Date = Date.init) {
        self.vaultId = vaultId
        self.displayName = displayName
        self.sessionManager = sessionManager
        self.now = now
    }

    /// Remaining throttle delay right now, derived from the stored snapshot
    /// — `nil` once it's expired (the view should let the user try again;
    /// the real check happens again inside `unlockWithPinSubmitted`).
    func currentRemainingDelay() -> TimeInterval? {
        guard let reportedAt = throttleReportedAt else { return nil }
        let elapsed = now().timeIntervalSince(reportedAt)
        let remaining = throttleRemainingAtReport - elapsed
        return remaining > 0 ? remaining : nil
    }

    func switchToRecoveryKey() {
        mode = .recoveryKey
        errorMessage = nil
    }

    /// A no-op if `keystoreKeyLost` — that path has no way back to PIN
    /// unlock for this vault.
    func switchToPin() {
        guard !keystoreKeyLost else { return }
        mode = .pin
        errorMessage = nil
    }

    func unlockWithPinSubmitted() async {
        guard !pin.isEmpty else { return }
        errorMessage = nil
        isUnlocking = true
        defer { isUnlocking = false }

        var pinBytes = Array(pin.utf8)
        defer { pinBytes.secureZero() }

        do {
            let outcome = try await sessionManager.unlockWithPin(id: vaultId, pin: pinBytes)
            handle(outcome)
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func unlockWithRecoveryKeySubmitted() async {
        guard let recoveryKey = RecoveryKeyFormat.parse(recoveryKeyText) else {
            errorMessage = "That doesn't look like a valid recovery key."
            return
        }
        errorMessage = nil
        isUnlocking = true
        defer { isUnlocking = false }

        do {
            let outcome = try await sessionManager.unlockWithRecoveryKey(id: vaultId, recoveryKey: recoveryKey)
            handle(outcome)
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    /// Applies one `UnlockOutcome` to this view model's published state.
    /// `.success` sets `didUnlock`, which the view observes to navigate on
    /// to the vault's contents.
    private func handle(_ outcome: UnlockOutcome) {
        switch outcome {
        case .success:
            pin = ""
            recoveryKeyText = ""
            throttleReportedAt = nil
            didUnlock = true
        case .wrongCredential:
            errorMessage = mode == .pin ? "Wrong PIN." : "That recovery key doesn't match this vault."
            pin = ""
        case .throttled(let remainingDelayMillis):
            throttleReportedAt = now()
            throttleRemainingAtReport = TimeInterval(remainingDelayMillis) / 1000
            errorMessage = nil
            pin = ""
        case .keystoreKeyLost:
            keystoreKeyLost = true
            mode = .recoveryKey
            errorMessage = "This device's secure hardware key for this vault is gone. Use your recovery key instead."
        }
    }
}
