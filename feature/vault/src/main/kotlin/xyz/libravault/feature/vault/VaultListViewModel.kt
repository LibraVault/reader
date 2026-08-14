package xyz.libravault.feature.vault

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class VaultListItemUiState(
    val id: String,
    val displayName: String,
    val isUnlocked: Boolean,
)

data class VaultListUiState(
    val vaults: List<VaultListItemUiState> = emptyList(),
    val isLoading: Boolean = true,
)

/**
 * Lists every registered vault with its current locked/unlocked state.
 * [refresh] is called on init and again on every `ON_RESUME` from the
 * screen (see [VaultListScreen]) — locked/unlocked state can change from
 * *outside* this ViewModel's own actions (auto-lock firing while some other
 * screen was in front, or a vault just created/unlocked on the screen this
 * one navigated to), so it's re-pulled rather than cached across navigation.
 */
@HiltViewModel
class VaultListViewModel @Inject constructor(
    private val sessionManager: VaultSessionManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(VaultListUiState())
    val uiState: StateFlow<VaultListUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val entries = sessionManager.listVaults()
            _uiState.update {
                VaultListUiState(
                    isLoading = false,
                    vaults = entries.map { e ->
                        VaultListItemUiState(e.id, e.displayName, sessionManager.isUnlocked(e.id))
                    },
                )
            }
        }
    }

    fun lock(id: String) {
        sessionManager.lock(id)
        refresh()
    }
}
