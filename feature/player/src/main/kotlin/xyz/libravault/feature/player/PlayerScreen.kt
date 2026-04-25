package xyz.libravault.feature.player

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.graphicsLayer
import coil.compose.AsyncImage
import coil.compose.rememberAsyncImagePainter
import xyz.libravault.feature.player.components.BookmarksSheet
import xyz.libravault.feature.player.components.ChapterListSheet
import xyz.libravault.feature.player.components.PlaybackControls
import xyz.libravault.feature.player.components.PlayerSeekBar
import xyz.libravault.feature.player.components.SleepTimerSheet
import xyz.libravault.feature.player.components.SpeedPickerSheet
import xyz.libravault.feature.player.service.SleepTimerState
import xyz.libravault.core.domain.model.formatPlaybackSpeed

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    itemId: Long? = null,
    fileUri: android.net.Uri? = null,
    onBack: () -> Unit,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val state     by viewModel.uiState.collectAsState()
    val bookmarks by viewModel.bookmarks.collectAsState()
    var showChapters    by remember { mutableStateOf(false) }
    var showSpeedPicker by remember { mutableStateOf(false) }

    // Start playback when item is loaded
    LaunchedEffect(state.item) {
        state.item?.let { item ->
            viewModel.play(Uri.parse(item.filePath))
        }
    }

    Scaffold(
            topBar = {
                TopAppBar(
                    title = { },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
                    },
                    actions = {
                        // Sleep timer
                        IconButton(onClick = viewModel::showSleepTimer) {
                            Icon(
                                imageVector = Icons.Default.Bedtime,
                                contentDescription = "Sleep timer",
                                tint = if (state.sleepTimerState !is SleepTimerState.Inactive)
                                    MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        // Bookmarks
                        IconButton(onClick = viewModel::addBookmark) {
                            Icon(Icons.Default.BookmarkAdd, "Add bookmark")
                        }
                        IconButton(onClick = viewModel::showBookmarks) {
                            Icon(Icons.Default.Bookmark, "Bookmarks")
                        }
                        // Chapter list — enabled only when chapters are loaded
                        IconButton(
                            onClick  = { if (state.chapters.isNotEmpty()) showChapters = true },
                            enabled  = state.chapters.isNotEmpty(),
                        ) {
                            Icon(Icons.Default.FormatListNumbered, "Chapters")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
                )
            },
        ) { innerPadding ->

            when {
                state.isLoading -> Box(
                    Modifier.fillMaxSize().padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                state.error != null -> Box(
                    Modifier.fillMaxSize().padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(state.error!!, color = MaterialTheme.colorScheme.error)
                }

                state.item != null -> {
                    val item = state.item!!

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .padding(horizontal = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.SpaceEvenly,
                    ) {

                        // ── Cover art ─────────────────────────────────────────
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.7f)
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(16.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (item.coverArtPath != null) {
                                AsyncImage(
                                    model = item.coverArtPath,
                                    contentDescription = item.title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(
                                            MaterialTheme.colorScheme.surfaceVariant
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text("🎧", style = MaterialTheme.typography.displayLarge)
                                }
                            }
                        }

                        // ── Title + author ────────────────────────────────────
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text      = item.title,
                                style     = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                maxLines  = 2,
                                overflow  = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text  = item.author,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                            // Chapter name
                            if (state.chapters.isNotEmpty()) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text  = state.chapters
                                        .getOrNull(state.currentChapterIndex)?.title ?: "",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    textAlign = TextAlign.Center,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }

                        // ── Seek bar ──────────────────────────────────────────
                        PlayerSeekBar(
                            positionMs = state.positionMs,
                            durationMs = state.durationMs,
                            bufferedMs = state.bufferedMs,
                            onSeek     = viewModel::seekTo,
                        )

                        // ── Controls with faint grimoire background ──────────
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Image(
                                painter = rememberAsyncImagePainter(
                                    xyz.libravault.feature.player.R.drawable.grimoire_bg
                                ),
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .size(220.dp)
                                    .graphicsLayer(alpha = 0.07f),
                            )
                        PlaybackControls(
                            isPlaying          = state.isPlaying,
                            hasPreviousChapter = state.currentChapterIndex > 0,
                            hasNextChapter     = state.currentChapterIndex < state.chapters.size - 1,
                            onPlayPause        = viewModel::togglePlayPause,
                            onSkipBack         = viewModel::skipBack,
                            onSkipForward      = viewModel::skipForward,
                            onPreviousChapter  = viewModel::previousChapter,
                            onNextChapter      = viewModel::nextChapter,
                        )
                        } // end grimoire Box

                        // ── Speed button — tap to open speed sheet ────────
                        TextButton(onClick = { showSpeedPicker = true }) {
                            Text(
                                text  = formatPlaybackSpeed(state.playbackSpeed),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }

                        // ── Sleep timer status ────────────────────────────────
                        if (state.sleepTimerState is SleepTimerState.Active) {
                            val remaining = (state.sleepTimerState as SleepTimerState.Active).remainingMs
                            val min = remaining / 60_000
                            val sec = (remaining % 60_000) / 1000
                            Text(
                                text  = "Sleep in %d:%02d".format(min, sec),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }

        // ── Bottom sheets ─────────────────────────────────────────────────────
        if (state.showSleepTimerSheet) {
            SleepTimerSheet(
                timerState       = state.sleepTimerState,
                onSetTimer       = viewModel::startSleepTimer,
                onSetEndOfChapter = viewModel::startSleepTimerEndOfChapter,
                onCancel         = viewModel::cancelSleepTimer,
                onDismiss        = viewModel::hideSleepTimer,
            )
        }

        if (state.showBookmarksSheet) {
            BookmarksSheet(
                bookmarks       = bookmarks,
                onBookmarkClick = viewModel::seekToBookmark,
                onDismiss       = viewModel::hideBookmarks,
            )
        }

        if (showSpeedPicker) {
            SpeedPickerSheet(
                currentSpeed    = state.playbackSpeed,
                onSpeedSelected = viewModel::setSpeed,
                onDismiss       = { showSpeedPicker = false },
            )
        }

        if (showChapters && state.chapters.isNotEmpty()) {
            ChapterListSheet(
                chapters            = state.chapters,
                currentChapterIndex = state.currentChapterIndex,
                onChapterSelected   = viewModel::goToChapter,
                onDismiss           = { showChapters = false },
            )
    }
}
