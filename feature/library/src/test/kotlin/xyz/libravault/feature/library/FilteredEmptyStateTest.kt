package xyz.libravault.feature.library

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.libravault.core.domain.model.MediaFormat

/**
 * Coverage for the empty state shown when a format filter chip matches nothing (#119).
 * [formatFilterEmptyMessageRes] is asserted directly (pure function, no Compose host
 * needed) and [FilteredEmptyState] is asserted via Robolectric, mirroring
 * FormatFilterRowTest's setup, to catch a message silently not rendering.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FilteredEmptyStateTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // ── formatFilterEmptyMessageRes ─────────────────────────────────────────────

    @Test
    fun `maps each real format filter to its own message`() {
        assertEquals(R.string.empty_filter_epub, formatFilterEmptyMessageRes(MediaFormat.EPUB.name))
        assertEquals(R.string.empty_filter_pdf, formatFilterEmptyMessageRes(MediaFormat.PDF.name))
        assertEquals(R.string.empty_filter_markdown, formatFilterEmptyMessageRes(MediaFormat.MARKDOWN.name))
    }

    @Test
    fun `maps the AUDIO and BOOK pseudo-formats to their own messages`() {
        assertEquals(R.string.empty_filter_audio, formatFilterEmptyMessageRes("AUDIO"))
        assertEquals(R.string.empty_filter_book, formatFilterEmptyMessageRes("BOOK"))
    }

    @Test
    fun `falls back to the generic message for null or an unrecognised value`() {
        assertEquals(R.string.empty_filter_generic, formatFilterEmptyMessageRes(null))
        assertEquals(R.string.empty_filter_generic, formatFilterEmptyMessageRes("SOMETHING_UNKNOWN"))
    }

    // ── FilteredEmptyState ───────────────────────────────────────────────────────

    @Test
    fun `renders the Markdown-specific message for the MARKDOWN filter`() {
        composeTestRule.setContent {
            FilteredEmptyState(formatFilter = MediaFormat.MARKDOWN.name)
        }

        composeTestRule.onNodeWithText("No Markdown files in your library").assertIsDisplayed()
    }

    @Test
    fun `renders the generic message for an unrecognised filter`() {
        composeTestRule.setContent {
            FilteredEmptyState(formatFilter = "SOMETHING_UNKNOWN")
        }

        composeTestRule.onNodeWithText("No items match this filter").assertIsDisplayed()
    }
}
