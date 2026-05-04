package xyz.libravault.feature.settings.activation

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import xyz.libravault.core.licensing.IProGate
import xyz.libravault.core.licensing.PurchaseOutcome
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

    init {
        // Transition to Activated whenever isPro flips true (purchase or restore).
        viewModelScope.launch {
            proGate.isPro.collect { isPro ->
                if (isPro) _uiState.value = UiState.Activated
            }
        }
        // Reset loading state on cancel or billing error.
        viewModelScope.launch {
            proGate.purchaseOutcomes.collect { outcome ->
                if (_uiState.value is UiState.Activating) {
                    _uiState.value = when (outcome) {
                        PurchaseOutcome.UserCancelled -> UiState.Idle
                        PurchaseOutcome.Error         -> UiState.Error("Purchase failed. Please try again.")
                    }
                }
            }
        }
    }

    /** Opens the Google Play purchase sheet. Must be called from an Activity context. */
    fun launchBillingFlow(activity: Activity) {
        if (_uiState.value is UiState.Activating) return
        _uiState.value = UiState.Activating
        viewModelScope.launch {
            try {
                proGate.launchPurchaseFlow(activity)
                // isPro updates via onPurchasesUpdated → init collector above
            } catch (e: Exception) {
                _uiState.value = UiState.Error("Could not start purchase.")
            }
        }
    }

    fun dismissError() {
        if (_uiState.value is UiState.Error) _uiState.value = UiState.Idle
    }
}
