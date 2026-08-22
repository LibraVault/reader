package xyz.libravault.feature.reader.components

import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.libravault.core.ui.theme.LibravaultTheme

/** Robolectric/Compose coverage for [ReaderTopBar] — regression guard for #4
 * (icon buttons were visibly mismatched: the font-size/TOC/add-bookmark buttons
 * were 38.dp while the bookmarks button fell back to Material3's default 48.dp
 * in the same bar). The settings action is deliberately excluded from the
 * shared-size assertion below: #424 gave it a persistent visible label, so it
 * is no longer a bare square icon button. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReaderTopBarUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun setTopBar() {
        composeTestRule.setContent {
            LibravaultTheme {
                ReaderTopBar(
                    title = "Book",
                    onBack = {},
                    onFontDecrease = {},
                    onFontIncrease = {},
                    onAddBookmark = {},
                    onShowBookmarks = {},
                    onSettings = {},
                    showFontControls = true,
                    onShowToc = {},
                )
            }
        }
    }

    @Test
    fun `all icon-only action buttons share the same size`() {
        setTopBar()

        val expectedSize = 38.dp
        val actionDescriptions = listOf(
            "Table of contents",
            "Add bookmark",
            "Bookmarks",
        )

        for (description in actionDescriptions) {
            composeTestRule.onNodeWithContentDescription(description)
                .assertWidthIsEqualTo(expectedSize)
                .assertHeightIsEqualTo(expectedSize)
        }
    }

    /** Regression guard for #424: the settings trigger must carry a visible
     * text label, not just an icon a user has to already know the meaning of. */
    @Test
    fun `settings action shows a persistent visible label`() {
        setTopBar()

        composeTestRule.onNodeWithText("Themes & Settings").assertIsDisplayed()
    }
}
