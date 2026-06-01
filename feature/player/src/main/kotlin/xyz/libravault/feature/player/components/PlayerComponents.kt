package xyz.libravault.feature.player.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay30
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import xyz.libravault.core.domain.model.snapPlaybackSpeed
import xyz.libravault.feature.player.service.Chapter
import xyz.libravault.feature.player.service.SleepTimerState

// ── Seek bar ──────────────────────────────────────────────────────────────────

@Composable
fun PlayerSeekBar(
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Track drag state locally so the thumb follows the finger without calling
    // ExoPlayer seekTo on every pixel of movement (which causes stutter and lag).
    var isDragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }

    val sliderValue = when {
        isDragging        -> dragFraction
        durationMs > 0    -> positionMs.toFloat() / durationMs
        else              -> 0f
    }
    val displayMs = if (isDragging) (dragFraction * durationMs).toLong() else positionMs

    Column(modifier = modifier.fillMaxWidth()) {
        Box(modifier = Modifier.fillMaxWidth()) {
            // Buffered progress (behind)
            if (durationMs > 0) {
                LinearProgressIndicatorCustom(
                    progress = bufferedMs.toFloat() / durationMs,
                    color    = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth().height(4.dp).align(Alignment.Center),
                )
            }
            // Playback slider — updates local drag state on every move,
            // only seeks ExoPlayer once the finger lifts.
            Slider(
                value         = sliderValue,
                onValueChange = { fraction ->
                    isDragging   = true
                    dragFraction = fraction
                },
                onValueChangeFinished = {
                    if (durationMs > 0) onSeek((dragFraction * durationMs).toLong())
                    isDragging = false
                },
                modifier      = Modifier.fillMaxWidth(),
                colors        = SliderDefaults.colors(
                    thumbColor            = MaterialTheme.colorScheme.primary,
                    activeTrackColor      = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor    = MaterialTheme.colorScheme.surfaceVariant,
                ),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(formatMs(displayMs), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(formatMs(durationMs), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LinearProgressIndicatorCustom(
    progress: Float,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.clip(RoundedCornerShape(2.dp)).background(
        MaterialTheme.colorScheme.surfaceVariant)) {
        Box(modifier = Modifier
            .fillMaxWidth(progress.coerceIn(0f, 1f))
            .height(4.dp)
            .background(color))
    }
}

// ── Playback controls ─────────────────────────────────────────────────────────

@Composable
fun PlaybackControls(
    isPlaying: Boolean,
    hasPreviousChapter: Boolean,
    hasNextChapter: Boolean,
    onPlayPause: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPreviousChapter, enabled = hasPreviousChapter) {
            Icon(Icons.Default.SkipPrevious, contentDescription = "Previous chapter",
                modifier = Modifier.size(28.dp))
        }
        IconButton(onClick = onSkipBack) {
            Icon(Icons.Default.Replay30, contentDescription = "Skip back 30s",
                modifier = Modifier.size(32.dp))
        }
        // Large play/pause button
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .clickable(onClick = onPlayPause),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(40.dp),
            )
        }
        IconButton(onClick = onSkipForward) {
            Icon(Icons.Default.Forward30, contentDescription = "Skip forward 30s",
                modifier = Modifier.size(32.dp))
        }
        IconButton(onClick = onNextChapter, enabled = hasNextChapter) {
            Icon(Icons.Default.SkipNext, contentDescription = "Next chapter",
                modifier = Modifier.size(28.dp))
        }
    }
}

// ── Speed picker ──────────────────────────────────────────────────────────────

@Composable
fun SpeedPicker(
    currentSpeed: Float,
    onSpeedSelected: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val snappedCurrent = snapPlaybackSpeed(currentSpeed)
    val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        speeds.forEach { speed ->
            FilterChip(
                selected = snappedCurrent == speed,
                onClick  = { onSpeedSelected(speed) },
                label    = {
                    Text(
                        text  = if (speed == speed.toLong().toFloat())
                            "${speed.toInt()}×" else "${speed}×",
                        style = MaterialTheme.typography.labelSmall,
                    )
                },
            )
        }
    }
}

