package xyz.libravault.feature.settings

import android.app.Activity
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
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
import xyz.libravault.core.billing.SupportBillingClient
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
import xyz.libravault.core.tts.TtsEngine
import xyz.libravault.core.tts.TtsEngineProvider
import xyz.libravault.core.tts.TtsEngineType
import xyz.libravault.core.tts.TtsPreferences
import xyz.libravault.core.tts.TtsState
import xyz.libravault.core.tts.TtsVoiceInfo
import xyz.libravault.core.tts.pocket.ModelStatus
import xyz.libravault.core.tts.pocket.PocketModelManager
import xyz.libravault.core.tts.pocket.PocketVoiceCatalog

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
    private val billingClient = mockk<SupportBillingClient>()
    private val context = mockk<Context>()
    private val packageManager = mockk<PackageManager>()

    private val ttsEngineProvider = mockk<TtsEngineProvider>()
    private val ttsPreferences = mockk<TtsPreferences>(relaxed = true)
    private val pocketModelManager = mockk<PocketModelManager>()
    private val pocketVoiceCatalog = mockk<PocketVoiceCatalog>()

    private val fakeTtsEngine = mockk<TtsEngine>(relaxed = true)
    private val ttsEngineTypeFlow = MutableStateFlow(TtsEngineType.ANDROID)
    private val ttsEngineFlow = MutableStateFlow(fakeTtsEngine)
    private val ttsEngineStateFlow = MutableStateFlow(TtsState())

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

        every { ttsEngineProvider.engineType } returns ttsEngineTypeFlow
        every { ttsEngineProvider.engine }     returns ttsEngineFlow
        every { fakeTtsEngine.state }          returns ttsEngineStateFlow
        every { pocketModelManager.ensureModelAvailable() } returns flowOf(ModelStatus.Idle)
        every { pocketVoiceCatalog.availableVoices() } returns emptyList()

        every { context.packageManager } returns packageManager
        every { context.packageName }    returns "xyz.libravault.app"
        every { packageManager.getPackageInfo("xyz.libravault.app", 0) } returns
            PackageInfo().apply { versionName = "9.9.9-test" }

        every { billingClient.isSupported } returns true
        every { billingClient.observeProductsAvailable() } returns flowOf(false)
        every { billingClient.observeSubscriptionActive() } returns flowOf(false)
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
            supporterRepository, billingClient,
            ttsEngineProvider, ttsPreferences, pocketModelManager, pocketVoiceCatalog,
            context,
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
    fun `reading theme change persists SYSTEM`() = runTest(mainDispatcher) {
        val vm = viewModel()
        vm.onReadingThemeChanged(AppReadingTheme.SYSTEM)
        verify { prefsRepo.update(match { it.defaultReadingTheme == AppReadingTheme.SYSTEM }) }
    }

    @Test
    fun `reading theme change persists AMOLED`() = runTest(mainDispatcher) {
        // #420
        val vm = viewModel()
        vm.onReadingThemeChanged(AppReadingTheme.AMOLED)
        verify { prefsRepo.update(match { it.defaultReadingTheme == AppReadingTheme.AMOLED }) }
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
    fun `screen security toggle persists`() = runTest(mainDispatcher) {
        val vm = viewModel()
        vm.onScreenSecurityToggled(false)
        verify { prefsRepo.update(match { !it.screenSecurityEnabled }) }
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

    // ── Text-to-Speech ────────────────────────────────────────────────────────

    @Test
    fun `tts state reflects engine type, engine state, and model status`() = runTest(mainDispatcher) {
        ttsEngineTypeFlow.value = TtsEngineType.POCKET_TTS
        ttsEngineStateFlow.value = TtsState(speechRate = 1.5f, selectedVoiceId = "en_US-ljspeech-medium")
        every { pocketVoiceCatalog.availableVoices() } returns listOf(
            TtsVoiceInfo(id = "en_US-ljspeech-medium", displayName = "Ljspeech", locale = "en-US"),
        )
        every { pocketModelManager.ensureModelAvailable() } returns flowOf(ModelStatus.Ready("/path"))

        val vm = viewModel()
        vm.ttsState.test {
            val state = awaitItem()
            assertEquals(TtsEngineType.POCKET_TTS, state.engineType)
            assertEquals(1.5f, state.speechRate)
            assertEquals("en_US-ljspeech-medium", state.selectedVoiceId)
            assertEquals(1, state.availableVoices.size)
            assertEquals(ModelStatus.Ready("/path"), state.modelStatus)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `tts state does not collect model setup progress while android engine selected`() =
        runTest(mainDispatcher) {
            ttsEngineTypeFlow.value = TtsEngineType.ANDROID

            val vm = viewModel()
            vm.ttsState.test {
                val state = awaitItem()
                assertEquals(ModelStatus.Idle, state.modelStatus)
                cancelAndIgnoreRemainingEvents()
            }
            // ensureModelAvailable() is a cold Flow that copies the bundled model out of
            // the APK's assets on collection - it must never be touched while Pocket TTS
            // isn't selected.
            verify(exactly = 0) { pocketModelManager.ensureModelAvailable() }
        }

    @Test
    fun `engine type selection persists to preferences rather than switching the engine directly`() =
        runTest(mainDispatcher) {
            val vm = viewModel()
            vm.onTtsEngineTypeSelected(TtsEngineType.POCKET_TTS)
            coVerify { ttsPreferences.setEngineType(TtsEngineType.POCKET_TTS) }
        }

    @Test
    fun `voice selection persists to preferences`() = runTest(mainDispatcher) {
        val vm = viewModel()
        vm.onTtsVoiceSelected("en_US-ljspeech-medium")
        coVerify { ttsPreferences.setSelectedVoice("en_US-ljspeech-medium") }
    }

    @Test
    fun `speech rate change applies directly to the current engine`() = runTest(mainDispatcher) {
        val vm = viewModel()
        vm.onTtsSpeechRateChanged(2.0f)
        verify { fakeTtsEngine.setSpeechRate(2.0f) }
    }

    // ── About ────────────────────────────────────────────────────────────────

    @Test
    fun `appVersionName reads the real version from PackageManager rather than a hardcoded string`() =
        runTest(mainDispatcher) {
            assertEquals("9.9.9-test", viewModel().appVersionName)
        }

    @Test
    fun `appVersionName falls back to unknown if the package can't be looked up`() =
        runTest(mainDispatcher) {
            every { packageManager.getPackageInfo("xyz.libravault.app", 0) } throws
                PackageManager.NameNotFoundException()

            assertEquals("unknown", viewModel().appVersionName)
        }

    // ── Support ──────────────────────────────────────────────────────────────

    @Test
    fun `isSupporter passes through the repository's stored value without a way to set it`() =
        runTest(mainDispatcher) {
            every { supporterRepository.isSupporter() } returns true
            every { supporterRepository.observe() } returns flowOf(true)

            viewModel().isSupporter.test {
                assertTrue(awaitItem())
                cancelAndIgnoreRemainingEvents()
            }

            // No donation/invoice flow exists anymore to call this — SupporterRepository
            // itself is still exercised in SupporterRepositoryTest.
            verify(exactly = 0) { supporterRepository.setSupporter(any()) }
        }

    // ── Billing ──────────────────────────────────────────────────────────────

    @Test
    fun `isBillingSupported reflects the fdroid no-op client`() = runTest(mainDispatcher) {
        every { billingClient.isSupported } returns false

        assertFalse(viewModel().isBillingSupported)
    }

    @Test
    fun `isBillingSupported reflects the play client`() = runTest(mainDispatcher) {
        every { billingClient.isSupported } returns true

        assertTrue(viewModel().isBillingSupported)
    }

    @Test
    fun `productsAvailable stays false while the store side isn't set up yet`() = runTest(mainDispatcher) {
        every { billingClient.observeProductsAvailable() } returns flowOf(false)

        viewModel().productsAvailable.test {
            assertFalse(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `productsAvailable reflects the billing client once products exist`() = runTest(mainDispatcher) {
        every { billingClient.observeProductsAvailable() } returns flowOf(true)

        viewModel().productsAvailable.test {
            assertTrue(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `subscriptionActive reflects the billing client`() = runTest(mainDispatcher) {
        every { billingClient.observeSubscriptionActive() } returns flowOf(true)

        viewModel().subscriptionActive.test {
            assertTrue(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `purchaseSubscription delegates to the billing client with the given activity`() =
        runTest(mainDispatcher) {
            val activity = mockk<Activity>()
            coEvery { billingClient.purchaseSubscription(activity) } returns Result.success(Unit)

            viewModel().purchaseSubscription(activity)

            coVerify(exactly = 1) { billingClient.purchaseSubscription(activity) }
        }

    @Test
    fun `purchaseOneTimeTip delegates to the billing client with the given activity`() =
        runTest(mainDispatcher) {
            val activity = mockk<Activity>()
            coEvery { billingClient.purchaseOneTimeTip(activity) } returns Result.success(Unit)

            viewModel().purchaseOneTimeTip(activity)

            coVerify(exactly = 1) { billingClient.purchaseOneTimeTip(activity) }
        }
}
