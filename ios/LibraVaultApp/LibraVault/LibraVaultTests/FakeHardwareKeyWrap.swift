import Foundation
@testable import LibraVault

/// Test-only fake for `HardwareKeyWrapFactory` — lets `VaultStore`'s create/
/// unlock/lock orchestration be unit-tested without a real Secure Enclave
/// (the Simulator has none — see `SecureEnclaveHardwareKeyWrapTests`'s own
/// doc comment). Mirrors Android's `FakeHardwareKeyWrapFactory`
/// (`core/vaultstore/testing`).
///
/// Behaves like a real hardware-backed key for the properties tests care
/// about (deterministic per-alias key, AEAD wrap/unwrap, clean failure on a
/// wrong/missing key) by reusing `AesGcmCipher`/`SecureRandom` directly
/// rather than going anywhere near `Security`/Secure Enclave APIs. Not the
/// same construction as the real `SecureEnclaveHardwareKeyWrap` (that one
/// uses ECIES; this uses plain AES-GCM with the nonce prefixed onto
/// `WrappedBlob.ciphertext`, since `WrappedBlob` has no separate nonce
/// field) — that's fine, this only needs to satisfy the `HardwareKeyWrap`
/// contract, not reproduce ECIES's own internals.
final class FakeHardwareKeyWrapFactory: HardwareKeyWrapFactory {

    private var keysByAlias: [String: Data] = [:]

    /// Set true to simulate a device with no Secure Enclave at all —
    /// exercises the `.secureEnclaveUnavailable` path.
    var simulateHardwareUnavailable = false

    func createNew(keyAlias: String) throws -> HardwareKeyWrap {
        if simulateHardwareUnavailable {
            throw HardwareKeyWrapError.secureEnclaveUnavailable(message: "fake: hardware unavailable")
        }
        let key = try SecureRandom.bytes(count: 32)
        keysByAlias[keyAlias] = key
        return FakeHardwareKeyWrap(key: key)
    }

    func forExisting(keyAlias: String) throws -> HardwareKeyWrap {
        guard let key = keysByAlias[keyAlias] else {
            throw HardwareKeyWrapError.keyLost(keyAlias: keyAlias)
        }
        return FakeHardwareKeyWrap(key: key)
    }

    /// Simulates losing the Secure Enclave key while the vault's files
    /// survive — the scenario the recovery key exists to rescue.
    func forgetKey(_ keyAlias: String) {
        keysByAlias.removeValue(forKey: keyAlias)
    }
}

private struct FakeHardwareKeyWrap: HardwareKeyWrap {
    let key: Data
    // Fakes always report success; unavailability is a factory-level concern.
    var isHardwareBacked: Bool { true }

    func wrap(_ plaintext: Data) throws -> WrappedBlob {
        let nonce = try SecureRandom.bytes(count: VaultFormat.nonceSizeBytes)
        let ciphertext = try AesGcmCipher.encrypt(key: key, nonce: nonce, aad: Data(), plaintext: plaintext)
        return WrappedBlob(ciphertext: nonce + ciphertext)
    }

    func unwrap(_ wrapped: WrappedBlob) throws -> Data {
        guard wrapped.ciphertext.count >= VaultFormat.nonceSizeBytes else {
            throw VaultCryptoError.authenticationFailed
        }
        let nonce = wrapped.ciphertext.prefix(VaultFormat.nonceSizeBytes)
        let ciphertext = wrapped.ciphertext.dropFirst(VaultFormat.nonceSizeBytes)
        return try AesGcmCipher.decrypt(key: key, nonce: nonce, aad: Data(), ciphertextWithTag: ciphertext)
    }
}
