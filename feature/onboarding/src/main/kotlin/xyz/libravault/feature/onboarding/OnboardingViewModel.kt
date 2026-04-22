package xyz.libravault.feature.onboarding

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import xyz.libravault.core.domain.usecase.AddVaultFolderUseCase
import xyz.libravault.core.domain.usecase.ScanVaultUseCase
import xyz.libravault.core.logger.LibravaultLogger
import xyz.libravault.core.storage.VaultManager
import javax.inject.Inject

data class OnboardingUiState(
    val addedVaultNames: List<String> = emptyList(),
    val isLoading: Boolean            = false,
    val error: String?                = null,
)

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val addVaultFolder: AddVaultFolderUseCase,
    private val vaultManager: VaultManager,
    private val scanVault: ScanVaultUseCase,
    private val logger: LibravaultLogger,
) : ViewModel() {

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    fun onFolderPicked(uri: Uri, displayName: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            runCatching {
                // Persist SAF permission so it survives app restarts
                vaultManager.persistPermission(uri)
                // Save vault to Room
                addVaultFolder(uri.toString(), displayName)
            }.onSuccess { vault ->
                logger.i("Onboarding", "Vault added: ${vault.displayName}")
                _uiState.value = _uiState.value.copy(
                    isLoading       = false,
                    addedVaultNames = _uiState.value.addedVaultNames + vault.displayName,
                )
                // Trigger background scan immediately after vault is added
                triggerScan()
            }.onFailure { e ->
                logger.e("Onboarding", "Failed to add vault", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error     = e.message,
                )
            }
        }
    }

    private fun triggerScan() {
        viewModelScope.launch {
            runCatching {
                scanVault().collect { /* progress emitted to LibraryViewModel on home screen */ }
            }.onFailure { e ->
                logger.w("Onboarding", "Background scan failed: ${e.message}")
            }
        }
    }
}
