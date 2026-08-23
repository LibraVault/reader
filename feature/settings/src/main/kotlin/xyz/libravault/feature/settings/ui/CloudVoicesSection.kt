package xyz.libravault.feature.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import xyz.libravault.core.cloudtts.CloudCredentialFields
import xyz.libravault.core.cloudtts.CloudProviderId

/**
 * "Cloud Voices" Settings section — PRD docs/cloud-tts-premium-prd.md §6.
 * Only ever shown by the caller when `subscriptionActive` is true (see
 * `SettingsScreen.kt`), but the consent toggle inside is independent of
 * that and off by default — buying the subscription must never itself
 * enable a network call (PRD §4). Same pure state-in/callbacks-out shape as
 * `TtsSettingsSection`, so it's testable with plain fakes.
 */
@Composable
fun CloudVoicesSection(
    consentEnabled: Boolean,
    selectedProvider: CloudProviderId?,
    configuredProviders: Set<CloudProviderId>,
    onConsentAccepted: () -> Unit,
    onConsentDisabled: () -> Unit,
    onProviderSelected: (CloudProviderId) -> Unit,
    onValidateAndSaveKey: suspend (CloudProviderId, Map<String, String>) -> Result<Unit>,
    onClearKey: (CloudProviderId) -> Unit,
) {
    var showDisclosure by remember { mutableStateOf(false) }
    var keyEntryProvider by remember { mutableStateOf<CloudProviderId?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(text = "Cloud Voices", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "Bring your own API key for a cloud text-to-speech vendor for higher-quality " +
                "voices. Off by default — enabling it sends the text you're reading to that " +
                "vendor's servers. LibraVault never sees your key or your usage.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val toggleConsent: (Boolean) -> Unit = { enabling ->
            if (enabling) showDisclosure = true else onConsentDisabled()
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(value = consentEnabled, onValueChange = toggleConsent),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "Enable Cloud Voices", style = MaterialTheme.typography.bodyMedium)
            Switch(checked = consentEnabled, onCheckedChange = toggleConsent)
        }

        if (consentEnabled) {
            HorizontalDivider()
            Text(text = "Provider", style = MaterialTheme.typography.labelLarge)
            CloudProviderId.entries.forEach { provider ->
                ProviderRow(
                    provider = provider,
                    isSelected = provider == selectedProvider,
                    isConfigured = provider in configuredProviders,
                    onSelect = { onProviderSelected(provider) },
                    onConfigure = { keyEntryProvider = provider },
                    onClear = { onClearKey(provider) },
                )
            }
        }
    }

    if (showDisclosure) {
        CloudVoicesDisclosureDialog(
            onAccept = {
                showDisclosure = false
                onConsentAccepted()
            },
            onDismiss = { showDisclosure = false },
        )
    }

    keyEntryProvider?.let { provider ->
        CloudVoicesKeyEntryDialog(
            provider = provider,
            onDismiss = { keyEntryProvider = null },
            onSave = { fields -> onValidateAndSaveKey(provider, fields) },
            onSaved = { keyEntryProvider = null },
        )
    }
}

@Composable
private fun ProviderRow(
    provider: CloudProviderId,
    isSelected: Boolean,
    isConfigured: Boolean,
    onSelect: () -> Unit,
    onConfigure: () -> Unit,
    onClear: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // Same shape as TtsSettingsSection's EngineRadioOption: .selectable()
        // directly on the Row containing RadioButton+Text, not on a wrapping
        // Column — nesting a second selectable/merged-semantics layer around
        // an already-selectable Row confused Compose's test-tree matching
        // (found in review: onNodeWithText found the node but reported it
        // "not displayed").
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectable(selected = isSelected, onClick = onSelect)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RadioButton(selected = isSelected, onClick = onSelect)
            Text(text = provider.displayName())
            if (isConfigured) {
                Text(
                    text = "✓ Configured",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (isSelected) {
            Row(
                modifier = Modifier.padding(start = 40.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = onConfigure) {
                    Text(if (isConfigured) "Update API Key" else "Configure API Key")
                }
                if (isConfigured) {
                    TextButton(onClick = onClear) { Text("Remove") }
                }
            }
        }
    }
}

/** Human-readable vendor name for each fixed preset — PRD §3's closed list. */
fun CloudProviderId.displayName(): String = when (this) {
    CloudProviderId.ELEVENLABS -> "ElevenLabs"
    CloudProviderId.OPENAI -> "OpenAI"
    CloudProviderId.GOOGLE_CLOUD_TTS -> "Google Cloud TTS"
    CloudProviderId.AZURE_SPEECH -> "Azure AI Speech"
    CloudProviderId.AMAZON_POLLY -> "Amazon Polly"
}

@Composable
private fun CloudVoicesDisclosureDialog(onAccept: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enable Cloud Voices?") },
        text = {
            Text(
                "Turning this on lets LibraVault send the text of what you're reading to a " +
                    "cloud text-to-speech vendor you choose and configure, in order to generate " +
                    "speech. This is optional and off by default. You provide your own API key — " +
                    "LibraVault never sees it, never proxies the call, and never sees your usage " +
                    "or cost. You can turn this off again at any time.",
            )
        },
        confirmButton = { TextButton(onClick = onAccept) { Text("Accept & Enable") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private sealed interface KeyEntryStatus {
    data object Idle : KeyEntryStatus
    data object Validating : KeyEntryStatus
    data class Failed(val message: String) : KeyEntryStatus
}

@Composable
private fun CloudVoicesKeyEntryDialog(
    provider: CloudProviderId,
    onDismiss: () -> Unit,
    onSave: suspend (Map<String, String>) -> Result<Unit>,
    onSaved: () -> Unit,
) {
    val requiredFields = remember(provider) { CloudCredentialFields.requiredFields(provider).toList() }
    val fieldValues = remember(provider) { requiredFields.associateWith { mutableStateOf("") } }
    var status by remember(provider) { mutableStateOf<KeyEntryStatus>(KeyEntryStatus.Idle) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${provider.displayName()} API Key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                requiredFields.forEach { field ->
                    val state = fieldValues.getValue(field)
                    OutlinedTextField(
                        value = state.value,
                        onValueChange = { state.value = it },
                        label = { Text(field.fieldLabel()) },
                        singleLine = true,
                        visualTransformation = if (field == CloudCredentialFields.REGION) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                    )
                }
                if (status is KeyEntryStatus.Failed) {
                    Text(
                        text = (status as KeyEntryStatus.Failed).message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (status is KeyEntryStatus.Validating) {
                    CircularProgressIndicator(modifier = Modifier.padding(top = 8.dp))
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = status !is KeyEntryStatus.Validating && requiredFields.all { fieldValues.getValue(it).value.isNotBlank() },
                onClick = {
                    val credentials = requiredFields.associateWith { fieldValues.getValue(it).value }
                    status = KeyEntryStatus.Validating
                    scope.launch {
                        val result = onSave(credentials)
                        status = result.fold(
                            onSuccess = { onSaved(); KeyEntryStatus.Idle },
                            onFailure = { KeyEntryStatus.Failed(it.message ?: "Validation failed") },
                        )
                    }
                },
            ) { Text("Validate & Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun String.fieldLabel(): String = when (this) {
    CloudCredentialFields.API_KEY -> "API Key"
    CloudCredentialFields.REGION -> "Region"
    CloudCredentialFields.ACCESS_KEY_ID -> "Access Key ID"
    CloudCredentialFields.SECRET_ACCESS_KEY -> "Secret Access Key"
    else -> this
}
