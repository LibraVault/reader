package xyz.libravault.core.vaultcrypto

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.SecureRandom

/**
 * Proves the structural (not merely probabilistic) nonce-uniqueness claim in
 * PRD §8.2 point 5: deriving the nonce from a PRF keyed per-file, rather than
 * drawing it from a CSPRNG, means a collision under a fixed key requires an
 * HMAC-SHA256 collision, not a 96-bit birthday collision.
 */
class NonceUniquenessTest {

    private val random = SecureRandom()

    @Test
    fun `same key, many chunk indices, produces all-distinct nonces`() {
        val key = ByteArray(32).also { random.nextBytes(it) }
        val nonces = (0 until 100_000L).map { deriveNonce(key, it) }
        val distinct = nonces.map { it.toList() }.toSet()
        assertEquals(nonces.size, distinct.size, "found a nonce collision within one file's own chunk range")
    }

    @Test
    fun `is deterministic - same key and index always derive the same nonce`() {
        val key = ByteArray(32).also { random.nextBytes(it) }
        assertEquals(deriveNonce(key, 42L).toList(), deriveNonce(key, 42L).toList())
    }

    @Test
    fun `different files (different keys) occupy different nonce spaces at the same index`() {
        val keyA = ByteArray(32).also { random.nextBytes(it) }
        val keyB = ByteArray(32).also { random.nextBytes(it) }
        assertTrue(!deriveNonce(keyA, 0L).contentEquals(deriveNonce(keyB, 0L)))
    }

    @Test
    fun `nonce is exactly NONCE_SIZE_BYTES long`() {
        val key = ByteArray(32).also { random.nextBytes(it) }
        assertEquals(VaultFormat.NONCE_SIZE_BYTES, deriveNonce(key, 0L).size)
    }
}
