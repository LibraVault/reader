package xyz.libravault.feature.player.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

// Speed range: 0.5x – 3.0x in 0.05x steps
private const val SPEED_MIN   = 0.5f
private const val SPEED_MAX   = 3.0f
private const val SPEED_STEP  = 0.05f
private const val SPEED_STEPS = ((SPEED_MAX - SPEED_MIN) / SPEED_STEP).toInt() // 50 steps

// Five presets that cover the most common listening speeds
private val SPEED_PRESETS = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

/**
 * Audible-style speed picker sheet.
 *
 * Layout:
 *  - Header: "Speed" label + current value (e.g. "1.25×")
 *  - Row: [-] slider [+]   — increments of 0.05×
 *  - Row: 5 preset chips (0.75×, 1×, 1.25×, 1.5×, 2×)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeedPickerSheet(
    currentSpeed: Float,
    onSpeedSelected: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    // Local state so the slider feels responsive; we commit on dismiss or chip tap
    var speed by remember(currentSpeed) { mutableFloatStateOf(currentSpeed) }

    ModalBottomSheet(
        onDismissRequest = {
            onSpeedSelected(speed)
            onDismiss()
        },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ── Header ──────────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Speed",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = formatSpeed(speed),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            // ── Slider with +/- buttons ──────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconButton(
                    onClick = { speed = (speed - SPEED_STEP).coerceAtLeast(SPEED_MIN).roundToStep() },
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        Icons.Default.Remove,
                        contentDescription = "Decrease speed",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }

                Slider(
                    value = speedToSlider(speed),
                    onValueChange = { sliderVal ->
                        val newSpeed = sliderToSpeed(sliderVal)
                        speed = newSpeed
                        onSpeedSelected(newSpeed)
                    },
                    valueRange = 0f..SPEED_STEPS.toFloat(),
                    steps = SPEED_STEPS - 1,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(
                        thumbColor         = MaterialTheme.colorScheme.primary,
                        activeTrackColor   = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                )

                IconButton(
                    onClick = { speed = (speed + SPEED_STEP).coerceAtMost(SPEED_MAX).roundToStep() },
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "Increase speed",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            // ── Preset chips ─────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                SPEED_PRESETS.forEach { preset ->
                    FilterChip(
                        selected = speed.roundToStep() == preset,
                        onClick  = {
                            speed = preset
                            onSpeedSelected(preset)
                        },
                        label = {
                            Text(
                                text  = formatSpeed(preset),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

/** Round to nearest 0.05x step to avoid floating point drift */
private fun Float.roundToStep(): Float {
    val steps = ((this - SPEED_MIN) / SPEED_STEP).roundToInt()
    return (SPEED_MIN + steps * SPEED_STEP).coerceIn(SPEED_MIN, SPEED_MAX)
}

/** Map speed value → slider position (0..SPEED_STEPS) */
private fun speedToSlider(speed: Float): Float =
    ((speed - SPEED_MIN) / SPEED_STEP).coerceIn(0f, SPEED_STEPS.toFloat())

/** Map slider position → speed value */
private fun sliderToSpeed(sliderVal: Float): Float =
    (SPEED_MIN + sliderVal * SPEED_STEP).coerceIn(SPEED_MIN, SPEED_MAX).roundToStep()

/** Format speed for display: "1×", "1.25×", "0.75×" */
private fun formatSpeed(speed: Float): String {
    val rounded = speed.roundToStep()
    val diff = rounded - rounded.toInt()
    return if (kotlin.math.abs(diff) < 0.001f) {
        "${rounded.toInt()}×"
    } else {
        String.format(java.util.Locale.ROOT, "%.2g×", rounded).replace(",", ".")
    }
}
