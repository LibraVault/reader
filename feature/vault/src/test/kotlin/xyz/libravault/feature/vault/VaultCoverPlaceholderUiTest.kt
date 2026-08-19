package xyz.libravault.feature.vault

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
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
                // An unrecognized format (see CoverFormatBadge.fromFormatName) deliberately
                // exercises the generic fallback path, keeping this test's original,
                // format-agnostic assertion meaningful rather than coupling it to a
                // specific format's label.
                VaultCoverPlaceholder(title = "Confidential Report", format = "MOBI", modifier = Modifier.size(96.dp))
            }
        }

        composeTestRule.onNodeWithContentDescription("Encrypted vault item").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(NO_COVER_ART_DESCRIPTION).assertIsDisplayed()
    }

    /** (#308) A recognized format still shows through the padlock overlay. */
    @Test
    fun `format-specific badge shows through the padlock overlay`() {
        composeTestRule.setContent {
            LibravaultTheme {
                VaultCoverPlaceholder(title = "Confidential Report", format = "PDF", modifier = Modifier.size(96.dp))
            }
        }

        composeTestRule.onNodeWithContentDescription("Encrypted vault item").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("$NO_COVER_ART_DESCRIPTION — PDF").assertIsDisplayed()
        composeTestRule.onNodeWithText("PDF").assertIsDisplayed()
    }
}
