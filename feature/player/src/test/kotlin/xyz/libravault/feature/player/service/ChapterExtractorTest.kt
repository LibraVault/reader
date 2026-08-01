package xyz.libravault.feature.player.service

import android.content.Context
import android.net.Uri
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import xyz.libravault.core.logger.LibravaultLogger

/**
 * ChapterExtractor is only ever mocked (never invoked for real) in
 * PlayerViewModelTest — its actual fallback logic had no direct coverage.
 */
class ChapterExtractorTest {

    private val extractor = ChapterExtractor(
        context = mockk<Context>(relaxed = true),
        logger = mockk<LibravaultLogger>(relaxed = true),
    )

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
}
