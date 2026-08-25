package xyz.libravault.feature.vault

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import xyz.libravault.core.ui.SecureScreenEffect
import xyz.libravault.feature.player.components.PlaybackControls
import xyz.libravault.feature.player.components.PlayerSeekBar

/**
 * Plays one vault audio file — foreground-only (see
 * [VaultPlayerViewModel]'s doc comment). Reuses `feature:player`'s
 * [PlayerSeekBar]/[PlaybackControls] (pure state+callback composables,
 * already the pattern `feature:reader`/`feature:library` depend on
 * `feature:player` for) rather than rebuilding seek/transport UI.
 *
 * Bookmarks work the same way as [VaultReaderScreen]'s: the add-bookmark
 * action in the top bar bookmarks the current playback position; tapping a
 * bookmark in [VaultBookmarksSheet] seeks the player to it via
 * [VaultPlayerViewModel.seekToBookmark].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultPlayerScreen(
    onBack: () -> Unit,
    viewModel: VaultPlayerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val bookmarks by viewModel.bookmarks.collectAsState()
    var showBookmarksSheet by remember { mutableStateOf(false) }
    val context = LocalContext.current
    SecureScreenEffect(enabled = rememberScreenSecurityEnabled(context))

    // #526 — re-check lock state every time this screen comes back to the
    // foreground, same DisposableEffect+ON_RESUME idiom VaultListScreen
    // already uses, since nothing else here observes VaultSessionManager
    // continuously.
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentViewModel = rememberUpdatedState(viewModel)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) currentViewModel.value.checkStillUnlocked()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(state.wasLocked) {
        if (state.wasLocked) onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.title.ifBlank { "Vault" }) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.addBookmark() }) {
                        Icon(Icons.Default.BookmarkAdd, contentDescription = "Add bookmark")
                    }
                    IconButton(onClick = { showBookmarksSheet = true }) {
                        Icon(Icons.Default.Bookmark, contentDescription = "Bookmarks")
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

    if (showBookmarksSheet) {
        VaultBookmarksSheet(
            bookmarks       = bookmarks,
            onBookmarkClick = { bookmark ->
                viewModel.seekToBookmark(bookmark)
                showBookmarksSheet = false
            },
            onBookmarkDelete = viewModel::removeBookmark,
            onEditNote       = viewModel::updateBookmarkNote,
            onDismiss        = { showBookmarksSheet = false },
        )
    }
}
