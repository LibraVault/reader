package xyz.libravault.feature.reader

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Coverage for [resolveBookmarkTarget] — docs/TEST_COVERAGE_PRD.md Phase 7's
 * `ReaderScreenKt` design pass (#607). Extracted verbatim from the inline
 * `when` block the BookmarksSheet `onBookmarkClick` callback used to run, so
 * these lock in the existing prefix-decoding behaviour, including the silent
 * no-navigate fallback for a malformed payload the original `toIntOrNull()`/
 * `toDoubleOrNull()` `?.let { }` already had.
 */
class ResolveBookmarkTargetTest {

    @Test
    fun `page prefix resolves to PdfPage`() {
        assertEquals(BookmarkTarget.PdfPage(7), resolveBookmarkTarget("page:7"))
    }

    @Test
    fun `page prefix with a non-integer payload resolves to Malformed`() {
        assertEquals(BookmarkTarget.Malformed, resolveBookmarkTarget("page:not-a-number"))
    }

    @Test
    fun `scroll prefix resolves to MarkdownScroll`() {
        assertEquals(BookmarkTarget.MarkdownScroll(0.75), resolveBookmarkTarget("scroll:0.75"))
    }

    @Test
    fun `scroll prefix with a non-numeric payload resolves to Malformed`() {
        assertEquals(BookmarkTarget.Malformed, resolveBookmarkTarget("scroll:nope"))
    }

    @Test
    fun `anything else is treated as a raw EPUB locator JSON string`() {
        val locatorJson = """{"href":"chapter1.xhtml"}"""
        assertEquals(BookmarkTarget.EpubLocator(locatorJson), resolveBookmarkTarget(locatorJson))
    }
}
