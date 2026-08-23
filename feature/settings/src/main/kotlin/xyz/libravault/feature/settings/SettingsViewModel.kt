package xyz.libravault.feature.settings

import android.app.Activity
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import xyz.libravault.core.billing.SupportBillingClient
import xyz.libravault.core.cloudtts.CloudApiKeyStore
import xyz.libravault.core.cloudtts.CloudProviderId
import xyz.libravault.core.cloudtts.CloudTtsGate
import xyz.libravault.core.cloudtts.CloudTtsProvider
import xyz.libravault.core.domain.model.AppReadingTheme
import xyz.libravault.core.domain.model.UserPreferences
import xyz.libravault.core.domain.model.VaultFolder
import xyz.libravault.core.domain.model.snapPlaybackSpeed
import xyz.libravault.core.domain.repository.LibraryRepository
import xyz.libravault.core.domain.scanner.ScanProgress
import xyz.libravault.core.domain.usecase.AddVaultFolderUseCase
import xyz.libravault.core.domain.usecase.ObserveVaultsUseCase
import xyz.libravault.core.domain.usecase.RemoveVaultFolderUseCase
import xyz.libravault.core.domain.usecase.ScanVaultUseCase
import xyz.libravault.core.storage.CoverArtCache
import xyz.libravault.core.storage.SupporterRepository
import xyz.libravault.core.storage.VaultManager
import xyz.libravault.core.logger.LibravaultLogger
import xyz.libravault.core.tts.TtsEngineProvider
import xyz.libravault.core.tts.TtsEngineType
import xyz.libravault.core.tts.TtsPreferences
import xyz.libravault.core.tts.TtsVoiceInfo
import xyz.libravault.core.tts.pocket.ModelStatus
import xyz.libravault.core.tts.pocket.PocketModelManager
import xyz.libravault.core.tts.pocket.PocketVoiceCatalog
import javax.inject.Inject

data class VaultManagementState(
    val vaults: List<VaultFolder> = emptyList(),
    val isScanning: Boolean = false,
    val scanMessage: String? = null,
)

