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
import xyz.libravault.core.vaultstore.UnlockOutcome
import javax.inject.Inject

enum class UnlockMode { PIN, RECOVERY_KEY }

data class UnlockVaultUiState(
    val displayName: String = "",
    val mode: UnlockMode = UnlockMode.PIN,
    val pin: String = "",
    val recoveryKeyInput: String = "",
    val isUnlocking: Boolean = false,
    val errorMessage: String? = null,
    /** Set together whenever [UnlockOutcome.Throttled] comes back — the UI
     * derives a live countdown from these two rather than the ViewModel
     * running its own ticker, so nothing here needs re-evaluating once the
     * wait is over; the next tap through [onUnlockWithPinSubmitted] just
     * re-asks the store, which is the actual source of truth. */
    val throttleReportedAtEpochMillis: Long? = null,
    val throttleRemainingMillisAtReport: Long? = null,
    /** The Keystore key for this vault is gone (implementation plan §A.4
     * failure case (c)) — PIN unlock is unavailable; [mode] is force-switched
     * to [UnlockMode.RECOVERY_KEY] when this becomes true. */
    val keystoreKeyLost: Boolean = false,
    val isUnlocked: Boolean = false,
)

/**
 * Drives unlocking one vault, identified by the `vaultId` nav argument. Two
 * independent credential paths — [onUnlockWithPinSubmitted] and
 * [onUnlockWithRecoveryKeySubmitted] — deliberately kept as separate methods
 * rather than one "submit" dispatching on [UnlockVaultUiState.mode], mirroring
 * [VaultSessionManager]'s own split (matches implementation plan §A.5: the
 * recovery path must keep working independently of the PIN/Keystore path,
 * even in the UI layer's shape).
 */
@HiltViewModel
class UnlockVaultViewModel @Inject constructor(
    private val sessionManager: VaultSessionManager,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val vaultId: String = checkNotNull(savedStateHandle["vaultId"]) { "UnlockVaultScreen requires a vaultId nav argument" }

    private val _uiState = MutableStateFlow(UnlockVaultUiState())
    val uiState: StateFlow<UnlockVaultUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val name = sessionManager.listVaults().find { it.id == vaultId }?.displayName ?: ""
            _uiState.update { it.copy(displayName = name) }
        }
    }

    fun onPinChanged(pin: String) {
        _uiState.update { it.copy(pin = pin, errorMessage = null) }
    }

    fun onUnlockWithPinSubmitted() {
        val pinChars = _uiState.value.pin.toCharArray()
        _uiState.update { it.copy(isUnlocking = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                when (val outcome = sessionManager.unlockWithPin(vaultId, pinChars)) {
                    UnlockOutcome.Success -> _uiState.update {
                        it.copy(isUnlocking = false, isUnlocked = true, pin = "")
                    }
                    UnlockOutcome.WrongCredential -> _uiState.update {
                        it.copy(isUnlocking = false, errorMessage = "Incorrect PIN", pin = "")
                    }
                    is UnlockOutcome.Throttled -> _uiState.update {
                        it.copy(
                            isUnlocking = false,
                            pin = "",
                            throttleReportedAtEpochMillis = System.currentTimeMillis(),
                            throttleRemainingMillisAtReport = outcome.remainingDelayMillis,
                        )
                    }
                    UnlockOutcome.KeystoreKeyLost -> _uiState.update {
                        it.copy(isUnlocking = false, keystoreKeyLost = true, mode = UnlockMode.RECOVERY_KEY, pin = "")
                    }
                }
            } finally {
                pinChars.fill(' ')
            }
        }
    }

    fun onRecoveryKeyInputChanged(text: String) {
        _uiState.update { it.copy(recoveryKeyInput = text, errorMessage = null) }
    }

    fun onUnlockWithRecoveryKeySubmitted() {
        val parsed = RecoveryKeyFormat.parse(_uiState.value.recoveryKeyInput)
        if (parsed == null) {
            _uiState.update { it.copy(errorMessage = "That doesn't look like a valid recovery key") }
            return
        }
        _uiState.update { it.copy(isUnlocking = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                val outcome = sessionManager.unlockWithRecoveryKey(vaultId, parsed)
                _uiState.update {
                    if (outcome == UnlockOutcome.Success) {
                        it.copy(isUnlocking = false, isUnlocked = true, recoveryKeyInput = "")
                    } else {
                        it.copy(isUnlocking = false, errorMessage = "Incorrect recovery key", recoveryKeyInput = "")
                    }
                }
            } finally {
                parsed.fill(0)
            }
        }
    }

    fun onSwitchMode(mode: UnlockMode) {
        _uiState.update { it.copy(mode = mode, errorMessage = null) }
    }
}
