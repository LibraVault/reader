import Foundation
import CryptoKit

/// AES-256-GCM encrypt/decrypt over CryptoKit, matching Android's
/// `AesGcmCipher.kt` wire format: the encrypted output (and expected decrypt
/// input) is `ciphertext || tag` concatenated into one blob, exactly what
/// `javax.crypto.Cipher`'s GCM mode produces from `doFinal`. CryptoKit's
/// `AES.GCM.SealedBox` keeps ciphertext and tag as separate properties, so
/// this type does the concatenation/splitting to preserve that wire format.
///
/// Unlike the JVM side (which keeps one `Cipher` instance around because
/// `Cipher.getInstance` is a measurably expensive provider lookup - see the
/// Kotlin doc comment), CryptoKit's `AES.GCM` namespace has no equivalent
/// per-call setup cost to amortize, so this type carries no mutable state at
/// all and every call is independently thread-safe.
enum AesGcmCipher {

    static func encrypt(key: Data, nonce: Data, aad: Data, plaintext: Data) throws -> Data {
        let symmetricKey = SymmetricKey(data: key)
        let gcmNonce = try AES.GCM.Nonce(data: nonce)
        let sealed = try AES.GCM.seal(plaintext, using: symmetricKey, nonce: gcmNonce, authenticating: aad)
        return sealed.ciphertext + sealed.tag
    }

    /// - Throws: `VaultCryptoError.authenticationFailed` if the tag doesn't verify - wrong key or tampered data.
    static func decrypt(key: Data, nonce: Data, aad: Data, ciphertextWithTag: Data) throws -> Data {
        guard ciphertextWithTag.count >= VaultFormat.tagSizeBytes else {
            throw VaultCryptoError.authenticationFailed
        }
        let tagStart = ciphertextWithTag.index(ciphertextWithTag.endIndex, offsetBy: -VaultFormat.tagSizeBytes)
        let ciphertext = ciphertextWithTag[ciphertextWithTag.startIndex..<tagStart]
        let tag = ciphertextWithTag[tagStart...]

        let symmetricKey = SymmetricKey(data: key)
        do {
            let gcmNonce = try AES.GCM.Nonce(data: nonce)
            let sealedBox = try AES.GCM.SealedBox(nonce: gcmNonce, ciphertext: ciphertext, tag: tag)
            return try AES.GCM.open(sealedBox, using: symmetricKey, authenticating: aad)
        } catch {
            // Any failure here (bad tag, malformed nonce/box) is a tamper/wrong-key
            // signal - deliberately not distinguished further, same as the JVM side.
            throw VaultCryptoError.authenticationFailed
        }
    }
}

/// Deterministic nonce derivation (PRD §8.2 point 5): nonce for chunk `chunkIndex`
/// of a file = the first 12 bytes of HMAC-SHA256(fileContentKey, "vaultcrypto-nonce-v1" || chunkIndex).
///
/// Deliberately NOT random. A random 96-bit nonce drawn from a CSPRNG is only
/// *improbably* unique; deriving it from a PRF keyed by a value already unique
/// per file makes reuse structurally impossible: a different chunk index within
/// a file gives a different nonce, guaranteed, and a different file has a
/// different key entirely, so its nonce space never overlaps this one's.
func deriveNonce(fileContentKey: Data, chunkIndex: Int64) -> Data {
    let key = SymmetricKey(data: fileContentKey)
    var message = Data("vaultcrypto-nonce-v1".utf8)
    message.append(contentsOf: BigEndian.bytes(ofInt64: chunkIndex))
    let mac = HMAC<SHA256>.authenticationCode(for: message, using: key)
    return Data(Data(mac).prefix(VaultFormat.nonceSizeBytes))
}
