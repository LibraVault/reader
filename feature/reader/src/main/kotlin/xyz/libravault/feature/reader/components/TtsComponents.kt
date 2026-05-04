package xyz.libravault.feature.reader.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import xyz.libravault.core.tts.TtsState
import xyz.libravault.core.tts.TtsStatus
import xyz.libravault.core.tts.TtsVoiceInfo

/**
 * Persistent bottom bar displayed in the reader when TTS is active.
 * Shows play/pause/stop controls and a button to open the settings sheet.
 */
@Composable
fun TtsBottomBar(
    state: TtsState,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shadowElevation = 8.dp,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Play / Pause
            IconButton(
                onClick = if (state.status == TtsStatus.PLAYING) onPause else onPlay,
                enabled = state.status != TtsStatus.UNINITIALIZED &&
                          state.status != TtsStatus.INITIALIZING &&
                          state.status != TtsStatus.ERROR,
            ) {
                Icon(
                    imageVector = if (state.status == TtsStatus.PLAYING) Icons.Default.Pause
                                  else Icons.Default.PlayArrow,
                    contentDescription = if (state.status == TtsStatus.PLAYING) "Pause" else "Play",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )
            }

            // Stop
            IconButton(
                onClick = onStop,
                enabled = state.status == TtsStatus.PLAYING || state.status == TtsStatus.PAUSED,
            ) {
                Icon(
                    Icons.Default.Stop,
                    contentDescription = "Stop",
                    modifier = Modifier.size(24.dp),
                )
            }

            Spacer(Modifier.weight(1f))

            // Status label
            val statusText = when (state.status) {
                TtsStatus.INITIALIZING -> "Loading voices…"
                TtsStatus.PLAYING      -> "Reading aloud"
                TtsStatus.PAUSED       -> "Paused"
                TtsStatus.ERROR        -> "Error"
                else                   -> "Ready"
            }
            Text(
                text  = statusText,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.weight(1f))

            // Voice / speed settings
            IconButton(onClick = onOpenSettings) {
                Icon(
                    Icons.Default.RecordVoiceOver,
                    contentDescription = "Voice settings",
                )
            }
        }
    }
}

/**
 * Bottom sheet for selecting TTS voice and adjusting speech rate.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TtsSettingsSheet(
    state: TtsState,
    onVoiceSelected: (String) -> Unit,
    onSpeechRateChanged: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            Text("Voice settings", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(16.dp))

            // ── Speech rate ────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Speed",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "%.1fx".format(state.speechRate),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                )
            }
            Slider(
                value = state.speechRate,
                onValueChange = onSpeechRateChanged,
                valueRange = 0.5f..3.0f,
                steps = 9,
                modifier = Modifier.fillMaxWidth(),
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // ── Voice list ─────────────────────────────────────────────────
            Text(
                "Voice",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))

            if (state.availableVoices.isEmpty()) {
                Text(
                    "No offline voices found. Install voices in Android Settings → Accessibility → Text-to-speech.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(state.availableVoices, key = TtsVoiceInfo::id) { voice ->
                        VoiceRow(
                            voice      = voice,
                            isSelected = voice.id == state.selectedVoiceId,
                            onClick    = { onVoiceSelected(voice.id) },
                        )
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun VoiceRow(
    voice: TtsVoiceInfo,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = isSelected, onClick = onClick, role = Role.RadioButton)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = isSelected, onClick = null)
        Text(
            text     = voice.displayName,
            style    = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp),
        )
    }
}
