package xyz.libravault.feature.settings.activation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.libravault.core.licensing.IProGate
import xyz.libravault.core.licensing.RecoveryRequest
import xyz.libravault.core.licensing.RecoveryService
import javax.inject.Inject

@HiltViewModel
class RecoveryViewModel @Inject constructor(
    private val proGate: IProGate,
    private val recoveryService: RecoveryService,
) : ViewModel() {

    sealed class UiState {
        data object Idle       : UiState()
        data object Recovering : UiState()
        data class  Recovered(val licenseKey: String) : UiState()
        data class  Error(val message: String) : UiState()
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState

    fun recover(rawPhrase: String) {
        val phrase = rawPhrase.trim().lowercase()
        if (phrase.split(Regex("\\s+")).size != 12) {
            _uiState.value = UiState.Error("Recovery phrase must be exactly 12 words.")
            return
        }
        _uiState.value = UiState.Recovering
        viewModelScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    recoveryService.recover(RecoveryRequest(phrase))
                }
                val key      = response.license_key
                val verified = withContext(Dispatchers.Default) { proGate.activateWithKey(key) }
                _uiState.value = if (verified) UiState.Recovered(key)
                                 else          UiState.Error("Server returned an invalid key.")
            } catch (e: Exception) {
                _uiState.value = UiState.Error("Could not reach recovery server.")
            }
        }
    }
}
