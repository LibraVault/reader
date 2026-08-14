package xyz.libravault.core.vaultcrypto

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class Argon2idKdfTest {

    // Small params — this test only needs to prove correctness properties, not
    // benchmark real-world latency (that was done on-device; see PRD §8.4b).
    private val fastParams = Argon2Params(memoryKiB = 8 * 1024, iterations = 1, parallelism = 1)
    private val salt = ByteArray(VaultFormat.ARGON2_SALT_SIZE_BYTES) { it.toByte() }

    @Test
    fun `is deterministic - same pin, salt, and params derive the same key`() {
        val k1 = Argon2idKdf.deriveKey("1234".toCharArray(), salt, fastParams)
        val k2 = Argon2idKdf.deriveKey("1234".toCharArray(), salt, fastParams)
        assertEquals(k1.toList(), k2.toList())
    }

    @Test
    fun `different PINs derive different keys`() {
        val k1 = Argon2idKdf.deriveKey("1234".toCharArray(), salt, fastParams)
        val k2 = Argon2idKdf.deriveKey("4321".toCharArray(), salt, fastParams)
        assertFalse(k1.contentEquals(k2))
    }

    @Test
    fun `different salts derive different keys for the same PIN`() {
        val salt2 = ByteArray(VaultFormat.ARGON2_SALT_SIZE_BYTES) { (it + 1).toByte() }
        val k1 = Argon2idKdf.deriveKey("1234".toCharArray(), salt, fastParams)
        val k2 = Argon2idKdf.deriveKey("1234".toCharArray(), salt2, fastParams)
        assertFalse(k1.contentEquals(k2))
    }

    @Test
    fun `respects requested output length`() {
        val key = Argon2idKdf.deriveKey("1234".toCharArray(), salt, fastParams, outputLengthBytes = 16)
        assertEquals(16, key.size)
    }
}
