package xyz.libravault.core.vaultcrypto

import java.security.SecureRandom

/** A key wrapped (encrypted) under another key: the nonce used plus the ciphertext+tag. */
data class WrappedKey(val nonce: ByteArray, val ciphertext: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is WrappedKey && nonce.contentEquals(other.nonce) && ciphertext.contentEquals(other.ciphertext)

    override fun hashCode(): Int = 31 * nonce.contentHashCode() + ciphertext.contentHashCode()
}

/**
 * Wraps/unwraps a key (typically the Vault Master Key) under a wrapping key
 * (a KEK derived from a PIN, or a recovery key) using AES-256-GCM with a fresh
 * random nonce per wrap operation.
 *
 * Random nonces are fine here — unlike chunk content encryption (see
 * [deriveNonce]), a wrap only happens once per vault-creation or PIN-change
 * event, never per-chunk in a hot loop, so there's no meaningful reuse risk to
 * design around and no benefit to a deterministic scheme.
 */
internal object KeyWrap {

    private val random = SecureRandom()

    fun wrap(wrappingKey: ByteArray, plaintextKey: ByteArray, aad: ByteArray): WrappedKey {
        val nonce = ByteArray(VaultFormat.NONCE_SIZE_BYTES).also { random.nextBytes(it) }
        val ciphertext = AesGcmCipher().encrypt(wrappingKey, nonce, aad, plaintextKey)
        return WrappedKey(nonce, ciphertext)
    }

    /** @throws VaultAuthenticationException if [wrappingKey] is wrong or [wrapped]/[aad] was tampered with. */
    fun unwrap(wrappingKey: ByteArray, wrapped: WrappedKey, aad: ByteArray): ByteArray =
        AesGcmCipher().decrypt(wrappingKey, wrapped.nonce, aad, wrapped.ciphertext)
}
