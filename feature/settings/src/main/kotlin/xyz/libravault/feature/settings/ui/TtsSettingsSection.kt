package xyz.libravault.feature.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import xyz.libravault.core.tts.TtsEngineType
import xyz.libravault.core.tts.TtsVoiceInfo
import xyz.libravault.core.tts.pocket.ModelStatus

/**
 * Text-to-Speech settings section for the Settings screen. Purely a function of
 * its parameters - all TTS state lives in `SettingsViewModel.ttsState` and every
 * user action is reported via a callback, so this composable can be exercised
 * with plain fakes and doesn't need to know about Hilt, `TtsEngineProvider`, or
 * `PocketModelManager` directly.
 *
 * Pocket TTS ships identically across the fdroid and play flavors (the model is
 * bundled at build time - see `PocketModelManager`), so there is intentionally
 * no flavor gating here.
 */
@Composable
fun TtsSettingsSection(
    engineType: TtsEngineType,
    speechRate: Float,
    selectedVoiceId: String?,
    availableVoices: List<TtsVoiceInfo>,
    modelStatus: ModelStatus,
    onEngineTypeSelected: (TtsEngineType) -> Unit,
    onVoiceSelected: (String) -> Unit,
    onSpeechRateChanged: (Float) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Text-to-Speech",
            style = MaterialTheme.typography.titleMedium,
        )

        // Engine selection radio group
        EngineSelectionRadioGroup(
            selectedType = engineType,
            onTypeSelected = onEngineTypeSelected,
        )

        if (engineType == TtsEngineType.POCKET_TTS) {
            HorizontalDivider()
            PocketTtsModelSection(modelStatus)
        }

        if (engineType == TtsEngineType.POCKET_TTS || engineType == TtsEngineType.ANDROID) {
            HorizontalDivider()
            VoicePickerSection(
                voices = availableVoices,
                selectedVoiceId = selectedVoiceId,
                onVoiceSelected = onVoiceSelected,
            )
        }

        HorizontalDivider()

        // Speech rate slider
        SpeechRateSlider(
            currentRate = speechRate,
            onRateChanged = onSpeechRateChanged,
        )
    }
}

@Composable
private fun EngineSelectionRadioGroup(
    selectedType: TtsEngineType,
    onTypeSelected: (TtsEngineType) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        EngineRadioOption(
            label = "Android System TTS",
            isSelected = selectedType == TtsEngineType.ANDROID,
            onClick = { onTypeSelected(TtsEngineType.ANDROID) },
        )
        EngineRadioOption(
            label = "Pocket TTS (offline)",
            isSelected = selectedType == TtsEngineType.POCKET_TTS,
            onClick = { onTypeSelected(TtsEngineType.POCKET_TTS) },
        )
    }
}

@Composable
private fun EngineRadioOption(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = isSelected, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = isSelected, onClick = onClick)
        Text(text = label)
    }
}

@Composable
private fun PocketTtsModelSection(modelStatus: ModelStatus) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (modelStatus) {
            is ModelStatus.Idle -> {
                Text(
                    text = "Model status: Preparing…",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            is ModelStatus.Preparing -> {
                Text(
                    text = "Preparing voice model… ${(modelStatus.progress * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                )
                LinearProgressIndicator(
                    progress = { modelStatus.progress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "~37 MB, bundled with the app (first use only)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            is ModelStatus.Ready -> {
                Text(
                    text = "Voice model ready",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            is ModelStatus.Failed -> {
                Text(
                    text = "Model setup failed: ${modelStatus.error}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun VoicePickerSection(
    voices: List<TtsVoiceInfo>,
    selectedVoiceId: String?,
    onVoiceSelected: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Voice",
            style = MaterialTheme.typography.labelLarge,
        )
        if (voices.isEmpty()) {
            Text(
                text = "Voices become available once the TTS engine is ready.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            voices.forEach { voice ->
                VoiceRadioOption(
                    voice = voice,
                    isSelected = voice.id == selectedVoiceId,
                    onClick = { onVoiceSelected(voice.id) },
                )
            }
        }
    }
}

@Composable
private fun VoiceRadioOption(
    voice: TtsVoiceInfo,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = isSelected, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = isSelected, onClick = onClick)
        Text(
            text = if (voice.requiresNetwork) {
                "${voice.displayName} (${voice.locale}) — requires network"
            } else {
                "${voice.displayName} (${voice.locale})"
            },
        )
    }
}

@Composable
private fun SpeechRateSlider(
    currentRate: Float,
    onRateChanged: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Speech Rate: %.1f×".format(currentRate),
            style = MaterialTheme.typography.labelMedium,
        )
        Slider(
            value = currentRate,
            onValueChange = onRateChanged,
            valueRange = 0.5f..3.0f,
            steps = 4,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
