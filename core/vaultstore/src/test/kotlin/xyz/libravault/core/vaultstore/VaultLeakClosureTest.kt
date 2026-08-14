package xyz.libravault.core.vaultstore

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import xyz.libravault.core.vaultcrypto.Argon2Params
import xyz.libravault.core.vaultstore.testing.FakeHardwareKeyWrapFactory
import java.io.ByteArrayInputStream
import java.security.SecureRandom
import kotlin.io.path.createTempDirectory

/**
 * Implementation plan §A.6/Phase 4: closes the two plaintext leaks the Phase
 * 0 review found that Phase 2 didn't already structurally avoid — cover art
 * and highlight/note text. (The third, titles/authors in plaintext Room, was
 * already closed by construction: [VaultStore.importFile] has written
 * straight to [VaultManifest] since Phase 2 and has never touched Room —
 * [`no core-database dependency`][VaultStoreHasNoLeakSurfaceDependencyTest]
 * below is the regression test that keeps it that way.)
 *
 * These tests are the actual point of this phase, per the plan: proving no
 * plaintext copy of sensitive content exists anywhere in the vault directory
 * on disk, not just that the encrypted API round-trips correctly.
 */
class VaultLeakClosureTest {

    private val fastParams = Argon2Params(memoryKiB = 8 * 1024, iterations = 1, parallelism = 1)

    /** Tracks the backing directory alongside the store — needed by the
     * plaintext-leak-scanning tests below, which have to inspect every file
     * actually on disk, not just what the encrypted API reports. */
    private lateinit var vaultDir: java.io.File

    private fun newStore(): VaultStore {
        val dir = createTempDirectory(prefix = "vaultstore-leak-test").toFile()
        dir.deleteOnExit()
        vaultDir = dir
        return VaultStore(dir, "test-vault-alias", FakeHardwareKeyWrapFactory())
    }

    // ── Cover art ──────────────────────────────────────────────────────────

    @Test
    fun `cover art imported alongside content round-trips exactly`() = runTest {
        val store = newStore()
        store.create("1234".toCharArray(), fastParams)
        val cover = ByteArray(2000).also { SecureRandom().nextBytes(it) }

        val entry = store.importFile(ByteArrayInputStream(ByteArray(10)), 10L, "Title", null, "pdf", coverArt = cover)

        assertTrue(entry.coverArtFileId != null)
        assertArrayEquals(cover, store.readCoverArt(entry.fileId))
    }

    @Test
    fun `readCoverArt returns null when no cover was set`() = runTest {
        val store = newStore()
        store.create("1234".toCharArray(), fastParams)
        val entry = store.importFile(ByteArrayInputStream(ByteArray(10)), 10L, "Title", null, "pdf")
        assertNull(store.readCoverArt(entry.fileId))
    }

    @Test
    fun `setCoverArt attaches a cover to an already-imported file`() = runTest {
        val store = newStore()
        store.create("1234".toCharArray(), fastParams)
        val entry = store.importFile(ByteArrayInputStream(ByteArray(10)), 10L, "Title", null, "pdf")

        val cover = ByteArray(500).also { SecureRandom().nextBytes(it) }
        store.setCoverArt(entry.fileId, cover)

        assertArrayEquals(cover, store.readCoverArt(entry.fileId))
    }

    @Test
    fun `setCoverArt replacing an existing cover removes the orphaned old file`() = runTest {
        val store = newStore()
        store.create("1234".toCharArray(), fastParams)
        val entry = store.importFile(
            ByteArrayInputStream(ByteArray(10)), 10L, "Title", null, "pdf",
            coverArt = ByteArray(100).also { SecureRandom().nextBytes(it) },
        )
        val firstCoverFileId = store.listEntries().first().coverArtFileId!!

        val newCover = ByteArray(200).also { SecureRandom().nextBytes(it) }
        store.setCoverArt(entry.fileId, newCover)

        assertFalse(store.contentFile(firstCoverFileId).exists(), "old cover file must not be left behind")
        assertArrayEquals(newCover, store.readCoverArt(entry.fileId))
    }

    @Test
    fun `oversized cover art is rejected before any bytes are written`() = runTest {
        val store = newStore()
        store.create("1234".toCharArray(), fastParams)
        val tooBig = ByteArray(VaultStore.MAX_COVER_ART_BYTES + 1)

        assertThrows<CoverArtTooLargeException> {
            store.importFile(ByteArrayInputStream(ByteArray(10)), 10L, "Title", null, "pdf", coverArt = tooBig)
        }
        assertTrue(store.listEntries().isEmpty(), "a rejected import must not appear in the manifest")
    }

    @Test
    fun `cover art bytes never appear in plaintext anywhere in the vault directory`() = runTest {
        val store = newStore()
        store.create("1234".toCharArray(), fastParams)
        // A distinctive, easy-to-search-for byte pattern rather than random noise.
        val cover = ByteArray(4096) { (it % 256).toByte() }
        val markerRun = cover.copyOfRange(0, 256) // a long enough run that random collision is not a concern

        store.importFile(ByteArrayInputStream(ByteArray(10)), 10L, "Title", null, "pdf", coverArt = cover)

        assertNoSubarrayOnDisk(vaultDir, markerRun)
    }

    // ── Highlights ─────────────────────────────────────────────────────────

