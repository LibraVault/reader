package xyz.libravault.feature.vault

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import xyz.libravault.core.ui.SecureScreenEffect

// ── Thin wrapper ─────────────────────────────────────────────────────────────
//
// Everything that needs a real ViewModel or Activity (SecureScreenEffect's
// window-flag toggling) lives here. Everything else — the whole wizard body —
// is [CreateVaultContent], a pure function of (state, actions) with no
// Hilt/Activity dependency, so it can be rendered directly in a Robolectric
// Compose test the same way SettingsContent already is (see
// docs/TEST_COVERAGE_PRD.md Phase 7 — this file is one of that phase's
// targets, split per the PlayerScreen/SettingsScreen template).
//
// [onCreated] fires only after the user has ticked "I've saved it" on the
// recovery-key step and tapped Done — never earlier, so there's no path that
// navigates away from the recovery key before the user has acknowledged it.
@Composable
fun CreateVaultScreen(
    onCreated: (vaultId: String) -> Unit,
    onCancel: () -> Unit,
    viewModel: CreateVaultViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    if (state.step == CreateVaultStep.RECOVERY_KEY) {
        // Unconditional — see SecureScreenEffect's doc comment.
        SecureScreenEffect()
    }

    val actions = CreateVaultActions(
        onDisplayNameChanged = viewModel::onDisplayNameChanged,
        onNameConfirmed = viewModel::onNameConfirmed,
        onPinChanged = viewModel::onPinChanged,
        onPinSubmitted = viewModel::onPinSubmitted,
        onConfirmPinChanged = viewModel::onConfirmPinChanged,
        onConfirmPinSubmitted = viewModel::onConfirmPinSubmitted,
        onSavedConfirmedChanged = viewModel::onSavedConfirmedChanged,
        // The AppBar's back arrow and the system/gesture back action must
        // agree on what "back" means at each wizard step — without this,
        // gesture back would skip the wizard's own step logic entirely and
        // pop the whole screen from the middle of PIN entry.
        onBack = { if (state.step == CreateVaultStep.NAME) onCancel() else viewModel.onBack() },
        onCreated = onCreated,
    )

    CreateVaultContent(state = state, actions = actions)
}

// ── State & actions bundle ──────────────────────────────────────────────────
//
// Mirrors SettingsScreen's SettingsActions pattern: bundling every
// collaborator into one plain data class turns the wizard body below into a
// pure function testable without a ViewModel or Hilt graph.

internal data class CreateVaultActions(
    val onDisplayNameChanged: (String) -> Unit,
    val onNameConfirmed: () -> Unit,
    val onPinChanged: (String) -> Unit,
    val onPinSubmitted: () -> Unit,
    val onConfirmPinChanged: (String) -> Unit,
    val onConfirmPinSubmitted: () -> Unit,
    val onSavedConfirmedChanged: (Boolean) -> Unit,
    val onBack: () -> Unit,
    val onCreated: (vaultId: String) -> Unit,
)

// ── Pure content — the whole wizard body, no ViewModel/Activity ─────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CreateVaultContent(
    state: CreateVaultUiState,
    actions: CreateVaultActions,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = actions.onBack)

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stepTitle(state.step)) },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            color = MaterialTheme.colorScheme.background,
        ) {
            when (state.step) {
                CreateVaultStep.NAME -> NameStep(state, actions)
                CreateVaultStep.PIN -> PinStep(state, actions)
                CreateVaultStep.CONFIRM_PIN -> ConfirmPinStep(state, actions)
                CreateVaultStep.RECOVERY_KEY -> RecoveryKeyStep(state, actions)
            }
        }
    }
}

private fun stepTitle(step: CreateVaultStep) = when (step) {
    CreateVaultStep.NAME -> "New Vault"
    CreateVaultStep.PIN -> "Set a PIN"
    CreateVaultStep.CONFIRM_PIN -> "Confirm PIN"
    CreateVaultStep.RECOVERY_KEY -> "Save your recovery key"
}

@Composable
private fun NameStep(state: CreateVaultUiState, actions: CreateVaultActions) {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("What should this Vault be called?", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = state.displayName,
            onValueChange = actions.onDisplayNameChanged,
            label = { Text("Vault name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = actions.onNameConfirmed,
            enabled = state.displayName.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Next") }
    }
}

@Composable
private fun PinStep(state: CreateVaultUiState, actions: CreateVaultActions) {
    var pinVisible by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text(
            "Choose a 4-digit PIN, or a longer passphrase for extra security.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "There is no way to reset this if you forget it — losing it means losing everything in this Vault.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(16.dp))
        PinField(
            value = state.pin,
            onValueChange = actions.onPinChanged,
            visible = pinVisible,
            onVisibleChange = { pinVisible = it },
            isError = state.pinError != null,
            supportingText = state.pinError,
        )
        state.creationError?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(24.dp))
        Button(onClick = actions.onPinSubmitted, modifier = Modifier.fillMaxWidth()) { Text("Next") }
    }
}

@Composable
private fun ConfirmPinStep(state: CreateVaultUiState, actions: CreateVaultActions) {
    var pinVisible by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("Enter the same PIN again to confirm.", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))
        PinField(
            value = state.confirmPin,
            onValueChange = actions.onConfirmPinChanged,
            visible = pinVisible,
            onVisibleChange = { pinVisible = it },
            isError = state.pinError != null,
            supportingText = state.pinError,
        )
        Spacer(Modifier.height(24.dp))
        if (state.isCreating) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
        } else {
            Button(
                onClick = actions.onConfirmPinSubmitted,
                enabled = state.confirmPin.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Create Vault") }
        }
    }
}

@Composable
private fun RecoveryKeyStep(state: CreateVaultUiState, actions: CreateVaultActions) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Text(
            "This is the only backup for your PIN. Write it down or store it in a password " +
                "manager — somewhere other than this device. Do not screenshot this screen.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(16.dp))
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                state.recoveryKeyDisplay.orEmpty(),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(16.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        RecoveryKeyQr(state.recoveryKeyDisplay.orEmpty())
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = state.hasConfirmedSaved, onCheckedChange = actions.onSavedConfirmedChanged)
            Text("I've saved this recovery key somewhere safe, off this device.")
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { state.createdVaultId?.let(actions.onCreated) },
            enabled = state.hasConfirmedSaved && state.createdVaultId != null,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Done") }
    }
}


@Composable
private fun RecoveryKeyQr(content: String) {
    val bitmap = rememberQrBitmap(content)
    bitmap?.let {
        Image(
            bitmap = it.asImageBitmap(),
            contentDescription = "Recovery key QR code",
            modifier = Modifier.size(200.dp),
        )
    }
}

/** Same approach as `feature:settings`'s `DonateScreen.rememberQrBitmap`
 * (donation-address QR codes) — not shared across modules since it's ~15
 * lines and `feature:vault` doesn't otherwise depend on `feature:settings`. */
@Composable
private fun rememberQrBitmap(content: String): Bitmap? = remember(content) {
    if (content.isEmpty()) return@remember null
    runCatching {
        val hints = mapOf(
            EncodeHintType.MARGIN to 1,
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        )
        val matrix = MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, 512, 512, hints)
        Bitmap.createBitmap(512, 512, Bitmap.Config.RGB_565).apply {
            for (x in 0 until 512) {
                for (y in 0 until 512) {
                    setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                }
            }
        }
    }.getOrNull()
}
