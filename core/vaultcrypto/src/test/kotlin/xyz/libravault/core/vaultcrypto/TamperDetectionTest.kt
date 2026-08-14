package xyz.libravault.core.vaultcrypto

import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import kotlin.io.path.createTempFile

/**
 * Proves the tamper-defense claims in PRD §8.2/§8.2b: reordering, splicing,
 * and truncation (both mid-chunk and chunk-boundary-aligned) all fail rather
 * than silently succeeding, and every header field is authenticated because
 * it's bound into every chunk's AAD (implementation plan §A.1).
 */
class TamperDetectionTest {

    private val random = SecureRandom()
    private val vmk = ByteArray(32).also { random.nextBytes(it) }
    private val fileId = ByteArray(16).also { random.nextBytes(it) }
    private val chunkSize = 64

    private fun encryptedBytes(plain: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        ChunkedVaultWriter.encrypt(vmk, fileId, plain.size.toLong(), ByteArrayInputStream(plain), out, chunkSize)
        return out.toByteArray()
    }

    private fun toTempFile(bytes: ByteArray): java.io.File {
        val f = createTempFile(prefix = "vaultcrypto-tamper-test").toFile()
        f.writeBytes(bytes)
        f.deleteOnExit()
        return f
    }

    @Test
    fun `flipping a ciphertext byte inside a chunk is detected`() {
        val plain = ByteArray(chunkSize * 2).also { random.nextBytes(it) }
        val bytes = encryptedBytes(plain)
        bytes[VaultFormat.HEADER_SIZE_BYTES + 5] = (bytes[VaultFormat.HEADER_SIZE_BYTES + 5].toInt() xor 0x01).toByte()

        val file = toTempFile(bytes)
        assertThrows<VaultAuthenticationException> {
            VaultFileReader(file, vmk, fileId).use { it.readAt(0, plain.size) }
        }
        file.delete()
    }

    @Test
    fun `flipping a tag byte is detected`() {
        val plain = ByteArray(chunkSize - 10).also { random.nextBytes(it) } // exactly 1 chunk
        val bytes = encryptedBytes(plain)
        val lastByteIdx = bytes.size - 1 // last byte of the (only) chunk's tag
        bytes[lastByteIdx] = (bytes[lastByteIdx].toInt() xor 0x01).toByte()

        val file = toTempFile(bytes)
        assertThrows<VaultAuthenticationException> {
            VaultFileReader(file, vmk, fileId).use { it.readAt(0, plain.size) }
        }
        file.delete()
    }

    @Test
    fun `tampering the header's total-length field invalidates every remaining chunk`() {
        // This is the core claim behind binding header fields into every chunk's
        // AAD (VaultFormat.chunkAad): editing totalPlaintextLength changes the
        // recomputed AAD for ALL chunks, not just chunks "after" the edit, so
        // even chunk 0 — untouched on disk — fails to authenticate.
        val plain = ByteArray(chunkSize * 3).also { random.nextBytes(it) }
        val bytes = encryptedBytes(plain)

        // total-length field is the last 8 bytes of the header (see VaultFormat.HEADER_SIZE_BYTES layout)
        val lengthFieldStart = VaultFormat.HEADER_SIZE_BYTES - 8
        bytes[lengthFieldStart + 7] = (bytes[lengthFieldStart + 7].toInt() xor 0x01).toByte() // shrink by 1

        val file = toTempFile(bytes)
        assertThrows<VaultAuthenticationException> {
            VaultFileReader(file, vmk, fileId).use { it.readAt(0, 1) } // even the FIRST chunk fails
        }
        file.delete()
    }

