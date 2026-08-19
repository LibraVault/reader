package xyz.libravault.feature.vault

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
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import xyz.libravault.core.ui.theme.ReadingTheme

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
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text("Reading settings", style = MaterialTheme.typography.headlineSmall)

            // ── Theme ──────────────────────────────────────────────────────
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

                // ── Font size ──────────────────────────────────────────────
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

                // ── Line spacing ───────────────────────────────────────────
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

                // ── Font family ────────────────────────────────────────────
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
