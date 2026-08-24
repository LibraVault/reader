import SwiftUI

/// "Cloud Voices" Settings section — PRD `docs/cloud-tts-premium-prd.md` §6. Only ever
/// rendered by `SettingsView` when `billingManager.isSubscribed` is true, but the
/// consent toggle inside stays independently off by default — buying the subscription
/// must never itself enable a network call (PRD §4, `CloudTtsGate`). Mirrors Android's
/// `CloudVoicesSection` Composable (`feature/settings/.../ui/CloudVoicesSection.kt`).
///
/// Owns its own persistence (`CloudVoicePreferences`/`CloudApiKeyStore`) via injectable
/// defaults — the same "local `@State` backed directly by its own persistence type, not
/// routed through `AppState`" shape `SettingsView.screenSecurityEnabled`/`loggingEnabled`
/// already use. This state has no reason to live anywhere `AppState`'s other properties
/// do, and `AppState` has no dependency on Cloud TTS today.
struct CloudVoicesSection: View {
    private let preferences: CloudVoicePreferences
    private let keyStore: CloudApiKeyStore
    private let ttsProvider: CloudTtsProvider

    @State private var consentEnabled: Bool
    @State private var selectedProvider: CloudProviderId?
    @State private var configuredProviders: Set<CloudProviderId>
    @State private var voiceID: String
    @State private var showDisclosure = false
    @State private var isShowingKeyEntry = false
    @State private var keyEntryProvider: CloudProviderId = .elevenLabs

    init(
        preferences: CloudVoicePreferences = CloudVoicePreferences(),
        keyStore: CloudApiKeyStore = KeychainCloudApiKeyStore(),
        ttsProvider: CloudTtsProvider = RealCloudTtsProvider()
    ) {
        self.preferences = preferences
        self.keyStore = keyStore
        self.ttsProvider = ttsProvider
        _consentEnabled = State(initialValue: preferences.loadConsentEnabled())
        _selectedProvider = State(initialValue: preferences.loadSelectedProvider())
        _configuredProviders = State(initialValue: Self.loadConfiguredProviders(keyStore: keyStore))
        _voiceID = State(initialValue: preferences.loadSelectedVoiceID() ?? "")
    }

    var body: some View {
        Section {
            Text("Bring your own API key for a cloud text-to-speech vendor for higher-quality voices. Off by default — enabling it sends the text you're reading to that vendor's servers. LibraVault never sees your key or your usage.")
                .font(LibraVaultTypography.bodySmall)
                .foregroundStyle(LibraVaultColor.onSurfaceVariant)

            // Deliberately does NOT flip `consentEnabled` on turn-on: the visible
            // toggle only commits once the disclosure alert below is accepted, so it
            // snaps back to off if the user cancels. Same shape as Android's
            // `toggleable(value = consentEnabled, onValueChange = toggleConsent)`.
            Toggle("Enable Cloud Voices", isOn: Binding(
                get: { consentEnabled },
                set: { newValue in
                    if newValue {
                        showDisclosure = true
                    } else {
                        consentEnabled = false
                        preferences.save(consentEnabled: false)
                    }
                }
            ))
            .tint(LibraVaultColor.primary)

            if consentEnabled {
                ForEach(CloudProviderId.allCases, id: \.self) { provider in
                    providerRow(provider)
                }

                // Shown once a provider is merely selected, not gated on it being
                // configured yet — same reasoning as Android's CloudVoicesSection.kt:
                // hiding this until a key is saved means a user configuring for the
                // first time never sees the field exists at all.
                if let selectedProvider {
                    VStack(alignment: .leading, spacing: LibraVaultSpacing.xs) {
                        TextField("Voice ID", text: Binding(
                            get: { voiceID },
                            set: { newValue in
                                voiceID = newValue
                                preferences.save(selectedVoiceID: newValue.isEmpty ? nil : newValue)
                            }
                        ))
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                        Text("From \(selectedProvider.displayName)'s own voice list/dashboard")
                            .font(LibraVaultTypography.bodySmall)
                            .foregroundStyle(LibraVaultColor.onSurfaceVariant)
                    }
                    .padding(.vertical, LibraVaultSpacing.xs)
                }
            }
        } header: {
            Text("Cloud Voices")
                .font(LibraVaultTypography.titleSmall)
                .foregroundStyle(LibraVaultColor.primary)
        }
        .alert("Enable Cloud Voices?", isPresented: $showDisclosure) {
            Button("Cancel", role: .cancel) {}
            Button("Accept & Enable") {
                consentEnabled = true
                preferences.save(consentEnabled: true)
            }
        } message: {
            Text("Turning this on lets LibraVault send the text of what you're reading to a cloud text-to-speech vendor you choose and configure, in order to generate speech. This is optional and off by default. You provide your own API key — LibraVault never sees it, never proxies the call, and never sees your usage or cost. You can turn this off again at any time.")
        }
        .sheet(isPresented: $isShowingKeyEntry) {
            CloudVoicesKeyEntrySheet(
                provider: keyEntryProvider,
                ttsProvider: ttsProvider,
                keyStore: keyStore,
                onSaved: {
                    isShowingKeyEntry = false
                    configuredProviders = Self.loadConfiguredProviders(keyStore: keyStore)
                }
            )
        }
    }

