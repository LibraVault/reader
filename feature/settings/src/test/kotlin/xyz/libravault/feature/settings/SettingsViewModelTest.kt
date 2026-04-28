package xyz.libravault.feature.settings

import android.net.Uri
import app.cash.turbine.test
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.Runs
import io.mockk.verify
import io.mockk.junit5.MockKExtension
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.launch
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import xyz.libravault.core.domain.model.AppReadingTheme
import xyz.libravault.core.domain.model.UserPreferences
import xyz.libravault.core.domain.model.VaultFolder
import xyz.libravault.core.domain.scanner.ScanProgress
import xyz.libravault.core.domain.usecase.AddVaultFolderUseCase
import xyz.libravault.core.domain.usecase.ObserveVaultsUseCase
import xyz.libravault.core.domain.usecase.RemoveVaultFolderUseCase
import xyz.libravault.core.domain.usecase.ScanVaultUseCase
import xyz.libravault.core.storage.CoverArtCache
import xyz.libravault.core.storage.VaultManager
import xyz.libravault.core.logger.LibravaultLogger

@ExtendWith(MockKExtension::class)
class SettingsViewModelTest {

    private val defaultPrefs = UserPreferences()

    private val prefsRepo  = mockk<UserPreferencesRepository>()
    private val coverCache = mockk<CoverArtCache>(relaxed = true)
    private val vaultManager = mockk<VaultManager>(relaxed = true)
    private val addVaultFolder = mockk<AddVaultFolderUseCase>()
    private val removeVaultFolder = mockk<RemoveVaultFolderUseCase>()
    private val observeVaults = mockk<ObserveVaultsUseCase>()
    private val scanVaultsUseCase = mockk<ScanVaultUseCase>()
    private val logger     = mockk<LibravaultLogger>(relaxed = true)

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        mockkStatic(Uri::class)
        every { Uri.parse(any()) } returns mockk()
        every { prefsRepo.observe() }       returns flowOf(defaultPrefs)
        every { prefsRepo.read() }          returns defaultPrefs
        every { prefsRepo.update(any()) }   just Runs
        every { observeVaults() }           returns flowOf(emptyList())
        every { scanVaultsUseCase() }        returns flowOf(ScanProgress.Completed(0))
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(): SettingsViewModel {
        return SettingsViewModel(
            prefsRepo, coverCache, vaultManager,
            addVaultFolder, removeVaultFolder, observeVaults, scanVaultsUseCase, logger,
        )
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
        verify(exactly = 1) { coverCache.clearAll() }
    }

    // ── Vault Management Tests ────────────────────────────────────────────────

    @Test
    fun `vault state observes vaults from use case`() = runTest {
        val vaults = listOf(
            VaultFolder(id = 1, uri = "content://vault/1", displayName = "Books"),
            VaultFolder(id = 2, uri = "content://vault/2", displayName = "Audio"),
        )
        every { observeVaults() } returns flowOf(vaults)

        val vm = viewModel()
        vm.vaultState.test {
            val state = awaitItem()
            assertEquals(2, state.vaults.size)
            assertEquals("Books", state.vaults[0].displayName)
            assertEquals("Audio", state.vaults[1].displayName)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `add vault persists permission and delegates to use case`() = runTest {
        val uri = Uri.parse("content://com.android.externalstorage/doc/home%2FBooks")
        val vault = VaultFolder(id = 1, uri = uri.toString(), displayName = "Books")
        coEvery { addVaultFolder(uri.toString(), "Books") } returns vault

        val vm = viewModel()
        vm.onVaultFolderPicked(uri, "Books")

        verify { vaultManager.persistPermission(uri) }
        coVerify { addVaultFolder(uri.toString(), "Books") }
    }

    @Test
    fun `remove vault releases permission and delegates to use case`() = runTest {
        coEvery { removeVaultFolder(42) } just Runs

        val vault = VaultFolder(id = 42, uri = "content://vault/mine", displayName = "Mine")
        val vm = viewModel()
        vm.removeVault(vault)

        coVerify { vaultManager.releasePermission(any()) }
        coVerify { removeVaultFolder(42) }
    }

    @Test
    fun `scan progress is reflected in vault state`() = runTest {
        every { scanVaultsUseCase() } returns flowOf(
            ScanProgress.Started,
            ScanProgress.ItemFound(3),
            ScanProgress.Completed(3),
        )

        val vm = viewModel()

        val items = mutableListOf<VaultManagementState>()
        val collectJob = launch {
            vm.vaultState.take(4).collect { items.add(it) }
        }

        vm.scanVaults()
        collectJob.join()

        // items[0] = initial empty {vaults=[]}
        // items[1] = intermediate isScanning=true scanMessage=null
        // items[2] = Started scanMessage="Scanning vaults…"
        // items[3] = ItemFound(3)
        // items[4] = Completed(3)

        // Now we can inspect the collected items
        val started = items[2]
        assertTrue(started.isScanning)
        assertEquals("Scanning vaults\u2026", started.scanMessage)

        val found = items[3]
        assertTrue(found.isScanning)
        assertEquals("Found 3 items\u2026", found.scanMessage)

        val completed = items[4]
        assertFalse(completed.isScanning)
        assertEquals("Scan complete \u2013 3 new items added", completed.scanMessage)
    }
}
