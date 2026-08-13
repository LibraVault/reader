package xyz.libravault.feature.library

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.libravault.core.domain.model.MediaFormat

/**
 * Compose UI coverage for [FormatFilterRow] — the chip row itself, as opposed to
 * [LibraryViewModelTest], which covers what the resulting filter string does to the
 * item list. Runs on Robolectric (JVM, no emulator) via the `testing.android` bundle,
 * mirroring :feature:settings' TtsSettingsSectionTest.
 *
 * Worth testing directly because the row is a hand-maintained list of `item { FilterChip }`
 * blocks rather than something derived from [MediaFormat] — a new format doesn't get a
 * chip automatically, and a chip can silently emit the wrong filter string (the value is
 * a `String?`, not a typed format, since "AUDIO"/"BOOK" are pseudo-formats with no
 * MediaFormat entry). Both mistakes compile fine.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FormatFilterRowTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `renders every format chip including MD`() {
        composeTestRule.setContent {
            FormatFilterRow(currentFilter = null, onFilterChanged = {})
        }

        composeTestRule.onNodeWithText("All").assertIsDisplayed()
        composeTestRule.onNodeWithText("EPUB").assertIsDisplayed()
        composeTestRule.onNodeWithText("PDF").assertIsDisplayed()
        composeTestRule.onNodeWithText("MD").assertIsDisplayed()
        composeTestRule.onNodeWithText("Listening").assertIsDisplayed()
    }

    @Test
    fun `tapping MD reports the MARKDOWN format filter`() {
        var reported: String? = "unset"
        composeTestRule.setContent {
            FormatFilterRow(currentFilter = null, onFilterChanged = { reported = it })
        }

        composeTestRule.onNodeWithText("MD").performClick()

        // Must be MediaFormat.MARKDOWN.name exactly — LibraryViewModel's filter falls
        // through to `items.filter { it.format.name == fmt }`, so a label-ish string
        // like "MD" would silently match nothing rather than fail loudly.
        assertEquals(MediaFormat.MARKDOWN.name, reported)
    }

    @Test
    fun `tapping All clears the filter`() {
        var reported: String? = "unset"
        composeTestRule.setContent {
            FormatFilterRow(currentFilter = MediaFormat.MARKDOWN.name, onFilterChanged = { reported = it })
        }

        composeTestRule.onNodeWithText("All").performClick()

        assertNull(reported)
    }

    @Test
    fun `each chip reports its own filter value`() {
        val reported = mutableListOf<String?>()
        composeTestRule.setContent {
            FormatFilterRow(currentFilter = null, onFilterChanged = { reported += it })
        }

        composeTestRule.onNodeWithText("EPUB").performClick()
        composeTestRule.onNodeWithText("PDF").performClick()
        composeTestRule.onNodeWithText("MD").performClick()
        composeTestRule.onNodeWithText("Listening").performClick()

        assertEquals(
            listOf(MediaFormat.EPUB.name, MediaFormat.PDF.name, MediaFormat.MARKDOWN.name, "AUDIO"),
            reported,
        )
    }

    @Test
    fun `MD chip renders as selected when the MARKDOWN filter is active`() {
        composeTestRule.setContent {
            FormatFilterRow(currentFilter = MediaFormat.MARKDOWN.name, onFilterChanged = {})
        }

        // FilterChip exposes its selection through the Selected semantics state.
        composeTestRule.onNodeWithText("MD").assertIsSelected()
        composeTestRule.onNodeWithText("EPUB").assertIsNotSelected()
    }
}
