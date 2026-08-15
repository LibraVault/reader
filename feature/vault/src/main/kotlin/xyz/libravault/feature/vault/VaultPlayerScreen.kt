package xyz.libravault.feature.vault

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import xyz.libravault.feature.player.components.PlaybackControls
import xyz.libravault.feature.player.components.PlayerSeekBar

/**
 * Plays one vault audio file — foreground-only (see
 * [VaultPlayerViewModel]'s doc comment). Reuses `feature:player`'s
 * [PlayerSeekBar]/[PlaybackControls] (pure state+callback composables,
 * already the pattern `feature:reader`/`feature:library` depend on
 * `feature:player` for) rather than rebuilding seek/transport UI.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultPlayerScreen(
    onBack: () -> Unit,
    viewModel: VaultPlayerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    SecureScreenEffect(enabled = remember { VaultScreenSecurityPreference.isEnabled(context) })

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.title.ifBlank { "Vault" }) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                state.error != null -> Text(
                    "Could not play file: ${state.error}",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                )
                state.isLoading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                else -> Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    PlayerSeekBar(
                        positionMs = state.positionMs,
                        durationMs = state.durationMs,
                        bufferedMs = state.bufferedMs,
                        onSeek     = viewModel::onSeek,
                        modifier   = Modifier.fillMaxWidth(),
                    )
                    PlaybackControls(
                        isPlaying          = state.isPlaying,
                        hasPreviousChapter = false,
                        hasNextChapter     = false,
                        onPlayPause        = viewModel::onPlayPause,
                        onSkipBack         = viewModel::onSkipBack,
                        onSkipForward      = viewModel::onSkipForward,
                        onPreviousChapter  = {},
                        onNextChapter      = {},
                        modifier           = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
