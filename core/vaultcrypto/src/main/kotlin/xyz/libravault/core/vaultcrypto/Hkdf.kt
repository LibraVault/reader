package xyz.libravault.core.vaultcrypto

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * RFC 5869 HKDF (Extract-then-Expand) over HMAC-SHA256.
 *
 * Used to derive two independent things from the Vault Master Key:
 *  - a per-file content key (so compromising one file's key doesn't help
 *    attack another file or the VMK itself — PRD §8.2 point 3)
 *  - the deterministic per-chunk nonce (PRD §8.2 point 5 — never random,
 *    so a nonce collision under a fixed key is structurally impossible
 *    rather than merely improbable)
 *
 * The VMK is already 256 bits of [java.security.SecureRandom] output, so
 * skipping HKDF-Extract (using it directly as the PRK) would be defensible —
 * but doing the full textbook Extract-then-Expand costs nothing here and
 * avoids having to justify the shortcut.
 */
internal object Hkdf {

    private const val ALGORITHM = "HmacSHA256"
    private const val HASH_LEN = 32

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(SecretKeySpec(key, ALGORITHM))
        return mac.doFinal(data)
    }

    /**
     * HKDF-Extract (RFC 5869 §2.2).
     *
     * An empty [salt] is substituted with HashLen zero bytes, as the RFC
     * requires ("if not provided, it is set to a string of HashLen zeros").
     * Without this, [javax.crypto.spec.SecretKeySpec] rejects a zero-length key
     * with `IllegalArgumentException: Empty key` and the call throws instead of
     * deriving — which is what RFC 5869 test vector A.3 caught when
     * [HkdfKnownAnswerTest] was added.
     *
     * This is a spec-conformance fix, not a behaviour change for this app: the
     * only production caller ([deriveFileContentKey]) passes a fixed non-empty
     * salt, and the empty-salt path previously threw rather than returning a
     * value, so no stored key material can depend on the old behaviour. The
     * golden vault fixture proves no existing derivation moved.
     *
     * (iOS never had this gap — it uses `CryptoKit.HKDF`, which implements the
     * substitution itself.)
     */
    fun extract(salt: ByteArray, ikm: ByteArray): ByteArray =
        hmac(if (salt.isEmpty()) ByteArray(HASH_LEN) else salt, ikm)

    fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val output = ByteArray(length)
        var previous = ByteArray(0)
        var generated = 0
        var counter: Byte = 1
        while (generated < length) {
            val mac = Mac.getInstance(ALGORITHM)
            mac.init(SecretKeySpec(prk, ALGORITHM))
            mac.update(previous)
            mac.update(info)
            mac.update(counter)
            previous = mac.doFinal()
            val toCopy = minOf(HASH_LEN, length - generated)
            System.arraycopy(previous, 0, output, generated, toCopy)
            generated += toCopy
            counter = (counter + 1).toByte()
        }
        return output
    }

    /** Convenience: derive [length] bytes from [ikm] in one call. */
    fun deriveKey(salt: ByteArray, ikm: ByteArray, info: ByteArray, length: Int): ByteArray =
        expand(extract(salt, ikm), info, length)
}
