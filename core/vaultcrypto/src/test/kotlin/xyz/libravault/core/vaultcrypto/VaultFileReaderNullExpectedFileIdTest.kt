package xyz.libravault.core.vaultcrypto

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import kotlin.io.path.createTempFile

/**
 * Covers [VaultFileReader]'s `expectedFileId = null` mode, added so a caller with no
 * independent way to know a blob's identity ahead of time (core:vaultstore's manifest —
 * see its class doc for why it can no longer use one fixed fileId) can still open it,
 * trusting whatever fileId is embedded in the header itself.
 */
class VaultFileReaderNullExpectedFileIdTest {

    private val random = SecureRandom()
    private val vmk = ByteArray(32).also { random.nextBytes(it) }
    private val fileId = ByteArray(16).also { random.nextBytes(it) }
    private val chunkSize = 64

    private fun encryptedBytes(plain: ByteArray, withFileId: ByteArray = fileId): ByteArray {
        val out = ByteArrayOutputStream()
        ChunkedVaultWriter.encrypt(vmk, withFileId, plain.size.toLong(), ByteArrayInputStream(plain), out, chunkSize)
        return out.toByteArray()
    }

    private fun toTempFile(bytes: ByteArray): java.io.File {
        val f = createTempFile(prefix = "vaultcrypto-null-fileid-test").toFile()
        f.writeBytes(bytes)
        f.deleteOnExit()
        return f
    }

    @Test
    fun `null expectedFileId decrypts correctly, trusting whatever fileId is in the header`() {
        val plain = ByteArray(chunkSize * 2 + 7).also { random.nextBytes(it) }
        val file = toTempFile(encryptedBytes(plain))

        VaultFileReader(file, vmk, expectedFileId = null).use { reader ->
            assertArrayEquals(plain, reader.readAt(0, plain.size))
        }
        file.delete()
    }

    @Test
    fun `null expectedFileId still surfaces the header's fileId via the fileId property`() {
        val file = toTempFile(encryptedBytes(ByteArray(chunkSize)))
        VaultFileReader(file, vmk, expectedFileId = null).use { reader ->
            assertArrayEquals(fileId, reader.fileId)
        }
        file.delete()
    }

    @Test
    fun `tampering the header's fileId is still detected even with no expected id to cross-check`() {
        // Proves the expectedFileId pre-check was never the actual security boundary: the
        // header's fileId is bound into every chunk's AAD (VaultFormat.chunkAad) AND used to
        // derive the decryption key, so corrupting it fails AEAD verification regardless of
        // whether a caller had an expected value to compare it against up front.
        val plain = ByteArray(chunkSize).also { random.nextBytes(it) }
        val bytes = encryptedBytes(plain)
        val fileIdFieldStart = 2 // format version (1) + cipher id (1) precede fileId in the header
        bytes[fileIdFieldStart] = (bytes[fileIdFieldStart].toInt() xor 0x01).toByte()

        val file = toTempFile(bytes)
        assertThrows<VaultAuthenticationException> {
            VaultFileReader(file, vmk, expectedFileId = null).use { it.readAt(0, plain.size) }
        }
        file.delete()
    }

    @Test
    fun `two blobs written under different fileIds both open fine with null expectedFileId`() {
        val plainA = ByteArray(chunkSize).also { random.nextBytes(it) }
        val plainB = ByteArray(chunkSize).also { random.nextBytes(it) }
        val otherFileId = ByteArray(16).also { random.nextBytes(it) }

        val fileA = toTempFile(encryptedBytes(plainA, fileId))
        val fileB = toTempFile(encryptedBytes(plainB, otherFileId))

        VaultFileReader(fileA, vmk, expectedFileId = null).use { assertArrayEquals(plainA, it.readAt(0, plainA.size)) }
        VaultFileReader(fileB, vmk, expectedFileId = null).use { assertArrayEquals(plainB, it.readAt(0, plainB.size)) }

        fileA.delete()
        fileB.delete()
    }
}
