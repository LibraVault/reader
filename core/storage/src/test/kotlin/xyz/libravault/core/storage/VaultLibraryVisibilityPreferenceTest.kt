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

class VaultLibraryVisibilityPreferenceTest {

    @Test
    fun `defaults to disabled when nothing is stored`() {
        val prefs = mockk<SharedPreferences>()
        every { prefs.getBoolean(LibravaultPreferences.KEY_VAULT_LIBRARY_VISIBLE, false) } returns false

        assertFalse(VaultLibraryVisibilityPreference.isEnabled(prefs))
    }

    @Test
    fun `reflects a stored true value`() {
        val prefs = mockk<SharedPreferences>()
        every { prefs.getBoolean(LibravaultPreferences.KEY_VAULT_LIBRARY_VISIBLE, false) } returns true

        assertTrue(VaultLibraryVisibilityPreference.isEnabled(prefs))
    }

    // ── observe() ─────────────────────────────────────────────────────────────

    @Test
    fun `observe emits the current value immediately on subscribe`() = runTest {
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { prefs.getBoolean(LibravaultPreferences.KEY_VAULT_LIBRARY_VISIBLE, false) } returns true

        VaultLibraryVisibilityPreference.observe(prefs).test {
            assertTrue(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `observe re-emits when the visibility key changes`() = runTest {
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { prefs.getBoolean(LibravaultPreferences.KEY_VAULT_LIBRARY_VISIBLE, false) } returns false
        val listenerSlot = slot<SharedPreferences.OnSharedPreferenceChangeListener>()
        every { prefs.registerOnSharedPreferenceChangeListener(capture(listenerSlot)) } just Runs

        VaultLibraryVisibilityPreference.observe(prefs).test {
            assertFalse(awaitItem())

            every { prefs.getBoolean(LibravaultPreferences.KEY_VAULT_LIBRARY_VISIBLE, false) } returns true
            listenerSlot.captured.onSharedPreferenceChanged(prefs, LibravaultPreferences.KEY_VAULT_LIBRARY_VISIBLE)

            assertTrue(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `observe ignores changes to unrelated keys`() = runTest {
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { prefs.getBoolean(LibravaultPreferences.KEY_VAULT_LIBRARY_VISIBLE, false) } returns false
        val listenerSlot = slot<SharedPreferences.OnSharedPreferenceChangeListener>()
        every { prefs.registerOnSharedPreferenceChangeListener(capture(listenerSlot)) } just Runs

        VaultLibraryVisibilityPreference.observe(prefs).test {
            assertFalse(awaitItem())

            listenerSlot.captured.onSharedPreferenceChanged(prefs, "some_other_key")

            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }
}
