package xyz.libravault.feature.settings

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.launch
import xyz.libravault.core.domain.model.AppReadingTheme
import xyz.libravault.core.domain.model.VaultFolder
import xyz.libravault.core.domain.model.formatPlaybackSpeed

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val prefs by viewModel.preferences.collectAsState()
    val vaultState by viewModel.vaultState.collectAsState()
    val context = LocalContext.current

    // SAF folder picker launcher
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri: Uri = result.data?.data ?: return@rememberLauncherForActivityResult
            val displayName = uri.lastPathSegment
                ?.substringAfterLast(':')
                ?.substringAfterLast('/')
                ?: "My Vault"
            viewModel.onVaultFolderPicked(uri, displayName)
        }
    }

    fun launchFolderPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        folderPickerLauncher.launch(intent)
    }

    // Remove-vault confirmation dialog state
    var vaultToRemove by remember { mutableStateOf<VaultFolder?>(null) }

    // ── Remove confirmation dialog ─────────────────────────────────────────────
    vaultToRemove?.let { vault ->
        AlertDialog(
            onDismissRequest = { vaultToRemove = null },
            title = { Text("Remove vault?") },
            text = {
                Text("This will remove \"${vault.displayName}\" and all its items from the library.")
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeVault(vault)
                    vaultToRemove = null
                }) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { vaultToRemove = null }) {
                    Text("Cancel")
                }
            },
        )
    }

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
            // ── Vaults ─────────────────────────────────────────────────────────
            SectionHeader("Vaults")

            if (vaultState.vaults.isEmpty()) {
                Text(
                    text = "No vaults configured. Add a folder to get started.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            } else {
                vaultState.vaults.forEach { vault ->
                    VaultRow(
                        vault = vault,
                        onRemove = { vaultToRemove = vault },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            OutlinedButton(
                onClick = { launchFolderPicker() },
                modifier = Modifier.fillMaxWidth(),
                enabled = !vaultState.isScanning,
            ) {
                if (vaultState.isScanning) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Add, contentDescription = null, decorative = true)
                }
                Spacer(Modifier.size(8.dp))
                Text("Add vault")
            }

            vaultState.scanMessage?.let { msg ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = msg,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Divider()

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
                title    = "LibraVault",
                subtitle = "Version 0.1.0 · GPL-3.0 · libravault.xyz",
            )
            SettingLabel(
                title    = "Permissions",
                subtitle = "This app does not request internet access, " +
                        "location, contacts, camera, or broad file access. " +
                        "It reads only folders you explicitly grant it.",
            )

            Divider()

            // ── Support Development ─────────────────────────────────────────────
            SectionHeader("Support Development")

            SettingLabel(
                title    = "LibraVault is free",
                subtitle = "No ads, no tracking, no accounts. If this app brings you " +
                        "joy, consider supporting its development.",
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DonationButton(
                    label = "Donate XMR",
                    address = "42RowRVVQgXNxC1691mAVmesXg2JR8MUNaYbnpbG7HMJ8zqExXC2qo4cYdbF9MJpE6Z8jq7ytHWhdXrtxgrFySt349R8WmF",
                )
                DonationButton(
                    label = "Donate BTC",
                    address = "bc1q9y4q49lxnwrt9pnkgrxfpq92s9mvwv9espc5yg",
                )
            }

            SettingLabel(
                title    = "Also on GitHub Sponsors",
                subtitle = "github.com/libravault-xyz/libravault",
            )

            Spacer(Modifier.height(32.dp))
        }
    }
}

// ── Vault row composable ───────────────────────────────────────────────────────

@Composable
private fun VaultRow(
    vault: VaultFolder,
    onRemove: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Folder,
                contentDescription = null,
                decorative = true,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = vault.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = vault.uri,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Remove vault",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
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

@Composable
private fun DonationButton(label: String, address: String) {
    val context  = LocalContext.current
    val scope    = rememberCoroutineScope()

    OutlinedButton(onClick = {
        copyToClipboard(context, address)
        scope.launch {
            Toast.makeText(context, "$label address copied", Toast.LENGTH_SHORT).show()
        }
    }) {
        Text(label)
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("crypto address", text)
    clipboard.setPrimaryClip(clip)
}
