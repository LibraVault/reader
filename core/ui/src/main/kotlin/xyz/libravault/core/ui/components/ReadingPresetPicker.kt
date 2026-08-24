package xyz.libravault.core.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import xyz.libravault.core.ui.theme.ReadingPreset

/**
 * Shared preset picker (#419) for `ReaderSettingsSheet` (feature:reader,
 * covering both real files and Encrypted Vault entries since #505 unified
 * the two) — a horizontally scrollable row of one-tap [ReadingPreset] chips,
 * the same `FilterChip` language the rest of the sheet already uses for
 * Theme/Font/Mode. Lives in core:ui, which feature:reader already depends
 * on, rather than being duplicated — unlike `FontFamily`/`ScrollMode`, this
 * component carries no feature-specific type, so there's nothing that would
 * diverge between copies.
 *
 * [activePreset] is null when the caller's current settings don't match any
 * built-in preset exactly ("Custom") — no chip is shown selected in that
 * case, deliberately: there is no "Custom" chip to select, only the absence
 * of a match.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadingPresetPicker(
    presets: List<ReadingPreset>,
    activePreset: ReadingPreset?,
    onPresetSelected: (ReadingPreset) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.horizontalScroll(rememberScrollState()),
    ) {
        presets.forEach { preset ->
            FilterChip(
                selected = preset.id == activePreset?.id,
                onClick  = { onPresetSelected(preset) },
                label    = { Text(preset.label) },
            )
        }
    }
}