    private func providerRow(_ provider: CloudProviderId) -> some View {
        VStack(alignment: .leading, spacing: LibraVaultSpacing.xs) {
            Button {
                voiceID = Self.voiceID(afterSelecting: provider, previousProvider: selectedProvider, currentVoiceID: voiceID)
                preferences.save(selectedVoiceID: voiceID.isEmpty ? nil : voiceID)
                selectedProvider = provider
                preferences.save(selectedProvider: provider)
            } label: {
                HStack(spacing: LibraVaultSpacing.sm) {
                    Image(systemName: provider == selectedProvider ? "largecircle.fill.circle" : "circle")
                        .foregroundStyle(LibraVaultColor.primary)
                    Text(provider.displayName)
                        .foregroundStyle(LibraVaultColor.onSurface)
                    if configuredProviders.contains(provider) {
                        Spacer()
                        Text("✓ Configured")
                            .font(LibraVaultTypography.labelSmall)
                            .foregroundStyle(LibraVaultColor.primary)
                    }
                }
            }
            .buttonStyle(.plain)

            if provider == selectedProvider {
                HStack(spacing: LibraVaultSpacing.md) {
                    Button(configuredProviders.contains(provider) ? "Update API Key" : "Configure API Key") {
                        keyEntryProvider = provider
                        isShowingKeyEntry = true
                    }
                    .font(LibraVaultTypography.labelLarge)
                    .foregroundStyle(LibraVaultColor.primary)

                    if configuredProviders.contains(provider) {
                        Button("Remove") {
                            keyStore.clear(provider: provider)
                            configuredProviders = Self.loadConfiguredProviders(keyStore: keyStore)
                        }
                        .font(LibraVaultTypography.labelLarge)
                        .foregroundStyle(.red)
                    }
                }
                .padding(.leading, LibraVaultSpacing.xl)
            }
        }
        .padding(.vertical, LibraVaultSpacing.xs)
        .buttonStyle(.plain)
    }

    /// `nil` credentials (nothing saved yet) vs. present — no partial/invalid state to
    /// account for, since `CloudApiKeyStore.save` only ever persists a field set that
    /// exactly matches `CloudCredentialFields.requiredFields`.
    static func loadConfiguredProviders(keyStore: CloudApiKeyStore) -> Set<CloudProviderId> {
        Set(CloudProviderId.allCases.filter { keyStore.load(provider: $0) != nil })
    }

    /// Only `.region` is shown in the clear — an Azure/Polly region string carries no
    /// secrecy, and obscuring it would just make it harder to double check for typos.
    /// Every other field is a real credential and stays obscured, mirroring Android's
    /// `PasswordVisualTransformation` for every field except `CloudCredentialFields.REGION`.
    static func isSecureField(_ field: CloudCredentialField) -> Bool {
        field != .region
    }

    /// Voice ID is provider-scoped — a value entered for one vendor is meaningless (and
    /// potentially misleading, since `CloudTtsEngine.performSpeak` submits it verbatim
    /// to whichever provider is currently selected) once the user switches to a
    /// different one. Mirrors Android's identical "cleared automatically whenever the
    /// selected provider changes" rule (`CloudVoicesSection.kt`'s field comment).
    /// Re-selecting the SAME provider (e.g. re-tapping the already-selected row) must
    /// NOT clear it.
    static func voiceID(afterSelecting provider: CloudProviderId, previousProvider: CloudProviderId?, currentVoiceID: String) -> String {
        provider == previousProvider ? currentVoiceID : ""
    }

