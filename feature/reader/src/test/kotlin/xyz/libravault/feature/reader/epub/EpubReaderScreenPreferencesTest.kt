package xyz.libravault.feature.reader.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.readium.r2.navigator.preferences.Color as ReadiumColor
import org.readium.r2.navigator.preferences.Theme
import xyz.libravault.core.ui.theme.ReadingTheme
import xyz.libravault.feature.reader.ReaderSettings

/**
 * Unit tests for [toEpubPreferences]. Runs on Robolectric (same setup as
 * [xyz.libravault.core.ui.theme.LibravaultThemeTest]) rather than a plain JVM test:
 * Readium's own [Theme]/[ReadiumColor] classes call `android.graphics.Color.parseColor`
 * at class-init time (`Types.kt`'s file-level constants), which throws "not mocked"
 * without Robolectric's android.jar shim — confirmed the hard way, not assumed, by
 * running this file as a plain JUnit 5 test first and watching every case fail with
 * `ExceptionInInitializerError` / `NoClassDefFoundError` before switching to this setup.
 *
 * #420's true-black claim lives entirely in [ReaderSettings.toEpubPreferences]'s
 * `backgroundColor`/`textColor` override: Readium's own [Theme] enum has no true-black
 * case, so `theme` alone maps AMOLED to [Theme.DARK] — these tests are what actually
 * proves the override is applied only for AMOLED and not silently for every dark-ish
 * theme.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EpubReaderScreenPreferencesTest {

    @Test
    fun `AMOLED maps to Theme DARK with pure black background and white text overrides`() {
        val prefs = ReaderSettings(theme = ReadingTheme.AMOLED).toEpubPreferences(systemInDarkTheme = false)

        assertEquals(Theme.DARK, prefs.theme)
        assertEquals(ReadiumColor(0xFF000000.toInt()), prefs.backgroundColor)
        assertEquals(ReadiumColor(0xFFFFFFFF.toInt()), prefs.textColor)
    }

    @Test
    fun `DARK does not get the AMOLED background-color override`() {
        val prefs = ReaderSettings(theme = ReadingTheme.DARK).toEpubPreferences(systemInDarkTheme = false)

        assertEquals(Theme.DARK, prefs.theme)
        assertNull("Plain Dark must not pick up Amoled's forced background override", prefs.backgroundColor)
        assertNull("Plain Dark must not pick up Amoled's forced text-color override", prefs.textColor)
    }

    @Test
    fun `LIGHT and SEPIA also leave backgroundColor and textColor unset`() {
        for (theme in listOf(ReadingTheme.LIGHT, ReadingTheme.SEPIA)) {
            val prefs = ReaderSettings(theme = theme).toEpubPreferences(systemInDarkTheme = false)
            assertNull("$theme must not set a backgroundColor override", prefs.backgroundColor)
            assertNull("$theme must not set a textColor override", prefs.textColor)
        }
    }

    @Test
    fun `SYSTEM resolving to AMOLED is never possible — resolves to DARK or LIGHT only`() {
        // SYSTEM can only ever resolve to Dark or Light (see ReadingThemeResolutionTest),
        // so it must never apply the Amoled background override either, for either value
        // of the ambient system setting.
        for (systemInDarkTheme in listOf(true, false)) {
            val prefs = ReaderSettings(theme = ReadingTheme.SYSTEM).toEpubPreferences(systemInDarkTheme)
            assertNull(prefs.backgroundColor)
            assertNull(prefs.textColor)
        }
    }

    @Test
    fun `every ReadingTheme value converts without throwing`() {
        // Fails to compile (exhaustive when in toEpubPreferences) rather than silently
        // defaulting if a new ReadingTheme case is ever added — same belt-and-braces shape
        // as ReadingThemeResolutionTest's equivalent loop.
        for (theme in ReadingTheme.entries) {
            ReaderSettings(theme = theme).toEpubPreferences(systemInDarkTheme = true)
            ReaderSettings(theme = theme).toEpubPreferences(systemInDarkTheme = false)
        }
    }
}
