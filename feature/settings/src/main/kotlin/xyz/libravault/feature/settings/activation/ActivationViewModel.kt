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
import javax.inject.Inject

@HiltViewModel
class ActivationViewModel @Inject constructor(
    private val proGate: IProGate,
) : ViewModel() {

    sealed class UiState {
        data object Idle       : UiState()
        data object Activating : UiState()
        data object Activated  : UiState()
        data class  Error(val message: String) : UiState()
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState

    val isPro: StateFlow<Boolean> = proGate.isPro

    fun activate(rawKey: String) {
        if (rawKey.isBlank()) {
            _uiState.value = UiState.Error("Please enter a license key.")
            return
        }
        _uiState.value = UiState.Activating
        viewModelScope.launch {
            val ok = withContext(Dispatchers.Default) { proGate.activateWithKey(rawKey) }
            _uiState.value = if (ok) UiState.Activated
                             else    UiState.Error("That license key is not valid.")
        }
    }
}
