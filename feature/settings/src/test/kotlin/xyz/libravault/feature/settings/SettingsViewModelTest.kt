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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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
import xyz.libravault.core.domain.scanner.FormatCounts
import xyz.libravault.core.domain.scanner.ScanProgress
import xyz.libravault.core.domain.usecase.AddVaultFolderUseCase
import xyz.libravault.core.domain.usecase.ObserveVaultsUseCase
import xyz.libravault.core.domain.usecase.RemoveVaultFolderUseCase
import xyz.libravault.core.domain.usecase.ScanVaultUseCase
import xyz.libravault.core.storage.CoverArtCache
import xyz.libravault.core.storage.SupporterRepository
import xyz.libravault.core.storage.VaultManager
import xyz.libravault.core.domain.repository.LibraryRepository
import xyz.libravault.core.logger.LibravaultLogger

@ExtendWith(MockKExtension::class)
class SettingsViewModelTest {

    private val defaultPrefs = UserPreferences()

    private val prefsRepo  = mockk<UserPreferencesRepository>()
    private val coverCache = mockk<CoverArtCache>(relaxed = true)
    private val libraryRepository = mockk<LibraryRepository>(relaxed = true)
    private val vaultManager = mockk<VaultManager>(relaxed = true)
    private val addVaultFolder = mockk<AddVaultFolderUseCase>()
    private val removeVaultFolder = mockk<RemoveVaultFolderUseCase>()
    private val observeVaults = mockk<ObserveVaultsUseCase>()
    private val scanVaultsUseCase = mockk<ScanVaultUseCase>()
    private val logger     = mockk<LibravaultLogger>(relaxed = true)
    private val supporterRepository = mockk<SupporterRepository>(relaxed = true)
    private val donationClient = mockk<DonationClient>(relaxed = true)

