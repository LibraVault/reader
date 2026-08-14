package xyz.libravault.core.vaultcrypto

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists

class ChunkedVaultRoundTripTest {

    private val random = SecureRandom()
    private val vmk = ByteArray(32).also { random.nextBytes(it) }
    private val fileId = ByteArray(16).also { random.nextBytes(it) }
    private val smallChunkSize = 64 // tiny, so tests exercise many chunk boundaries cheaply

    private fun encryptToTempFile(plain: ByteArray, chunkSize: Int = smallChunkSize): java.io.File {
        val out = ByteArrayOutputStream()
        ChunkedVaultWriter.encrypt(vmk, fileId, plain.size.toLong(), ByteArrayInputStream(plain), out, chunkSize)
        val tmp = createTempFile(prefix = "vaultcrypto-test").toFile()
        tmp.writeBytes(out.toByteArray())
        tmp.deleteOnExit()
        return tmp
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 63, 64, 65, 127, 128, 129, 1000])
    fun `round-trips whole file for various sizes relative to chunk boundaries`(size: Int) {
        val plain = ByteArray(size).also { random.nextBytes(it) }
        val file = encryptToTempFile(plain)

        VaultFileReader(file, vmk, fileId).use { reader ->
            assertEquals(size.toLong(), reader.plainSize)
            assertArrayEquals(plain, reader.readAt(0, size))
        }
        file.delete()
    }

    @Test
    fun `random access across a chunk boundary returns exactly the requested bytes`() {
        val plain = ByteArray(smallChunkSize * 3 + 10).also { random.nextBytes(it) }
        val file = encryptToTempFile(plain)

        VaultFileReader(file, vmk, fileId).use { reader ->
            val off = smallChunkSize - 5
            val expected = plain.copyOfRange(off, off + 20)
            assertArrayEquals(expected, reader.readAt(off.toLong(), 20))
        }
        file.delete()
    }

    @Test
    fun `reading the tail returns exactly the remaining bytes, not more`() {
        val plain = ByteArray(smallChunkSize * 2 + 7).also { random.nextBytes(it) }
        val file = encryptToTempFile(plain)

        VaultFileReader(file, vmk, fileId).use { reader ->
            val tailOff = plain.size - 3
            val result = reader.readAt(tailOff.toLong(), 100) // ask for more than exists
            assertArrayEquals(plain.copyOfRange(tailOff, plain.size), result)
        }
        file.delete()
    }

    @Test
    fun `sequential reads within one chunk hit the cache, not a fresh decrypt`() {
        val plain = ByteArray(smallChunkSize * 2).also { random.nextBytes(it) }
        val file = encryptToTempFile(plain)

        VaultFileReader(file, vmk, fileId).use { reader ->
            reader.readAt(0, 10)
            reader.readAt(10, 10)
            reader.readAt(20, 10)
            // All three reads are within chunk 0 — should be exactly one real decrypt.
            assertEquals(1, reader.decryptCount)
        }
        file.delete()
    }

    @Test
    fun `reading two different chunks decrypts exactly twice`() {
        val plain = ByteArray(smallChunkSize * 2).also { random.nextBytes(it) }
        val file = encryptToTempFile(plain)

        VaultFileReader(file, vmk, fileId).use { reader ->
            reader.readAt(0, 1)
            reader.readAt(smallChunkSize.toLong(), 1)
            assertEquals(2, reader.decryptCount)
        }
        file.delete()
    }

    @Test
    fun `wrong VMK fails to decrypt even a correctly-formed file`() {
        val plain = ByteArray(smallChunkSize + 1).also { random.nextBytes(it) }
        val file = encryptToTempFile(plain)
        val wrongVmk = ByteArray(32).also { random.nextBytes(it) }

        org.junit.jupiter.api.assertThrows<VaultAuthenticationException> {
            VaultFileReader(file, wrongVmk, fileId).use { it.readAt(0, 1) }
        }
        file.delete()
    }
}
