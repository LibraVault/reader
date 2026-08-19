import Foundation

/// Exponential backoff on failed unlock attempts. Deliberately a speed-bump,
/// not a hard limit — there is no server to enforce a real limit — and
/// deliberately **no auto-wipe**: an attacker holding the device could
/// otherwise trip a wipe deliberately to destroy the vault, turning a
/// confidentiality feature into a destruction tool.
///
/// Pure function of (`failedAttempts`, `lastAttemptEpochMillis`,
/// `nowEpochMillis`) — no I/O, fully unit-testable without a real device.
/// Mirrors Android's `UnlockAttemptThrottle` object (`core/vaultstore`)
/// exactly, so the two platforms back off identically for the same inputs.
/// State is persisted separately by `UnlockAttemptThrottleStore`.
enum UnlockAttemptThrottle {

    /// No delay for the first few attempts — typos shouldn't feel punitive.
    private static let freeAttempts = 3

    private static let baseDelayMillis: Int64 = 1_000
    private static let maxDelayMillis: Int64 = 5 * 60 * 1_000 // cap at 5 minutes

    /// - Returns: milliseconds the caller must wait before the next attempt
    ///   is allowed, or 0 if an attempt is allowed right now.
    static func remainingDelayMillis(failedAttempts: Int, lastAttemptEpochMillis: Int64, nowEpochMillis: Int64) -> Int64 {
        guard failedAttempts > freeAttempts else { return 0 }
        let exponent = min(failedAttempts - freeAttempts, 20) // avoid overflow in the shift
        let delay = min(baseDelayMillis << exponent, maxDelayMillis)
        let elapsed = nowEpochMillis - lastAttemptEpochMillis
        return max(delay - elapsed, 0)
    }

    static func isThrottled(failedAttempts: Int, lastAttemptEpochMillis: Int64, nowEpochMillis: Int64) -> Bool {
        remainingDelayMillis(
            failedAttempts: failedAttempts,
            lastAttemptEpochMillis: lastAttemptEpochMillis,
            nowEpochMillis: nowEpochMillis
        ) > 0
    }
}

/// `UnlockAttemptThrottle`'s persisted state for one vault: a plaintext
/// counter + timestamp, fine to store unencrypted since it's a rate limiter,
/// not a secret — restarting the process must not reset it, otherwise the
/// backoff would be trivially defeated by killing and relaunching the app.
struct UnlockThrottleState: Codable, Equatable {
    var failedAttempts: Int
    var lastAttemptEpochMillis: Int64

    static let initial = UnlockThrottleState(failedAttempts: 0, lastAttemptEpochMillis: 0)
}

/// Persists `UnlockThrottleState` per vault, alongside the vault directory it
/// protects (see `docs/threat-model.md`'s data-inventory table) — a plaintext
/// counter file living next to the (not-yet-implemented on iOS) encrypted
/// vault contents, same as Android stores the equivalent fields in
/// `VaultConfig`'s `vault.json`. Deliberately its own small file rather than
/// folded into a larger config: this module has no crypto-config type to fold
/// it into yet, and the throttle must keep working even if one is added later
/// with a different persistence shape.
enum UnlockAttemptThrottleStore {

    private static let fileName = "unlock-throttle.json"

    /// Returns `UnlockThrottleState.initial` if `vaultDir` has no throttle
    /// state yet — a vault that has never failed an unlock attempt.
    static func read(vaultDir: URL) -> UnlockThrottleState {
        let file = vaultDir.appendingPathComponent(fileName)
        guard let data = try? Data(contentsOf: file) else { return .initial }
        return (try? JSONDecoder().decode(UnlockThrottleState.self, from: data)) ?? .initial
    }

    /// Same write-to-temp-then-rename atomicity as `VaultRegistry.writeAll`.
    static func write(vaultDir: URL, state: UnlockThrottleState) throws {
        try FileManager.default.createDirectory(at: vaultDir, withIntermediateDirectories: true)
        let data = try JSONEncoder().encode(state)
        try data.write(to: vaultDir.appendingPathComponent(fileName), options: .atomic)
    }
}
