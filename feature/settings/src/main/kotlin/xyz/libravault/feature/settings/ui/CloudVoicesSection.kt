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
    selectedVoiceId: String?,
    isCloudEngineActive: Boolean,
    onConsentAccepted: () -> Unit,
    onConsentDisabled: () -> Unit,
    onProviderSelected: (CloudProviderId) -> Unit,
    onVoiceIdChanged: (String) -> Unit,
    onValidateAndSaveKey: suspend (CloudProviderId, Map<String, String>) -> Result<Unit>,
    onClearKey: (CloudProviderId) -> Unit,
    onUseCloudEngineToggled: (Boolean) -> Unit,
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

            // Shown once a provider is merely selected, not gated on it being
            // configured yet: the toggle row below stays disabled (with an
            // explanation) until it actually is, but hiding this whole block
            // until then means a user picking a provider for the first time
            // never sees the voice ID field or the switch exists at all —
            // no path to discover them (found via a test written from the
            // intended UX, not the implementation).
            if (selectedProvider != null) {
                HorizontalDivider()
                // Free-text, not a hardcoded picker: real vendor voice IDs
                // (ElevenLabs' opaque per-voice hashes, Polly's/Azure's/
                // Google's named voices) are looked up in each vendor's own
                // dashboard/docs and can change or be custom per account —
                // guessing at a fixed catalog here risked shipping stale or
                // wrong IDs. The field is cleared automatically whenever the
                // selected provider changes (see SettingsViewModel.onCloudProviderSelected)
                // so a voice ID from a different provider/engine can never
                // leak through unnoticed.
                OutlinedTextField(
                    value = selectedVoiceId ?: "",
                    onValueChange = onVoiceIdChanged,
                    label = { Text("Voice ID") },
                    supportingText = { Text("From ${selectedProvider.displayName()}'s own voice list/dashboard") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                val canUseCloud = selectedProvider in configuredProviders && !selectedVoiceId.isNullOrBlank()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = isCloudEngineActive,
                            enabled = canUseCloud || isCloudEngineActive,
                            onValueChange = onUseCloudEngineToggled,
                        ),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(text = "Use Cloud Voices for Read Aloud", style = MaterialTheme.typography.bodyMedium)
                        if (!canUseCloud && !isCloudEngineActive) {
                            Text(
                                text = "Configure a provider and voice ID above first",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Switch(
                        checked = isCloudEngineActive,
                        enabled = canUseCloud || isCloudEngineActive,
                        onCheckedChange = onUseCloudEngineToggled,
                    )
                }
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
    // Sorted, not the raw Set: CloudCredentialFields.requiredFields() only
    // promises Set<String> in its declared contract — relying on incidental
    // LinkedHashSet iteration order for stable form-field ordering would be
    // a silent trap for a future change to that function (found in review).
    val requiredFields = remember(provider) { CloudCredentialFields.requiredFields(provider).sorted() }
    val fieldValues = remember(provider) { requiredFields.associateWith { mutableStateOf("") } }
    var status by remember(provider) { mutableStateOf<KeyEntryStatus>(KeyEntryStatus.Idle) }
    val isValidating = status is KeyEntryStatus.Validating
    val scope = rememberCoroutineScope()

    AlertDialog(
        // While a real network validation call is in flight, dismissing
        // (tap-outside/back) would abandon it silently mid-request with no
        // success/failure feedback to the user (found in review) — block
        // dismissal until it resolves, same as the Cancel button below.
        onDismissRequest = { if (!isValidating) onDismiss() },
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
                // `when` on a captured local, not two separate `is`
                // checks (the second re-cast unnecessarily since `status`
                // is a `var by remember` — smart-cast doesn't survive
                // across a lambda capture boundary; a `when` avoids the
                // redundant cast and won't silently miss a future variant).
                when (val current = status) {
                    is KeyEntryStatus.Failed -> Text(
                        text = current.message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    is KeyEntryStatus.Validating -> CircularProgressIndicator(modifier = Modifier.padding(top = 8.dp))
                    is KeyEntryStatus.Idle -> Unit
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !isValidating && requiredFields.all { fieldValues.getValue(it).value.isNotBlank() },
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
        dismissButton = { TextButton(enabled = !isValidating, onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun String.fieldLabel(): String = when (this) {
    CloudCredentialFields.API_KEY -> "API Key"
    CloudCredentialFields.REGION -> "Region"
    CloudCredentialFields.ACCESS_KEY_ID -> "Access Key ID"
    CloudCredentialFields.SECRET_ACCESS_KEY -> "Secret Access Key"
    else -> this
}
