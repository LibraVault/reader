package xyz.libravault.feature.settings

import app.cash.turbine.test
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xyz.libravault.core.domain.model.AppReadingTheme
import xyz.libravault.core.domain.model.UserPreferences
import xyz.libravault.core.storage.CoverArtCache
import xyz.libravault.core.logger.LibravaultLogger

class SettingsViewModelTest {

    private val defaultPrefs = UserPreferences()

    private val prefsRepo    = mockk<UserPreferencesRepository>()
    private val coverCache   = mockk<CoverArtCache>(relaxed = true)
    private val logger       = mockk<LibravaultLogger>(relaxed = true)

    init {
        every { prefsRepo.observe() } returns flowOf(defaultPrefs)
        every { prefsRepo.read() } returns defaultPrefs
    }

    private fun viewModel(): SettingsViewModel {
        return SettingsViewModel(prefsRepo, coverCache, logger)
    }

    @Test
    fun `emits initial preferences`() = runTest {
        viewModel().preferences.test {
            val prefs = awaitItem()
            assertEquals(AppReadingTheme.DARK, prefs.defaultReadingTheme)
            assertEquals(1.0f, prefs.defaultPlaybackSpeed)
            assertFalse(prefs.loggingEnabled)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `reading theme change persists`() = runTest {
        val vm = viewModel()
        vm.onReadingThemeChanged(AppReadingTheme.SEPIA)
        verify { prefsRepo.update(match { it.defaultReadingTheme == AppReadingTheme.SEPIA }) }
    }

    @Test
    fun `playback speed is clamped`() = runTest {
        val vm = viewModel()
        vm.onPlaybackSpeedChanged(10.0f)
        verify { prefsRepo.update(match { it.defaultPlaybackSpeed == 3.0f }) }

        vm.onPlaybackSpeedChanged(0.0f)
        verify { prefsRepo.update(match { it.defaultPlaybackSpeed == 0.5f }) }
    }

    @Test
    fun `skip duration is clamped`() = runTest {
        val vm = viewModel()
        vm.onSkipDurationChanged(0)
        verify { prefsRepo.update(match { it.defaultSkipDurationSec == 5 }) }

        vm.onSkipDurationChanged(999)
        verify { prefsRepo.update(match { it.defaultSkipDurationSec == 120 }) }
    }

    @Test
    fun `logging toggle updates logger`() = runTest {
        val vm = viewModel()
        vm.onLoggingToggled(true)
        verify { prefsRepo.update(match { it.loggingEnabled }) }
    }

    @Test
    fun `dynamic colour toggle persists`() = runTest {
        val vm = viewModel()
        vm.onDynamicColorToggled(false)
        verify { prefsRepo.update(match { !it.dynamicColorEnabled }) }
    }

    @Test
    fun `clear cover cache delegates to CoverArtCache`() = runTest {
        val vm = viewModel()
        vm.clearCoverCache()
        // clearCoverCache launches in viewModelScope - verify was called
        verify(exactly = 1) { coverCache.clearAll() }
    }
}
