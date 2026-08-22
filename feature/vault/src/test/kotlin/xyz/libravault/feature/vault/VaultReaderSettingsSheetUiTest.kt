package xyz.libravault.feature.vault

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
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

/** Robolectric/Compose coverage for [VaultReaderSettingsSheet] — same setup
 * as `VaultBookmarksSheetUiTest`/`VaultCoverPlaceholderUiTest`.
 *
 * Uses [createAndroidComposeRule] (real Activity host), same as
 * `SecureScreenEffectTest`/`LibravaultThemeTest`. Interactions use
 * [click] (invoking the `OnClick` semantics action directly) rather than
 * [androidx.compose.ui.test.performClick] (a real coordinate-based touch
 * gesture) — [VaultReaderSettingsSheet] renders inside a `ModalBottomSheet`,
 * which hosts its content in its own Popup/Dialog window, and gesture-based
 * clicks on descendants of that second window don't reliably register in
 * this Robolectric setup (confirmed empirically while adding the #419
 * preset/Customize click-interaction tests below — the pre-existing tests
 * never exercised a click, only direct-state assertions, so this gap was
 * latent). Invoking the semantics action directly sidesteps the gesture
 * dispatch path entirely and is reliable here. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VaultReaderSettingsSheetUiTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private fun SemanticsNodeInteraction.click() = performSemanticsAction(SemanticsActions.OnClick)

    private fun setSheet(settings: VaultReaderSettings = VaultReaderSettings(), showFontControls: Boolean = true) {
        composeTestRule.setContent {
            LibravaultTheme {
                VaultReaderSettingsSheet(
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
    fun `font controls are shown when showFontControls is true and Customize is expanded`() {
        setSheet(showFontControls = true)

        composeTestRule.onNodeWithText("Customize").click()

        composeTestRule.onNodeWithText("Line spacing").assertExists()
        composeTestRule.onNodeWithText("Font").assertExists()
    }

    @Test
    fun `font controls are hidden for PDF (showFontControls = false) even with Customize expanded`() {
        setSheet(showFontControls = false)

        composeTestRule.onNodeWithText("Customize").click()

        composeTestRule.onNodeWithText("Line spacing").assertDoesNotExist()
        composeTestRule.onNodeWithText("Font").assertDoesNotExist()
        // Theme is still offered — it's the one setting that applies regardless of format.
        composeTestRule.onNodeWithText("Theme").assertExists()
    }

    @Test
    fun `the current theme chip is shown selected once Customize is expanded`() {
        setSheet(settings = VaultReaderSettings(theme = ReadingTheme.SEPIA))

        composeTestRule.onNodeWithText("Customize").click()

        composeTestRule.onNodeWithText("Sepia").assertIsSelected()
    }

    @Test
    fun `System appears as a 4th theme chip and can be shown selected`() {
        // #349/#370: ReadingTheme.entries.forEach in VaultReaderSettingsSheet is what
        // makes this appear with no changes to the sheet itself — this test is the
        // regression guard for that.
        setSheet(settings = VaultReaderSettings(theme = ReadingTheme.SYSTEM))

        composeTestRule.onNodeWithText("Customize").click()

        composeTestRule.onNodeWithText("System").assertIsSelected()
        composeTestRule.onNodeWithText("Dark").assertExists()
        composeTestRule.onNodeWithText("Light").assertExists()
        composeTestRule.onNodeWithText("Sepia").assertExists()
    }

    @Test
    fun `OpenDyslexic appears as a font chip and can be shown selected once Customize is expanded`() {
        // #423 — VaultReaderFontFamily.entries.forEach in VaultReaderSettingsSheet is
        // what makes this appear with no changes to the sheet itself, same regression-
        // guard shape as the theme SYSTEM chip test above.
        setSheet(settings = VaultReaderSettings(fontFamily = VaultReaderFontFamily.OPEN_DYSLEXIC))

        composeTestRule.onNodeWithText("Customize").click()

        composeTestRule.onNodeWithText("OpenDyslexic (dyslexia-friendly)").assertIsSelected()
        composeTestRule.onNodeWithText("System Default").assertExists()
    }

    @Test
    fun `scroll mode row is shown even when font controls are hidden and Customize is collapsed`() {
        setSheet(settings = VaultReaderSettings(scrollMode = VaultScrollMode.SCROLLING), showFontControls = false)

        composeTestRule.onNodeWithText("Mode").assertExists()
        composeTestRule.onNodeWithText("Scrolling").assertIsSelected()
        composeTestRule.onNodeWithText("Paginated").assertExists()
    }

    // ── Presets (#419) ───────────────────────────────────────────────────────

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

        composeTestRule.onNodeWithText("Customize").click()

        composeTestRule.onNodeWithText("Theme").assertExists()
    }

    @Test
    fun `default settings match no preset`() {
        // VaultReaderSettings() defaults to VaultReaderFontFamily.SYSTEM, which none
        // of the built-in presets bundle with ReadingTheme.DARK — so this is "Custom".
        setSheet(settings = VaultReaderSettings())

        ReadingPresets.builtIns.forEach { preset ->
            composeTestRule.onNodeWithText(preset.label).assertIsNotSelected()
        }
    }

    @Test
    fun `settings matching a preset's bundle show it selected`() {
        val fireside = ReadingPresets.builtIns.first { it.id == "fireside" }
        setSheet(
            settings = VaultReaderSettings(
                theme       = fireside.theme,
                fontFamily  = fireside.fontFamily.toVaultReaderFontFamily(),
                fontSize    = fireside.fontSize,
                lineSpacing = fireside.lineSpacing,
            ),
        )

        composeTestRule.onNodeWithText(fireside.label).assertIsSelected()
    }

    @Test
    fun `tapping a preset applies all four of its fields`() {
        val daylight = ReadingPresets.builtIns.first { it.id == "daylight" }
        var appliedTheme: ReadingTheme? = null
        var appliedFontFamily: VaultReaderFontFamily? = null
        var appliedFontSize: Float? = null
        var appliedLineSpacing: Float? = null

        composeTestRule.setContent {
            LibravaultTheme {
                VaultReaderSettingsSheet(
                    settings = VaultReaderSettings(),
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

        composeTestRule.onNodeWithText(daylight.label).click()

        assert(appliedTheme == daylight.theme)
        assert(appliedFontFamily == daylight.fontFamily.toVaultReaderFontFamily())
        assert(appliedFontSize == daylight.fontSize)
        assert(appliedLineSpacing == daylight.lineSpacing)
    }
}
