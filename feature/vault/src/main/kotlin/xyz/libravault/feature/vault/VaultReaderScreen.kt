package xyz.libravault.feature.vault

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * Reads one vault file — EPUB ([VaultEpubReaderScreen]) or PDF
 * ([VaultPdfReaderScreen]), dispatched by [VaultReaderViewModel.state]. Tap
 * the centre of an EPUB page to toggle the top bar (matches the PDF/audio
 * screens' always-visible bar less, but preserves the immersive-reading
 * expectation `feature:reader`'s own EPUB screen sets — see
 * `VaultEpubReaderScreen`'s `onCentreTap`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultReaderScreen(
    onBack: () -> Unit,
    viewModel: VaultReaderViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var showToolbar by remember { mutableStateOf(true) }
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    SecureScreenEffect(enabled = remember { VaultScreenSecurityPreference.isEnabled(context) })

    LaunchedEffect(state) {
        if (state is VaultReaderState.WrongScreen) onBack()
    }

    Scaffold(
        topBar = {
            if (showToolbar) {
                TopAppBar(
                    title = { Text(titleFor(state)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                )
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(if (showToolbar) padding else PaddingValues(0.dp))) {
            when (val s = state) {
                is VaultReaderState.Loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                is VaultReaderState.Error -> Text(
                    "Could not open file: ${s.message}",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                )
                is VaultReaderState.WrongScreen -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                is VaultReaderState.EpubReady -> {
                    val fragmentManager = activity?.supportFragmentManager
                    if (fragmentManager != null) {
                        VaultEpubReaderScreen(
                            publication     = s.publication,
                            fragmentManager = fragmentManager,
                            onCentreTap     = { showToolbar = !showToolbar },
                            modifier        = Modifier.fillMaxSize(),
                        )
                    }
                }
                is VaultReaderState.PdfReady -> VaultPdfReaderScreen(
                    reader   = viewModel.pdfReader(),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

private fun titleFor(state: VaultReaderState): String = when (state) {
    is VaultReaderState.EpubReady -> state.title
    is VaultReaderState.PdfReady -> state.title
    else -> "Vault"
}
