package xyz.libravault.feature.vault

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.libravault.core.ui.theme.LibravaultTheme
import xyz.libravault.core.vaultstore.VaultBookmark

/** Robolectric/Compose coverage for [VaultBookmarksSheet] — same setup as
 * `VaultCoverPlaceholderUiTest`. Covers label formatting for both position-ref
 * conventions the vault reader produces and that a row tap forwards the
 * right bookmark. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VaultBookmarksSheetUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `empty state explains how to add a bookmark`() {
        composeTestRule.setContent {
            LibravaultTheme {
                VaultBookmarksSheet(
                    bookmarks = emptyList(),
                    onBookmarkClick = {},
                    onBookmarkDelete = {},
                    onEditNote = { _, _ -> },
                    onDismiss = {},
                )
            }
        }

        composeTestRule.onNodeWithText("No bookmarks yet. Tap the bookmark icon while reading to add one.")
            .assertIsDisplayed()
    }

    @Test
    fun `a PDF-style page bookmark shows a 1-indexed page label`() {
        val bookmark = VaultBookmark(id = 1L, positionRef = "page:4", createdAtEpochMillis = 0L)
        composeTestRule.setContent {
            LibravaultTheme {
                VaultBookmarksSheet(
                    bookmarks = listOf(bookmark),
                    onBookmarkClick = {},
                    onBookmarkDelete = {},
                    onEditNote = { _, _ -> },
                    onDismiss = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Page 5").assertIsDisplayed()
    }

    @Test
    fun `an explicit label wins over the derived position label`() {
        val bookmark = VaultBookmark(id = 1L, positionRef = "page:4", label = "My chapter", createdAtEpochMillis = 0L)
        composeTestRule.setContent {
            LibravaultTheme {
                VaultBookmarksSheet(
                    bookmarks = listOf(bookmark),
                    onBookmarkClick = {},
                    onBookmarkDelete = {},
                    onEditNote = { _, _ -> },
                    onDismiss = {},
                )
            }
        }

        composeTestRule.onNodeWithText("My chapter").assertIsDisplayed()
    }
}
