package xyz.libravault.core.vaultcrypto

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import kotlin.io.path.createTempFile

/**
 * Regression tests for two issues found during PR review (not in the original
 * commit): a corrupted `chunkSize == 0` header field crashing with an
 * unhandled [ArithmeticException] instead of failing cleanly, and
 * [VaultFormat.chunkAad] silently using the CURRENT build's format
 * version/cipher id instead of what a file's header actually says (a trap for
 * whoever adds multi-version support later).
 */
class HeaderValidationTest {

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
        val f = createTempFile(prefix = "vaultcrypto-header-test").toFile()
        f.writeBytes(bytes)
        f.deleteOnExit()
        return f
    }

    /** Overwrites the 4-byte chunkSize field (offset 18, per HEADER_SIZE_BYTES layout: 1+1+16). */
    private fun withChunkSize(bytes: ByteArray, value: Int): ByteArray {
        val out = bytes.copyOf()
        ByteBuffer.wrap(out, 18, 4).putInt(value)
        return out
    }

    @Test
    fun `chunkSize of 0 in the header fails cleanly, not with an unhandled exception`() {
        val corrupted = withChunkSize(validEncryptedBytes(), 0)
        val file = toTempFile(corrupted)
        assertThrows<MalformedVaultHeaderException> {
            VaultFileReader(file, vmk, fileId)
        }
        file.delete()
    }

    @Test
    fun `negative chunkSize in the header fails cleanly`() {
        val corrupted = withChunkSize(validEncryptedBytes(), -1)
        val file = toTempFile(corrupted)
        assertThrows<MalformedVaultHeaderException> {
            VaultFileReader(file, vmk, fileId)
        }
        file.delete()
    }

    @Test
    fun `absurdly large chunkSize in the header is rejected rather than attempting a huge allocation`() {
        val corrupted = withChunkSize(validEncryptedBytes(), Int.MAX_VALUE)
        val file = toTempFile(corrupted)
        assertThrows<MalformedVaultHeaderException> {
            VaultFileReader(file, vmk, fileId)
        }
        file.delete()
    }

    @Test
    fun `chunkAad output depends on the formatVersion and cipherId parameters, not just build constants`() {
        // Proves the AAD builder actually uses its parameters (the bug being
        // guarded against: hardcoding VaultFormat.FORMAT_VERSION/CIPHER_AES_256_GCM
        // internally instead of taking them as arguments).
        val fid = ByteArray(16).also { random.nextBytes(it) }
        val aadV1 = VaultFormat.chunkAad(1, 1, fid, 100L, 1024, 0L, true)
        val aadV2 = VaultFormat.chunkAad(2, 1, fid, 100L, 1024, 0L, true)
        val aadCipher2 = VaultFormat.chunkAad(1, 2, fid, 100L, 1024, 0L, true)
        assertFalse(aadV1.contentEquals(aadV2))
        assertFalse(aadV1.contentEquals(aadCipher2))
    }
}
