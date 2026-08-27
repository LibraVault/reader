package xyz.libravault.feature.library

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.libravault.core.domain.model.LibraryItem
import xyz.libravault.core.domain.model.MediaFormat

/**
 * Compose UI coverage for [LibraryItemCard]'s padlock badge (Phase 3, #508) —
 * the one visible signal in the main Library grid that an item comes from an
 * Encrypted Vault rather than a real file. Runs on Robolectric, mirroring
 * :feature:library's own FormatFilterRowTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryItemCardTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun item(filePath: String) = LibraryItem(
        id = 1,
        vaultFolderId = 1,
        filePath = filePath,
        title = "Some Book",
        author = "Author",
        format = MediaFormat.EPUB,
    )

    @Test
    fun `shows the padlock badge for a vault item`() {
        composeTestRule.setContent {
            LibraryItemCard(item = item("vault://vault-1/aabbcc"), onClick = {})
        }

        composeTestRule.onNodeWithContentDescription("Encrypted vault item").assertIsDisplayed()
    }

    @Test
    fun `does not show the padlock badge for a real file`() {
        composeTestRule.setContent {
            LibraryItemCard(item = item("content://tree/books/some.epub"), onClick = {})
        }

        composeTestRule.onNodeWithContentDescription("Encrypted vault item").assertDoesNotExist()
    }
}
