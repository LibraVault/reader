package xyz.libravault.feature.vault

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.delay

/**
 * Unlock one vault. [UnlockMode.PIN] is the default; the "use recovery key
 * instead" link and the forced switch on [UnlockVaultUiState.keystoreKeyLost]
 * both route through the same [UnlockMode.RECOVERY_KEY] branch — see
 * [UnlockVaultViewModel]'s doc comment for why the two credential paths stay
 * independent methods under the hood.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnlockVaultScreen(
    onUnlocked: (vaultId: String) -> Unit,
    onBack: () -> Unit,
    viewModel: UnlockVaultViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(state.isUnlocked) {
        if (state.isUnlocked) onUnlocked(viewModel.vaultId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.displayName.ifBlank { "Unlock Vault" }) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Surface(
            modifier = Modifier.fillMaxSize().padding(padding),
            color = MaterialTheme.colorScheme.background,
        ) {
            when (state.mode) {
                UnlockMode.PIN -> PinUnlockBody(state, viewModel)
                UnlockMode.RECOVERY_KEY -> RecoveryKeyUnlockBody(state, viewModel)
            }
        }
    }
}

@Composable
private fun PinUnlockBody(state: UnlockVaultUiState, viewModel: UnlockVaultViewModel) {
    var pinVisible by remember { mutableStateOf(false) }
    val remainingMillis = throttleCountdown(state.throttleReportedAtEpochMillis, state.throttleRemainingMillisAtReport)

    Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("Enter your PIN", style = MaterialTheme.typography.titleMedium)
        Spacer24()
        OutlinedTextField(
            value = state.pin,
            onValueChange = viewModel::onPinChanged,
            label = { Text("PIN or passphrase") },
            singleLine = true,
            isError = state.errorMessage != null,
            supportingText = state.errorMessage?.let { { Text(it) } },
            visualTransformation = if (pinVisible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                IconButton(onClick = { pinVisible = !pinVisible }) {
                    Icon(
                        if (pinVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (pinVisible) "Hide" else "Show",
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer24()
        if (state.isUnlocking) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
        } else if (remainingMillis > 0) {
            Text(
                "Too many attempts. Try again in ${(remainingMillis / 1000) + 1}s.",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        } else {
            Button(
                onClick = viewModel::onUnlockWithPinSubmitted,
                enabled = state.pin.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Unlock") }
        }
        Spacer24()
        TextButton(
            onClick = { viewModel.onSwitchMode(UnlockMode.RECOVERY_KEY) },
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) { Text("Forgot your PIN? Use your recovery key") }
    }
}

@Composable
private fun RecoveryKeyUnlockBody(state: UnlockVaultUiState, viewModel: UnlockVaultViewModel) {
    // Recovery-key entry renders sensitive material just like the create-time
    // display step does — same unconditional treatment (SecureScreenEffect's
    // doc comment covers the display side; entry deserves no less).
    SecureScreenEffect()

    Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        if (state.keystoreKeyLost) {
            Text(
                "This device's secure hardware no longer recognizes this vault's key. " +
                    "Enter your recovery key to regain access.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer24()
        }
        Text("Enter your recovery key", style = MaterialTheme.typography.titleMedium)
        Spacer24()
        OutlinedTextField(
            value = state.recoveryKeyInput,
            onValueChange = viewModel::onRecoveryKeyInputChanged,
            label = { Text("Recovery key") },
            isError = state.errorMessage != null,
            supportingText = state.errorMessage?.let { { Text(it) } },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer24()
        if (state.isUnlocking) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
        } else {
            Button(
                onClick = viewModel::onUnlockWithRecoveryKeySubmitted,
                enabled = state.recoveryKeyInput.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Unlock") }
        }
        if (!state.keystoreKeyLost) {
            Spacer24()
            TextButton(
                onClick = { viewModel.onSwitchMode(UnlockMode.PIN) },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) { Text("Use PIN instead") }
        }
    }
}

@Composable
private fun Spacer24() = Spacer(Modifier.height(24.dp))

/** Live countdown derived from a single (reportedAt, remainingAtReport) pair —
 * see [UnlockVaultUiState]'s doc comment for why the ViewModel doesn't run
 * its own ticker. */
@Composable
private fun throttleCountdown(reportedAtEpochMillis: Long?, remainingAtReportMillis: Long?): Long {
    var remaining by remember(reportedAtEpochMillis) {
        mutableLongStateOf(remainingAtReportMillis ?: 0L)
    }
    LaunchedEffect(reportedAtEpochMillis) {
        if (reportedAtEpochMillis == null || remainingAtReportMillis == null) return@LaunchedEffect
        while (true) {
            val elapsed = System.currentTimeMillis() - reportedAtEpochMillis
            val left = remainingAtReportMillis - elapsed
            remaining = left.coerceAtLeast(0L)
            if (left <= 0L) break
            delay(250)
        }
    }
    return remaining
}
