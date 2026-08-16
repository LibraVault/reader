import Foundation
import Security

/// A key wrapped (encrypted) under another key: the nonce used plus the ciphertext+tag.
struct WrappedKey: Equatable {
    let nonce: Data
    let ciphertext: Data
}

/// Wraps/unwraps a key (typically the Vault Master Key) under a wrapping key
/// (a KEK derived from a PIN, or a recovery key) using AES-256-GCM with a fresh
/// random nonce per wrap operation.
///
/// Random nonces are fine here - unlike chunk content encryption (see
/// `deriveNonce`), a wrap only happens once per vault-creation or PIN-change
/// event, never per-chunk in a hot loop, so there's no meaningful reuse risk to
/// design around and no benefit to a deterministic scheme.
enum KeyWrap {

    static func wrap(wrappingKey: Data, plaintextKey: Data, aad: Data) throws -> WrappedKey {
        var nonceBytes = [UInt8](repeating: 0, count: VaultFormat.nonceSizeBytes)
        let status = SecRandomCopyBytes(kSecRandomDefault, nonceBytes.count, &nonceBytes)
        guard status == errSecSuccess else {
            throw VaultCryptoError.randomGenerationFailed(status: status)
        }
        let nonce = Data(nonceBytes)
        let ciphertext = try AesGcmCipher.encrypt(key: wrappingKey, nonce: nonce, aad: aad, plaintext: plaintextKey)
        return WrappedKey(nonce: nonce, ciphertext: ciphertext)
    }

    /// - Throws: `VaultCryptoError.authenticationFailed` if `wrappingKey` is wrong or `wrapped`/`aad` was tampered with.
    static func unwrap(wrappingKey: Data, wrapped: WrappedKey, aad: Data) throws -> Data {
        try AesGcmCipher.decrypt(key: wrappingKey, nonce: wrapped.nonce, aad: aad, ciphertextWithTag: wrapped.ciphertext)
    }
}
