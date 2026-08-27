package xyz.libravault.feature.player.service

import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xyz.libravault.core.storage.LibravaultPreferences

class VaultStopOnLockPreferenceTest {

    @Test
    fun `defaults to enabled (always pause) when nothing is stored`() {
        val prefs = mockk<SharedPreferences>()
        every { prefs.getBoolean(LibravaultPreferences.KEY_VAULT_STOP_ON_LOCK, true) } returns true

        assertTrue(VaultStopOnLockPreference.isEnabled(prefs))
    }

    @Test
    fun `reflects a stored false value`() {
        val prefs = mockk<SharedPreferences>()
        every { prefs.getBoolean(LibravaultPreferences.KEY_VAULT_STOP_ON_LOCK, true) } returns false

        assertFalse(VaultStopOnLockPreference.isEnabled(prefs))
    }
}
