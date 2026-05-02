package xyz.libravault.feature.settings.activation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecoveryScreen(
    onBack: () -> Unit,
    viewModel: RecoveryViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    var phraseInput by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Recover license", style = MaterialTheme.typography.headlineMedium) },
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

            // Network-use disclosure — shown prominently before any input
            Surface(
                color  = MaterialTheme.colorScheme.secondaryContainer,
                shape  = MaterialTheme.shapes.small,
            ) {
                Text(
                    text     = "This is the only screen in LibraVault that contacts the network. " +
                               "Your recovery phrase is never stored anywhere — it is sent to the " +
                               "recovery server, which looks up only the hash of your phrase.",
                    style    = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                    modifier = Modifier.padding(12.dp),
                    color    = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }

            OutlinedTextField(
                value         = phraseInput,
                onValueChange = { phraseInput = it },
                modifier      = Modifier.fillMaxWidth(),
                label         = { Text("Recovery phrase") },
                placeholder   = { Text("twelve words separated by spaces") },
                minLines      = 3,
                maxLines      = 5,
                isError       = uiState is RecoveryViewModel.UiState.Error,
                supportingText = when (uiState) {
                    is RecoveryViewModel.UiState.Error     ->
                        { { Text((uiState as RecoveryViewModel.UiState.Error).message,
                                 color = MaterialTheme.colorScheme.error) } }
                    is RecoveryViewModel.UiState.Recovered ->
                        { { Text("License recovered and activated.",
                                 color = MaterialTheme.colorScheme.primary) } }
                    else -> null
                },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    keyboardType   = KeyboardType.Text,
                    imeAction      = ImeAction.Done,
                ),
            )

            if (uiState is RecoveryViewModel.UiState.Recovered) {
                val key = (uiState as RecoveryViewModel.UiState.Recovered).licenseKey
                Text(
                    text     = "Your key: $key",
                    style    = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color    = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text  = "Save this key somewhere safe — you can use it to activate on any device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Button(
                onClick  = { viewModel.recover(phraseInput) },
                modifier = Modifier.fillMaxWidth(),
                enabled  = uiState !is RecoveryViewModel.UiState.Recovering,
            ) {
                if (uiState is RecoveryViewModel.UiState.Recovering) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Text("Recover license")
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}
