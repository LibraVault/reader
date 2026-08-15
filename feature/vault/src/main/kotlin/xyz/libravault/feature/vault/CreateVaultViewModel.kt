package xyz.libravault.feature.vault

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import xyz.libravault.core.vaultstore.CreateVaultResult
import xyz.libravault.core.vaultstore.VaultSessionManager
import javax.inject.Inject

/** Minimum PIN/passphrase length — PRD §7.2's 4-digit PIN is the *suggested*
 * default (defensible only because of the hardware-backed Keystore wrap,
 * decision log #1/#2), not a ceiling; a longer PIN or full passphrase is
 * explicitly supported and this is the only length the UI enforces. */
const val MIN_PIN_LENGTH = 4

enum class CreateVaultStep { NAME, PIN, CONFIRM_PIN, RECOVERY_KEY }

data class CreateVaultUiState(
    val step: CreateVaultStep = CreateVaultStep.NAME,
    val displayName: String = "",
    val pin: String = "",
    val confirmPin: String = "",
    val pinError: String? = null,
    val isCreating: Boolean = false,
    val creationError: String? = null,
    val recoveryKeyDisplay: String? = null,
    val hasConfirmedSaved: Boolean = false,
    val createdVaultId: String? = null,
)

/**
 * Drives the create-vault wizard: name → PIN → confirm PIN → show recovery
 * key (once) → done. [uiState.recoveryKeyDisplay] is intentionally the only
 * place the raw recovery key touches UI state — [VaultSessionManager.createVault]
 * doesn't persist it in recoverable form anywhere, so once the user leaves
 * this screen it's gone for good if they didn't save it (PRD §7.2's honest
 * "we cannot help you" tradeoff).
 *
 * PIN handling caveat, deliberately not hidden: Compose's [androidx.compose.foundation.text.BasicTextField]
 * only exposes text as an immutable [String], which can't be zeroed the way
 * [CharArray] can. This class converts to [CharArray] and zeroes that copy
 * immediately after use (see [onConfirmPinSubmitted]'s `finally`), but the
 * `String` the text field itself held is left to the garbage collector, same
 * as every other password field in a standard Android UI toolkit.
 */
@HiltViewModel
class CreateVaultViewModel @Inject constructor(
    private val sessionManager: VaultSessionManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CreateVaultUiState())
    val uiState: StateFlow<CreateVaultUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val suggestedName = "Vault ${sessionManager.listVaults().size + 1}"
            _uiState.update { if (it.displayName.isEmpty()) it.copy(displayName = suggestedName) else it }
        }
    }

    fun onDisplayNameChanged(name: String) {
        _uiState.update { it.copy(displayName = name) }
    }

    fun onNameConfirmed() {
        if (_uiState.value.displayName.isBlank()) return
        _uiState.update { it.copy(step = CreateVaultStep.PIN) }
    }

    fun onPinChanged(pin: String) {
        _uiState.update { it.copy(pin = pin, pinError = null) }
    }

    fun onPinSubmitted() {
        val pin = _uiState.value.pin
        if (pin.length < MIN_PIN_LENGTH) {
            _uiState.update { it.copy(pinError = "At least $MIN_PIN_LENGTH characters") }
            return
        }
        _uiState.update { it.copy(step = CreateVaultStep.CONFIRM_PIN, confirmPin = "", pinError = null) }
    }

    fun onConfirmPinChanged(pin: String) {
        _uiState.update { it.copy(confirmPin = pin, pinError = null) }
    }

    fun onConfirmPinSubmitted() {
        val state = _uiState.value
        if (state.confirmPin != state.pin) {
            _uiState.update { it.copy(pinError = "Doesn't match", confirmPin = "") }
            return
        }

        _uiState.update { it.copy(isCreating = true, creationError = null) }
        val pinChars = state.pin.toCharArray()
        viewModelScope.launch {
            try {
                when (val result = sessionManager.createVault(state.displayName.trim(), pinChars)) {
                    is CreateVaultResult.Success -> {
                        val display = RecoveryKeyFormat.toDisplayString(result.recoveryKey)
                        result.recoveryKey.fill(0)
                        _uiState.update {
                            it.copy(
                                isCreating = false,
                                step = CreateVaultStep.RECOVERY_KEY,
                                recoveryKeyDisplay = display,
                                createdVaultId = result.id,
                                pin = "",
                                confirmPin = "",
                            )
                        }
                    }
                    CreateVaultResult.HardwareUnavailable -> _uiState.update {
                        it.copy(
                            isCreating = false,
                            step = CreateVaultStep.PIN,
                            creationError = "This device can't provide the security a PIN needs here. " +
                                "Try a longer passphrase, or check whether a system update is available.",
                        )
                    }
                }
            } finally {
                pinChars.fill(' ')
            }
        }
    }

    fun onSavedConfirmedChanged(checked: Boolean) {
        _uiState.update { it.copy(hasConfirmedSaved = checked) }
    }

    /** Back one step; a no-op from [CreateVaultStep.NAME] (the caller should
     * navigate away instead) and from [CreateVaultStep.RECOVERY_KEY] (no way
     * back once the vault exists — the key was shown, there's nothing to redo). */
    fun onBack() {
        _uiState.update {
            when (it.step) {
                CreateVaultStep.NAME -> it
                CreateVaultStep.PIN -> it.copy(step = CreateVaultStep.NAME)
                CreateVaultStep.CONFIRM_PIN -> it.copy(step = CreateVaultStep.PIN, confirmPin = "", pinError = null)
                CreateVaultStep.RECOVERY_KEY -> it
            }
        }
    }
}
