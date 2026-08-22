package xyz.libravault.feature.reader.readaloud

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import xyz.libravault.core.domain.model.formatPlaybackSpeed
import xyz.libravault.feature.player.components.PlaybackControls
import xyz.libravault.feature.player.components.PlayerSeekBar
import xyz.libravault.feature.player.components.SleepTimerSheet
import xyz.libravault.feature.player.components.SpeedPickerSheet
import xyz.libravault.feature.player.service.SleepTimerState

/**
 * Full Player-screen experience for an active Read Aloud (TTS) session (#138) —
 * reuses `feature:player`'s shared scrubber/controls/speed/sleep-timer components,
 * but is driven entirely by [ReaderViewModel][xyz.libravault.feature.reader.ReaderViewModel]
 * state rather than [xyz.libravault.feature.player.PlayerViewModel]/a Media3
 * `MediaController` — see this PR's description for why the two don't share a
 * ViewModel. Rendered as an in-reader overlay (like the reader's other bottom
 * sheets), not a nav-graph destination — a TTS session is reader-scoped and
 * cannot outlive the reader.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadAloudPlayerScreen(
    title: String,
    author: String,
    isPlaying: Boolean,
    elapsedMs: Long,
    durationMs: Long,
    speed: Float,
    chapterIndex: Int,
    chapterCount: Int,
    sleepTimerState: SleepTimerState,
    showSleepTimerSheet: Boolean,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onStop: () -> Unit,
    onSeek: (Long) -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onSpeedSelected: (Float) -> Unit,
    onShowSleepTimer: () -> Unit,
    onHideSleepTimer: () -> Unit,
    onSetSleepTimer: (Long) -> Unit,
    onSetSleepTimerEndOfChapter: () -> Unit,
    onCancelSleepTimer: () -> Unit,
) {
    var showSpeedPicker by remember { mutableStateOf(false) }

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
                    IconButton(onClick = onShowSleepTimer) {
                        Icon(
                            imageVector = Icons.Default.Bedtime,
                            contentDescription = "Sleep timer",
                            tint = if (sleepTimerState !is SleepTimerState.Inactive) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
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
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.6f)
                    .size(200.dp)
                    .padding(vertical = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Headphones,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(64.dp),
                        )
                    }
                }
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = author,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                if (chapterCount > 0) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Chapter ${chapterIndex + 1} of $chapterCount",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            PlayerSeekBar(
                positionMs = elapsedMs,
                durationMs = durationMs,
                bufferedMs = durationMs,
                onSeek = onSeek,
            )

            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                PlaybackControls(
                    isPlaying = isPlaying,
                    hasPreviousChapter = chapterIndex > 0,
                    hasNextChapter = chapterIndex < chapterCount - 1,
                    onPlayPause = onPlayPause,
                    onSkipBack = onSkipBack,
                    onSkipForward = onSkipForward,
                    onPreviousChapter = onPreviousChapter,
                    onNextChapter = onNextChapter,
                )
            }

            TextButton(onClick = { showSpeedPicker = true }) {
                Text(
                    text = formatPlaybackSpeed(speed),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            if (sleepTimerState is SleepTimerState.Active) {
                val min = sleepTimerState.remainingMs / 60_000
                val sec = (sleepTimerState.remainingMs % 60_000) / 1000
                Text(
                    text = "Sleep in %d:%02d".format(min, sec),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            TextButton(onClick = onStop) {
                Text("Stop reading aloud", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (showSleepTimerSheet) {
        SleepTimerSheet(
            timerState = sleepTimerState,
            onSetTimer = onSetSleepTimer,
            onSetEndOfChapter = onSetSleepTimerEndOfChapter,
            onCancel = onCancelSleepTimer,
            onDismiss = onHideSleepTimer,
        )
    }

    if (showSpeedPicker) {
        SpeedPickerSheet(
            currentSpeed = speed,
            onSpeedSelected = onSpeedSelected,
            onDismiss = { showSpeedPicker = false },
        )
    }
}