    @Test
    fun `reordering two chunks is detected via the chunk-index binding`() {
        val plain = ByteArray(chunkSize * 2).also { random.nextBytes(it) }
        val bytes = encryptedBytes(plain)

        val storedChunkLen = chunkSize + VaultFormat.TAG_SIZE_BYTES
        val chunk0Start = VaultFormat.HEADER_SIZE_BYTES
        val chunk1Start = chunk0Start + storedChunkLen

        val chunk0 = bytes.copyOfRange(chunk0Start, chunk0Start + storedChunkLen)
        val chunk1 = bytes.copyOfRange(chunk1Start, chunk1Start + storedChunkLen)

        val swapped = bytes.copyOf()
        System.arraycopy(chunk1, 0, swapped, chunk0Start, storedChunkLen)
        System.arraycopy(chunk0, 0, swapped, chunk1Start, storedChunkLen)

        val file = toTempFile(swapped)
        assertThrows<VaultAuthenticationException> {
            VaultFileReader(file, vmk, fileId).use { it.readAt(0, plain.size) }
        }
        file.delete()
    }

    @Test
    fun `splicing in a chunk from a DIFFERENT file is detected via the fileId binding`() {
        val plainA = ByteArray(chunkSize).also { random.nextBytes(it) }
        val otherFileId = ByteArray(16).also { random.nextBytes(it) }
        val plainB = ByteArray(chunkSize).also { random.nextBytes(it) }

        val outB = ByteArrayOutputStream()
        ChunkedVaultWriter.encrypt(vmk, otherFileId, plainB.size.toLong(), ByteArrayInputStream(plainB), outB, chunkSize)
        val bytesB = outB.toByteArray()
        val chunkFromB = bytesB.copyOfRange(VaultFormat.HEADER_SIZE_BYTES, bytesB.size)

        val bytesA = encryptedBytes(plainA)
        val spliced = bytesA.copyOf()
        System.arraycopy(chunkFromB, 0, spliced, VaultFormat.HEADER_SIZE_BYTES, chunkFromB.size)

        val file = toTempFile(spliced)
        assertThrows<VaultAuthenticationException> {
            VaultFileReader(file, vmk, fileId).use { it.readAt(0, plainA.size) }
        }
        file.delete()
    }

    @Test
    fun `truncating trailing bytes mid-chunk is detected as a short read`() {
        val plain = ByteArray(chunkSize + 20).also { random.nextBytes(it) }
        val bytes = encryptedBytes(plain)
        val truncated = bytes.copyOf(bytes.size - 5) // chop off part of the last chunk's tag

        val file = toTempFile(truncated)
        // Header still claims the original (larger) length, so the reader will
        // try to read a chunk that no longer has enough bytes on disk.
        assertThrows<VaultTruncatedException> {
            VaultFileReader(file, vmk, fileId).use { it.readAt(0, plain.size) }
        }
        file.delete()
    }

    @Test
    fun `truncating exactly on a chunk boundary is still detected`() {
        // Drop the entire final chunk. If an attacker leaves the header untouched,
        // the reader expects more chunks than physically exist -> short read.
        val plain = ByteArray(chunkSize * 3).also { random.nextBytes(it) }
        val bytes = encryptedBytes(plain)
        val storedChunkLen = chunkSize + VaultFormat.TAG_SIZE_BYTES
        val truncated = bytes.copyOf(VaultFormat.HEADER_SIZE_BYTES + storedChunkLen * 2) // keep only 2 of 3 chunks

        val file = toTempFile(truncated)
        assertThrows<VaultTruncatedException> {
            VaultFileReader(file, vmk, fileId).use { it.readAt(0, plain.size) }
        }
        file.delete()
    }

    @Test
    fun `an empty file still gets one authenticated chunk, so deleting all chunks is still detected`() {
        val bytes = encryptedBytes(ByteArray(0))
        assertNotEquals(VaultFormat.HEADER_SIZE_BYTES, bytes.size) // proves a chunk really was written

        val truncatedToHeaderOnly = bytes.copyOf(VaultFormat.HEADER_SIZE_BYTES)
        val file = toTempFile(truncatedToHeaderOnly)
        assertThrows<VaultTruncatedException> {
            VaultFileReader(file, vmk, fileId).use { it.readAt(0, 1) }
        }
        file.delete()
    }
}
