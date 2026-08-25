package xyz.libravault.feature.reader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import xyz.libravault.core.domain.model.MediaFormat

/**
 * Coverage for [bookmarkPositionRef] — docs/TEST_COVERAGE_PRD.md Phase 7's
 * `ReaderScreenKt` design pass (#607). Extracted verbatim from the inline
 * `when (format)` block ReaderScreen's toolbar `onAddBookmark` callback used to
 * build, so these lock in the existing per-format ref encoding.
 */
class BookmarkPositionRefTest {

    @Test
    fun `PDF encodes page index, defaulting to 0 when unknown`() {
        assertEquals("page:3", bookmarkPositionRef(MediaFormat.PDF, pageIndex = 3, null, null, null))
        assertEquals("page:0", bookmarkPositionRef(MediaFormat.PDF, pageIndex = null, null, null, null))
    }

    @Test
    fun `Markdown encodes scroll fraction, defaulting to 0-0 when unknown`() {
        assertEquals(
            "scroll:0.42",
            bookmarkPositionRef(MediaFormat.MARKDOWN, null, markdownScrollFraction = 0.42, null, null),
        )
        assertEquals(
            "scroll:0.0",
            bookmarkPositionRef(MediaFormat.MARKDOWN, null, markdownScrollFraction = null, null, null),
        )
    }

    @Test
    fun `EPUB prefers the persisted positionCfi over the live locator`() {
        assertEquals(
            "cfi-from-progress",
            bookmarkPositionRef(
                MediaFormat.EPUB, null, null,
                positionCfi = "cfi-from-progress",
                currentLocatorJson = "live-locator-json",
            ),
        )
    }

    @Test
    fun `EPUB falls back to the live locator when no persisted positionCfi exists yet`() {
        assertEquals(
            "live-locator-json",
            bookmarkPositionRef(
                MediaFormat.EPUB, null, null,
                positionCfi = null,
                currentLocatorJson = "live-locator-json",
            ),
        )
    }

    @Test
    fun `EPUB with neither a persisted locator nor a live one yields null, suppressing the bookmark`() {
        assertNull(
            bookmarkPositionRef(MediaFormat.EPUB, null, null, positionCfi = null, currentLocatorJson = null),
        )
    }
}
