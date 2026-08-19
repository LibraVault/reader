import Foundation

/// Output of a `HardwareKeyWrap.wrap` call. A single opaque blob, unlike
/// Android's `WrappedBlob` (`nonce` + `ciphertext`): the ECIES scheme
/// `SecureEnclaveHardwareKeyWrap` uses bundles its ephemeral key material and
/// AES-GCM nonce into the one output `SecKeyCreateEncryptedData` produces,
/// so there's no separate nonce for a caller to keep alongside it.
struct WrappedBlob: Equatable {
    let ciphertext: Data
}

/// The additional, hardware-backed wrapping layer around
/// `VaultKeyMaterial.wrappedVmkByKek` (see that type's doc comment) — iOS
/// equivalent of Android core:vaultstore's `HardwareKeyWrap`/
/// `AndroidKeystoreHardwareKeyWrap`. This is what removes the offline
/// brute-force path against a 4-digit PIN entirely: without it, an attacker
/// who copies the app's sandbox could enumerate 10,000 PIN candidates
/// against Argon2id offline; with it, the key material never leaves secure
/// hardware, so guessing has to go through the rate-limited on-device unlock
/// path (`UnlockAttemptThrottle`, #301).
///
/// Deliberately a protocol, not a direct call to the Security framework at
/// every call site: mirrors Android's own reasoning (`HardwareKeyWrap.kt`'s
/// doc comment) for keeping this fake-able in plain unit tests, and — on
/// iOS specifically — for keeping `SecureEnclaveHardwareKeyWrap`'s
/// `wrap`/`unwrap` ECIES logic testable against a plain (non-Secure-Enclave)
/// P-256 key in the Simulator, where real Secure Enclave key generation
/// always fails (there's no SE hardware to emulate).
protocol HardwareKeyWrap {

    /// Encrypts `plaintext` under a hardware-backed key.
    func wrap(_ plaintext: Data) throws -> WrappedBlob

    /// - Throws: `VaultCryptoError.authenticationFailed` if `wrapped` doesn't verify.
    func unwrap(_ wrapped: WrappedBlob) throws -> Data

    /// Whether the wrapping key is actually inside the Secure Enclave,
    /// reported at creation/lookup time. A caller that gets `false` back
    /// should require a passphrase instead of a 4-digit PIN on this device
    /// rather than proceeding as if the hardware guarantee were in place —
    /// same "do not silently downgrade" rule as Android's PRD §7.1.
    var isHardwareBacked: Bool { get }
}

/// Creates or loads a `HardwareKeyWrap` for a given alias. Split into two
/// operations rather than one "get or create," same reasoning as Android's
/// `HardwareKeyWrapFactory`: `createNew` can fail with
/// `HardwareKeyWrapError.secureEnclaveUnavailable` (no Secure Enclave on
/// this device/environment), which is only meaningful to check at
/// vault-creation time, not on every unlock.
protocol HardwareKeyWrapFactory {
    func createNew(keyAlias: String) throws -> HardwareKeyWrap
    func forExisting(keyAlias: String) throws -> HardwareKeyWrap
}

/// Errors specific to the hardware-wrap layer. Deliberately separate from
/// `VaultCryptoError` (core:vaultcrypto's error type) — these are about
/// hardware/Keychain availability, not the AEAD construction itself.
/// `unwrap`'s tamper/wrong-key case reuses `VaultCryptoError.authenticationFailed`
/// directly instead of duplicating it here, mirroring Android's own choice
/// to reuse `VaultAuthenticationException` from core:vaultcrypto rather than
/// invent a parallel case in core:vaultstore.
enum HardwareKeyWrapError: Error, Equatable {

    /// No Secure Enclave available (Simulator, or key generation otherwise
    /// failed) — see `SecureEnclaveHardwareKeyWrapFactory.createNew`.
    /// Callers MUST NOT fall back to a software-backed key silently; the
    /// caller decides what to do next (passphrase, refuse), same contract
    /// as Android's `KeystoreHardwareUnavailableException`.
    case secureEnclaveUnavailable(message: String)

    /// This vault's Secure Enclave key no longer exists in the Keychain —
    /// e.g. after a device restore/factory reset, which clears the Secure
    /// Enclave. Mirrors Android's `KeystoreKeyLostException`: the scenario
    /// the recovery key (#302/#304) exists to rescue.
    case keyLost(keyAlias: String)

    /// A Keychain query other than "item not found" failed (e.g.
    /// `errSecInteractionNotAllowed`) — distinct from `.keyLost` because it
    /// doesn't mean the key is gone, just unreachable right now.
    case keychainError(status: OSStatus)

    /// `wrap()`/`unwrap()`'s own `SecKeyCreateEncryptedData`/
    /// `SecKeyIsAlgorithmSupported` calls failed for a reason that isn't
    /// "wrong key/tampered ciphertext" (that's `VaultCryptoError
    /// .authenticationFailed`) and isn't "no key available" (that's
    /// `.secureEnclaveUnavailable`/`.keyLost`) — e.g. a malformed key that
    /// isn't actually EC/P-256. Kept distinct from `.secureEnclaveUnavailable`
    /// specifically so a `wrap()` failure against a perfectly valid
    /// *non*-Secure-Enclave test key (see `SecureEnclaveHardwareKeyWrapTests`)
    /// doesn't misreport "no Secure Enclave" for a failure that has nothing
    /// to do with the Secure Enclave at all.
    case operationFailed(message: String)
}

extension HardwareKeyWrapError: LocalizedError {
    var errorDescription: String? {
        switch self {
        case .secureEnclaveUnavailable(let message):
            return "No Secure Enclave available on this device: \(message)"
        case .keyLost(let keyAlias):
            return "Secure Enclave key '\(keyAlias)' no longer exists — recovery key required to unlock this vault"
        case .keychainError(let status):
            return "Keychain operation failed (OSStatus \(status))"
        case .operationFailed(let message):
            return "Hardware key wrap operation failed: \(message)"
        }
    }
}
