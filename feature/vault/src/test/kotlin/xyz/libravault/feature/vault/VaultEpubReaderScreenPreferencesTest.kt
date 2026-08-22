package xyz.libravault.feature.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.readium.r2.navigator.preferences.Color as ReadiumColor
import org.readium.r2.navigator.preferences.TextAlign
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

    // ── Margins/justification/hyphenation (#421) ────────────────────────────────

    @Test
    fun `default settings leave pageMargins at 1_0, textAlign unset, and hyphens false`() {
        val prefs = VaultReaderSettings().toVaultEpubPreferences(systemInDarkTheme = false)

        assertEquals(1.0, prefs.pageMargins)
        assertNull("Off by default — must not force a text alignment", prefs.textAlign)
        assertEquals(false, prefs.hyphens)
    }

    @Test
    fun `marginScale multiplier round-trips into pageMargins verbatim`() {
        assertEquals(0.5, VaultReaderSettings(marginScale = 0.5f).toVaultEpubPreferences(false).pageMargins)
        assertEquals(
            1.6,
            VaultReaderSettings(marginScale = 1.6f).toVaultEpubPreferences(false).pageMargins!!,
            0.0001,
        )
        assertEquals(2.0, VaultReaderSettings(marginScale = 2.0f).toVaultEpubPreferences(false).pageMargins)
    }

    @Test
    fun `justifyText true maps to TextAlign JUSTIFY, false leaves textAlign unset`() {
        assertEquals(
            TextAlign.JUSTIFY,
            VaultReaderSettings(justifyText = true).toVaultEpubPreferences(false).textAlign,
        )
        assertNull(VaultReaderSettings(justifyText = false).toVaultEpubPreferences(false).textAlign)
    }

    @Test
    fun `hyphenation flag round-trips into hyphens verbatim`() {
        assertTrue(VaultReaderSettings(hyphenation = true).toVaultEpubPreferences(false).hyphens!!)
        assertFalse(VaultReaderSettings(hyphenation = false).toVaultEpubPreferences(false).hyphens!!)
    }
}
