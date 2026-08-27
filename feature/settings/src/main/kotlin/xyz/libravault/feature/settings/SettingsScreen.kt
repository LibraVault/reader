package xyz.libravault.feature.settings

import android.app.Activity
import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import xyz.libravault.core.domain.model.AppReadingTheme
import xyz.libravault.core.domain.model.VaultFolder
import xyz.libravault.core.domain.model.formatPlaybackSpeed
import xyz.libravault.core.cloudtts.CloudProviderId
import xyz.libravault.core.tts.TtsEngineType
import xyz.libravault.core.ui.findActivity
import xyz.libravault.feature.settings.ui.CloudVoicesSection
import xyz.libravault.feature.settings.ui.TtsSettingsSection

// ── Thin wrapper ─────────────────────────────────────────────────────────────
//
// Everything that needs a real ViewModel, Activity, or Context (SAF folder
// picker, external browser intent, in-app-billing purchase flow) lives here.
// Everything else — the whole screen body — is [SettingsContent], a pure
// function of (state, actions) with no Hilt/Context dependency, so it can be
// rendered directly in a Robolectric Compose test the same way
// TtsSettingsSection and PlayerScreen's PortraitPlayerContent already are (see
// docs/TEST_COVERAGE_PRD.md Phase 7 — this file is one of that phase's
// targets, split per the PlayerScreen template).
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onEncryptedVaultsClick: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val prefs by viewModel.preferences.collectAsState()
    val vaultState by viewModel.vaultState.collectAsState()
    val isSupporter by viewModel.isSupporter.collectAsState()
    val productsAvailable by viewModel.productsAvailable.collectAsState()
    val subscriptionActive by viewModel.subscriptionActive.collectAsState()
    val ttsState by viewModel.ttsState.collectAsState()
    val cloudVoicesConsent by viewModel.cloudVoicesConsent.collectAsState()
    val selectedCloudProvider by viewModel.selectedCloudProvider.collectAsState()
    val configuredCloudProviders by viewModel.configuredCloudProviders.collectAsState()
    val appVersionName by viewModel.appVersionName.collectAsState()
    val context = LocalContext.current
    val activity = context.findActivity()

    // SAF folder picker launcher — needs an Activity result contract, so it
    // cannot move into the pure content composable.
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

    val state = SettingsContentState(
        prefs = prefs,
        vaultState = vaultState,
        isSupporter = isSupporter,
        productsAvailable = productsAvailable,
        subscriptionActive = subscriptionActive,
        isBillingSupported = viewModel.isBillingSupported,
        appVersionName = appVersionName,
        ttsState = ttsState,
        cloudVoicesConsent = cloudVoicesConsent,
        selectedCloudProvider = selectedCloudProvider,
        configuredCloudProviders = configuredCloudProviders,
    )

    val actions = SettingsActions(
        onBack = onBack,
        onEncryptedVaultsClick = onEncryptedVaultsClick,
        onAddVaultClick = {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }
            folderPickerLauncher.launch(intent)
        },
        onRemoveVault = viewModel::removeVault,
        onReadingThemeChanged = viewModel::onReadingThemeChanged,
        onPlaybackSpeedChanged = viewModel::onPlaybackSpeedChanged,
        onSkipDurationChanged = viewModel::onSkipDurationChanged,
        onTtsEngineTypeSelected = viewModel::onTtsEngineTypeSelected,
        onTtsVoiceSelected = viewModel::onTtsVoiceSelected,
        onTtsSpeechRateChanged = viewModel::onTtsSpeechRateChanged,
        onCloudVoicesConsentAccepted = viewModel::onCloudVoicesConsentAccepted,
        onCloudVoicesConsentDisabled = viewModel::onCloudVoicesConsentDisabled,
        onCloudProviderSelected = viewModel::onCloudProviderSelected,
        onCloudVoiceIdChanged = viewModel::onCloudVoiceIdChanged,
        onValidateAndSaveCloudKey = viewModel::onValidateAndSaveCloudKey,
        onClearCloudKey = viewModel::onClearCloudKey,
        onUseCloudEngineToggled = viewModel::onUseCloudEngineToggled,
        onDynamicColorToggled = viewModel::onDynamicColorToggled,
        onLoggingToggled = viewModel::onLoggingToggled,
        onViewLogs = viewModel::viewLogs,
        onClearLogs = viewModel::clearLogs,
        onClearCoverCache = viewModel::clearCoverCache,
        onScreenSecurityToggled = viewModel::onScreenSecurityToggled,
        onSupportProjectClick = {
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SUPPORT_URL)))
            }
        },
        onSubscribeClick = { activity?.let(viewModel::purchaseSubscription) },
        onTipClick = { activity?.let(viewModel::purchaseOneTimeTip) },
    )

    SettingsContent(state = state, actions = actions)
}

