package xyz.libravault.core.vaultcrypto

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Direct tests for [deriveFileContentKey], which had no test file of its own
 * (docs/TEST_COVERAGE_PRD.md, S1) — it was only ever exercised through vault
 * round-trips.
 *
 * The expected key below is the value both platforms must produce. Android
 * hand-rolls RFC 5869 ([Hkdf]); iOS calls `CryptoKit.HKDF<SHA256>`. Those are
 * different implementations of the same construction, so pinning the composed
 * result — salt, info prefix, fileId concatenation and output length together —
 * is what stops the two drifting. `GoldenVaultInteropTests.swift` asserts the
 * same constant.
 *
 * The vector was computed independently (Python `hmac`/`hashlib`) from the
 * documented inputs, not captured from this implementation's output, so it is a
 * genuine known-answer rather than a regression snapshot.
 */
class FileContentKeyTest {

    private val vmk = ByteArray(32) { it.toByte() }
    private val fileId = ByteArray(16) { (0xA0 + it).toByte() }
    private val otherFileId = ByteArray(16) { (0xB0 + it).toByte() }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    @Test
    fun `derives the known key for the shared cross-platform test vector`() {
        assertEquals(
            "fa5c385575f0b8cb445d5c430aad2a837ac73fcb8c783918aab0943a4187e038",
            deriveFileContentKey(vmk, fileId).hex(),
            "File content key derivation changed — this constant is shared with iOS " +
                "(GoldenVaultInteropTests.swift) and with every vault already on disk.",
        )
    }

    @Test
    fun `derives a different known key for a different file id`() {
        assertEquals(
            "90e86286779495a2d98e58b239ae075b4c275a2a8bcfa3c4a9efda84629868ce",
            deriveFileContentKey(vmk, otherFileId).hex(),
        )
    }

    /**
     * The security property the per-file key exists for (PRD §8.2 point 3):
     * compromising one file's key must not help against another file.
     */
    @Test
    fun `different file ids yield unrelated keys under the same VMK`() {
        assertNotEquals(
            deriveFileContentKey(vmk, fileId).hex(),
            deriveFileContentKey(vmk, otherFileId).hex(),
        )
    }

    @Test
    fun `different VMKs yield different keys for the same file id`() {
        val otherVmk = ByteArray(32) { (it + 1).toByte() }
        assertNotEquals(
            deriveFileContentKey(vmk, fileId).hex(),
            deriveFileContentKey(otherVmk, fileId).hex(),
        )
    }

    /** Keys are derived on every open and never stored, so this must be pure. */
    @Test
    fun `derivation is deterministic`() {
        assertArrayEquals(deriveFileContentKey(vmk, fileId), deriveFileContentKey(vmk, fileId))
    }

    @Test
    fun `derived key is exactly the VMK size`() {
        assertEquals(VaultFormat.VMK_SIZE_BYTES, deriveFileContentKey(vmk, fileId).size)
    }

    /**
     * A short/long fileId would silently change the info string and therefore
     * the derived key, so it is rejected rather than accepted and hashed.
     */
    @Test
    fun `rejects a file id that is not exactly the declared size`() {
        assertThrows(IllegalArgumentException::class.java) {
            deriveFileContentKey(vmk, ByteArray(15))
        }
        assertThrows(IllegalArgumentException::class.java) {
            deriveFileContentKey(vmk, ByteArray(17))
        }
    }
}
