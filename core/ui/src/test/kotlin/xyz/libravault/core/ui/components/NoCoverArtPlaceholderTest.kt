package xyz.libravault.core.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.robolectric.annotation.Config
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import xyz.libravault.core.ui.theme.LibravaultTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NoCoverArtPlaceholderTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `wide enough for a label shows the No cover art text`() {
        composeTestRule.setContent {
            LibravaultTheme {
                NoCoverArtPlaceholder(modifier = Modifier.size(160.dp))
            }
        }

        composeTestRule.onNodeWithText("No cover art").assertIsDisplayed()
    }

    @Test
    fun `too narrow for a label falls back to an icon with a content description`() {
        composeTestRule.setContent {
            LibravaultTheme {
                NoCoverArtPlaceholder(modifier = Modifier.size(40.dp))
            }
        }

        composeTestRule.onNodeWithText("No cover art").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("No cover art").assertIsDisplayed()
    }
}
