package xyz.libravault.feature.reader.markdown

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.isSpecified
import com.mikepenz.markdown.model.MarkdownTypography
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.libravault.core.ui.theme.LibravaultTheme
import xyz.libravault.core.ui.theme.OpenDyslexicFontFamily
import xyz.libravault.feature.reader.FontFamily
import xyz.libravault.feature.reader.ReaderSettings
import androidx.compose.ui.text.font.FontFamily as ComposeFontFamily

/**
 * Covers [rememberMarkdownTypography]'s OpenDyslexic font-family/letter-spacing
 * wiring (#423) — this is the Markdown/Compose-rendered half of the same
 * accessibility option `EpubPreferencesMappingTest` covers for the EPUB/WebView
 * half. Runs on Robolectric (JVM, no emulator), same setup as
 * `MarkdownTableRenderingTest` in this package.
 *
 * Wrapped in [LibravaultTheme] so `MaterialTheme.typography` resolves to this
 * app's own [xyz.libravault.core.ui.theme.LibravaultTypography] (which leaves
 * body/label roles' letterSpacing unset) rather than Material3's own baseline
 * defaults (which set a non-zero letterSpacing for every role) — matching how
 * [MarkdownReaderScreen] actually calls this in production.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MarkdownTypographyTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun typographyFor(settings: ReaderSettings): MarkdownTypography {
        lateinit var captured: MarkdownTypography
        composeTestRule.setContent {
            LibravaultTheme {
                captured = rememberMarkdownTypography(settings)
            }
        }
        composeTestRule.waitForIdle()
        return captured
    }

    /** setContent can only be called once per test (Compose UI test rule
     * limitation) — this captures both typographies in a single composition
     * for tests that need to compare two [ReaderSettings]. */
    private fun typographiesFor(a: ReaderSettings, b: ReaderSettings): Pair<MarkdownTypography, MarkdownTypography> {
        lateinit var capturedA: MarkdownTypography
        lateinit var capturedB: MarkdownTypography
        composeTestRule.setContent {
            LibravaultTheme {
                capturedA = rememberMarkdownTypography(a)
                capturedB = rememberMarkdownTypography(b)
            }
        }
        composeTestRule.waitForIdle()
        return capturedA to capturedB
    }

    @Test
    fun `OPEN_DYSLEXIC resolves body and heading text to the bundled OpenDyslexic font family`() {
        val typography = typographyFor(ReaderSettings(fontFamily = FontFamily.OPEN_DYSLEXIC))

        assertEquals(OpenDyslexicFontFamily, typography.paragraph.fontFamily)
        assertEquals(OpenDyslexicFontFamily, typography.h1.fontFamily)
    }

    @Test
    fun `SYSTEM font resolves to the platform default Compose font family`() {
        val typography = typographyFor(ReaderSettings(fontFamily = FontFamily.SYSTEM))

        assertEquals(ComposeFontFamily.Default, typography.paragraph.fontFamily)
    }

    @Test
    fun `OPEN_DYSLEXIC gives body text a specified letter-spacing, unlike the default font`() {
        val (default, dyslexic) = typographiesFor(
            ReaderSettings(fontFamily = FontFamily.SYSTEM),
            ReaderSettings(fontFamily = FontFamily.OPEN_DYSLEXIC),
        )

        assertFalse(default.paragraph.letterSpacing.isSpecified)
        assertTrue(dyslexic.paragraph.letterSpacing.isSpecified)
        assertEquals(0.4f, dyslexic.paragraph.letterSpacing.value)
    }

    @Test
    fun `OPEN_DYSLEXIC does not bleed its letter-spacing bump into Monospace code styles`() {
        val typography = typographyFor(ReaderSettings(fontFamily = FontFamily.OPEN_DYSLEXIC))

        assertEquals(ComposeFontFamily.Monospace, typography.code.fontFamily)
        assertEquals(ComposeFontFamily.Monospace, typography.inlineCode.fontFamily)
        assertFalse(typography.code.letterSpacing.isSpecified)
        assertFalse(typography.inlineCode.letterSpacing.isSpecified)
    }
}