    @Test
    fun `a highlight round-trips through the manifest`() = runTest {
        val store = newStore()
        store.create("1234".toCharArray(), fastParams)
        val entry = store.importFile(ByteArrayInputStream(ByteArray(10)), 10L, "Title", null, "pdf")

        val h = store.addHighlight(entry.fileId, "page:1:0,0,10,10", "a highlighted sentence", note = "my note")

        val reloaded = store.listEntries().first { it.fileId.contentEquals(entry.fileId) }
        assertEquals(1, reloaded.highlights.size)
        assertEquals(h, reloaded.highlights[0])
    }

    @Test
    fun `multiple highlights get distinct, increasing ids`() = runTest {
        val store = newStore()
        store.create("1234".toCharArray(), fastParams)
        val entry = store.importFile(ByteArrayInputStream(ByteArray(10)), 10L, "Title", null, "pdf")

        val h1 = store.addHighlight(entry.fileId, "ref1", "text1")
        val h2 = store.addHighlight(entry.fileId, "ref2", "text2")

        assertTrue(h2.id > h1.id)
    }

    @Test
    fun `removeHighlight deletes exactly the targeted highlight`() = runTest {
        val store = newStore()
        store.create("1234".toCharArray(), fastParams)
        val entry = store.importFile(ByteArrayInputStream(ByteArray(10)), 10L, "Title", null, "pdf")
        val h1 = store.addHighlight(entry.fileId, "ref1", "text1")
        val h2 = store.addHighlight(entry.fileId, "ref2", "text2")

        store.removeHighlight(entry.fileId, h1.id)

        val remaining = store.listEntries().first().highlights
        assertEquals(listOf(h2), remaining)
    }

    @Test
    fun `removing a nonexistent highlight id is a harmless no-op`() = runTest {
        val store = newStore()
        store.create("1234".toCharArray(), fastParams)
        val entry = store.importFile(ByteArrayInputStream(ByteArray(10)), 10L, "Title", null, "pdf")
        store.addHighlight(entry.fileId, "ref1", "text1")

        store.removeHighlight(entry.fileId, 999L) // does not throw
        assertEquals(1, store.listEntries().first().highlights.size)
    }

    @Test
    fun `highlights survive a lock-unlock cycle`() = runTest {
        val store = newStore()
        store.create("1234".toCharArray(), fastParams)
        val entry = store.importFile(ByteArrayInputStream(ByteArray(10)), 10L, "Title", null, "pdf")
        store.addHighlight(entry.fileId, "ref", "some highlighted text")

        store.lock()
        store.unlockWithPin("1234".toCharArray())

        assertEquals(1, store.listEntries().first().highlights.size)
    }

    @Test
    fun `addHighlight and removeHighlight on an unknown fileId throw, not silently no-op`() = runTest {
        val store = newStore()
        store.create("1234".toCharArray(), fastParams)
        val bogusFileId = ByteArray(16)

        assertThrows<VaultEntryNotFoundException> { store.addHighlight(bogusFileId, "ref", "text") }
        assertThrows<VaultEntryNotFoundException> { store.removeHighlight(bogusFileId, 1L) }
        assertThrows<VaultEntryNotFoundException> { store.readCoverArt(bogusFileId) }
        assertThrows<VaultEntryNotFoundException> { store.setCoverArt(bogusFileId, ByteArray(10)) }
    }

    @Test
    fun `highlighted text never appears in plaintext anywhere in the vault directory`() = runTest {
        val store = newStore()
        store.create("1234".toCharArray(), fastParams)
        val entry = store.importFile(ByteArrayInputStream(ByteArray(10)), 10L, "Title", null, "pdf")

        val sensitiveText = "Patient John Q. Confidential has a rare condition XKCD1234"
        store.addHighlight(entry.fileId, "ref", sensitiveText, note = "Client_Divorce_Case privileged note")

        assertNoSubarrayOnDisk(vaultDir, sensitiveText.toByteArray(Charsets.UTF_8))
        assertNoSubarrayOnDisk(vaultDir, "Client_Divorce_Case".toByteArray(Charsets.UTF_8))
    }

    @Test
    fun `title and author never appear in plaintext anywhere in the vault directory`() = runTest {
        val store = newStore()
        store.create("1234".toCharArray(), fastParams)
        store.importFile(
            ByteArrayInputStream(ByteArray(10)), 10L,
            title = "Smith v. Jones Deposition Transcript", author = "Jane Attorney", format = "pdf",
        )

        assertNoSubarrayOnDisk(vaultDir, "Smith v. Jones".toByteArray(Charsets.UTF_8))
        assertNoSubarrayOnDisk(vaultDir, "Jane Attorney".toByteArray(Charsets.UTF_8))
    }

    private fun assertNoSubarrayOnDisk(dir: java.io.File, needle: ByteArray) {
        val files = dir.listFiles() ?: emptyArray()
        assertTrue(files.isNotEmpty(), "expected at least one file on disk to actually check")
        for (f in files) {
            val bytes = f.readBytes()
            assertFalse(bytes.containsSubarray(needle), "found plaintext bytes in ${f.name} — leak!")
        }
    }

    private fun ByteArray.containsSubarray(needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > size) return false
        outer@ for (i in 0..size - needle.size) {
            for (j in needle.indices) {
                if (this[i + j] != needle[j]) continue@outer
            }
            return true
        }
        return false
    }
}