    /// "Validate & Save" is only actionable once every required field for the selected
    /// provider is non-blank and no validation call is already in flight.
    static func isSaveEnabled(
        requiredFields: Set<CloudCredentialField>,
        values: [CloudCredentialField: String],
        isValidating: Bool
    ) -> Bool {
        guard !isValidating else { return false }
        return requiredFields.allSatisfy { !(values[$0] ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
    }

    /// Validates BEFORE saving (PRD §6: "key is validated with a single cheap test
    /// call, then stored") — a failed validation never reaches `CloudApiKeyStore` at
    /// all. Mirrors Android's `SettingsViewModel.onValidateAndSaveCloudKey`. A plain
    /// `static` async function, not a method on a live view, so it's directly
    /// testable against fakes without standing up SwiftUI.
    static func validateAndSave(
        provider: CloudProviderId,
        credentials: [CloudCredentialField: String],
        using ttsProvider: CloudTtsProvider,
        keyStore: CloudApiKeyStore
    ) async -> Result<Void, Error> {
        do {
            try await ttsProvider.validateKey(provider: provider, credentials: credentials)
        } catch {
            return .failure(error)
        }
        do {
            try keyStore.save(provider: provider, credentials: credentials)
            return .success(())
        } catch {
            return .failure(error)
        }
    }
}

/// Per-provider API key entry sheet, shown from `CloudVoicesSection.providerRow`.
/// Its own `View` (not inlined into `CloudVoicesSection.body`) so its field-entry state
/// resets cleanly per `provider` via `init`, matching Android's `CloudVoicesKeyEntryDialog`
/// `remember(provider) { ... }` keying.
private struct CloudVoicesKeyEntrySheet: View {
    let provider: CloudProviderId
    let ttsProvider: CloudTtsProvider
    let keyStore: CloudApiKeyStore
    let onSaved: () -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var fieldValues: [CloudCredentialField: String]
    @State private var isValidating = false
    @State private var errorMessage: String?

    private let requiredFields: [CloudCredentialField]

    init(provider: CloudProviderId, ttsProvider: CloudTtsProvider, keyStore: CloudApiKeyStore, onSaved: @escaping () -> Void) {
        self.provider = provider
        self.ttsProvider = ttsProvider
        self.keyStore = keyStore
        self.onSaved = onSaved
        let fields = CloudCredentialFields.requiredFields(for: provider).sorted { $0.label < $1.label }
        requiredFields = fields
        _fieldValues = State(initialValue: Dictionary(uniqueKeysWithValues: fields.map { ($0, "") }))
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    ForEach(requiredFields, id: \.self) { field in
                        if CloudVoicesSection.isSecureField(field) {
                            SecureField(field.label, text: binding(for: field))
                        } else {
                            TextField(field.label, text: binding(for: field))
                                .autocorrectionDisabled()
                                .textInputAutocapitalization(.never)
                        }
                    }
                } footer: {
                    if let errorMessage {
                        Text(errorMessage)
                            .font(LibraVaultTypography.bodySmall)
                            .foregroundStyle(.red)
                    } else if isValidating {
                        Text("Validating…")
                            .font(LibraVaultTypography.bodySmall)
                            .foregroundStyle(LibraVaultColor.onSurfaceVariant)
                    }
                }
            }
            .navigationTitle("\(provider.displayName) API Key")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Validate & Save") { validateAndSave() }
                        .disabled(!CloudVoicesSection.isSaveEnabled(
                            requiredFields: Set(requiredFields),
                            values: fieldValues,
                            isValidating: isValidating
                        ))
                }
            }
        }
    }

    private func binding(for field: CloudCredentialField) -> Binding<String> {
        Binding(
            get: { fieldValues[field] ?? "" },
            set: { fieldValues[field] = $0 }
        )
    }

    private func validateAndSave() {
        isValidating = true
        errorMessage = nil
        Task {
            let result = await CloudVoicesSection.validateAndSave(
                provider: provider,
                credentials: fieldValues,
                using: ttsProvider,
                keyStore: keyStore
            )
            isValidating = false
            switch result {
            case .success:
                onSaved()
            case .failure(let error):
                errorMessage = error.localizedDescription
            }
        }
    }
}

#Preview {
    Form {
        CloudVoicesSection(
            preferences: CloudVoicePreferences(defaults: UserDefaults(suiteName: "CloudVoicesSectionPreview")!),
            keyStore: KeychainCloudApiKeyStore(),
            ttsProvider: RealCloudTtsProvider()
        )
    }
}
