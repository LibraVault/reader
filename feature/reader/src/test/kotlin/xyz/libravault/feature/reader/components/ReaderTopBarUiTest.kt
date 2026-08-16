package xyz.libravault.feature.reader.components

import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.libravault.core.ui.theme.LibravaultTheme

/** Robolectric/Compose coverage for [ReaderTopBar] — regression guard for #4
 * (icon buttons were visibly mismatched: the font-size/TOC/add-bookmark buttons
 * were 38.dp while the bookmarks/settings buttons fell back to Material3's
 * default 48.dp in the same bar). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReaderTopBarUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `all action icon buttons share the same size`() {
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

        val expectedSize = 38.dp
        val actionDescriptions = listOf(
            "Table of contents",
            "Add bookmark",
            "Bookmarks",
            "Reader settings",
        )

        for (description in actionDescriptions) {
            composeTestRule.onNodeWithContentDescription(description)
                .assertWidthIsEqualTo(expectedSize)
                .assertHeightIsEqualTo(expectedSize)
        }
    }
}
