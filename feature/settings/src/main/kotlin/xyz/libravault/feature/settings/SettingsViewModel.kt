package xyz.libravault.feature.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import xyz.libravault.core.domain.model.AppReadingTheme
import xyz.libravault.core.domain.model.UserPreferences
import xyz.libravault.core.domain.model.VaultFolder
import xyz.libravault.core.domain.model.snapPlaybackSpeed
import xyz.libravault.core.domain.scanner.ScanProgress
import xyz.libravault.core.domain.usecase.AddVaultFolderUseCase
import xyz.libravault.core.domain.usecase.ObserveVaultsUseCase
import xyz.libravault.core.domain.usecase.RemoveVaultFolderUseCase
import xyz.libravault.core.domain.usecase.ScanVaultUseCase
import xyz.libravault.core.storage.CoverArtCache
import xyz.libravault.core.storage.VaultManager
import xyz.libravault.core.logger.LibravaultLogger
import javax.inject.Inject

data class VaultManagementState(
    val vaults: List<VaultFolder> = emptyList(),
    val isScanning: Boolean = false,
    val scanMessage: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val prefsRepo: UserPreferencesRepository,
    private val coverArtCache: CoverArtCache,
    private val vaultManager: VaultManager,
    private val addVaultFolder: AddVaultFolderUseCase,
    private val removeVaultFolder: RemoveVaultFolderUseCase,
    private val observeVaults: ObserveVaultsUseCase,
    private val scanVaultsUseCase: ScanVaultUseCase,
    private val logger: LibravaultLogger,
) : ViewModel() {

    val preferences: StateFlow<UserPreferences> = prefsRepo.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), prefsRepo.read())

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

    fun clearCoverCache() {
        viewModelScope.launch {
            coverArtCache.clearAll()
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
                        val formatMsg = progress.formatCounts?.let { fmt ->
                            " (${fmt.epub} EPUB, ${fmt.pdf} PDF, ${fmt.audiobook} audiobooks)"
                        } ?: ""
                        _vaultState.value = _vaultState.value.copy(
                            isScanning = false,
                            scanMessage = if (progress.total > 0) {
                                "Scan complete – ${progress.total} new items added$formatMsg"
                            } else {
                                null
                            },
                        )
                        logger.i("Settings", "Scan complete: ${progress.total} new items$formatMsg")
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

    // ── Helpers ──────────────────────────────────────────────────────────────

    private var _logsContent: String = ""

    private fun update(transform: (UserPreferences) -> UserPreferences) {
        prefsRepo.update(transform(prefsRepo.read()))
    }
}
