package xyz.libravault.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import xyz.libravault.core.domain.model.AppReadingTheme
import xyz.libravault.core.domain.model.formatPlaybackSpeed

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val prefs by viewModel.preferences.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", style = MaterialTheme.typography.headlineMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // ── Reading ───────────────────────────────────────────────────────
            SectionHeader("Reading")

            SettingLabel(
                title    = "Default theme",
                subtitle = "Applied when opening a book or PDF",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppReadingTheme.entries.forEach { theme ->
                    FilterChip(
                        selected = prefs.defaultReadingTheme == theme,
                        onClick  = { viewModel.onReadingThemeChanged(theme) },
                        label    = {
                            Text(theme.name.lowercase().replaceFirstChar { it.uppercase() })
                        },
                    )
                }
            }

            Divider()

            // ── Playback ──────────────────────────────────────────────────────
            SectionHeader("Playback")

            SettingLabel(
                title    = "Default speed",
                subtitle = formatPlaybackSpeed(prefs.defaultPlaybackSpeed),
            )
            Slider(
                value         = prefs.defaultPlaybackSpeed,
                onValueChange = viewModel::onPlaybackSpeedChanged,
                valueRange    = 0.5f..3.0f,
                steps         = 9,
            )

            Spacer(Modifier.height(8.dp))

            SettingLabel(
                title    = "Skip duration",
                subtitle = "${prefs.defaultSkipDurationSec} seconds per skip",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(10, 15, 30, 45, 60).forEach { sec ->
                    FilterChip(
                        selected = prefs.defaultSkipDurationSec == sec,
                        onClick  = { viewModel.onSkipDurationChanged(sec) },
                        label    = { Text("${sec}s") },
                    )
                }
            }

            Divider()

            // ── Appearance ────────────────────────────────────────────────────
            SectionHeader("Appearance")

            SwitchSetting(
                title    = "Material You dynamic colour",
                subtitle = "Use your wallpaper colours throughout the app",
                checked  = prefs.dynamicColorEnabled,
                onCheckedChange = viewModel::onDynamicColorToggled,
            )

            Divider()

            // ── Privacy & Diagnostics ─────────────────────────────────────────
            SectionHeader("Privacy & Diagnostics")

            SwitchSetting(
                title    = "Local crash logging",
                subtitle = "Logs are stored only on this device and never transmitted. " +
                        "You can view or clear them at any time.",
                checked  = prefs.loggingEnabled,
                onCheckedChange = viewModel::onLoggingToggled,
            )

            if (prefs.loggingEnabled) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(onClick = viewModel::viewLogs) { Text("View logs") }
                    TextButton(onClick = viewModel::clearLogs) { Text("Clear logs") }
                }
            }

            Divider()

            // ── Storage ───────────────────────────────────────────────────────
            SectionHeader("Storage")

            SettingLabel(
                title    = "Cover art cache",
                subtitle = "Extracted cover images stored locally for fast display",
            )
            TextButton(onClick = viewModel::clearCoverCache) {
                Text("Clear cover cache")
            }

            Divider()

            // ── About ─────────────────────────────────────────────────────────
            SectionHeader("About")

            SettingLabel(
                title    = "Libravault",
                subtitle = "Version 0.1.0 · GPL-3.0 · libravault.xyz",
            )
            SettingLabel(
                title    = "Permissions",
                subtitle = "This app does not request internet access, " +
                        "location, contacts, camera, or broad file access. " +
                        "It reads only folders you explicitly grant it.",
            )

            Spacer(Modifier.height(32.dp))
        }
    }
}

// ── Sub-composables ───────────────────────────────────────────────────────────

@Composable
private fun SectionHeader(title: String) {
    Spacer(Modifier.height(16.dp))
    Text(
        text  = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

@Composable
private fun SettingLabel(title: String, subtitle: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        Text(subtitle, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SwitchSetting(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun Divider() {
    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
}
