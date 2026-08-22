package xyz.libravault.feature.vault

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import xyz.libravault.core.ui.components.ReadingPresetPicker
import xyz.libravault.core.ui.theme.ReadingPresets
import xyz.libravault.core.ui.theme.ReadingTheme
import xyz.libravault.core.ui.theme.matching

/**
 * Reading settings sheet for the vault-native reader — same shape as
 * `feature:reader`'s `ReaderSettingsSheet`, over [VaultReaderSettings]. Like
 * the original, [showFontControls] hides font-size/line-spacing/font-family
 * for PDF — those are HTML/CSS-driven and PDF pages here are pre-rendered
 * bitmaps — but the scroll-mode row is shown regardless, same as the
 * original: it applies to PDF too now that [VaultPdfReaderScreen] has a
 * paginated mode.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultReaderSettingsSheet(
    settings: VaultReaderSettings,
    showFontControls: Boolean,
    onThemeChanged: (ReadingTheme) -> Unit,
    onFontSizeChanged: (Float) -> Unit,
    onFontFamilyChanged: (VaultReaderFontFamily) -> Unit,
    onLineSpacingChanged: (Float) -> Unit,
    onScrollModeChanged: (VaultScrollMode) -> Unit,
    onDismiss: () -> Unit,
    // Margins/justification/hyphenation (#421) — EPUB only, same rationale as
    // `feature:reader`'s `ReaderSettingsSheet.showEpubLayoutControls`.
    showEpubLayoutControls: Boolean = false,
    onMarginScaleChanged: (Float) -> Unit = {},
    onJustifyTextChanged: (Boolean) -> Unit = {},
    onHyphenationChanged: (Boolean) -> Unit = {},
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        var customizeExpanded by remember { mutableStateOf(false) }
        val activePreset = remember(settings.theme, settings.fontFamily, settings.fontSize, settings.lineSpacing) {
            ReadingPresets.builtIns.matching(
                theme       = settings.theme,
                fontFamily  = settings.fontFamily.toPresetFontFamily(),
                fontSize    = settings.fontSize,
                lineSpacing = settings.lineSpacing,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text("Reading settings", style = MaterialTheme.typography.headlineSmall)

            // ── Presets (#419) ─────────────────────────────────────────────
            Text(
                "Presets", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ReadingPresetPicker(
                presets = ReadingPresets.builtIns,
                activePreset = activePreset,
                onPresetSelected = { preset ->
                    onThemeChanged(preset.theme)
                    onFontFamilyChanged(preset.fontFamily.toVaultReaderFontFamily())
                    onFontSizeChanged(preset.fontSize)
                    onLineSpacingChanged(preset.lineSpacing)
                },
            )

            TextButton(onClick = { customizeExpanded = !customizeExpanded }) {
                Text(if (customizeExpanded) "Hide customization" else "Customize")
            }

            if (customizeExpanded) {
                HorizontalDivider()

                // ── Theme ──────────────────────────────────────────────────
                Text(
                    "Theme", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReadingTheme.entries.forEach { theme ->
                        FilterChip(
                            selected = settings.theme == theme,
                            onClick  = { onThemeChanged(theme) },
                            label    = { Text(theme.name.lowercase().replaceFirstChar { it.uppercase() }) },
                        )
                    }
                }

                if (showFontControls) {
                    HorizontalDivider()

                    // ── Font size ────────────────────────────────────────────
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.TextFields, contentDescription = "Text formatting", modifier = Modifier.size(16.dp))
                        Spacer(Modifier.weight(1f))
                        Text(
                            "${(settings.fontSize * 100).toInt()}%",
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                    Slider(
                        value = settings.fontSize,
                        onValueChange = onFontSizeChanged,
                        valueRange = 0.8f..2.0f,
                        steps = 11,
                    )

                    HorizontalDivider()

                    // ── Line spacing ─────────────────────────────────────────
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Line spacing", style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            "%.1f×".format(settings.lineSpacing),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                    Slider(
                        value = settings.lineSpacing,
                        onValueChange = onLineSpacingChanged,
                        valueRange = 1.0f..2.5f,
                        steps = 14,
                    )

                    HorizontalDivider()

                    // ── Font family ──────────────────────────────────────────
                    Text(
                        "Font", style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                    ) {
                        VaultReaderFontFamily.entries.forEach { family ->
                            FilterChip(
                                selected = settings.fontFamily == family,
                                onClick  = { onFontFamilyChanged(family) },
                                label    = { Text(family.displayName) },
                            )
                        }
                    }
                }

                if (showEpubLayoutControls) {
                    HorizontalDivider()

                    // ── Margins (#421) ────────────────────────────────────────
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Margins", style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            "${(settings.marginScale * 100).toInt()}%",
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                    Slider(
                        value = settings.marginScale,
                        onValueChange = onMarginScaleChanged,
                        valueRange = 0.5f..2.0f,
                        steps = 14,
                    )

                    HorizontalDivider()

                    // ── Justify text (#421) ───────────────────────────────────
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onJustifyTextChanged(!settings.justifyText) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Justify text", style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(checked = settings.justifyText, onCheckedChange = null)
                    }

                    // ── Hyphenation (#421) ────────────────────────────────────
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onHyphenationChanged(!settings.hyphenation) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Hyphenation", style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(checked = settings.hyphenation, onCheckedChange = null)
                    }
                }
            }

            HorizontalDivider()

            // ── Scroll mode ────────────────────────────────────────────────
            Text(
                "Mode", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VaultScrollMode.entries.forEach { mode ->
                    FilterChip(
                        selected = settings.scrollMode == mode,
                        onClick  = { onScrollModeChanged(mode) },
                        label    = { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }) },
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}
