package xyz.libravault.core.vaultcrypto

/**
 * On-disk format constants for Encrypted Vaults chunked content encryption.
 *
 * See the Encrypted Vaults PRD §8.2/§8.2b for the design this implements:
 *  - Fixed-size plaintext chunks, each an independent AES-256-GCM message.
 *  - A deterministic per-chunk nonce (never random — see [deriveNonce]).
 *  - Per-chunk associated data binds the chunk index, the final-chunk flag,
 *    AND the header fields (file id, total plaintext length, chunk size,
 *    format version, cipher id) — so tampering with any header field, or
 *    truncating the ciphertext (even exactly on a chunk boundary), breaks
 *    AEAD verification rather than silently succeeding. This is the fix for
 *    the truncation gap found in the Phase 0 review (implementation plan §A.1):
 *    binding the chunk index alone stops reordering/splicing, but not
 *    truncation, because a shortened file still has every remaining chunk
 *    individually valid. Binding the total length into every chunk's AAD
 *    means a truncated or length-tampered file fails at the very first chunk.
 *
 * [FORMAT_VERSION] must be parsed and rejected-if-unknown from day one — this
 * is what lets future changes to chunk size, cipher, or KDF parameters ship
 * without breaking vaults already on disk (implementation plan §A.2).
 */
object VaultFormat {

    /** Bump on any incompatible change to header layout, AAD construction, or defaults. */
    const val FORMAT_VERSION: Byte = 1

    /** Cipher ids — stored per-vault so a future vault could use a different one
     * without requiring a global migration. AES-256-GCM chosen 2026-08-14 after
     * on-device measurement showed it decrypts faster than ChaCha20-Poly1305 on
     * both a budget and flagship device (PRD §8.3 — supersedes the original pick). */
    const val CIPHER_AES_256_GCM: Byte = 1

    /** KDF ids, for the same forward-compatibility reason as [CIPHER_AES_256_GCM]. */
    const val KDF_ARGON2ID: Byte = 1

    /** 32 KiB — validated on-device in the Phase 0 spike (implementation plan
     * §D.0.RESULTS): this exact size is what let a PDF proxy-fd open in ~100ms
     * and decrypt only ~200 of ~1200 chunks to render two pages of a 39MB file. */
    const val DEFAULT_CHUNK_SIZE: Int = 32 * 1024

    /** AES-GCM authentication tag size in bytes. */
    const val TAG_SIZE_BYTES: Int = 16

    /** AES-GCM nonce size in bytes (96 bits, the standard/recommended size). */
    const val NONCE_SIZE_BYTES: Int = 12

    /** Vault Master Key size in bytes (AES-256). */
    const val VMK_SIZE_BYTES: Int = 32

    /** Recovery key size in bytes — same size as the VMK it wraps. */
    const val RECOVERY_KEY_SIZE_BYTES: Int = 32

    /** Argon2id salt size in bytes. */
    const val ARGON2_SALT_SIZE_BYTES: Int = 16

    /**
     * Fixed-length identifier for a single encrypted file within a vault.
     * 16 bytes — large enough to be collision-free when randomly generated,
     * independent of any Android-specific ID scheme (Room row ids, etc.),
     * which belongs to core:vaultstore (Phase 2), not this module.
     */
    const val FILE_ID_SIZE_BYTES: Int = 16

    /**
     * Per-file blob header layout, in order: format version (1) + cipher id (1)
     * + file id (16) + chunk size (4) + total plaintext length (8) = 30 bytes.
     * Unencrypted — it has to be readable before the VMK is available, since it
     * tells the reader how to derive/apply keys in the first place — but every
     * field in it is bound into every chunk's AEAD tag via [chunkAad], so
     * tampering with any of it invalidates every chunk's authentication rather
     * than being trusted at face value.
     */
    const val HEADER_SIZE_BYTES: Int = 1 + 1 + FILE_ID_SIZE_BYTES + 4 + 8

