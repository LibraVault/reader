package xyz.libravault.feature.reader.components

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
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
 * same setup as [ReaderTopBarUiTest]/`VaultReaderSettingsSheetUiTest`.
 *
 * Uses [createAndroidComposeRule] (real Activity host), same as
 * `SecureScreenEffectTest`/`LibravaultThemeTest`. Interactions use [click]
 * (invoking the `OnClick` semantics action directly) rather than
 * [androidx.compose.ui.test.performClick] (a real coordinate-based touch
 * gesture) — [ReaderSettingsSheet] renders inside a `ModalBottomSheet`, which
 * hosts its content in its own Popup/Dialog window, and gesture-based clicks
 * on descendants of that second window don't reliably register in this
 * Robolectric setup (confirmed empirically while adding these tests — the
 * pre-existing sheet tests never exercised a click, only direct-state
 * assertions, so this gap was latent). Invoking the semantics action
 * directly sidesteps the gesture dispatch path entirely and is reliable
 * here. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReaderSettingsSheetUiTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private fun SemanticsNodeInteraction.click() = performSemanticsAction(SemanticsActions.OnClick)

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
                    onWarmthChanged = {},
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

        composeTestRule.onNodeWithText("Customize").click()

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
                    onWarmthChanged = {},
                    onDismiss = {},
                )
            }
        }

        composeTestRule.onNodeWithText(parchment.label).click()

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

    @Test
    fun `OpenDyslexic appears as a font chip and can be shown selected once Customize is expanded`() {
        // #423 — FontFamily.entries.forEach in ReaderSettingsSheet is what makes
        // this appear with no changes to the sheet itself, same regression-guard
        // shape as `VaultReaderSettingsSheetUiTest`'s equivalent case.
        setSheet(settings = ReaderSettings(fontFamily = FontFamily.OPEN_DYSLEXIC))

        composeTestRule.onNodeWithText("Customize").click()

        composeTestRule.onNodeWithText("OpenDyslexic (dyslexia-friendly)").assertIsSelected()
        composeTestRule.onNodeWithText("System Default").assertExists()
    }

    @Test
    fun `tapping the Easy Read preset applies OpenDyslexic and its bundled spacing`() {
        // #423 — "Easy Read" is the accessibility preset ReadingPresets.builtIns
        // gained alongside the standalone OpenDyslexic font option.
        val easyRead = ReadingPresets.builtIns.first { it.id == "easy_read" }
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
                    onWarmthChanged = {},
                    onDismiss = {},
                )
            }
        }

        composeTestRule.onNodeWithText(easyRead.label).click()

        assert(appliedTheme == easyRead.theme)
        assert(appliedFontFamily == FontFamily.OPEN_DYSLEXIC)
        assert(appliedFontSize == easyRead.fontSize)
        assert(appliedLineSpacing == easyRead.lineSpacing)
    }

    // ── Warmth (#422) ────────────────────────────────────────────────────────

    @Test
    fun `warmth control is shown once Customize is expanded, even for PDF (showFontControls = false)`() {
        // Unlike font size/line spacing/font family — HTML CSS-driven, no hook for PDF —
        // warmth is a screen-level overlay that applies to every format, so it must not
        // be gated behind showFontControls the way those are.
        setSheet(showFontControls = false)

        composeTestRule.onNodeWithText("Warmth").assertDoesNotExist()

        composeTestRule.onNodeWithText("Customize").click()

        composeTestRule.onNodeWithText("Warmth").assertExists()
    }

    @Test
    fun `warmth percentage reflects the current setting`() {
        setSheet(settings = ReaderSettings(warmth = 0.5f))

        composeTestRule.onNodeWithText("Customize").click()

        composeTestRule.onNodeWithText("50%").assertExists()
    }

    @Test
    fun `warmth defaults to 0 percent`() {
        setSheet()

        composeTestRule.onNodeWithText("Customize").click()

        composeTestRule.onNodeWithText("0%").assertExists()
    }
}
