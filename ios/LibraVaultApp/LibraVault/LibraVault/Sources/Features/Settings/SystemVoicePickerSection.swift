import SwiftUI

/// System Voice picker row for the System TTS engine — issue #506, split from #500.
/// Enumerates every real installed system voice via `SystemVoiceCatalog` (no
/// demo/fallback list) and persists the selection through `UserPreferencesPersistence`,
/// read back by `TTSEngineBridge.speak` at speak-time (mirrors `CloudTtsEngine`'s own
/// "read preferences fresh on every call" shape — see `TTSEngineBridge.preferences`'s
/// doc comment). Rendered as a `NavigationLink` row rather than inlined the way
/// Android's `VoicePickerSection` (`TtsSettingsSection.kt`) is: a real device's
/// installed voice catalog can run into dozens of entries across locales/quality
/// tiers, which doesn't fit a Settings `Form` row the way Pocket TTS's handful of
/// bundled voices do there.
///
/// Owns its own persistence via an injectable default, matching `CloudVoicesSection`'s
/// "local `@State` backed directly by its own persistence type, not routed through
/// `AppState`" shape — there's no reason for this selection to live on `AppState`
/// (nothing else needs to observe it; `TTSEngineBridge` reads it directly).
struct SystemVoicePickerRow: View {
    private let preferences: UserPreferencesPersistence
    private let voices: [SystemVoiceInfo]
    @State private var selectedVoiceIdentifier: String?

    init(
        preferences: UserPreferencesPersistence = UserPreferencesPersistence(),
        voices: [SystemVoiceInfo] = SystemVoiceCatalog.availableVoices()
    ) {
        self.preferences = preferences
        self.voices = voices
        _selectedVoiceIdentifier = State(initialValue: preferences.loadSelectedSystemVoiceIdentifier())
    }

    /// `nil`, or an identifier with no matching installed voice (e.g. the picked
    /// voice's language pack was since removed) both read as "Automatic" here —
    /// same fallback `TTSEngineBridge.voice(for:)` itself applies at speak-time, so
    /// this row never claims a voice is selected that speech won't actually use.
    static func selectedVoiceLabel(selectedVoiceIdentifier: String?, voices: [SystemVoiceInfo]) -> String {
        guard let selectedVoiceIdentifier, let voice = voices.first(where: { $0.identifier == selectedVoiceIdentifier }) else {
            return "Automatic"
        }
        return voice.name
    }

    var body: some View {
        NavigationLink {
            SystemVoicePickerList(
                voices: voices,
                selectedVoiceIdentifier: selectedVoiceIdentifier,
                onSelect: { identifier in
                    selectedVoiceIdentifier = identifier
                    preferences.save(selectedSystemVoiceIdentifier: identifier)
                }
            )
        } label: {
            HStack {
                Text("Voice")
                    .foregroundStyle(LibraVaultColor.onSurface)
                Spacer()
                Text(Self.selectedVoiceLabel(selectedVoiceIdentifier: selectedVoiceIdentifier, voices: voices))
                    .foregroundStyle(LibraVaultColor.onSurfaceVariant)
            }
        }
    }
}

/// `internal` rather than `private` — this repo's convention for pure helpers that
/// need direct unit-test access (see `filteredVoices` below) without standing up
/// SwiftUI, matching `LibraryScannerImpl.needsEnrichment`'s precedent on the Android
/// side (AGENTS.md's "Test conventions" section).
struct SystemVoicePickerList: View {
    let voices: [SystemVoiceInfo]
    let selectedVoiceIdentifier: String?
    let onSelect: (String?) -> Void

    @State private var searchText = ""
    @State private var localSelection: String?

    init(voices: [SystemVoiceInfo], selectedVoiceIdentifier: String?, onSelect: @escaping (String?) -> Void) {
        self.voices = voices
        self.selectedVoiceIdentifier = selectedVoiceIdentifier
        self.onSelect = onSelect
        _localSelection = State(initialValue: selectedVoiceIdentifier)
    }

    static func filteredVoices(_ voices: [SystemVoiceInfo], matching searchText: String) -> [SystemVoiceInfo] {
        guard !searchText.isEmpty else { return voices }
        return voices.filter {
            $0.name.localizedCaseInsensitiveContains(searchText) || $0.language.localizedCaseInsensitiveContains(searchText)
        }
    }

    var body: some View {
        List {
            Section {
                Button {
                    localSelection = nil
                    onSelect(nil)
                } label: {
                    HStack {
                        Text("Automatic")
                            .foregroundStyle(LibraVaultColor.onSurface)
                        Spacer()
                        if localSelection == nil {
                            Image(systemName: "checkmark")
                                .foregroundStyle(LibraVaultColor.primary)
                        }
                    }
                }
                .buttonStyle(.plain)
            } footer: {
                Text("Matches the language of the book being read instead of a fixed voice.")
                    .font(LibraVaultTypography.bodySmall)
                    .foregroundStyle(LibraVaultColor.onSurfaceVariant)
            }

            if voices.isEmpty {
                Text("No system voices found.")
                    .font(LibraVaultTypography.bodySmall)
                    .foregroundStyle(LibraVaultColor.onSurfaceVariant)
            } else {
                ForEach(Self.filteredVoices(voices, matching: searchText)) { voice in
                    Button {
                        localSelection = voice.identifier
                        onSelect(voice.identifier)
                    } label: {
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(voice.name)
                                    .foregroundStyle(LibraVaultColor.onSurface)
                                Text(voice.quality == "Standard" ? voice.language : "\(voice.language) — \(voice.quality)")
                                    .font(LibraVaultTypography.bodySmall)
                                    .foregroundStyle(LibraVaultColor.onSurfaceVariant)
                            }
                            Spacer()
                            if voice.identifier == localSelection {
                                Image(systemName: "checkmark")
                                    .foregroundStyle(LibraVaultColor.primary)
                            }
                        }
                    }
                    .buttonStyle(.plain)
                }
            }
        }
        .searchable(text: $searchText, prompt: "Search voices")
        .navigationTitle("Voice")
        .navigationBarTitleDisplayMode(.inline)
    }
}
