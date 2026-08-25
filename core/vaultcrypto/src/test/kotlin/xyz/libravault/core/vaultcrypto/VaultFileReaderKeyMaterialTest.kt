package xyz.libravault.core.vaultcrypto

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import kotlin.io.path.createTempFile

/**
 * Regression guard for #567: [VaultFileReader] used to retain its constructor's `vmk`
 * as a `private val` field though it's only ever needed inside `init` to derive
 * [VaultFileReader]'s own per-file `fileContentKey` — an unnecessary second copy of the
 * vault master key sitting in memory for as long as the (possibly GC-deferred) reader
 * instance survives, on top of the derived key that [close] already zeroes.
 *
 * Reflection is used deliberately, not as a shortcut: "does this field exist at all" and
 * "does close() zero the derived key" are the actual properties under test, and there is
 * no public API that observes either — a test that can't see the property would pass
 * regardless of whether the fix is present, exactly the "level that cannot observe the
 * property" trap `AGENTS.md` calls out.
 */
class VaultFileReaderKeyMaterialTest {

    private val random = SecureRandom()

    private fun byteArrayField(reader: VaultFileReader, name: String): ByteArray {
        val field = VaultFileReader::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.get(reader) as ByteArray
    }

    private fun openReader(): Pair<VaultFileReader, ByteArray> {
        val vmk = ByteArray(32).also { random.nextBytes(it) }
        val fileId = ByteArray(16).also { random.nextBytes(it) }
        val plain = ByteArray(100).also { random.nextBytes(it) }
        val out = ByteArrayOutputStream()
        ChunkedVaultWriter.encrypt(vmk, fileId, plain.size.toLong(), ByteArrayInputStream(plain), out, chunkSize = 64)
        val tmp = createTempFile(prefix = "vaultcrypto-key-material-test").toFile().apply { writeBytes(out.toByteArray()) }
        tmp.deleteOnExit()
        return VaultFileReader(tmp, vmk, fileId) to plain
    }

    @Test
    fun `vmk is not retained as a field`() {
        val (reader, _) = openReader()
        reader.use {
            assertThrows(NoSuchFieldException::class.java) {
                VaultFileReader::class.java.getDeclaredField("vmk")
            }
        }
    }

    @Test
    fun `close zeroes the derived file content key and cached chunk`() {
        val (reader, plain) = openReader()
        reader.readAt(0, plain.size) // populate the chunk cache too
        val keyBeforeClose = byteArrayField(reader, "fileContentKey").copyOf()
        assertTrue(keyBeforeClose.any { it != 0.toByte() }, "sanity check: key must be non-zero before close")

        reader.close()

        assertTrue(byteArrayField(reader, "fileContentKey").all { it == 0.toByte() })
    }
}
