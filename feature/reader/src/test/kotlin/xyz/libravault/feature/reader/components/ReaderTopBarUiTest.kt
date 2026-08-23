package xyz.libravault.feature.reader.components

import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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

    private fun setTopBar(
        showReadAloud: Boolean = false,
        readAloudActive: Boolean = false,
        onReadAloudClick: () -> Unit = {},
    ) {
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
                    showReadAloud = showReadAloud,
                    readAloudActive = readAloudActive,
                    onReadAloudClick = onReadAloudClick,
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

    // ── Read Aloud toolbar promotion ──────────────────────────────────────────
    // It used to be a row inside the settings sheet (Settings > Customize >
    // scroll); a live usability catch found that buried a headline feature, so
    // it's now this bar's one filled (not outlined) action. These guard both
    // ends: the button shows up when the format supports it, and it's actually
    // gone — not just disabled — when it doesn't (PDF).

    @Test
    fun `Read Aloud action is absent when the format doesn't support it`() {
        setTopBar(showReadAloud = false)

        composeTestRule.onNodeWithContentDescription("Read Aloud").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Stop Read Aloud").assertDoesNotExist()
    }

    @Test
    fun `Read Aloud action is shown when inactive`() {
        setTopBar(showReadAloud = true, readAloudActive = false)

        composeTestRule.onNodeWithContentDescription("Read Aloud").assertIsDisplayed()
    }

    @Test
    fun `Read Aloud action switches to a stop affordance while active`() {
        setTopBar(showReadAloud = true, readAloudActive = true)

        composeTestRule.onNodeWithContentDescription("Stop Read Aloud").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Read Aloud").assertDoesNotExist()
    }

    @Test
    fun `tapping the Read Aloud action invokes the callback`() {
        var clicked = false
        setTopBar(showReadAloud = true, onReadAloudClick = { clicked = true })

        composeTestRule.onNodeWithContentDescription("Read Aloud").performClick()

        assert(clicked)
    }
}
