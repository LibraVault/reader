package xyz.libravault.core.storage

import android.content.Context
import android.content.SharedPreferences
import app.cash.turbine.test
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for [SupporterRepository]. This class was previously always
 * mocked wholesale wherever it's injected (e.g. [SettingsViewModelTest]), so
 * its own read/write/observe logic — including the [SharedPreferences.OnSharedPreferenceChangeListener]
 * key filter in [SupporterRepository.observe] — was never actually exercised.
 */
class SupporterRepositoryTest {

    private lateinit var mockPrefs: SharedPreferences
    private lateinit var mockEditor: SharedPreferences.Editor
    private lateinit var mockContext: Context
    private lateinit var repository: SupporterRepository

    @BeforeEach
    fun setUp() {
        mockEditor = mockk(relaxed = true)
        mockPrefs = mockk(relaxed = true)
        every { mockPrefs.edit() } returns mockEditor
        every { mockEditor.putBoolean(any(), any()) } returns mockEditor
        every { mockEditor.putString(any(), any()) } returns mockEditor
        every { mockEditor.remove(any()) } returns mockEditor

        mockContext = mockk()
        every { mockContext.getSharedPreferences("libravault_prefs", Context.MODE_PRIVATE) } returns mockPrefs

        repository = SupporterRepository(mockContext)
    }

    // ── isSupporter / setSupporter ───────────────────────────────────────────

    @Test
    fun `isSupporter defaults to false`() {
        every { mockPrefs.getBoolean("is_supporter", false) } returns false
        assertFalse(repository.isSupporter())
    }

    @Test
    fun `isSupporter reflects stored value`() {
        every { mockPrefs.getBoolean("is_supporter", false) } returns true
        assertTrue(repository.isSupporter())
    }

    @Test
    fun `setSupporter writes the flag and applies`() {
        repository.setSupporter(true)

        verify { mockEditor.putBoolean("is_supporter", true) }
        verify { mockEditor.apply() }
    }

    // ── observe() ─────────────────────────────────────────────────────────────

    @Test
    fun `observe emits the current value immediately on subscribe`() = runTest {
        every { mockPrefs.getBoolean("is_supporter", false) } returns true

        repository.observe().test {
            assertTrue(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `observe re-emits when the supporter key changes`() = runTest {
        every { mockPrefs.getBoolean("is_supporter", false) } returns false
        val listenerSlot = slot<SharedPreferences.OnSharedPreferenceChangeListener>()
        every { mockPrefs.registerOnSharedPreferenceChangeListener(capture(listenerSlot)) } just Runs

        repository.observe().test {
            assertFalse(awaitItem())

            every { mockPrefs.getBoolean("is_supporter", false) } returns true
            listenerSlot.captured.onSharedPreferenceChanged(mockPrefs, "is_supporter")

            assertTrue(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `observe ignores changes to unrelated keys`() = runTest {
        every { mockPrefs.getBoolean("is_supporter", false) } returns false
        val listenerSlot = slot<SharedPreferences.OnSharedPreferenceChangeListener>()
        every { mockPrefs.registerOnSharedPreferenceChangeListener(capture(listenerSlot)) } just Runs

        repository.observe().test {
            assertFalse(awaitItem())

            // A change to an unrelated key must not trigger a re-emission of
            // the supporter flag.
            listenerSlot.captured.onSharedPreferenceChanged(mockPrefs, "some_other_key")

            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }
}
