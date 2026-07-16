package xyz.libravault.core.storage

import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.libravault.core.domain.model.LibraryItem
import xyz.libravault.core.domain.model.MediaFormat
import java.io.File
import java.nio.file.Path

/**
 * Covers the Phase 2 enrichment gate in [LibraryScannerImpl]. This is the
 * pure function that decides which library items get their metadata
 * re-extracted during a scan. The original bug was: the EPUB/PDF branch
 * only fired when `coverArtPath == null`, so after `CoverArtCache.clearAll()`
 * wiped the on-disk JPEG files, the stale absolute path in Room kept the
 * gate closed forever and covers could never come back. The defensive
 * check added here re-opens the gate whenever the file on disk is gone,
 * regardless of format.
 */
class LibraryScannerImplTest {

    @TempDir
    lateinit var tempDir: Path

    // The scanner is otherwise unused — `needsEnrichment` is a pure
    // helper that only inspects the item. Constructing it keeps the test
    // honest if the helper ever starts needing scanner state.
    private val scanner = LibraryScannerImpl(
        vaultRepository    = mockk(relaxed = true),
        libraryRepository  = mockk(relaxed = true),
        fileScanner        = mockk(relaxed = true),
        metadataExtractor  = mockk(relaxed = true),
        coverArtCache      = mockk(relaxed = true),
        logger             = mockk(relaxed = true),
    )

    // ── Defensive cover-file gate (the bug fix) ───────────────────────────────

    @Test
    fun `EPUB with cover path pointing at a deleted file is re-enriched`() {
        val missingPath = File(tempDir.toFile(), "never-existed-${System.nanoTime()}.jpg").absolutePath
        assertFalse(File(missingPath).exists(), "precondition: file must not exist")

        val item = libraryItem(
            format       = MediaFormat.EPUB,
            coverArtPath = missingPath,
            author       = "Real Author",   // already enriched — would otherwise be skipped
        )

        assertTrue(scanner.needsEnrichment(item))
    }

    @Test
    fun `PDF with cover path pointing at a deleted file is re-enriched`() {
        val missingPath = "/nonexistent/cache/cover-${System.nanoTime()}.jpg"
        val item = libraryItem(
            format       = MediaFormat.PDF,
            coverArtPath = missingPath,
            author       = "Real Author",
        )

        assertTrue(scanner.needsEnrichment(item))
    }

    @Test
    fun `audio item with cover path pointing at a deleted file is re-enriched`() {
        // The audio branch of the original gate only checks durationMs, so
        // a deleted cover file on an already-enriched audio item would
        // have stayed broken. The defensive gate catches this too.
        val missingPath = "/nonexistent/cache/cover-${System.nanoTime()}.jpg"
        val item = libraryItem(
            format       = MediaFormat.M4B,
            coverArtPath = missingPath,
            durationMs   = 3_600_000L,    // already enriched — would otherwise be skipped
        )

        assertTrue(scanner.needsEnrichment(item))
    }

    // ── Original gate still works (no regression) ────────────────────────────

    @Test
    fun `EPUB stub with null cover and Unknown author is enriched`() {
        val item = libraryItem(
            format       = MediaFormat.EPUB,
            coverArtPath = null,
            author       = "Unknown",
        )

        assertTrue(scanner.needsEnrichment(item))
    }

    @Test
    fun `PDF stub with null cover and Unknown author is enriched`() {
        val item = libraryItem(
            format       = MediaFormat.PDF,
            coverArtPath = null,
            author       = "Unknown",
        )

        assertTrue(scanner.needsEnrichment(item))
    }

    @Test
    fun `already enriched EPUB with existing cover file is skipped`() {
        val existing = File(tempDir.toFile(), "real-cover.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        assertTrue(existing.exists(), "precondition: file must exist")

        val item = libraryItem(
            format       = MediaFormat.EPUB,
            coverArtPath = existing.absolutePath,
            author       = "Real Author",
        )

        assertFalse(scanner.needsEnrichment(item))
    }

    @Test
    fun `audio item with null duration is enriched`() {
        val item = libraryItem(
            format     = MediaFormat.MP3,
            coverArtPath = null,
            durationMs = null,
        )

        assertTrue(scanner.needsEnrichment(item))
    }

    @Test
    fun `already enriched audio item with existing cover is skipped`() {
        val existing = File(tempDir.toFile(), "real-cover.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val item = libraryItem(
            format       = MediaFormat.MP3,
            coverArtPath = existing.absolutePath,
            durationMs   = 60_000L,
        )

        assertFalse(scanner.needsEnrichment(item))
    }

    @Test
    fun `already enriched EPUB whose author was overwritten is skipped even when cover is null`() {
        // The EPUB/PDF branch requires BOTH coverArtPath == null AND
        // author == "Unknown". An item with a known author but no cover
        // path has already been through enrichment and must not run again.
        val item = libraryItem(
            format       = MediaFormat.EPUB,
            coverArtPath = null,
            author       = "Real Author",
        )

        assertFalse(scanner.needsEnrichment(item))
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun libraryItem(
        format: MediaFormat,
        coverArtPath: String?,
        author: String = "Unknown",
        durationMs: Long? = null,
    ): LibraryItem = LibraryItem(
        id            = 1,
        vaultFolderId = 1,
        filePath      = "content://test/book",
        title         = "Test",
        author        = author,
        format        = format,
        coverArtPath  = coverArtPath,
        durationMs    = durationMs,
    )
}