package xyz.libravault.feature.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Divider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import xyz.libravault.core.tts.TtsEngineProvider
import xyz.libravault.core.tts.TtsEngineType
import xyz.libravault.core.tts.pocket.ModelStatus
import xyz.libravault.core.tts.pocket.PocketModelManager
import xyz.libravault.core.tts.pocket.PocketVoiceCatalog

@Composable
fun TtsSettingsSection(
    engineProvider: TtsEngineProvider,
    modelManager: PocketModelManager? = null,
    voiceCatalog: PocketVoiceCatalog? = null,
    isFdroidBuild: Boolean = false,
) {
    val engineType by engineProvider.engineType.collectAsState()
    val currentEngine by engineProvider.engine.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Text-to-Speech",
            style = MaterialTheme.typography.titleMedium,
        )

        // Engine selection radio group
        EngineSelectionRadioGroup(
            selectedType = engineType,
            onTypeSelected = { newType ->
                engineProvider.switchEngineSync(newType)
            },
            isFdroidBuild = isFdroidBuild,
        )

        Divider()

        // Pocket TTS model download (if selected)
        if (engineType == TtsEngineType.POCKET_TTS && modelManager != null && !isFdroidBuild) {
            PocketTtsModelSection(modelManager)
            Divider()
        }

        // Voice selection (TODO: wire up voice picker)
        Text(
            text = "Current Engine: ${engineType.name}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
        )

        // Speech rate slider
        SpeechRateSlider(
            currentRate = currentEngine.state.collectAsState().value.speechRate,
            onRateChanged = { rate ->
                currentEngine.setSpeechRate(rate)
            },
        )
    }
}

@Composable
private fun EngineSelectionRadioGroup(
    selectedType: TtsEngineType,
    onTypeSelected: (TtsEngineType) -> Unit,
    isFdroidBuild: Boolean,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        EngineRadioOption(
            label = "Android System TTS",
            isSelected = selectedType == TtsEngineType.ANDROID,
            onClick = { onTypeSelected(TtsEngineType.ANDROID) },
        )

        if (!isFdroidBuild) {
            EngineRadioOption(
                label = "Pocket TTS (offline)",
                isSelected = selectedType == TtsEngineType.POCKET_TTS,
                onClick = { onTypeSelected(TtsEngineType.POCKET_TTS) },
            )
        }
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
private fun PocketTtsModelSection(modelManager: PocketModelManager) {
    // TODO: Observe modelManager.ensureModelAvailable() state and show download UI
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Model Status: Downloading…",
            style = MaterialTheme.typography.bodySmall,
        )
        LinearProgressIndicator(
            progress = 0.5f,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = "~120 MB (first use only)",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.secondary,
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