data class TtsSettingsUiState(
    val engineType: TtsEngineType = TtsEngineType.ANDROID,
    val speechRate: Float = 1.0f,
    val selectedVoiceId: String? = null,
    val availableVoices: List<TtsVoiceInfo> = emptyList(),
    val modelStatus: ModelStatus = ModelStatus.Idle,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val prefsRepo: UserPreferencesRepository,
    private val coverArtCache: CoverArtCache,
    private val libraryRepository: LibraryRepository,
    private val vaultManager: VaultManager,
    private val addVaultFolder: AddVaultFolderUseCase,
    private val removeVaultFolder: RemoveVaultFolderUseCase,
    private val observeVaults: ObserveVaultsUseCase,
    private val scanVaultsUseCase: ScanVaultUseCase,
    private val logger: LibravaultLogger,
    private val supporterRepository: SupporterRepository,
    private val billingClient: SupportBillingClient,
    private val ttsEngineProvider: TtsEngineProvider,
    private val ttsPreferences: TtsPreferences,
    private val pocketModelManager: PocketModelManager,
    private val pocketVoiceCatalog: PocketVoiceCatalog,
    private val cloudApiKeyStore: CloudApiKeyStore,
    private val cloudTtsProvider: CloudTtsProvider,
    private val cloudTtsGate: CloudTtsGate,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    val preferences: StateFlow<UserPreferences> = prefsRepo.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), prefsRepo.read())

    // ── About ────────────────────────────────────────────────────────────────

    /**
     * The app's real version, read from [android.content.pm.PackageManager] at
     * runtime rather than hardcoded — this project doesn't use BuildConfig (see
     * app/build.gradle.kts). Falls back to "unknown" if the package somehow can't
     * be looked up, which should never happen for the app's own package.
     */
    val appVersionName: String = runCatching {
        @Suppress("DEPRECATION") // getPackageInfo(String, Int) — the non-deprecated
        // overload needs PackageManager.PackageInfoFlags, API 33+; minSdk here is 31.
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "unknown"

    /**
     * Reflects whatever's already stored — nothing in *this* purchase flow
     * (see [SUPPORT_URL]) can flip it to `true` going forward. (The play
     * flavor does make real network calls elsewhere now, for Premium Cloud
     * TTS Voices — see [subscriptionActive]/[cloudVoicesConsent] below —
     * but that's unrelated to how this particular flag gets set; flagging
     * explicitly since this comment used to claim the app made no network
     * calls "of any kind," which stopped being true once Cloud TTS shipped.)
     * Kept read-only rather than deleted so donors who earned the badge before
     * the in-app BTCPay flow was removed keep seeing it.
     */
    val isSupporter: StateFlow<Boolean> = supporterRepository.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), supporterRepository.isSupporter())

    /** False on F-Droid — that flavour keeps the external-link "Support the Project" button unchanged. */
    val isBillingSupported: Boolean = billingClient.isSupported

    /**
     * Whether both Play Billing products exist yet. False until the store side
     * of setup (Play Console products + a testing track) is complete — this is
     * the expected state right after this code ships, not an error.
     */
    val productsAvailable: StateFlow<Boolean> = billingClient.observeProductsAvailable()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val subscriptionActive: StateFlow<Boolean> = billingClient.observeSubscriptionActive()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * Model setup progress is exposed separately from [TtsEngineProvider.engine]'s own
     * state (see [xyz.libravault.core.tts.pocket.PocketTtsEngine] init) - only re-collect
     * [PocketModelManager.ensureModelAvailable] while Pocket TTS is actually selected, since
     * that Flow copies the bundled model out of the APK's assets on collection (an
     * already-valid on-disk copy short-circuits to [ModelStatus.Ready] immediately, so
     * switching away and back is safe).
     */
    val ttsState: StateFlow<TtsSettingsUiState> = combine(
        ttsEngineProvider.engineType,
        ttsEngineProvider.engine.flatMapLatest { it.state },
        ttsEngineProvider.engineType.flatMapLatest { type ->
            if (type == TtsEngineType.POCKET_TTS) {
                pocketModelManager.ensureModelAvailable()
            } else {
                flowOf(ModelStatus.Idle)
            }
        },
    ) { engineType, engineState, modelStatus ->
        TtsSettingsUiState(
            engineType = engineType,
            speechRate = engineState.speechRate,
            selectedVoiceId = engineState.selectedVoiceId,
            availableVoices = pocketVoiceCatalog.availableVoices(),
            modelStatus = modelStatus,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TtsSettingsUiState())

    private val _vaultState = MutableStateFlow(VaultManagementState())
    val vaultState: StateFlow<VaultManagementState> = _vaultState.asStateFlow()

    init {
        viewModelScope.launch {
            observeVaults().collect { vaults ->
                _vaultState.value = _vaultState.value.copy(vaults = vaults)
            }
        }
    }

    fun onReadingThemeChanged(theme: AppReadingTheme) = update { it.copy(defaultReadingTheme = theme) }

    fun onPlaybackSpeedChanged(speed: Float) = update {
        it.copy(defaultPlaybackSpeed = snapPlaybackSpeed(speed))
    }

    fun onSkipDurationChanged(seconds: Int) = update {
        it.copy(defaultSkipDurationSec = seconds.coerceIn(5, 120))
    }

    fun onLoggingToggled(enabled: Boolean) {
        logger.isEnabled = enabled
        update { it.copy(loggingEnabled = enabled) }
    }

    fun onDynamicColorToggled(enabled: Boolean) = update {
        it.copy(dynamicColorEnabled = enabled)
    }

    fun onScreenSecurityToggled(enabled: Boolean) = update {
        it.copy(screenSecurityEnabled = enabled)
    }

    // ── Text-to-Speech ───────────────────────────────────────────────────────

    /**
     * Persists the choice to [TtsPreferences] rather than calling
     * `TtsEngineProvider.switchEngineSync` directly - [TtsEngineProvider] already
     * observes `engineTypeFlow` and switches reactively, so writing only to the
     * engine (and not the preference) would revert on the next app launch.
     */
    fun onTtsEngineTypeSelected(type: TtsEngineType) {
        viewModelScope.launch { ttsPreferences.setEngineType(type) }
    }

    fun onTtsVoiceSelected(voiceId: String) {
        viewModelScope.launch { ttsPreferences.setSelectedVoice(voiceId) }
    }

    fun onTtsSpeechRateChanged(rate: Float) {
        ttsEngineProvider.engine.value.setSpeechRate(rate)
    }

    // ── Cloud Voices (Premium, BYOK) ─────────────────────────────────────────
    // PRD docs/cloud-tts-premium-prd.md §6. This section is only ever shown by
    // SettingsScreen when `subscriptionActive` is true, but `cloudVoicesConsent`
    // stays independently off by default — see CloudTtsGate's class doc.

    val cloudVoicesConsent: StateFlow<Boolean> = ttsPreferences.cloudVoicesConsentFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val selectedCloudProvider: StateFlow<CloudProviderId?> = ttsPreferences.selectedCloudProviderFlow
        .map { name -> name?.let { runCatching { CloudProviderId.valueOf(it) }.getOrNull() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** No reactive API on [CloudApiKeyStore] (it's a plain suspend interface,
     * matching `HardwareKeyWrap`'s shape) — refreshed explicitly after every
     * save/clear rather than polled, since credential changes only ever
     * happen through this ViewModel's own actions below. */
    private val _configuredCloudProviders = MutableStateFlow<Set<CloudProviderId>>(emptySet())
    val configuredCloudProviders: StateFlow<Set<CloudProviderId>> = _configuredCloudProviders.asStateFlow()

    init {
        viewModelScope.launch { refreshConfiguredCloudProviders() }
    }

    /** Checks all five providers concurrently, not sequentially — each
     * [CloudApiKeyStore.loadCredentials] call does a DataStore read plus a
     * hardware Keystore lookup (and, if a key exists, a StrongBox/TEE
     * unwrap); on real hardware those can each take tens of ms, so five in
     * a row turned "should be instant" into a serialized chain (found in
     * review). */
    private suspend fun refreshConfiguredCloudProviders() {
        _configuredCloudProviders.value = coroutineScope {
            CloudProviderId.entries
                .map { provider -> async { provider to (cloudApiKeyStore.loadCredentials(provider) != null) } }
                .awaitAll()
                .filter { (_, configured) -> configured }
                .map { (provider, _) -> provider }
                .toSet()
        }
    }

    fun onCloudVoicesConsentAccepted() {
        viewModelScope.launch { ttsPreferences.setCloudVoicesConsent(true) }
    }

    fun onCloudVoicesConsentDisabled() {
        viewModelScope.launch { ttsPreferences.setCloudVoicesConsent(false) }
    }

    /** Also clears the shared voice-id preference — [TtsPreferences.selectedVoiceFlow]'s
     * own doc explains why: it's shared across all three [TtsEngineType]s,
     * and a stale voice id from a previous engine/provider must never
     * silently carry over into a cloud synthesis call (found in review). */
    fun onCloudProviderSelected(provider: CloudProviderId) {
        viewModelScope.launch {
            ttsPreferences.setSelectedCloudProvider(provider.name)
            ttsPreferences.setSelectedVoice(null)
        }
    }

    fun onCloudVoiceIdChanged(voiceId: String) {
        viewModelScope.launch { ttsPreferences.setSelectedVoice(voiceId.ifBlank { null }) }
    }

    /** Only actually switches [TtsEngineType.CLOUD] on when [enabled] and the
     * gate is genuinely open right now — [CloudVoicesSection] already only
     * enables its own toggle when a provider/voice are configured, but this
     * is the ViewModel-level backstop, not just a UI-affordance check. */
    fun onUseCloudEngineToggled(enabled: Boolean) {
        viewModelScope.launch {
            if (enabled && !cloudTtsGate.observeCanUseCloudTts().first()) return@launch
            ttsPreferences.setEngineType(if (enabled) TtsEngineType.CLOUD else TtsEngineType.ANDROID)
        }
    }

    /**
     * Validates BEFORE saving (PRD §6: "key is validated with a single
     * cheap test call, then stored") — a failed validation never reaches
     * [CloudApiKeyStore] at all.
     *
     * Explicitly re-checks [CloudTtsGate] here too, not just relying on
     * [CloudVoicesSection] only being reachable from the UI when
     * subscribed+consented — [CloudTtsProvider]'s own class doc requires
     * every caller to check the gate immediately before every call, and a
     * future UI change (a shortcut, a deep link) could otherwise reach this
     * method without going through that gated section (found in review).
     */
    suspend fun onValidateAndSaveCloudKey(provider: CloudProviderId, credentials: Map<String, String>): Result<Unit> {
        if (!cloudTtsGate.observeCanUseCloudTts().first()) {
            return Result.failure(IllegalStateException("Cloud Voices is not currently enabled (subscription/consent)"))
        }
        val validation = cloudTtsProvider.validateKey(provider, credentials)
        if (validation.isFailure) return validation
        return runCatching { cloudApiKeyStore.saveCredentials(provider, credentials) }
            .onSuccess { refreshConfiguredCloudProviders() }
    }

    fun onClearCloudKey(provider: CloudProviderId) {
        viewModelScope.launch {
            cloudApiKeyStore.clearCredentials(provider)
            refreshConfiguredCloudProviders()
        }
    }

    /**
     * Wipes the on-disk cover cache AND nulls `coverArtPath` for every
     * library item. Both must move together: deleting the JPEG files
     * alone leaves the DB holding stale absolute paths, which makes the
     * scanner's enrichment gate (`coverArtPath == null`) refuse to
     * re-extract, so covers stay missing forever. See
     * `LibraryScannerImpl.enrichMetadata`.
     */
    fun clearCoverCache() {
        viewModelScope.launch {
            coverArtCache.clearAll()
            libraryRepository.clearCoverArtPaths()
            logger.i("Settings", "Cover art cache cleared")
        }
    }

    fun viewLogs() {
        viewModelScope.launch {
            val logs = logger.readLogs()
            _logsContent = logs
        }
    }

    fun clearLogs() {
        viewModelScope.launch {
            logger.clearLogs()
        }
    }

    // ── Vault Management ─────────────────────────────────────────────────────

    fun onVaultFolderPicked(uri: Uri, displayName: String) {
        viewModelScope.launch {
            vaultManager.persistPermission(uri)
            addVaultFolder(uri.toString(), displayName)
            logger.i("Settings", "Vault added from Settings: $displayName")
            scanVaults()
        }
    }

    fun removeVault(vault: VaultFolder) {
        viewModelScope.launch {
            val uri = Uri.parse(vault.uri)
            vaultManager.releasePermission(uri)
            removeVaultFolder(vault.id)
            logger.i("Settings", "Vault removed: ${vault.displayName}")
            scanVaults()
        }
    }

    fun scanVaults() {
        viewModelScope.launch {
            _vaultState.value = _vaultState.value.copy(isScanning = true, scanMessage = null)
            scanVaultsUseCase().collect { progress ->
                when (progress) {
                    is ScanProgress.Started -> {
                        _vaultState.value = _vaultState.value.copy(scanMessage = "Scanning vaults…")
                    }
                    is ScanProgress.ItemFound -> {
                        _vaultState.value = _vaultState.value.copy(
                            scanMessage = "Found ${progress.count} items…"
                        )
                    }
                    is ScanProgress.Completed -> {
                        val msg = if (progress.total > 0) {
                            val prefix = "Scan complete – ${progress.total} new items added"
                            val fc = progress.formatCounts
                            if (fc != null) {
                                "$prefix (${fc.epub} EPUB, ${fc.pdf} PDF, ${fc.audiobook} audiobooks)"
                            } else {
                                prefix
                            }
                        } else null
                        _vaultState.value = _vaultState.value.copy(
                            isScanning = false,
                            scanMessage = msg,
                        )
                        logger.i("Settings", "Scan complete: ${progress.total} new items")
                    }
                    is ScanProgress.Error -> {
                        _vaultState.value = _vaultState.value.copy(
                            isScanning = false,
                            scanMessage = "Error: ${progress.message}",
                        )
                    }
                }
            }
        }
    }

    // ── Billing ──────────────────────────────────────────────────────────────

    /**
     * Fire-and-forget from the UI's perspective — [productsAvailable] /
     * [subscriptionActive] / [isSupporter] reflect the outcome reactively once
     * Play reports it, there's no separate "purchase result" state to observe.
     * Failures (including user cancellation) are silently dropped here rather
     * than surfaced as an error: cancelling a purchase sheet isn't a bug.
     */
    fun purchaseSubscription(activity: Activity) {
        viewModelScope.launch { billingClient.purchaseSubscription(activity) }
    }

    fun purchaseOneTimeTip(activity: Activity) {
        viewModelScope.launch { billingClient.purchaseOneTimeTip(activity) }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private var _logsContent: String = ""

    private fun update(transform: (UserPreferences) -> UserPreferences) {
        prefsRepo.update(transform(prefsRepo.read()))
    }
}
