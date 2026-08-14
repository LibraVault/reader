package xyz.libravault.core.vaultcrypto

import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * A single reusable AES/GCM [Cipher] instance.
 *
 * **Not thread-safe** — one instance per thread/reader, matching how
 * [VaultFileReader] and the chunked writer use it. This exists specifically
 * because the Phase 0 spike measured [Cipher.getInstance] being called once
 * per 32 KiB chunk, which is a provider lookup on every call and measurably
 * hurt its own throughput numbers (implementation plan §D.0.RESULTS.1).
 * `cipher.init(...)` is cheap to call repeatedly; `Cipher.getInstance(...)` is
 * not. Production code MUST get the [Cipher] once and re-init it per chunk —
 * this class exists to make that the only way to use it.
 */
internal class AesGcmCipher {

    private val cipher: Cipher = Cipher.getInstance("AES/GCM/NoPadding")

    fun encrypt(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray {
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(VaultFormat.TAG_SIZE_BYTES * 8, nonce),
        )
        cipher.updateAAD(aad)
        return cipher.doFinal(plaintext)
    }

    /** @throws VaultAuthenticationException if the tag doesn't verify — wrong key or tampered data. */
    fun decrypt(key: ByteArray, nonce: ByteArray, aad: ByteArray, ciphertextWithTag: ByteArray): ByteArray {
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(VaultFormat.TAG_SIZE_BYTES * 8, nonce),
        )
        cipher.updateAAD(aad)
        return try {
            cipher.doFinal(ciphertextWithTag)
        } catch (e: AEADBadTagException) {
            throw VaultAuthenticationException(e)
        } catch (e: javax.crypto.BadPaddingException) {
            // Some JCA providers throw BadPaddingException rather than the AEAD-specific
            // subclass for a failed GCM tag check — treat identically.
            throw VaultAuthenticationException(e)
        }
    }
}

/**
 * Deterministic nonce derivation (PRD §8.2 point 5): nonce for chunk [chunkIndex]
 * of a file = the first 12 bytes of HMAC-SHA256([fileContentKey], chunkIndex).
 *
 * Deliberately NOT random. A random 96-bit nonce drawn from a CSPRNG is only
 * *improbably* unique; deriving it from a PRF keyed by a value already unique
 * per file makes reuse structurally impossible: a different chunk index within
 * a file gives a different nonce, guaranteed, and a different file has a
 * different key entirely, so its nonce space never overlaps this one's.
 */
internal fun deriveNonce(fileContentKey: ByteArray, chunkIndex: Long): ByteArray {
    val mac = javax.crypto.Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(fileContentKey, "HmacSHA256"))
    mac.update("vaultcrypto-nonce-v1".toByteArray(Charsets.US_ASCII))
    mac.update(java.nio.ByteBuffer.allocate(8).putLong(chunkIndex).array())
    return mac.doFinal().copyOf(VaultFormat.NONCE_SIZE_BYTES)
}
