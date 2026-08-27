package xyz.libravault.feature.player.service

import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xyz.libravault.core.storage.LibravaultPreferences

class VaultNotificationMetadataPreferenceTest {

    @Test
    fun `defaults to the placeholder (disabled) when nothing is stored`() {
        val prefs = mockk<SharedPreferences>()
        every { prefs.getBoolean(LibravaultPreferences.KEY_VAULT_NOTIFICATION_REAL_METADATA, false) } returns false

        assertFalse(VaultNotificationMetadataPreference.isEnabled(prefs))
    }

    @Test
    fun `reflects a stored true value`() {
        val prefs = mockk<SharedPreferences>()
        every { prefs.getBoolean(LibravaultPreferences.KEY_VAULT_NOTIFICATION_REAL_METADATA, false) } returns true

        assertTrue(VaultNotificationMetadataPreference.isEnabled(prefs))
    }
}
