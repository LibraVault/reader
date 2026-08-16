import Foundation

/// On-disk format constants for Encrypted Vaults chunked content encryption.
///
/// This is the Swift port of Android's `core/vaultcrypto/VaultFormat.kt` —
/// every constant, byte layout, and the AAD construction below are bit-for-bit
/// identical to that module, so a vault's on-disk bytes are structurally
/// interchangeable between the Android and iOS apps (only the *key wrapping*
/// layer above this differs, since the Android Keystore / Secure Enclave
/// hardware wrap is platform-specific).
///
/// See the Encrypted Vaults PRD §8.2/§8.2b for the design this implements:
///  - Fixed-size plaintext chunks, each an independent AES-256-GCM message.
///  - A deterministic per-chunk nonce (never random - see `deriveNonce`).
///  - Per-chunk associated data binds the chunk index, the final-chunk flag,
///    AND the header fields (file id, total plaintext length, chunk size,
///    format version, cipher id) - so tampering with any header field, or
///    truncating the ciphertext (even exactly on a chunk boundary), breaks
///    AEAD verification rather than silently succeeding. Binding the chunk
///    index alone stops reordering/splicing, but not truncation, because a
///    shortened file still has every remaining chunk individually valid.
///    Binding the total length into every chunk's AAD means a truncated or
///    length-tampered file fails at the very first chunk.
///
/// `formatVersion` must be parsed and rejected-if-unknown from day one - this
/// is what lets future changes to chunk size, cipher, or KDF parameters ship
/// without breaking vaults already on disk.
enum VaultFormat {

    /// Bump on any incompatible change to header layout, AAD construction, or defaults.
    static let formatVersion: UInt8 = 1

    /// Cipher ids - stored per-vault so a future vault could use a different one
    /// without requiring a global migration. AES-256-GCM chosen 2026-08-14 after
    /// on-device measurement showed it decrypts faster than ChaCha20-Poly1305 on
    /// both a budget and flagship Android device (PRD §8.3).
    static let cipherAes256Gcm: UInt8 = 1

    /// KDF ids, for the same forward-compatibility reason as `cipherAes256Gcm`.
    static let kdfArgon2id: UInt8 = 1

    /// 32 KiB - validated on-device in the Phase 0 spike (Android implementation
    /// plan §D.0.RESULTS): this exact size is what let a PDF proxy-fd open in
    /// ~100ms and decrypt only ~200 of ~1200 chunks to render two pages of a
    /// 39MB file.
    static let defaultChunkSize: Int = 32 * 1024

    /// AES-GCM authentication tag size in bytes.
    static let tagSizeBytes: Int = 16

    /// AES-GCM nonce size in bytes (96 bits, the standard/recommended size).
    static let nonceSizeBytes: Int = 12

    /// Vault Master Key size in bytes (AES-256).
    static let vmkSizeBytes: Int = 32

    /// Recovery key size in bytes - same size as the VMK it wraps.
    static let recoveryKeySizeBytes: Int = 32

    /// Argon2id salt size in bytes.
    static let argon2SaltSizeBytes: Int = 16

    /// Fixed-length identifier for a single encrypted file within a vault.
    /// 16 bytes - large enough to be collision-free when randomly generated,
    /// independent of any platform-specific id scheme.
    static let fileIdSizeBytes: Int = 16

    /// Per-file blob header layout, in order: format version (1) + cipher id (1)
    /// + file id (16) + chunk size (4) + total plaintext length (8) = 30 bytes.
    /// Unencrypted - it has to be readable before the VMK is available, since it
    /// tells the reader how to derive/apply keys in the first place - but every
    /// field in it is bound into every chunk's AEAD tag via `chunkAad`, so
    /// tampering with any of it invalidates every chunk's authentication rather
    /// than being trusted at face value.
    static let headerSizeBytes: Int = 1 + 1 + fileIdSizeBytes + 4 + 8

    /// Chunk size ceiling for header validation. Generous headroom above
    /// `defaultChunkSize` while still catching a corrupted/malicious header
    /// field before it turns into a huge allocation.
    static let maxReasonableChunkSize: Int = 16 * 1024 * 1024

    /// Builds the associated data bound into every chunk's AEAD tag.
    ///
    /// Binding the header fields (not just the chunk index) into every single
    /// chunk's AAD - rather than authenticating them once, separately - means
    /// there is no separate "header integrity" mechanism to get wrong: corrupting
    /// ANY of these fields fails AEAD verification on literally the first chunk
    /// decrypted. This is deliberately simpler than a detached header signature.
    ///
    /// `formatVersion` and `cipherId` are taken as explicit parameters - NOT read
    /// from this enum's own `formatVersion`/`cipherAes256Gcm` constants -
    /// specifically so a future build that supports reading multiple format
    /// versions computes AAD from what a given file's header actually says, not
    /// from whatever the current build happens to default to. `ChunkedVaultWriter`
    /// always passes the current constants (it only ever writes the newest
    /// format); `VaultFileReader` passes what it parsed from the file.
    static func chunkAad(
        formatVersion: UInt8,
        cipherId: UInt8,
        fileId: Data,
        totalPlaintextLength: Int64,
        chunkSize: Int32,
        chunkIndex: Int64,
        isFinalChunk: Bool
    ) -> Data {
        precondition(fileId.count == fileIdSizeBytes, "fileId must be \(fileIdSizeBytes) bytes")
        var buf = Data(capacity: 1 + 1 + 4 + fileIdSizeBytes + 8 + 8 + 1)
        buf.append(formatVersion)
        buf.append(cipherId)
        buf.append(contentsOf: BigEndian.bytes(ofUInt32: UInt32(bitPattern: chunkSize)))
        buf.append(fileId)
        buf.append(contentsOf: BigEndian.bytes(ofInt64: totalPlaintextLength))
        buf.append(contentsOf: BigEndian.bytes(ofInt64: chunkIndex))
        buf.append(isFinalChunk ? 1 : 0)
        return buf
    }
}
