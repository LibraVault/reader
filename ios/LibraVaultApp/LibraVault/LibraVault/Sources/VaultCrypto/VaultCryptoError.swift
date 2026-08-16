import Foundation

/// Every error this module can throw. Callers should catch this type, not a
/// bare `Error`, to distinguish vault-format problems from unrelated bugs —
/// mirrors Android core:vaultcrypto's `VaultCryptoException` hierarchy.
enum VaultCryptoError: Error, Equatable {

    /// AEAD verification failed - wrong key (wrong PIN/recovery key), OR the
    /// ciphertext/header was tampered with (bit-flip, truncation, splice,
    /// reorder). Deliberately not distinguished further: an oracle that says
    /// "your PIN was wrong" vs. "the file was tampered with" is itself a
    /// side channel.
    case authenticationFailed

    /// The stored format version is newer/unrecognized. Fails closed rather
    /// than misinterpreting the bytes.
    case unsupportedFormatVersion(found: UInt8)

    /// The stored cipher id is not one this build implements.
    case unsupportedCipher(found: UInt8)

    /// Fewer bytes were available on disk than the header's authenticated
    /// total length requires - a chunk-boundary-aligned truncation caught at
    /// the I/O layer, distinct from `.authenticationFailed` (a cryptographic
    /// verification failure). Both are "the file was tampered with," reported
    /// separately only because they're detected at different layers.
    case truncated(expectedBytes: Int64, actualBytes: Int64)

    /// A structurally invalid header field (e.g. `chunkSize <= 0`, an
    /// unreasonably huge `chunkSize`, or a negative total length) - caught by
    /// validation before any decryption is attempted, so a corrupted header
    /// fails cleanly instead of crashing on an unguarded division/allocation.
    case malformedHeader(String)

    /// The underlying Argon2 C call reported a non-zero error code (e.g. an
    /// invalid parameter combination). Has no Android equivalent - BouncyCastle's
    /// Argon2BytesGenerator doesn't surface this as a distinct failure mode,
    /// but the vendored C API can.
    case argon2Failed(code: Int32, message: String)

    /// `SecRandomCopyBytes` reported failure while generating a random nonce or
    /// key. Effectively unrecoverable (no entropy source available) - has no
    /// Android equivalent, since `java.security.SecureRandom` has no failure
    /// mode to mirror here.
    case randomGenerationFailed(status: Int32)

    /// An `InputStream`/`OutputStream` read or write failed for a reason other
    /// than the stream simply running out of bytes (which is
    /// `.truncated`/a precondition trap instead, matching Android's
    /// `check`/`IOException` split). Has no direct Android equivalent - the
    /// JVM's `IOException` propagates on its own there; Foundation's stream
    /// APIs report failure via a return code instead, so this wraps that.
    case ioError(String)
}

extension VaultCryptoError: LocalizedError {
    var errorDescription: String? {
        switch self {
        case .authenticationFailed:
            return "Decryption failed: wrong key or corrupted/tampered data"
        case .unsupportedFormatVersion(let found):
            return "Unsupported vault format version: \(found) (this build supports \(VaultFormat.formatVersion))"
        case .unsupportedCipher(let found):
            return "Unsupported cipher id: \(found)"
        case .truncated(let expected, let actual):
            return "Vault file truncated: expected at least \(expected) bytes, found \(actual)"
        case .malformedHeader(let message):
            return message
        case .argon2Failed(let code, let message):
            return "Argon2id key derivation failed (code \(code)): \(message)"
        case .randomGenerationFailed(let status):
            return "Secure random generation failed (OSStatus \(status))"
        case .ioError(let message):
            return message
        }
    }
}
