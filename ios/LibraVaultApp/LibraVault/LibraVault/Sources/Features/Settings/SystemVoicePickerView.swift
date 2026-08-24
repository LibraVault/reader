import AVFoundation
import SwiftUI

/// Lets the user pick a specific installed System Voice for Read Aloud,
/// instead of the automatic language-detected pick `TTSEngineBridge` used
/// exclusively before this (#506). Pushed from `SettingsView.ttsSection`'s
/// new "Voice" row, shown only while `.system` is the active engine.
///
/// A dedicated `List`-based screen rather than inline rows in `SettingsView`'s
/// `Form` `Section` (the way `CloudVoicesSection.providerRow` does for its
/// 4-5 providers) - `AVSpeechSynthesisVoice.speechVoices()` returns dozens of
/// voices across many languages on a real device (English alone can have
/// 10-20 with enhanced/premium voices installed), which reads and scrolls
/// far better as its own grouped screen than crammed into the Settings form.
struct SystemVoicePickerView: View {
    @Binding var selectedVoiceIdentifier: String?

    private let groups: [VoiceGroup]

    init(selectedVoiceIdentifier: Binding<String?>, voices: [AVSpeechSynthesisVoice] = AVSpeechSynthesisVoice.speechVoices()) {
        self._selectedVoiceIdentifier = selectedVoiceIdentifier
        self.groups = Self.grouped(voices)
    }

    var body: some View {
        List {
            Section {
                Button {
                    selectedVoiceIdentifier = nil
                } label: {
                    HStack {
                        Text("Automatic")
                            .foregroundStyle(LibraVaultColor.onSurface)
                        Spacer()
                        if selectedVoiceIdentifier == nil {
                            Image(systemName: "checkmark")
                                .foregroundStyle(LibraVaultColor.primary)
                        }
                    }
                }
                .buttonStyle(.plain)
            } footer: {
                Text("Picks a voice based on each book's detected language, the same way Read Aloud always worked before this screen existed.")
                    .font(LibraVaultTypography.bodySmall)
                    .foregroundStyle(LibraVaultColor.onSurfaceVariant)
            }

            ForEach(groups) { group in
                Section(group.languageDisplayName) {
                    ForEach(group.voices, id: \.identifier) { voice in
                        Button {
                            selectedVoiceIdentifier = voice.identifier
                        } label: {
                            HStack {
                                Text(voice.name)
                                    .foregroundStyle(LibraVaultColor.onSurface)
                                Spacer()
                                if selectedVoiceIdentifier == voice.identifier {
                                    Image(systemName: "checkmark")
                                        .foregroundStyle(LibraVaultColor.primary)
                                }
                            }
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
        }
        .navigationTitle("Voice")
        .navigationBarTitleDisplayMode(.inline)
    }

    struct VoiceGroup: Identifiable {
        let languageCode: String
        let languageDisplayName: String
        let voices: [AVSpeechSynthesisVoice]

        var id: String { languageCode }
    }

    /// Groups voices by BCP-47 language code (`voice.language`, e.g. "en-US"),
    /// sorted by each group's localized display name, and each group's own
    /// voices sorted by name. Extracted as a pure `static` function - takes
    /// and returns plain data, no SwiftUI - so it's testable directly against
    /// the real `AVSpeechSynthesisVoice.speechVoices()` catalog (confirmed
    /// safe to call in the CI Simulator, unlike actual playback - see
    /// `TTSEngineBridgeTests`) without standing up this view at all.
    static func grouped(_ voices: [AVSpeechSynthesisVoice]) -> [VoiceGroup] {
        let byLanguage = Dictionary(grouping: voices, by: \.language)
        return byLanguage
            .map { languageCode, voices in
                VoiceGroup(
                    languageCode: languageCode,
                    languageDisplayName: Locale.current.localizedString(forIdentifier: languageCode) ?? languageCode,
                    voices: voices.sorted { $0.name < $1.name }
                )
            }
            .sorted { $0.languageDisplayName < $1.languageDisplayName }
    }
}
