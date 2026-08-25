package xyz.libravault.core.storage

import android.content.SharedPreferences
import app.cash.turbine.test
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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

    // ── observe() ─────────────────────────────────────────────────────────────

    @Test
    fun `observe emits the current value immediately on subscribe`() = runTest {
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { prefs.getBoolean(LibravaultPreferences.KEY_SCREEN_SECURITY_ENABLED, true) } returns false

        VaultScreenSecurityPreference.observe(prefs).test {
            assertFalse(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `observe re-emits when the screen security key changes`() = runTest {
        // Regression guard (issue #530 L5 / #569): callers that instead cached
        // isEnabled() under a keyless remember { } only ever picked this change
        // up after leaving and re-entering the screen.
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { prefs.getBoolean(LibravaultPreferences.KEY_SCREEN_SECURITY_ENABLED, true) } returns true
        val listenerSlot = slot<SharedPreferences.OnSharedPreferenceChangeListener>()
        every { prefs.registerOnSharedPreferenceChangeListener(capture(listenerSlot)) } just Runs

        VaultScreenSecurityPreference.observe(prefs).test {
            assertTrue(awaitItem())

            every { prefs.getBoolean(LibravaultPreferences.KEY_SCREEN_SECURITY_ENABLED, true) } returns false
            listenerSlot.captured.onSharedPreferenceChanged(prefs, LibravaultPreferences.KEY_SCREEN_SECURITY_ENABLED)

            assertFalse(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `observe ignores changes to unrelated keys`() = runTest {
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { prefs.getBoolean(LibravaultPreferences.KEY_SCREEN_SECURITY_ENABLED, true) } returns true
        val listenerSlot = slot<SharedPreferences.OnSharedPreferenceChangeListener>()
        every { prefs.registerOnSharedPreferenceChangeListener(capture(listenerSlot)) } just Runs

        VaultScreenSecurityPreference.observe(prefs).test {
            assertTrue(awaitItem())

            listenerSlot.captured.onSharedPreferenceChanged(prefs, "some_other_key")

            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }
}
