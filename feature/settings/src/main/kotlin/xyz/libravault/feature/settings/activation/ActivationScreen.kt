package xyz.libravault.feature.settings.activation

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
    val activity = LocalContext.current as? Activity

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
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(24.dp))

            when (uiState) {
                is ActivationViewModel.UiState.Activated -> ActiveState()
                else -> PurchaseState(
                    uiState   = uiState,
                    onGetPro  = { activity?.let { viewModel.launchBillingFlow(it) } },
                    onDismiss = { viewModel.dismissError() },
                )
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ActiveState() {
    Spacer(Modifier.height(16.dp))
    Icon(
        imageVector        = Icons.Default.CheckCircle,
        contentDescription = null,
        tint               = MaterialTheme.colorScheme.primary,
        modifier           = Modifier.size(72.dp),
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text      = "Pro is active",
        style     = MaterialTheme.typography.headlineSmall,
        color     = MaterialTheme.colorScheme.primary,
        textAlign = TextAlign.Center,
    )
    Text(
        text      = "All Pro features are unlocked on this device.\nThank you for your support!",
        style     = MaterialTheme.typography.bodyMedium,
        color     = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun PurchaseState(
    uiState: ActivationViewModel.UiState,
    onGetPro: () -> Unit,
    onDismiss: () -> Unit,
) {
    Icon(
        imageVector        = Icons.Default.Star,
        contentDescription = null,
        tint               = MaterialTheme.colorScheme.primary,
        modifier           = Modifier.size(64.dp),
    )
    Spacer(Modifier.height(4.dp))
    Text(
        text      = "Upgrade to Pro",
        style     = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
    )
    Text(
        text = "One-time purchase via Google Play.\n" +
               "Automatically restores on any device\n" +
               "when you sign in with the same account.",
        style     = MaterialTheme.typography.bodyMedium,
        color     = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )

    Spacer(Modifier.height(8.dp))

    Button(
        onClick  = onGetPro,
        modifier = Modifier.fillMaxWidth(),
        enabled  = uiState !is ActivationViewModel.UiState.Activating,
    ) {
        if (uiState is ActivationViewModel.UiState.Activating) {
            CircularProgressIndicator(
                modifier    = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color       = MaterialTheme.colorScheme.onPrimary,
            )
        } else {
            Text("Get LibraVault Pro", style = MaterialTheme.typography.labelLarge)
        }
    }

    if (uiState is ActivationViewModel.UiState.Error) {
        Text(
            text  = uiState.message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        TextButton(onClick = onDismiss) { Text("Dismiss") }
    }
}
