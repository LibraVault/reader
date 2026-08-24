package xyz.libravault.core.vaultcrypto

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import kotlin.io.path.createTempFile

/**
 * Regression guard: [VaultFileReader.close] used to only close the underlying
 * `RandomAccessFile`, leaving the derived per-file content key and any cached
 * decrypted chunk sitting in memory for as long as the (possibly
 * long-lived, e.g. GC-deferred) reader instance survives.
 *
 * Reflection is used here deliberately, not as a shortcut: [close] zeroing
 * private key-material fields is the actual property under test, and there is
 * no public API that observes it — a test that can't see the property would
 * pass regardless of whether the fix is present, exactly the "level that
 * cannot observe the property" trap `AGENTS.md` calls out.
 */
class VaultFileReaderCloseZeroesKeyMaterialTest {

    private val random = SecureRandom()

    private fun byteArrayField(reader: VaultFileReader, name: String): ByteArray {
        val field = VaultFileReader::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.get(reader) as ByteArray
    }

    @Test
    fun `close zeroes the derived file content key`() {
        val vmk = ByteArray(32).also { random.nextBytes(it) }
        val fileId = ByteArray(16).also { random.nextBytes(it) }
        val plain = ByteArray(100).also { random.nextBytes(it) }
        val out = ByteArrayOutputStream()
        ChunkedVaultWriter.encrypt(vmk, fileId, plain.size.toLong(), ByteArrayInputStream(plain), out, chunkSize = 64)
        val tmp = createTempFile(prefix = "vaultcrypto-test").toFile().apply { writeBytes(out.toByteArray()) }

        val reader = VaultFileReader(tmp, vmk, fileId)
        reader.readAt(0, plain.size) // populate the chunk cache too
        val keyBeforeClose = byteArrayField(reader, "fileContentKey").copyOf()
        assertTrue(keyBeforeClose.any { it != 0.toByte() }, "sanity check: key must be non-zero before close")

        reader.close()

        assertTrue(byteArrayField(reader, "fileContentKey").all { it == 0.toByte() })
        tmp.delete()
    }
}
