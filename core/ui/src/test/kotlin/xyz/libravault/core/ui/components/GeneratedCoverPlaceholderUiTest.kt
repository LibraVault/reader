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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.libravault.core.ui.theme.LibravaultTheme

/**
 * Covers issue #168: [GeneratedCover] must read as an explicit "no cover art"
 * placeholder, not as a real, deliberately-designed cover. Runs on Robolectric
 * (JVM, no emulator) — same setup as `:feature:library`'s `FormatFilterRowTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GeneratedCoverPlaceholderUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `always exposes a No cover art content description, regardless of size`() {
        composeTestRule.setContent {
            LibravaultTheme {
                GeneratedCover(title = "Dune", modifier = Modifier.size(40.dp))
            }
        }

        composeTestRule.onNodeWithContentDescription(NO_COVER_ART_DESCRIPTION).assertIsDisplayed()
    }

    @Test
    fun `shows the literal label at a comfortable width`() {
        composeTestRule.setContent {
            LibravaultTheme {
                GeneratedCover(title = "The Pragmatic Programmer", modifier = Modifier.size(160.dp))
            }
        }

        composeTestRule.onNodeWithText(NO_COVER_ART_DESCRIPTION).assertIsDisplayed()
    }

    @Test
    fun `omits the text label at a narrow width, like the MiniPlayerBar thumbnail`() {
        composeTestRule.setContent {
            LibravaultTheme {
                GeneratedCover(title = "The Pragmatic Programmer", modifier = Modifier.size(40.dp))
            }
        }

        composeTestRule.onNodeWithText(NO_COVER_ART_DESCRIPTION).assertDoesNotExist()
    }

    @Test
    fun `still renders deterministic initials so the identity cue is not lost`() {
        composeTestRule.setContent {
            LibravaultTheme {
                GeneratedCover(title = "Dune", modifier = Modifier.size(160.dp))
            }
        }

        composeTestRule.onNodeWithText(initialsFor("Dune")).assertIsDisplayed()
    }
}
