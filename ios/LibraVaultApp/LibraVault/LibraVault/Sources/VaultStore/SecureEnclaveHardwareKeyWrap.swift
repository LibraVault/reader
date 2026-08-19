import Foundation
import Security

/// Real `HardwareKeyWrap` backed by a non-exportable Secure Enclave P-256
/// keypair, one per vault (keyed by `keyAlias`).
///
/// Android's equivalent (`AndroidKeystoreHardwareKeyWrap`) wraps a
/// *symmetric* Keystore AES key directly with `Cipher`. The Secure Enclave
/// has no symmetric-key equivalent — it only ever holds P-256 EC keypairs —
/// so there's no literal port of that shape. This uses ECIES instead
/// (`SecKeyCreateEncryptedData`/`SecKeyCreateDecryptedData`, which combine
/// ephemeral ECDH key agreement, an X9.63 KDF, and AES-GCM under the hood),
/// Apple's standard pattern for "wrap arbitrary-length data under a hardware
/// key." Design decided and posted to
/// https://github.com/LibraVault/reader/issues/201#issuecomment-5337718396
/// before this was written.
///
/// PRD §7.1-equivalent requirements, all deliberate, mirroring the reasoning
/// `AndroidKeystoreHardwareKeyWrap` documents for its own choices:
///  - No `.biometryCurrentSet`/`.userPresence` access-control flag on the
///    key (`SecureEnclaveHardwareKeyWrapFactory.createNew`) — binding to the
///    lock screen would invalidate the key on a passcode change or
///    biometric re-enrollment, destroying the vault for a reason that has
///    nothing to do with the vault's own PIN. Same reasoning as Android's
///    `setUserAuthenticationRequired(false)`/`setInvalidatedByBiometricEnrollment(false)`.
///  - No StrongBox/TEE two-tier fallback: unlike Android hardware, the
///    Secure Enclave is either present (every device this app can run on)
///    or key generation fails outright — `isHardwareBacked` is only ever
///    `true` for an instance that exists at all; there is no software
///    fallback to guard against silently accepting.
final class SecureEnclaveHardwareKeyWrap: HardwareKeyWrap {

    /// Cofactor + variable-IV X9.63/SHA-256/AES-GCM: the "cofactor" variant
    /// is Apple's documented recommendation for the NIST curves LibraVault
    /// uses (P-256's cofactor is 1, so cofactor and non-cofactor variants are
    /// mathematically equivalent here — cofactor is used because it's the
    /// documented default, not picked arbitrarily). "VariableIV" allows
    /// arbitrary-length plaintext instead of being fixed to one AES block —
    /// needed since this wraps a serialized `WrappedKey` (nonce + VMK
    /// ciphertext + tag), not a single 16-byte block.
    private static let algorithm: SecKeyAlgorithm = .eciesEncryptionCofactorVariableIVX963SHA256AESGCM

    private let privateKey: SecKey
    let isHardwareBacked: Bool

    /// Internal initializer taking an already-resolved `SecKey` directly
    /// (rather than a keyAlias) — this is what makes `wrap`/`unwrap`'s ECIES
    /// logic unit-testable against a plain (non-Secure-Enclave) P-256
    /// keypair in the Simulator, where `kSecAttrTokenIDSecureEnclave` key
    /// generation always fails. Mirrors `AndroidKeystoreHardwareKeyWrapTest`'s
    /// own `seedKeyAndWrap` bypass of `create()` for the identical reason —
    /// wrap()/unwrap() don't care how the key was created, only
    /// `SecureEnclaveHardwareKeyWrapFactory.createNew` does.
    init(privateKey: SecKey, isHardwareBacked: Bool) {
        self.privateKey = privateKey
        self.isHardwareBacked = isHardwareBacked
    }

    func wrap(_ plaintext: Data) throws -> WrappedBlob {
        guard let publicKey = SecKeyCopyPublicKey(privateKey) else {
            throw HardwareKeyWrapError.operationFailed(message: "could not derive public key from private key")
        }
        guard SecKeyIsAlgorithmSupported(publicKey, .encrypt, Self.algorithm) else {
            throw HardwareKeyWrapError.operationFailed(message: "ECIES encrypt not supported for this key")
        }
        var error: Unmanaged<CFError>?
        guard let ciphertext = SecKeyCreateEncryptedData(publicKey, Self.algorithm, plaintext as CFData, &error) as Data? else {
            let message = error.map { String(describing: $0.takeRetainedValue()) } ?? "unknown SecKeyCreateEncryptedData failure"
            throw HardwareKeyWrapError.operationFailed(message: message)
        }
        return WrappedBlob(ciphertext: ciphertext)
    }

    /// - Throws: `VaultCryptoError.authenticationFailed` if `wrapped` doesn't
    ///   verify — wrong key, or the blob was tampered with. ECIES's AES-GCM
    ///   tag check surfaces both as one opaque `SecKeyCreateDecryptedData`
    ///   failure, same "don't distinguish wrong-key from tampered" stance as
    ///   `VaultCryptoError.authenticationFailed`'s own doc comment.
    func unwrap(_ wrapped: WrappedBlob) throws -> Data {
        guard SecKeyIsAlgorithmSupported(privateKey, .decrypt, Self.algorithm) else {
            throw HardwareKeyWrapError.operationFailed(message: "ECIES decrypt not supported for this key")
        }
        var error: Unmanaged<CFError>?
        guard let plaintext = SecKeyCreateDecryptedData(privateKey, Self.algorithm, wrapped.ciphertext as CFData, &error) as Data? else {
            throw VaultCryptoError.authenticationFailed
        }
        return plaintext
    }
}

