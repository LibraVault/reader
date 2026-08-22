package xyz.libravault.feature.vault

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.libravault.core.ui.theme.LibravaultTheme

/**
 * Robolectric/Compose coverage for [VaultReaderTopBar] — mirrors
 * `feature:reader`'s `ReaderTopBarUiTest`/`ReaderTopBarAccessibilityTest` for
 * the vault reader's independent (but near-identical) top bar. Regression
 * guard for #424: the settings trigger must carry a persistent visible
 * label rather than a bare gear icon.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VaultReaderTopBarUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun setTopBar(onSettings: () -> Unit = {}) {
        composeTestRule.setContent {
            LibravaultTheme {
                VaultReaderTopBar(
                    title = "Book",
                    onBack = {},
                    onAddBookmark = {},
                    onShowBookmarks = {},
                    onSettings = onSettings,
                )
            }
        }
    }

    @Test
    fun `settings action shows a persistent visible label`() {
        setTopBar()

        composeTestRule.onNodeWithText("Themes & Settings").assertIsDisplayed()
    }

    @Test
    fun `tapping the settings label invokes the callback`() {
        var clicked = false
        setTopBar(onSettings = { clicked = true })

        composeTestRule.onNodeWithText("Themes & Settings").performClick()

        assertTrue("Expected onSettings to be invoked", clicked)
    }

    @Test
    fun `other toolbar actions keep their content descriptions`() {
        setTopBar()

        composeTestRule.onNodeWithContentDescription("Back").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Add bookmark").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Bookmarks").assertIsDisplayed()
    }

    /** The settings control's spoken name must come from its visible label,
     * not a leftover "Reader settings" `contentDescription` duplicating it. */
    @Test
    fun `settings control announces its visible label, not a stale content description`() {
        setTopBar()

        val labels = composeTestRule.onAllNodes(hasClickAction()).fetchSemanticsNodes().map { node ->
            val description = node.config.getOrNull(SemanticsProperties.ContentDescription)
                ?.joinToString(" ")
                ?.trim()
                .orEmpty()
            val text = node.config.getOrNull(SemanticsProperties.Text)
                ?.joinToString(" ") { it.text }
                ?.trim()
                .orEmpty()
            description.ifEmpty { text }
        }

        assertTrue(
            "Expected the settings control's accessible name to be its visible label. Labels: $labels",
            labels.any { it == "Themes & Settings" },
        )
        assertTrue(
            "Did not expect a stale \"Reader settings\" content description. Labels: $labels",
            labels.none { it == "Reader settings" },
        )
    }
}