// ── State & actions bundles ─────────────────────────────────────────────────
//
// Mirrors PlayerScreen's PlayerActions pattern (see PlayerScreen.kt):
// bundling every collaborator into two plain data classes turns the screen
// body below into a pure function testable without a ViewModel or Hilt graph.

internal data class SettingsContentState(
    val prefs: xyz.libravault.core.domain.model.UserPreferences,
    val vaultState: VaultManagementState,
    val isSupporter: Boolean,
    val productsAvailable: Boolean,
    val subscriptionActive: Boolean,
    val isBillingSupported: Boolean,
    val appVersionName: String,
    val ttsState: TtsSettingsUiState,
    val cloudVoicesConsent: Boolean,
    val selectedCloudProvider: CloudProviderId?,
    val configuredCloudProviders: Set<CloudProviderId>,
) {
    /** Same AND as CloudTtsGate's own — see the "Permissions" copy below for why
     * all three conditions matter, not just [cloudVoicesConsent] alone. */
    val cloudVoicesActuallySending: Boolean
        get() = subscriptionActive && cloudVoicesConsent && ttsState.engineType == TtsEngineType.CLOUD
}

internal data class SettingsActions(
    val onBack: () -> Unit,
    val onEncryptedVaultsClick: () -> Unit,
    val onAddVaultClick: () -> Unit,
    val onRemoveVault: (VaultFolder) -> Unit,
    val onReadingThemeChanged: (AppReadingTheme) -> Unit,
    val onPlaybackSpeedChanged: (Float) -> Unit,
    val onSkipDurationChanged: (Int) -> Unit,
    val onTtsEngineTypeSelected: (TtsEngineType) -> Unit,
    val onTtsVoiceSelected: (String) -> Unit,
    val onTtsSpeechRateChanged: (Float) -> Unit,
    val onCloudVoicesConsentAccepted: () -> Unit,
    val onCloudVoicesConsentDisabled: () -> Unit,
    val onCloudProviderSelected: (CloudProviderId) -> Unit,
    val onCloudVoiceIdChanged: (String) -> Unit,
    val onValidateAndSaveCloudKey: suspend (CloudProviderId, Map<String, String>) -> Result<Unit>,
    val onClearCloudKey: (CloudProviderId) -> Unit,
    val onUseCloudEngineToggled: (Boolean) -> Unit,
    val onDynamicColorToggled: (Boolean) -> Unit,
    val onLoggingToggled: (Boolean) -> Unit,
    val onViewLogs: () -> Unit,
    val onClearLogs: () -> Unit,
    val onClearCoverCache: () -> Unit,
    val onScreenSecurityToggled: (Boolean) -> Unit,
    val onSupportProjectClick: () -> Unit,
    val onSubscribeClick: () -> Unit,
    val onTipClick: () -> Unit,
)

