package xyz.libravault.feature.reader.components

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.libravault.core.ui.theme.LibravaultTheme
import xyz.libravault.core.ui.theme.ReadingPresets
import xyz.libravault.core.ui.theme.ReadingTheme
import xyz.libravault.feature.reader.FontFamily
import xyz.libravault.feature.reader.ReaderSettings
import xyz.libravault.feature.reader.toFontFamily

/** Robolectric/Compose coverage for [ReaderSettingsSheet]'s preset picker (#419) —
 * same setup as [ReaderTopBarUiTest]/`VaultReaderSettingsSheetUiTest`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReaderSettingsSheetUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun setSheet(settings: ReaderSettings = ReaderSettings(), showFontControls: Boolean = true) {
        composeTestRule.setContent {
            LibravaultTheme {
                ReaderSettingsSheet(
                    settings = settings,
                    showFontControls = showFontControls,
                    onThemeChanged = {},
                    onFontSizeChanged = {},
                    onFontFamilyChanged = {},
                    onLineSpacingChanged = {},
                    onScrollModeChanged = {},
                    onDismiss = {},
                )
            }
        }
    }

    @Test
    fun `all built-in presets are shown`() {
        setSheet()

        ReadingPresets.builtIns.forEach { preset ->
            composeTestRule.onNodeWithText(preset.label).assertExists()
        }
    }

    @Test
    fun `customize controls are hidden until Customize is tapped`() {
        setSheet()

        composeTestRule.onNodeWithText("Theme").assertDoesNotExist()
        composeTestRule.onNodeWithText("Line spacing").assertDoesNotExist()

        composeTestRule.onNodeWithText("Customize").performClick()

        composeTestRule.onNodeWithText("Theme").assertExists()
        composeTestRule.onNodeWithText("Line spacing").assertExists()
    }

    @Test
    fun `default settings match no preset`() {
        // ReaderSettings() defaults to FontFamily.SYSTEM, which none of the
        // built-in presets bundle with ReadingTheme.DARK — so this is "Custom".
        setSheet(settings = ReaderSettings())

        ReadingPresets.builtIns.forEach { preset ->
            composeTestRule.onNodeWithText(preset.label).assertIsNotSelected()
        }
    }

    @Test
    fun `settings matching a preset's bundle show it selected`() {
        val fireside = ReadingPresets.builtIns.first { it.id == "fireside" }
        setSheet(
            settings = ReaderSettings(
                theme       = fireside.theme,
                fontFamily  = fireside.fontFamily.toFontFamily(),
                fontSize    = fireside.fontSize,
                lineSpacing = fireside.lineSpacing,
            ),
        )

        composeTestRule.onNodeWithText(fireside.label).assertIsSelected()
    }

    @Test
    fun `tapping a preset applies all four of its fields`() {
        val parchment = ReadingPresets.builtIns.first { it.id == "parchment" }
        var appliedTheme: ReadingTheme? = null
        var appliedFontFamily: FontFamily? = null
        var appliedFontSize: Float? = null
        var appliedLineSpacing: Float? = null

        composeTestRule.setContent {
            LibravaultTheme {
                ReaderSettingsSheet(
                    settings = ReaderSettings(),
                    showFontControls = true,
                    onThemeChanged = { appliedTheme = it },
                    onFontSizeChanged = { appliedFontSize = it },
                    onFontFamilyChanged = { appliedFontFamily = it },
                    onLineSpacingChanged = { appliedLineSpacing = it },
                    onScrollModeChanged = {},
                    onDismiss = {},
                )
            }
        }

        composeTestRule.onNodeWithText(parchment.label).performClick()

        assert(appliedTheme == parchment.theme)
        assert(appliedFontFamily == parchment.fontFamily.toFontFamily())
        assert(appliedFontSize == parchment.fontSize)
        assert(appliedLineSpacing == parchment.lineSpacing)
    }

    @Test
    fun `Mode row remains visible without expanding Customize`() {
        setSheet()

        composeTestRule.onNodeWithText("Mode").assertExists()
    }
}