/// Real, Secure-Enclave-and-Keychain-backed `HardwareKeyWrapFactory`.
///
/// Every key this factory creates or loads is looked up by an
/// `kSecAttrApplicationTag` derived from `keyAlias` — see `tag(for:)`. That
/// tag scheme is intentionally documented here rather than left implicit:
/// `SecureEnclaveHardwareKeyWrapTests` duplicates it (rather than importing
/// this type's private helper) to seed/inspect Keychain items directly,
/// mirroring `AndroidKeystoreHardwareKeyWrapTest`'s own deliberate bypass of
/// production code to test the surrounding contract independently of it.
final class SecureEnclaveHardwareKeyWrapFactory: HardwareKeyWrapFactory {

    private static let keyType = kSecAttrKeyTypeECSECPrimeRandom
    private static let keySizeInBits = 256

    /// Generates a fresh Secure Enclave key for `keyAlias` (any existing key
    /// under that alias is deleted first) and returns a wrapper around it.
    ///
    /// - Throws: `HardwareKeyWrapError.secureEnclaveUnavailable` if this
    ///   device/environment has no Secure Enclave (always true in the
    ///   Simulator — there is no SE hardware to emulate, unlike Android's
    ///   emulator which can still produce a *software*-backed Keystore key).
    ///   Callers MUST NOT fall back to a software-backed key silently; the
    ///   caller should require a passphrase on this device instead, or
    ///   refuse to create the vault.
    func createNew(keyAlias: String) throws -> HardwareKeyWrap {
        deleteKey(alias: keyAlias)

        var accessControlError: Unmanaged<CFError>?
        guard let accessControl = SecAccessControlCreateWithFlags(
            kCFAllocatorDefault,
            kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
            [], // deliberately no .biometryCurrentSet/.userPresence — see class doc
            &accessControlError
        ) else {
            let message = accessControlError.map { String(describing: $0.takeRetainedValue()) } ?? "SecAccessControlCreateWithFlags failed"
            throw HardwareKeyWrapError.secureEnclaveUnavailable(message: message)
        }

        let attributes: [String: Any] = [
            kSecAttrKeyType as String: Self.keyType,
            kSecAttrKeySizeInBits as String: Self.keySizeInBits,
            kSecAttrTokenID as String: kSecAttrTokenIDSecureEnclave,
            kSecPrivateKeyAttrs as String: [
                kSecAttrIsPermanent as String: true,
                kSecAttrApplicationTag as String: tag(for: keyAlias),
                kSecAttrAccessControl as String: accessControl,
            ],
        ]

        var error: Unmanaged<CFError>?
        guard let privateKey = SecKeyCreateRandomKey(attributes as CFDictionary, &error) else {
            let message = error.map { String(describing: $0.takeRetainedValue()) } ?? "SecKeyCreateRandomKey failed"
            throw HardwareKeyWrapError.secureEnclaveUnavailable(message: message)
        }

        return SecureEnclaveHardwareKeyWrap(privateKey: privateKey, isHardwareBacked: true)
    }

    /// Loads an already-created Secure Enclave key (e.g. on vault unlock,
    /// after `createNew` ran once before).
    ///
    /// - Throws: `HardwareKeyWrapError.keyLost` if the key no longer exists
    ///   in the Keychain (device restore/factory reset, which clears the
    ///   Secure Enclave) — the scenario the recovery key exists to rescue.
    func forExisting(keyAlias: String) throws -> HardwareKeyWrap {
        guard let privateKey = try loadKey(alias: keyAlias) else {
            throw HardwareKeyWrapError.keyLost(keyAlias: keyAlias)
        }
        return SecureEnclaveHardwareKeyWrap(privateKey: privateKey, isHardwareBacked: true)
    }

    // MARK: - Keychain helpers

    /// `kSecAttrApplicationTag` for `alias` — documented and stable (see
    /// class doc): changing this format orphans every already-created
    /// vault's Secure Enclave key, indistinguishable from `.keyLost`.
    private func tag(for alias: String) -> Data {
        Data("xyz.libravault.vaultstore.se.\(alias)".utf8)
    }

    private func loadKey(alias: String) throws -> SecKey? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassKey,
            kSecAttrApplicationTag as String: tag(for: alias),
            kSecAttrKeyType as String: Self.keyType,
            kSecReturnRef as String: true,
        ]
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        switch status {
        case errSecSuccess:
            // swiftlint:disable:next force_cast — kSecReturnRef + kSecClassKey
            // guarantees a SecKey back on success; there is no other type
            // SecItemCopyMatching could hand back for this query shape.
            return (item as! SecKey)
        case errSecItemNotFound:
            return nil
        default:
            throw HardwareKeyWrapError.keychainError(status: status)
        }
    }

    /// Best-effort delete of any existing key under `alias`. Mirrors
    /// Android `create()`'s "any existing key under that alias is
    /// replaced" contract — deliberately not surfaced as a throwing call:
    /// a missing key to delete is not a failure here.
    private func deleteKey(alias: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassKey,
            kSecAttrApplicationTag as String: tag(for: alias),
            kSecAttrKeyType as String: Self.keyType,
        ]
        SecItemDelete(query as CFDictionary)
    }
}
