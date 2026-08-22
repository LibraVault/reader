package xyz.libravault.feature.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.readium.r2.navigator.preferences.Color as ReadiumColor
import org.readium.r2.navigator.preferences.Theme
import xyz.libravault.core.ui.theme.ReadingTheme

/**
 * Unit tests for [toVaultEpubPreferences] — the vault-native equivalent of
 * `feature:reader`'s `EpubReaderScreenPreferencesTest`, same "parallel, not shared"
 * duplication as the rest of this file's mapping (see that function's doc).
 *
 * Runs on Robolectric — see that class's doc for why a plain JVM test isn't enough
 * (Readium's [Theme]/[ReadiumColor] call `android.graphics.Color.parseColor` at
 * class-init time).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VaultEpubReaderScreenPreferencesTest {

    @Test
    fun `AMOLED maps to Theme DARK with pure black background and white text overrides`() {
        val prefs = VaultReaderSettings(theme = ReadingTheme.AMOLED).toVaultEpubPreferences(systemInDarkTheme = false)

        assertEquals(Theme.DARK, prefs.theme)
        assertEquals(ReadiumColor(0xFF000000.toInt()), prefs.backgroundColor)
        assertEquals(ReadiumColor(0xFFFFFFFF.toInt()), prefs.textColor)
    }

    @Test
    fun `DARK does not get the AMOLED background-color override`() {
        val prefs = VaultReaderSettings(theme = ReadingTheme.DARK).toVaultEpubPreferences(systemInDarkTheme = false)

        assertEquals(Theme.DARK, prefs.theme)
        assertNull("Plain Dark must not pick up Amoled's forced background override", prefs.backgroundColor)
        assertNull("Plain Dark must not pick up Amoled's forced text-color override", prefs.textColor)
    }

    @Test
    fun `LIGHT and SEPIA also leave backgroundColor and textColor unset`() {
        for (theme in listOf(ReadingTheme.LIGHT, ReadingTheme.SEPIA)) {
            val prefs = VaultReaderSettings(theme = theme).toVaultEpubPreferences(systemInDarkTheme = false)
            assertNull("$theme must not set a backgroundColor override", prefs.backgroundColor)
            assertNull("$theme must not set a textColor override", prefs.textColor)
        }
    }

    @Test
    fun `every ReadingTheme value converts without throwing`() {
        // Fails to compile (exhaustive when in toVaultEpubPreferences) rather than
        // silently defaulting if a new ReadingTheme case is ever added.
        for (theme in ReadingTheme.entries) {
            VaultReaderSettings(theme = theme).toVaultEpubPreferences(systemInDarkTheme = true)
            VaultReaderSettings(theme = theme).toVaultEpubPreferences(systemInDarkTheme = false)
        }
    }
}
