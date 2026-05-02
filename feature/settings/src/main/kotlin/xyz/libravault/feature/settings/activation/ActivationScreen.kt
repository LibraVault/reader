package xyz.libravault.feature.settings.activation

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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivationScreen(
    onBack: () -> Unit,
    onNavigateToRecovery: () -> Unit,
    viewModel: ActivationViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val isPro   by viewModel.isPro.collectAsState()
    var keyInput by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("LibraVault Pro", style = MaterialTheme.typography.headlineMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(8.dp))

            if (isPro) {
                ProActiveCard()
            } else {
                ActivationForm(
                    keyInput       = keyInput,
                    onKeyChange    = { keyInput = it },
                    uiState        = uiState,
                    onActivate     = { viewModel.activate(keyInput) },
                    onOpenRecovery = onNavigateToRecovery,
                )
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ProActiveCard() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            tint     = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(64.dp),
        )
        Text(
            text  = "Pro is active",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text  = "All Pro features are unlocked on this device.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ActivationForm(
    keyInput: String,
    onKeyChange: (String) -> Unit,
    uiState: ActivationViewModel.UiState,
    onActivate: () -> Unit,
    onOpenRecovery: () -> Unit,
) {
    Text(
        text  = "Enter your license key",
        style = MaterialTheme.typography.titleMedium,
    )
    Text(
        text  = "Verification happens entirely on this device — no network call is made.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    OutlinedTextField(
        value         = keyInput,
        onValueChange = onKeyChange,
        modifier      = Modifier.fillMaxWidth(),
        label         = { Text("License key") },
        placeholder   = { Text("XXXXX-XXXXX-XXXXX-XXXXX", fontFamily = FontFamily.Monospace) },
        textStyle     = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        singleLine    = true,
        isError       = uiState is ActivationViewModel.UiState.Error,
        supportingText = when (uiState) {
            is ActivationViewModel.UiState.Error ->
                { { Text(uiState.message, color = MaterialTheme.colorScheme.error) } }
            is ActivationViewModel.UiState.Activated ->
                { { Text("Pro features unlocked.", color = MaterialTheme.colorScheme.primary) } }
            else -> null
        },
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Characters,
            keyboardType   = KeyboardType.Ascii,
            imeAction      = ImeAction.Done,
        ),
    )

    Button(
        onClick  = onActivate,
        modifier = Modifier.fillMaxWidth(),
        enabled  = uiState !is ActivationViewModel.UiState.Activating,
    ) {
        if (uiState is ActivationViewModel.UiState.Activating) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            Text("Activate")
        }
    }

    Row(
        modifier            = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        TextButton(onClick = onOpenRecovery) {
            Text("Lost your key? Recover it")
        }
    }
}