// ── Pure content — the whole screen body, no ViewModel/Context/Activity ────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsContent(
    state: SettingsContentState,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
) {
    val prefs = state.prefs
    val vaultState = state.vaultState
    val ttsState = state.ttsState
    val isCloudEngineActive = ttsState.engineType == TtsEngineType.CLOUD

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
                    actions.onRemoveVault(vault)
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
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Settings", style = MaterialTheme.typography.headlineMedium) },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) {
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
                onClick = actions.onAddVaultClick,
                modifier = Modifier.fillMaxWidth(),
                enabled = !vaultState.isScanning,
            ) {
                if (vaultState.isScanning) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Add, contentDescription = "Add vault")
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
            // horizontalScroll — found via the Phase 7 screenshot baseline below:
            // with 5 AppReadingTheme entries (System, added by #349, was the
            // 5th), this Row had no wrap or scroll and squeezed "System" down to
            // chip width, breaking it mid-word into "Syst"/"em". A fixed Row of
            // chips will keep hitting this as themes are added; scrolling avoids
            // relying on the current entry count staying small.
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                AppReadingTheme.entries.forEach { theme ->
                    FilterChip(
                        selected = prefs.defaultReadingTheme == theme,
                        onClick  = { actions.onReadingThemeChanged(theme) },
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
                onValueChange = actions.onPlaybackSpeedChanged,
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
                        onClick  = { actions.onSkipDurationChanged(sec) },
                        label    = { Text("${sec}s") },
                    )
                }
            }

            Divider()

            TtsSettingsSection(
                engineType = ttsState.engineType,
                speechRate = ttsState.speechRate,
                selectedVoiceId = ttsState.selectedVoiceId,
                availableVoices = ttsState.availableVoices,
                modelStatus = ttsState.modelStatus,
                onEngineTypeSelected = actions.onTtsEngineTypeSelected,
                onVoiceSelected = actions.onTtsVoiceSelected,
                onSpeechRateChanged = actions.onTtsSpeechRateChanged,
            )

            // Only ever rendered when subscriptionActive — the real signal
            // (#397/#398), no mock/stub (PRD §8: safely inert until a real
            // Play Console product exists). The consent toggle inside stays
            // independently off by default regardless.
            if (state.subscriptionActive) {
                Divider()
                CloudVoicesSection(
                    consentEnabled = state.cloudVoicesConsent,
                    selectedProvider = state.selectedCloudProvider,
                    configuredProviders = state.configuredCloudProviders,
                    selectedVoiceId = ttsState.selectedVoiceId,
                    isCloudEngineActive = isCloudEngineActive,
                    onConsentAccepted = actions.onCloudVoicesConsentAccepted,
                    onConsentDisabled = actions.onCloudVoicesConsentDisabled,
                    onProviderSelected = actions.onCloudProviderSelected,
                    onVoiceIdChanged = actions.onCloudVoiceIdChanged,
                    onValidateAndSaveKey = actions.onValidateAndSaveCloudKey,
                    onClearKey = actions.onClearCloudKey,
                    onUseCloudEngineToggled = actions.onUseCloudEngineToggled,
                )
            }

            Divider()

            // ── Appearance ────────────────────────────────────────────────────
            SectionHeader("Appearance")

            SwitchSetting(
                title    = "Material You dynamic colour",
                subtitle = "Use your wallpaper colours throughout the app",
                checked  = prefs.dynamicColorEnabled,
                onCheckedChange = actions.onDynamicColorToggled,
            )

            Divider()

            // ── Privacy & Diagnostics ─────────────────────────────────────────
            SectionHeader("Privacy & Diagnostics")

            SwitchSetting(
                title    = "Local crash logging",
                subtitle = "Logs are stored only on this device and never transmitted. " +
                        "You can view or clear them at any time.",
                checked  = prefs.loggingEnabled,
                onCheckedChange = actions.onLoggingToggled,
            )

            if (prefs.loggingEnabled) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(onClick = actions.onViewLogs) { Text("View logs") }
                    TextButton(onClick = actions.onClearLogs) { Text("Clear logs") }
                }
            }

            Divider()

            // ── Storage ───────────────────────────────────────────────────────
            SectionHeader("Storage")

            SettingLabel(
                title    = "Cover art cache",
                subtitle = "Extracted cover images stored locally for fast display",
            )
            TextButton(onClick = actions.onClearCoverCache) {
                Text("Clear cover cache")
            }

            Divider()

            // ── Encrypted Vaults ─────────────────────────────────────────────────
            // Deliberately its own section, not folded into "Vaults" above: that
            // section is the unencrypted SAF-folder concept ("Folder" in PRD §9's
            // still-pending rename), a different guarantee from an Encrypted Vault.
            SectionHeader("Encrypted Vaults")

            SettingLabel(
                title    = "PIN-protected, encrypted at rest",
                subtitle = "Separate from the Folders above — files added to an Encrypted " +
                        "Vault are unreadable without its PIN, even with direct access to this device's storage.",
            )
            OutlinedButton(
                onClick = actions.onEncryptedVaultsClick,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Manage Encrypted Vaults")
            }

            Spacer(Modifier.height(8.dp))

            SwitchSetting(
                title    = "Screen Security",
                subtitle = "Block screenshots and screen recording while viewing or listening " +
                        "to Encrypted Vault content. Applies to all vaults; on by default.",
                checked  = prefs.screenSecurityEnabled,
                onCheckedChange = actions.onScreenSecurityToggled,
            )

            Divider()

            // ── About ─────────────────────────────────────────────────────────
            SectionHeader("About")

            SettingLabel(
                title    = "LibraVault",
                subtitle = "Version ${state.appVersionName} · GPL-3.0 · libravault.xyz",
            )
            SettingLabel(
                title    = "Permissions",
                subtitle = "This app does not request location, contacts, camera, or broad " +
                        "file access. It reads only folders you explicitly grant it." +
                        privacySubtitleSuffix(
                            isBillingSupported = state.isBillingSupported,
                            // Real network risk requires all three — subscribed
                            // AND consented AND actually the active Read Aloud
                            // engine, matching CloudTtsGate's own AND — not
                            // just the consent flag alone (found in review: the
                            // consent toggle can outlive a lapsed subscription
                            // that already hid the section it was set from).
                            cloudVoicesActuallySending = state.cloudVoicesActuallySending,
                        ),
            )

            Divider()

            // ── Support Development ─────────────────────────────────────────────
            SectionHeader("Support Development")

            if (state.isSupporter) {
                Text(
                    text = "★ You're a Supporter — thank you!",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = androidx.compose.ui.graphics.Color(0xFFFFB300),
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }

            SettingLabel(
                title    = "LibraVault is free",
                subtitle = "No ads, no tracking, no accounts. If this app brings you " +
                        "joy, consider supporting its development. BTC and XMR " +
                        "donation addresses are on the website, not in this app.",
            )

            if (!state.isBillingSupported) {
                // F-Droid: no billing backend exists there at all — unchanged external link
                // for the existing one-off flow.
                OutlinedButton(
                    onClick = actions.onSupportProjectClick,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Support the Project")
                }
                Spacer(Modifier.height(8.dp))
                // Recurring donation via BTCPay Subscriptions (#396) — no live
                // checkout page exists yet (the BTCPay-side plan setup is
                // separate infra work), so mirror the Play flavor's own
                // "coming soon" state below rather than shipping a
                // placeholder/guessed URL in a real-money flow.
                Text(
                    text = "Recurring support is coming soon",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            } else if (state.productsAvailable) {
                OutlinedButton(
                    onClick = actions.onSubscribeClick,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Subscribe — $1/mo")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = actions.onTipClick,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Send a one-time tip")
                }
            } else {
                // Play flavour, but the products haven't been created in Play Console
                // yet — deliberately no external-link fallback here (that's the whole
                // point of moving this flavour to native billing).
                Text(
                    text = "Support options are coming soon",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }

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
            Icon(imageVector = Icons.Default.Folder, contentDescription = "Folder", tint = MaterialTheme.colorScheme.primary)
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

/** Extracted from an inline 3-way string-concatenation conditional (found in
 * review) — the "Permissions" card's description of what network activity
 * exists on this build. [cloudVoicesActuallySending] should already be the
 * full AND (subscribed && consented && actually the active engine), not
 * just the consent flag alone — see the call site. */
private fun privacySubtitleSuffix(isBillingSupported: Boolean, cloudVoicesActuallySending: Boolean): String {
    if (!isBillingSupported) return " It makes no network calls of any kind."
    val billing = " Google Play Billing handles the optional purchases below."
    val cloudVoices = if (cloudVoicesActuallySending) {
        " Cloud Voices is on: text you choose to read aloud is sent to the cloud TTS vendor you configured below."
    } else {
        " Cloud Voices (optional, off by default, in Text-to-Speech above once subscribed) is the only other " +
            "network activity this app can ever have, and only sends text to a vendor you explicitly configure."
    }
    return billing + cloudVoices
}

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
