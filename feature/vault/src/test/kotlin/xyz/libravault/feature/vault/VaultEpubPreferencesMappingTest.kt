package xyz.libravault.feature.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.navigator.preferences.FontFamily as ReadiumFontFamily
import org.readium.r2.shared.ExperimentalReadiumApi
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.libravault.core.ui.theme.ReadingTheme

/**
 * Covers [VaultReaderSettings.toVaultEpubPreferences]'s font-family/letter-spacing
 * mapping (#423) — same coverage as `feature:reader`'s `EpubPreferencesMappingTest`,
 * duplicated for the same "parallel, not shared" reason as the production code.
 *
 * Runs on Robolectric — see `EpubPreferencesMappingTest`'s doc for why plain
 * JUnit5 doesn't work here (Readium's `EpubPreferences` companion types touch
 * `android.graphics.Color.parseColor` in a static initializer).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalReadiumApi::class)
class VaultEpubPreferencesMappingTest {

    private val baseSettings = VaultReaderSettings(
        theme = ReadingTheme.LIGHT,
        fontSize = 1.2f,
        lineSpacing = 1.6f,
        scrollMode = VaultScrollMode.PAGINATED,
    )

    @Test
    fun `OPEN_DYSLEXIC maps to Readium's built-in OpenDyslexic font family`() {
        val prefs = baseSettings.copy(fontFamily = VaultReaderFontFamily.OPEN_DYSLEXIC)
            .toVaultEpubPreferences(systemInDarkTheme = false)

        assertEquals(ReadiumFontFamily.OPEN_DYSLEXIC, prefs.fontFamily)
    }

    @Test
    fun `OPEN_DYSLEXIC bundles a non-null letter-spacing bump`() {
        val prefs = baseSettings.copy(fontFamily = VaultReaderFontFamily.OPEN_DYSLEXIC)
            .toVaultEpubPreferences(systemInDarkTheme = false)

        assertEquals(0.125, prefs.letterSpacing)
    }

    @Test
    fun `SYSTEM font leaves fontFamily and letterSpacing both null`() {
        val prefs = baseSettings.copy(fontFamily = VaultReaderFontFamily.SYSTEM)
            .toVaultEpubPreferences(systemInDarkTheme = false)

        assertNull(prefs.fontFamily)
        assertNull(prefs.letterSpacing)
    }

    @Test
    fun `SERIF SANS_SERIF and MONOSPACE map to their Readium equivalents with no letter-spacing override`() {
        val serif = baseSettings.copy(fontFamily = VaultReaderFontFamily.SERIF).toVaultEpubPreferences(false)
        val sansSerif = baseSettings.copy(fontFamily = VaultReaderFontFamily.SANS_SERIF).toVaultEpubPreferences(false)
        val monospace = baseSettings.copy(fontFamily = VaultReaderFontFamily.MONOSPACE).toVaultEpubPreferences(false)

        assertEquals(ReadiumFontFamily.SERIF, serif.fontFamily)
        assertEquals(ReadiumFontFamily.SANS_SERIF, sansSerif.fontFamily)
        assertEquals(ReadiumFontFamily.MONOSPACE, monospace.fontFamily)
        assertNull(serif.letterSpacing)
        assertNull(sansSerif.letterSpacing)
        assertNull(monospace.letterSpacing)
    }
}
