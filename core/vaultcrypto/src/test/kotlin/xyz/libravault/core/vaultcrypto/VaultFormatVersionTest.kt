package xyz.libravault.core.vaultcrypto

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import kotlin.io.path.createTempFile

/**
 * Proves implementation plan §A.2's requirement: an unrecognized format
 * version or cipher id is rejected cleanly and immediately, not
 * misinterpreted or allowed to crash unpredictably deeper in the code.
 */
class VaultFormatVersionTest {

    private val random = SecureRandom()
    private val vmk = ByteArray(32).also { random.nextBytes(it) }
    private val fileId = ByteArray(16).also { random.nextBytes(it) }

    private fun validEncryptedBytes(): ByteArray {
        val out = ByteArrayOutputStream()
        val plain = ByteArray(10).also { random.nextBytes(it) }
        ChunkedVaultWriter.encrypt(vmk, fileId, plain.size.toLong(), ByteArrayInputStream(plain), out, 64)
        return out.toByteArray()
    }

    private fun toTempFile(bytes: ByteArray): java.io.File {
        val f = createTempFile(prefix = "vaultcrypto-version-test").toFile()
        f.writeBytes(bytes)
        f.deleteOnExit()
        return f
    }

    @Test
    fun `a future, unrecognized format version is rejected cleanly`() {
        val bytes = validEncryptedBytes().copyOf()
        bytes[0] = 99 // format version byte — see VaultFormat.HEADER_SIZE_BYTES layout

        val file = toTempFile(bytes)
        val ex = assertThrows<UnsupportedVaultFormatException> {
            VaultFileReader(file, vmk, fileId)
        }
        org.junit.jupiter.api.Assertions.assertEquals(99.toByte(), ex.foundVersion)
        file.delete()
    }

    @Test
    fun `an unrecognized cipher id is rejected cleanly`() {
        val bytes = validEncryptedBytes().copyOf()
        bytes[1] = 99 // cipher id byte

        val file = toTempFile(bytes)
        val ex = assertThrows<UnsupportedCipherException> {
            VaultFileReader(file, vmk, fileId)
        }
        org.junit.jupiter.api.Assertions.assertEquals(99.toByte(), ex.foundCipherId)
        file.delete()
    }

    @Test
    fun `a file shorter than the header is rejected as truncated, not crashed on`() {
        val file = toTempFile(ByteArray(5)) // far shorter than HEADER_SIZE_BYTES
        assertThrows<VaultTruncatedException> {
            VaultFileReader(file, vmk, fileId)
        }
        file.delete()
    }

    @Test
    fun `mismatched fileId is rejected before touching chunk content`() {
        val bytes = validEncryptedBytes()
        val file = toTempFile(bytes)
        val wrongFileId = ByteArray(16).also { random.nextBytes(it) }
        assertThrows<VaultAuthenticationException> {
            VaultFileReader(file, vmk, wrongFileId)
        }
        file.delete()
    }
}
