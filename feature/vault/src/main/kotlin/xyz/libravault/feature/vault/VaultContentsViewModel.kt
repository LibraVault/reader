package xyz.libravault.feature.vault

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.libravault.core.vaultstore.VaultManifestEntry
import javax.inject.Inject

data class VaultContentsUiState(
    val displayName: String = "",
    val entries: List<VaultManifestEntry> = emptyList(),
    val isLoading: Boolean = true,
    /** Flips true if [refresh] finds the vault has been locked out from under
     * this screen (auto-lock firing while it was in front). The screen should
     * pop back to the vault list rather than show a stale entry list. */
    val wasLocked: Boolean = false,
)

/**
 * Vault contents (browse-only in this phase — import is Phase 5b's scope;
 * see the implementation plan). Requires the vault named by the `vaultId`
 * nav argument to already be unlocked; if it isn't (or auto-lock fires while
 * this screen is in front), [refresh] surfaces [VaultContentsUiState.wasLocked]
 * rather than crashing on [VaultSessionManager.requireUnlocked].
 */
@HiltViewModel
class VaultContentsViewModel @Inject constructor(
    private val sessionManager: VaultSessionManager,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val vaultId: String = checkNotNull(savedStateHandle["vaultId"]) { "VaultContentsScreen requires a vaultId nav argument" }

    private val _uiState = MutableStateFlow(VaultContentsUiState())
    val uiState: StateFlow<VaultContentsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val name = sessionManager.listVaults().find { it.id == vaultId }?.displayName ?: ""
            _uiState.update { it.copy(displayName = name) }
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            if (!sessionManager.isUnlocked(vaultId)) {
                _uiState.update { it.copy(isLoading = false, wasLocked = true) }
                return@launch
            }
            val entries = sessionManager.requireUnlocked(vaultId).listEntries()
            _uiState.update { it.copy(entries = entries, isLoading = false) }
        }
    }

    fun lock() {
        sessionManager.lock(vaultId)
    }
}
