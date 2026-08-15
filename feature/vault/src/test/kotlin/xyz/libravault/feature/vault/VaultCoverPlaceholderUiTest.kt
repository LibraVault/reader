package xyz.libravault.feature.vault

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.libravault.core.ui.components.NO_COVER_ART_DESCRIPTION
import xyz.libravault.core.ui.theme.LibravaultTheme

/**
 * Covers issue #169's placeholder half: a vault entry with no cover art must
 * read as "encrypted, no cover" — not the app's generic no-cover treatment
 * shown bare, and not something that looks like a failed decrypt. Runs on
 * Robolectric, same setup as `core:ui`'s `GeneratedCoverPlaceholderUiTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VaultCoverPlaceholderUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `shows the padlock badge on top of the generic no-cover-art placeholder`() {
        composeTestRule.setContent {
            LibravaultTheme {
                VaultCoverPlaceholder(title = "Confidential Report", modifier = Modifier.size(96.dp))
            }
        }

        composeTestRule.onNodeWithContentDescription("Encrypted vault item").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(NO_COVER_ART_DESCRIPTION).assertIsDisplayed()
    }
}
