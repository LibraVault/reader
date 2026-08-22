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

    private fun setSheet(
        settings: VaultReaderSettings = VaultReaderSettings(),
        showFontControls: Boolean = true,
        showEpubLayoutControls: Boolean = false,
        onMarginScaleChanged: (Float) -> Unit = {},
        onJustifyTextChanged: (Boolean) -> Unit = {},
        onHyphenationChanged: (Boolean) -> Unit = {},
    ) {
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
                    onWarmthChanged = {},
                    onDismiss = {},
                    showEpubLayoutControls = showEpubLayoutControls,
                    onMarginScaleChanged = onMarginScaleChanged,
                    onJustifyTextChanged = onJustifyTextChanged,
                    onHyphenationChanged = onHyphenationChanged,
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
    fun `Amoled appears as a 5th theme chip and can be shown selected`() {
        // #420: same "ReadingTheme.entries.forEach needs no sheet changes" regression
        // guard as the System test above, for the newest ReadingTheme case.
        setSheet(settings = VaultReaderSettings(theme = ReadingTheme.AMOLED))

        composeTestRule.onNodeWithText("Customize").click()

        composeTestRule.onNodeWithText("Amoled").assertIsSelected()
        composeTestRule.onNodeWithText("Dark").assertExists()
        composeTestRule.onNodeWithText("Light").assertExists()
        composeTestRule.onNodeWithText("Sepia").assertExists()
        composeTestRule.onNodeWithText("System").assertExists()
    }

    // ── Warmth (#422) ────────────────────────────────────────────────────────

    @Test
    fun `warmth control is shown once Customize is expanded, even for PDF (showFontControls = false)`() {
        // Same rationale as feature:reader's ReaderSettingsSheet — warmth is a
        // screen-level overlay, unlike font size/line spacing/font family.
        setSheet(showFontControls = false)

        composeTestRule.onNodeWithText("Warmth").assertDoesNotExist()

        composeTestRule.onNodeWithText("Customize").click()

        composeTestRule.onNodeWithText("Warmth").assertExists()
    }

    @Test
    fun `warmth percentage reflects the current setting`() {
        setSheet(settings = VaultReaderSettings(warmth = 0.5f))

        composeTestRule.onNodeWithText("Customize").click()

        composeTestRule.onNodeWithText("50%").assertExists()
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
                    onWarmthChanged = {},
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

    // ── Margins/justification/hyphenation (#421) ───────────────────────────────

    @Test
    fun `layout controls are hidden when showEpubLayoutControls is false, even with Customize expanded`() {
        setSheet(showEpubLayoutControls = false)

        composeTestRule.onNodeWithText("Customize").click()

        composeTestRule.onNodeWithText("Margins").assertDoesNotExist()
        composeTestRule.onNodeWithText("Justify text").assertDoesNotExist()
        composeTestRule.onNodeWithText("Hyphenation").assertDoesNotExist()
    }

    @Test
    fun `layout controls appear once Customize is expanded when showEpubLayoutControls is true`() {
        setSheet(showEpubLayoutControls = true)

        composeTestRule.onNodeWithText("Margins").assertDoesNotExist()

        composeTestRule.onNodeWithText("Customize").click()

        composeTestRule.onNodeWithText("Margins").assertExists()
        composeTestRule.onNodeWithText("Justify text").assertExists()
        composeTestRule.onNodeWithText("Hyphenation").assertExists()
    }

    @Test
    fun `tapping the Justify text row invokes onJustifyTextChanged with the flipped value`() {
        var applied: Boolean? = null
        setSheet(
            settings = VaultReaderSettings(justifyText = false),
            showEpubLayoutControls = true,
            onJustifyTextChanged = { applied = it },
        )

        composeTestRule.onNodeWithText("Customize").click()
        composeTestRule.onNodeWithText("Justify text").click()

        assert(applied == true)
    }

    @Test
    fun `tapping the Hyphenation row invokes onHyphenationChanged with the flipped value`() {
        var applied: Boolean? = null
        setSheet(
            settings = VaultReaderSettings(hyphenation = true),
            showEpubLayoutControls = true,
            onHyphenationChanged = { applied = it },
        )

        composeTestRule.onNodeWithText("Customize").click()
        composeTestRule.onNodeWithText("Hyphenation").click()

        assert(applied == false)
    }

    @Test
    fun `tapping the Easy Read preset applies OpenDyslexic and its bundled spacing`() {
        // #423 — "Easy Read" is the accessibility preset ReadingPresets.builtIns
        // gained alongside the standalone OpenDyslexic font option.
        val easyRead = ReadingPresets.builtIns.first { it.id == "easy_read" }
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
                    onWarmthChanged = {},
                    onDismiss = {},
                )
            }
        }

        composeTestRule.onNodeWithText(easyRead.label).click()

        assert(appliedTheme == easyRead.theme)
        assert(appliedFontFamily == VaultReaderFontFamily.OPEN_DYSLEXIC)
        assert(appliedFontSize == easyRead.fontSize)
        assert(appliedLineSpacing == easyRead.lineSpacing)
    }
}