// ── Sleep timer sheet ─────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepTimerSheet(
    timerState: SleepTimerState,
    onSetTimer: (Long) -> Unit,
    onSetEndOfChapter: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Sleep timer", style = MaterialTheme.typography.headlineSmall)

            // Active timer status
            when (timerState) {
                is SleepTimerState.Active -> {
                    val remaining = timerState.remainingMs
                    val min = remaining / 60_000
                    val sec = (remaining % 60_000) / 1000
                    Text(
                        text  = "Stopping in %d:%02d — fades out over 10 s".format(min, sec),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    TextButton(onClick = onCancel) { Text("Cancel timer") }
                    HorizontalDivider()
                }
                is SleepTimerState.FadingOut -> {
                    Text("Fading out…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary)
                    HorizontalDivider()
                }
                SleepTimerState.Inactive -> Unit
            }

            // Preset durations
            Text("Set timer", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)

            val presets = listOf(
                "5 min"  to 5  * 60_000L,
                "10 min" to 10 * 60_000L,
                "15 min" to 15 * 60_000L,
                "30 min" to 30 * 60_000L,
                "45 min" to 45 * 60_000L,
                "60 min" to 60 * 60_000L,
            )

            presets.chunked(3).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    row.forEach { (label, ms) ->
                        FilterChip(
                            selected = false,
                            onClick  = { onSetTimer(ms) },
                            label    = { Text(label) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    // Pad last row if needed
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }

            FilterChip(
                selected = false,
                onClick  = onSetEndOfChapter,
                label    = { Text("End of chapter") },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(16.dp))
        }
    }
}

// ── Chapter list ──────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChapterListSheet(
    chapters: List<Chapter>,
    currentChapterIndex: Int,
    onChapterSelected: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "Chapters",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            )
            LazyColumn {
                itemsIndexed(chapters) { index, chapter ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onChapterSelected(index); onDismiss() }
                            .padding(horizontal = 24.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (index == currentChapterIndex) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = "Current chapter",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(12.dp))
                        } else {
                            Spacer(Modifier.width(30.dp))
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text     = chapter.title,
                                style    = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (index == currentChapterIndex)
                                    FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text  = formatMs(chapter.startMs),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp))
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun formatMs(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

// ── Bookmarks sheet (player) ──────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarksSheet(
    bookmarks: List<xyz.libravault.core.domain.model.Bookmark>,
    onBookmarkClick: (xyz.libravault.core.domain.model.Bookmark) -> Unit,
    onBookmarkDelete: (Long) -> Unit,
    onEditNote: (Long, String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val sorted = remember(bookmarks) {
        bookmarks.sortedBy { ref ->
            ref.positionRef.removePrefix("ms:").toLongOrNull() ?: Long.MAX_VALUE
        }
    }
    var editingBookmark by remember { mutableStateOf<xyz.libravault.core.domain.model.Bookmark?>(null) }
    var noteText by remember { mutableStateOf("") }

    editingBookmark?.let { bm ->
        AlertDialog(
            onDismissRequest = { editingBookmark = null },
            title = { Text("Edit note") },
            text = {
                OutlinedTextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    label = { Text("Note") },
                    singleLine = false,
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Button(onClick = {
                    onEditNote(bm.id, noteText.takeIf { it.isNotBlank() })
                    editingBookmark = null
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { editingBookmark = null }) { Text("Cancel") }
            },
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "Bookmarks",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            )
            if (bookmarks.isEmpty()) {
                Text(
                    text = "No bookmarks yet. Tap the bookmark icon to mark your position.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 32.dp),
                )
            } else {
                LazyColumn {
                    items(
                        items = sorted,
                        key   = { it.id },
                    ) { bookmark ->
                        val dismissState = rememberSwipeToDismissBoxState(
                            confirmValueChange = { value ->
                                if (value == SwipeToDismissBoxValue.EndToStart) {
                                    onBookmarkDelete(bookmark.id)
                                    true
                                } else false
                            }
                        )
                        SwipeToDismissBox(
                            state = dismissState,
                            enableDismissFromStartToEnd = false,
                            backgroundContent = {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(MaterialTheme.colorScheme.errorContainer)
                                        .padding(end = 20.dp),
                                    contentAlignment = Alignment.CenterEnd,
                                ) {
                                    Icon(Icons.Default.Delete, "Delete",
                                        tint = MaterialTheme.colorScheme.onErrorContainer)
                                }
                            },
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surface)
                                    .clickable { onBookmarkClick(bookmark) }
                                    .padding(horizontal = 24.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Icon(Icons.Default.Bookmark, "Bookmark",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text  = bookmark.label ?: bookmark.positionRef,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    bookmark.note?.takeIf { it.isNotBlank() }?.let { note ->
                                        Text(
                                            text  = note,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                                IconButton(
                                    onClick = {
                                        noteText = bookmark.note ?: ""
                                        editingBookmark = bookmark
                                    },
                                    modifier = Modifier.size(36.dp),
                                ) {
                                    Icon(Icons.Default.Edit, contentDescription = "Edit note",
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp))
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}
