import Foundation
import CryptoKit

// Fixed application-specific HKDF salt. Not a secret - HKDF salts don't need to
// be, their purpose is domain separation, not confidentiality.
private let fileContentHkdfSalt = Data("vaultcrypto:hkdf-salt:v1".utf8)
private let fileContentInfoPrefix = "vaultcrypto:file-content:v1"

/// Derives a per-file content key from the Vault Master Key (PRD §8.2 point 3).
///
/// Derived, never stored: compromising one file's key (e.g. via some future
/// side-channel specific to how a decrypted file is handled downstream) does not
/// help an attacker derive another file's key or the VMK itself, since HKDF is
/// one-way.
///
/// Uses `CryptoKit.HKDF` directly rather than a hand-rolled RFC 5869
/// implementation (which is what the Android side has, since the JVM has no
/// built-in HKDF) - it's the same Extract-then-Expand construction over
/// HMAC-SHA256, so the derived key is identical either way.
func deriveFileContentKey(vmk: Data, fileId: Data) -> Data {
    precondition(fileId.count == VaultFormat.fileIdSizeBytes, "fileId must be \(VaultFormat.fileIdSizeBytes) bytes")
    let info = Data(fileContentInfoPrefix.utf8) + fileId
    let key = HKDF<SHA256>.deriveKey(
        inputKeyMaterial: SymmetricKey(data: vmk),
        salt: fileContentHkdfSalt,
        info: info,
        outputByteCount: VaultFormat.vmkSizeBytes
    )
    return key.withUnsafeBytes { Data($0) }
}
