package xyz.libravault.feature.reader.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.navigator.preferences.FontFamily as ReadiumFontFamily
import org.readium.r2.shared.ExperimentalReadiumApi
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.libravault.core.ui.theme.ReadingTheme
import xyz.libravault.feature.reader.FontFamily
import xyz.libravault.feature.reader.ReaderSettings
import xyz.libravault.feature.reader.ScrollMode

/**
 * Covers [ReaderSettings.toEpubPreferences]'s font-family/letter-spacing mapping
 * (#423) — in particular that [FontFamily.OPEN_DYSLEXIC] resolves to Readium's
 * already-bundled `ReadiumFontFamily.OPEN_DYSLEXIC` and picks up the paired
 * letter-spacing bump, while every other family stays exactly as before
 * (no letter-spacing override).
 *
 * Runs on Robolectric, not plain JVM JUnit5, despite [toEpubPreferences] being
 * pure Kotlin logic with no Android call of its own: `EpubPreferences`'
 * companion types (`Theme`, `FontFamily`) have a static initializer that calls
 * `android.graphics.Color.parseColor`, which throws "not mocked" without a real
 * (or Robolectric-shadowed) Android runtime. Confirmed by running this against
 * plain JUnit5 first — every case failed with that exact cause, not a logic bug.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalReadiumApi::class)
class EpubPreferencesMappingTest {

    private val baseSettings = ReaderSettings(
        theme = ReadingTheme.LIGHT,
        fontSize = 1.2f,
        lineSpacing = 1.6f,
        scrollMode = ScrollMode.PAGINATED,
    )

    @Test
    fun `OPEN_DYSLEXIC maps to Readium's built-in OpenDyslexic font family`() {
        val prefs = baseSettings.copy(fontFamily = FontFamily.OPEN_DYSLEXIC)
            .toEpubPreferences(systemInDarkTheme = false)

        assertEquals(ReadiumFontFamily.OPEN_DYSLEXIC, prefs.fontFamily)
    }

    @Test
    fun `OPEN_DYSLEXIC bundles a non-null letter-spacing bump`() {
        val prefs = baseSettings.copy(fontFamily = FontFamily.OPEN_DYSLEXIC)
            .toEpubPreferences(systemInDarkTheme = false)

        assertEquals(0.125, prefs.letterSpacing)
    }

    @Test
    fun `SYSTEM font leaves fontFamily and letterSpacing both null`() {
        val prefs = baseSettings.copy(fontFamily = FontFamily.SYSTEM)
            .toEpubPreferences(systemInDarkTheme = false)

        assertNull(prefs.fontFamily)
        assertNull(prefs.letterSpacing)
    }

    @Test
    fun `SERIF SANS_SERIF and MONOSPACE map to their Readium equivalents with no letter-spacing override`() {
        val serif = baseSettings.copy(fontFamily = FontFamily.SERIF).toEpubPreferences(false)
        val sansSerif = baseSettings.copy(fontFamily = FontFamily.SANS_SERIF).toEpubPreferences(false)
        val monospace = baseSettings.copy(fontFamily = FontFamily.MONOSPACE).toEpubPreferences(false)

        assertEquals(ReadiumFontFamily.SERIF, serif.fontFamily)
        assertEquals(ReadiumFontFamily.SANS_SERIF, sansSerif.fontFamily)
        assertEquals(ReadiumFontFamily.MONOSPACE, monospace.fontFamily)
        assertNull(serif.letterSpacing)
        assertNull(sansSerif.letterSpacing)
        assertNull(monospace.letterSpacing)
    }

    @Test
    fun `fontSize and lineHeight pass through as raw multipliers`() {
        val prefs = baseSettings.toEpubPreferences(systemInDarkTheme = false)

        // Compared against the Float->Double widening of the same literals (not the
        // Double literals directly) — 1.2f.toDouble() != 1.2 due to Float's lower
        // precision, and ReaderSettings.fontSize/lineSpacing are Float.
        assertEquals(1.2f.toDouble(), prefs.fontSize)
        assertEquals(1.6f.toDouble(), prefs.lineHeight)
    }
}
