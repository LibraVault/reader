package xyz.libravault.core.vaultstore

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xyz.libravault.core.vaultcrypto.ChunkedVaultWriter
import xyz.libravault.core.vaultcrypto.VaultFormat
import java.io.ByteArrayInputStream
import java.security.SecureRandom
import kotlin.io.path.createTempDirectory

/**
 * Regression coverage for the AES-GCM nonce-reuse fix in [VaultManifest]:
 * every [VaultManifest.write] used to re-encrypt under the same fixed
 * [VaultManifest.MANIFEST_FILE_ID], which — combined with a VMK that never
 * rotates — reused the exact same (key, nonce) sequence on every write while
 * encrypting different plaintext. See VaultManifest's class doc.
 */
class VaultManifestTest {

    private val random = SecureRandom()
    private val vmk = ByteArray(32).also { random.nextBytes(it) }

    private fun newVaultDir() = createTempDirectory(prefix = "vaultmanifest-test").toFile().also { it.deleteOnExit() }

    private fun entry(title: String) = VaultManifestEntry(
        fileId = ByteArray(16).also { random.nextBytes(it) },
        title = title,
        author = "An Author",
        format = "EPUB",
        sizeBytes = 1234L,
        addedAtEpochMillis = 0L,
    )

    /** The 16-byte fileId embedded in the manifest blob's own unencrypted-but-authenticated
     * header — see VaultFormat.HEADER_SIZE_BYTES layout (version + cipherId precede it). */
    private fun headerFileId(vaultDir: java.io.File): ByteArray {
        val bytes = VaultManifest.manifestPath(vaultDir).readBytes()
        return bytes.copyOfRange(2, 2 + VaultFormat.FILE_ID_SIZE_BYTES)
    }

    @Test
    fun `write then read round-trips entries unchanged`() {
        val vaultDir = newVaultDir()
        val entries = listOf(entry("Book One"), entry("Book Two"))

        VaultManifest.write(vaultDir, vmk, entries)
        val readBack = VaultManifest.read(vaultDir, vmk)

        assertEquals(entries, readBack)
    }

    @Test
    fun `read on a brand-new vault directory returns an empty list`() {
        val vaultDir = newVaultDir()
        assertTrue(VaultManifest.read(vaultDir, vmk).isEmpty())
    }

    @Test
    fun `two consecutive writes embed two different fileIds in the header`() {
        // This is the actual regression test for the nonce-reuse bug: if the manifest
        // were still encrypted under one fixed fileId, this header field would be
        // identical across writes and every chunk's nonce would repeat.
        val vaultDir = newVaultDir()

        VaultManifest.write(vaultDir, vmk, listOf(entry("Book One")))
        val firstFileId = headerFileId(vaultDir)

        VaultManifest.write(vaultDir, vmk, listOf(entry("Book One"), entry("Book Two")))
        val secondFileId = headerFileId(vaultDir)

        assertFalse(firstFileId.contentEquals(secondFileId), "manifest reused the same fileId (and therefore the same nonce sequence) across two writes")
        assertFalse(firstFileId.contentEquals(VaultManifest.MANIFEST_FILE_ID), "manifest wrote under the legacy all-zero sentinel instead of a fresh random id")
    }

    @Test
    fun `many consecutive writes never repeat a fileId`() {
        val vaultDir = newVaultDir()
        val seen = mutableSetOf<List<Byte>>()

        repeat(50) { i ->
            VaultManifest.write(vaultDir, vmk, listOf(entry("Book $i")))
            val id = headerFileId(vaultDir).toList()
            assertTrue(seen.add(id), "fileId repeated after $i writes — nonce sequence would repeat too")
        }
    }

    @Test
    fun `a manifest written under the legacy all-zero fileId still reads back correctly`() {
        // Proves the fix is self-migrating: an existing user's vault, written by a
        // pre-fix build, must keep working with no special-case migration code.
        val vaultDir = newVaultDir()
        val entries = listOf(entry("Legacy Book"))
        val dto = entries.map {
            """{"fileIdHex":"${it.fileId.toHex()}","title":"${it.title}","author":"${it.author}","format":"${it.format}","sizeBytes":${it.sizeBytes},"addedAtEpochMillis":${it.addedAtEpochMillis}}"""
        }.joinToString(",", prefix = """{"entries":[""", postfix = "]}")
        val plainBytes = dto.toByteArray(Charsets.UTF_8)

        ChunkedVaultWriter.encrypt(
            vmk, VaultManifest.MANIFEST_FILE_ID, plainBytes.size.toLong(),
            ByteArrayInputStream(plainBytes), VaultManifest.manifestPath(vaultDir).outputStream(),
        )

        val readBack = VaultManifest.read(vaultDir, vmk)
        assertEquals(entries, readBack)

        // And the very next write must rotate it off the legacy sentinel.
        VaultManifest.write(vaultDir, vmk, entries)
        assertFalse(headerFileId(vaultDir).contentEquals(VaultManifest.MANIFEST_FILE_ID))
    }
}
