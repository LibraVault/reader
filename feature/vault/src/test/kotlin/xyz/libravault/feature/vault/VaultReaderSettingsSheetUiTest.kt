package xyz.libravault.feature.vault

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.libravault.core.ui.theme.LibravaultTheme
import xyz.libravault.core.ui.theme.ReadingTheme

/** Robolectric/Compose coverage for [VaultReaderSettingsSheet] — same setup
 * as `VaultBookmarksSheetUiTest`/`VaultCoverPlaceholderUiTest`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VaultReaderSettingsSheetUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `font controls are shown when showFontControls is true`() {
        composeTestRule.setContent {
            LibravaultTheme {
                VaultReaderSettingsSheet(
                    settings = VaultReaderSettings(),
                    showFontControls = true,
                    onThemeChanged = {},
                    onFontSizeChanged = {},
                    onFontFamilyChanged = {},
                    onLineSpacingChanged = {},
                    onDismiss = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Line spacing").assertExists()
        composeTestRule.onNodeWithText("Font").assertExists()
    }

    @Test
    fun `font controls are hidden for PDF (showFontControls = false)`() {
        composeTestRule.setContent {
            LibravaultTheme {
                VaultReaderSettingsSheet(
                    settings = VaultReaderSettings(),
                    showFontControls = false,
                    onThemeChanged = {},
                    onFontSizeChanged = {},
                    onFontFamilyChanged = {},
                    onLineSpacingChanged = {},
                    onDismiss = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Line spacing").assertDoesNotExist()
        composeTestRule.onNodeWithText("Font").assertDoesNotExist()
        // Theme is still offered — it's the one setting that applies regardless of format.
        composeTestRule.onNodeWithText("Theme").assertExists()
    }

    @Test
    fun `the current theme chip is shown selected`() {
        composeTestRule.setContent {
            LibravaultTheme {
                VaultReaderSettingsSheet(
                    settings = VaultReaderSettings(theme = ReadingTheme.SEPIA),
                    showFontControls = true,
                    onThemeChanged = {},
                    onFontSizeChanged = {},
                    onFontFamilyChanged = {},
                    onLineSpacingChanged = {},
                    onDismiss = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Sepia").assertIsSelected()
    }
}