    private val mainDispatcher = UnconfinedTestDispatcher()
    private val vaultsFlow = MutableStateFlow(emptyList<VaultFolder>())

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        mockkStatic(Uri::class)
        every { Uri.parse(any()) } returns mockk()
        every { prefsRepo.observe() }       returns flowOf(defaultPrefs)
        every { prefsRepo.read() }          returns defaultPrefs
        every { prefsRepo.update(any()) }   just Runs
        every { observeVaults() }           returns vaultsFlow
        every { scanVaultsUseCase() }        returns flowOf(ScanProgress.Completed(0))
    }

    @AfterEach
    fun tearDown() {
        // Test dispatcher is automatically cleaned up by runTest.
        // No need to manually cancel; removed to fix build break.
        Dispatchers.resetMain()
    }

    private fun viewModel(): SettingsViewModel {
        return SettingsViewModel(
            prefsRepo, coverCache, libraryRepository, vaultManager,
            addVaultFolder, removeVaultFolder, observeVaults, scanVaultsUseCase, logger,
            supporterRepository, donationClient,
        )
    }

    @Test
    fun `emits initial preferences`() = runTest(mainDispatcher) {
        viewModel().preferences.test {
            val prefs = awaitItem()
            assertEquals(AppReadingTheme.DARK, prefs.defaultReadingTheme)
            assertEquals(1.0f, prefs.defaultPlaybackSpeed)
            assertFalse(prefs.loggingEnabled)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `reading theme change persists`() = runTest(mainDispatcher) {
        val vm = viewModel()
        vm.onReadingThemeChanged(AppReadingTheme.SEPIA)
        verify { prefsRepo.update(match { it.defaultReadingTheme == AppReadingTheme.SEPIA }) }
    }

    @Test
    fun `playback speed is clamped`() = runTest(mainDispatcher) {
        val vm = viewModel()
        vm.onPlaybackSpeedChanged(10.0f)
        verify { prefsRepo.update(match { it.defaultPlaybackSpeed == 3.0f }) }

        vm.onPlaybackSpeedChanged(0.0f)
        verify { prefsRepo.update(match { it.defaultPlaybackSpeed == 0.5f }) }
    }

    @Test
    fun `skip duration is clamped`() = runTest(mainDispatcher) {
        val vm = viewModel()
        vm.onSkipDurationChanged(0)
        verify { prefsRepo.update(match { it.defaultSkipDurationSec == 5 }) }

        vm.onSkipDurationChanged(999)
        verify { prefsRepo.update(match { it.defaultSkipDurationSec == 120 }) }
    }

    @Test
    fun `logging toggle updates logger`() = runTest(mainDispatcher) {
        val vm = viewModel()
        vm.onLoggingToggled(true)
        verify { prefsRepo.update(match { it.loggingEnabled }) }
    }

    @Test
    fun `dynamic colour toggle persists`() = runTest(mainDispatcher) {
        val vm = viewModel()
        vm.onDynamicColorToggled(false)
        verify { prefsRepo.update(match { !it.dynamicColorEnabled }) }
    }

    @Test
    fun `clear cover cache wipes files AND nulls coverArtPaths so refresh can recover`() =
        runTest(mainDispatcher) {
            val vm = viewModel()
            vm.clearCoverCache()
            // Both calls are mandatory: deleting JPEG files alone leaves the DB
            // with stale absolute paths, and the enrichment gate keys off
            // `coverArtPath == null`, so without the DB clear refresh would
            // *not* recover the covers.
            verify(exactly = 1) { coverCache.clearAll() }
            coVerify(exactly = 1) { libraryRepository.clearCoverArtPaths() }
        }

    @Test
    fun `vault state observes vaults from use case`() = runTest(mainDispatcher) {
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
    fun `add vault persists permission and delegates to use case`() = runTest(mainDispatcher) {
        val uri = Uri.parse("content://com.android.externalstorage/doc/home%2FBooks")
        val vault = VaultFolder(id = 1, uri = uri.toString(), displayName = "Books")
        coEvery { addVaultFolder(uri.toString(), "Books") } returns vault

        val vm = viewModel()
        vm.onVaultFolderPicked(uri, "Books")

        verify { vaultManager.persistPermission(uri) }
        coVerify { addVaultFolder(uri.toString(), "Books") }
    }

    @Test
    fun `remove vault releases permission and delegates to use case`() = runTest(mainDispatcher) {
        coEvery { removeVaultFolder(42) } just Runs

        val vault = VaultFolder(id = 42, uri = "content://vault/mine", displayName = "Mine")
        val vm = viewModel()
        vm.removeVault(vault)

        coVerify { vaultManager.releasePermission(any()) }
        coVerify { removeVaultFolder(42) }
    }

    @Test
    fun `scan progress is reflected in vault state`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)

        val scanFlow = MutableStateFlow<ScanProgress?>(null)
        every { scanVaultsUseCase() } returns scanFlow.filterNotNull()

        val vm = viewModel()

        vm.vaultState.test {
            vm.scanVaults()
            runCurrent()

            scanFlow.value = ScanProgress.Started
            runCurrent()

            // sequence: initial(empty) -> isScanning(true,null) -> Started -> ItemFound(3) -> Completed(3)
            val initial = awaitItem()
            assertFalse(initial.isScanning)
            assertEquals(0, initial.vaults.size)

            val mutation = awaitItem()
            assertTrue(mutation.isScanning)
            assertEquals(null, mutation.scanMessage)

            val started = awaitItem()
            assertTrue(started.isScanning)
            assertEquals("Scanning vaults…", started.scanMessage)

            scanFlow.value = ScanProgress.ItemFound(3)
            runCurrent()

            val found = awaitItem()
            assertTrue(found.isScanning)
            assertEquals("Found 3 items…", found.scanMessage)

            scanFlow.value = ScanProgress.Completed(processed = 3, total = 3)
            runCurrent()

            val completed = awaitItem()
            assertFalse(completed.isScanning)
            assertEquals("Scan complete – 3 new items added", completed.scanMessage)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `scan complete message includes format breakdown when formatCounts is present`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(testDispatcher)

        val scanFlow = MutableStateFlow<ScanProgress?>(null)
        every { scanVaultsUseCase() } returns scanFlow.filterNotNull()

        val vm = viewModel()

        vm.vaultState.test {
            vm.scanVaults()
            runCurrent()

            scanFlow.value = ScanProgress.Started
            runCurrent()

            val initial = awaitItem()
            assertFalse(initial.isScanning)
            assertEquals(0, initial.vaults.size)

            val mutation = awaitItem()
            assertTrue(mutation.isScanning)
            assertEquals(null, mutation.scanMessage)

            val started = awaitItem()
            assertTrue(started.isScanning)
            assertEquals("Scanning vaults…", started.scanMessage)

            scanFlow.value = ScanProgress.ItemFound(5)
            runCurrent()

            val found = awaitItem()
            assertTrue(found.isScanning)
            assertEquals("Found 5 items…", found.scanMessage)

            scanFlow.value = ScanProgress.Completed(
                processed = 5,
                total = 5,
                formatCounts = FormatCounts(epub = 2, pdf = 1, audiobook = 2),
            )
            runCurrent()

            val completed = awaitItem()
            assertFalse(completed.isScanning)
            assertEquals(
                "Scan complete – 5 new items added (2 EPUB, 1 PDF, 2 audiobooks)",
                completed.scanMessage,
            )

            cancelAndIgnoreRemainingEvents()
        }
    }
}
