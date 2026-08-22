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
                    onScrollModeChanged = {},
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
                    onScrollModeChanged = {},
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
                    onScrollModeChanged = {},
                    onDismiss = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Sepia").assertIsSelected()
    }

    @Test
    fun `System appears as a 4th theme chip and can be shown selected`() {
        // #349/#370: ReadingTheme.entries.forEach in VaultReaderSettingsSheet is what
        // makes this appear with no changes to the sheet itself — this test is the
        // regression guard for that.
        composeTestRule.setContent {
            LibravaultTheme {
                VaultReaderSettingsSheet(
                    settings = VaultReaderSettings(theme = ReadingTheme.SYSTEM),
                    showFontControls = true,
                    onThemeChanged = {},
                    onFontSizeChanged = {},
                    onFontFamilyChanged = {},
                    onLineSpacingChanged = {},
                    onScrollModeChanged = {},
                    onDismiss = {},
                )
            }
        }

        composeTestRule.onNodeWithText("System").assertIsSelected()
        composeTestRule.onNodeWithText("Dark").assertExists()
        composeTestRule.onNodeWithText("Light").assertExists()
        composeTestRule.onNodeWithText("Sepia").assertExists()
    }

    @Test
    fun `OpenDyslexic appears as a font chip and can be shown selected`() {
        // #423 — VaultReaderFontFamily.entries.forEach in VaultReaderSettingsSheet is
        // what makes this appear with no changes to the sheet itself, same regression-
        // guard shape as the theme SYSTEM chip test above.
        composeTestRule.setContent {
            LibravaultTheme {
                VaultReaderSettingsSheet(
                    settings = VaultReaderSettings(fontFamily = VaultReaderFontFamily.OPEN_DYSLEXIC),
                    showFontControls = true,
                    onThemeChanged = {},
                    onFontSizeChanged = {},
                    onFontFamilyChanged = {},
                    onLineSpacingChanged = {},
                    onScrollModeChanged = {},
                    onDismiss = {},
                )
            }
        }

        composeTestRule.onNodeWithText("OpenDyslexic (dyslexia-friendly)").assertIsSelected()
        composeTestRule.onNodeWithText("System Default").assertExists()
    }

    @Test
    fun `scroll mode row is shown even when font controls are hidden`() {
        composeTestRule.setContent {
            LibravaultTheme {
                VaultReaderSettingsSheet(
                    settings = VaultReaderSettings(scrollMode = VaultScrollMode.SCROLLING),
                    showFontControls = false,
                    onThemeChanged = {},
                    onFontSizeChanged = {},
                    onFontFamilyChanged = {},
                    onLineSpacingChanged = {},
                    onScrollModeChanged = {},
                    onDismiss = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Mode").assertExists()
        composeTestRule.onNodeWithText("Scrolling").assertIsSelected()
        composeTestRule.onNodeWithText("Paginated").assertExists()
    }
}
