package xyz.libravault.feature.player.service

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.Label
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.metadata.Chapter as Media3Chapter
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import xyz.libravault.core.logger.LibravaultLogger

/**
 * Covers both halves of [ChapterExtractor]:
 *  - [ChapterExtractor.extract] end-to-end, which (in a plain JVM test, with no real
 *    playable file for [androidx.media3.inspector.MetadataRetriever] to read) always
 *    exercises the failure/fallback path — that's real coverage, since falling back
 *    cleanly on any read failure is exactly the behavior the "Full Book" fallback exists
 *    for.
 *  - [ChapterExtractor.toChapters], the mapping logic, directly with real
 *    [Media3Chapter] entries built via [Media3Chapter.Builder] — the same type media3's
 *    own MP4 `chpl`-atom parser (`BoxParser.parseChpl`) builds them with, and a plain
 *    POJO, safe to construct without Android runtime — this is where the actual
 *    chapter-list behavior lives, so it's tested with real multi-chapter data rather
 *    than only through the always-empty fallback path above.
 */
@OptIn(UnstableApi::class)
class ChapterExtractorTest {

    private val extractor = ChapterExtractor(
        context = mockk<Context>(relaxed = true),
        logger = mockk<LibravaultLogger>(relaxed = true),
    )

    // ── extract() — fallback path ───────────────────────────────────────────────

    @Test
    fun `extract returns a single Full Book chapter spanning the whole duration`() = runTest {
        val uri = mockk<Uri>(relaxed = true)

        val chapters = extractor.extract(uri, durationMs = 3_600_000)

        assertEquals(1, chapters.size)
        val chapter = chapters.single()
        assertEquals(0, chapter.index)
        assertEquals("Full Book", chapter.title)
        assertEquals(0L, chapter.startMs)
        assertEquals(3_600_000L, chapter.endMs)
    }

    @Test
    fun `extract handles a zero duration without throwing`() = runTest {
        val uri = mockk<Uri>(relaxed = true)

        val chapters = extractor.extract(uri, durationMs = 0)

        assertEquals(0L, chapters.single().endMs)
    }

    // ── toChapters() — mapping logic ────────────────────────────────────────────

    private fun chapterFrame(startTimeMs: Int, title: String?): Media3Chapter =
        Media3Chapter.Builder()
            .setStartTimeMs(startTimeMs.toLong())
            .apply { if (title != null) setTitle(Label(/* language= */ null, title)) }
            .build()

    @Test
    fun `toChapters sorts entries and derives end times from the next chapter's start`() {
        val entries = listOf(
            chapterFrame(startTimeMs = 600_000, title = "Chapter Two"),
            chapterFrame(startTimeMs = 0, title = "Chapter One"),
            chapterFrame(startTimeMs = 1_200_000, title = "Chapter Three"),
        )

        val chapters = extractor.toChapters(entries, durationMs = 1_800_000)

        assertEquals(3, chapters.size)
        assertEquals(listOf("Chapter One", "Chapter Two", "Chapter Three"), chapters.map { it.title })
        assertEquals(listOf(0, 1, 2), chapters.map { it.index })
        assertEquals(listOf(0L, 600_000L, 1_200_000L), chapters.map { it.startMs })
        // Each chapter's end is the next chapter's start; the last ends at the file duration.
        assertEquals(listOf(600_000L, 1_200_000L, 1_800_000L), chapters.map { it.endMs })
    }

    @Test
    fun `toChapters falls back to Full Book when there are no entries`() {
        val chapters = extractor.toChapters(emptyList(), durationMs = 500_000)

        assertEquals(1, chapters.size)
        assertEquals("Full Book", chapters.single().title)
        assertEquals(0L, chapters.single().startMs)
        assertEquals(500_000L, chapters.single().endMs)
    }

    @Test
    fun `toChapters drops entries with an out-of-range start time`() {
        // Negative (invalid, per BoxParser's own convention for unparseable start times)
        // and past-the-end-of-file entries should never produce a chapter.
        val entries = listOf(
            chapterFrame(startTimeMs = -1, title = "Invalid"),
            chapterFrame(startTimeMs = 999_999, title = "Past the end"),
        )

        val chapters = extractor.toChapters(entries, durationMs = 500_000)

        assertEquals(1, chapters.size)
        assertEquals("Full Book", chapters.single().title)
    }

    @Test
    fun `toChapters deduplicates entries that share a start time`() {
        val entries = listOf(
            chapterFrame(startTimeMs = 0, title = "First"),
            chapterFrame(startTimeMs = 0, title = "Duplicate of first"),
            chapterFrame(startTimeMs = 300_000, title = "Second"),
        )

        val chapters = extractor.toChapters(entries, durationMs = 600_000)

        assertEquals(2, chapters.size)
        assertEquals(listOf("First", "Second"), chapters.map { it.title })
    }

    @Test
    fun `toChapters falls back to a numbered title when the entry has none`() {
        val entries = listOf(
            chapterFrame(startTimeMs = 0, title = null),
            chapterFrame(startTimeMs = 100_000, title = null),
        )

        val chapters = extractor.toChapters(entries, durationMs = 200_000)

        assertEquals(listOf("Chapter 1", "Chapter 2"), chapters.map { it.title })
    }
}
