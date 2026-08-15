package xyz.libravault.feature.vault

import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xyz.libravault.core.storage.LibravaultPreferences

class VaultScreenSecurityPreferenceTest {

    @Test
    fun `defaults to enabled when nothing is stored`() {
        val prefs = mockk<SharedPreferences>()
        every { prefs.getBoolean(LibravaultPreferences.KEY_SCREEN_SECURITY_ENABLED, true) } returns true

        assertTrue(VaultScreenSecurityPreference.isEnabled(prefs))
    }

    @Test
    fun `reflects a stored false value`() {
        val prefs = mockk<SharedPreferences>()
        every { prefs.getBoolean(LibravaultPreferences.KEY_SCREEN_SECURITY_ENABLED, true) } returns false

        assertFalse(VaultScreenSecurityPreference.isEnabled(prefs))
    }
}