    /** Chunk size ceiling for header validation. Generous headroom above
     * [DEFAULT_CHUNK_SIZE] while still catching a corrupted/malicious header
     * field before it turns into a huge allocation ([VaultFileReader] sizes its
     * per-chunk buffer directly from this value). */
    const val MAX_REASONABLE_CHUNK_SIZE: Int = 16 * 1024 * 1024

    /**
     * Builds the associated data bound into every chunk's AEAD tag.
     *
     * Binding the header fields (not just the chunk index) into every single
     * chunk's AAD — rather than authenticating them once, separately — means
     * there is no separate "header integrity" mechanism to get wrong: corrupting
     * ANY of these fields fails AEAD verification on literally the first chunk
     * decrypted. This is deliberately simpler than a detached header signature.
     *
     * [formatVersion] and [cipherId] are taken as explicit parameters — NOT read
     * from this object's own [FORMAT_VERSION]/[CIPHER_AES_256_GCM] constants —
     * specifically so a future build that supports reading multiple format
     * versions computes AAD from what a given file's header actually says, not
     * from whatever the current build happens to default to. [ChunkedVaultWriter]
     * always passes the current constants (it only ever writes the newest
     * format); [VaultFileReader] passes what it parsed from the file.
     */
    fun chunkAad(
        formatVersion: Byte,
        cipherId: Byte,
        fileId: ByteArray,
        totalPlaintextLength: Long,
        chunkSize: Int,
        chunkIndex: Long,
        isFinalChunk: Boolean,
    ): ByteArray {
        require(fileId.size == FILE_ID_SIZE_BYTES) { "fileId must be $FILE_ID_SIZE_BYTES bytes" }
        val buf = java.nio.ByteBuffer.allocate(
            1 + 1 + 4 + FILE_ID_SIZE_BYTES + 8 + 8 + 1,
        )
        buf.put(formatVersion)
        buf.put(cipherId)
        buf.putInt(chunkSize)
        buf.put(fileId)
        buf.putLong(totalPlaintextLength)
        buf.putLong(chunkIndex)
        buf.put(if (isFinalChunk) 1 else 0)
        return buf.array()
    }
}

/** Base type for every error this module can throw. Callers should catch this,
 * not [Exception], to distinguish vault-format problems from unrelated bugs. */
sealed class VaultCryptoException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** AEAD verification failed — wrong key (wrong PIN/recovery key), OR the
 * ciphertext/header was tampered with (bit-flip, truncation, splice, reorder).
 * Deliberately not distinguished further: an oracle that says "your PIN was
 * wrong" vs. "the file was tampered with" is itself a side channel. */
class VaultAuthenticationException(cause: Throwable? = null) :
    VaultCryptoException("Decryption failed: wrong key or corrupted/tampered data", cause)

/** The stored format version is newer/unrecognized. Must fail closed and
 * cleanly (implementation plan §A.2) rather than misinterpreting the bytes. */
class UnsupportedVaultFormatException(val foundVersion: Byte) :
    VaultCryptoException("Unsupported vault format version: $foundVersion (this build supports ${VaultFormat.FORMAT_VERSION})")

/** The stored cipher id is not one this build implements. */
class UnsupportedCipherException(val foundCipherId: Byte) :
    VaultCryptoException("Unsupported cipher id: $foundCipherId")

/** Fewer bytes were available on disk than the header's authenticated total
 * length requires — a chunk-boundary-aligned truncation caught at the I/O
 * layer, distinct from [VaultAuthenticationException] (which is a cryptographic
 * verification failure). Both are "the file was tampered with," reported
 * separately only because they're detected at different layers. */
class VaultTruncatedException(expectedBytes: Long, actualBytes: Long) :
    VaultCryptoException("Vault file truncated: expected at least $expectedBytes bytes, found $actualBytes")

/** A structurally invalid header field (e.g. `chunkSize <= 0`, an unreasonably
 * huge `chunkSize`, or a negative total length) — caught by validation before
 * any decryption is attempted. Found during PR review: without this, a
 * corrupted `chunkSize == 0` field crashed with an unhandled
 * [ArithmeticException] (division by zero) instead of failing cleanly like
 * every other tamper case this module defends against. */
class MalformedVaultHeaderException(message: String) : VaultCryptoException(message)
