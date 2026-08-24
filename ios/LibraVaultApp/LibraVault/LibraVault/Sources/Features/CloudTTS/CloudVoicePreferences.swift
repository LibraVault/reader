import Foundation

/// Consent + selection state for Premium Cloud TTS Voices (PRD §4/§6). Mirrors
/// `SupporterStatusPersistence`'s exact shape (plain, non-`ObservableObject` struct,
/// injectable `UserDefaults` for test isolation, namespaced `"xyz.libravault.*"` keys) —
/// deliberately NOT bolted onto `UserPreferencesPersistence`, whose existing boolean
/// keys (e.g. `loadMiniPlayerAutoHideEnabled`) use `object(forKey:) as? Bool` specifically
/// to default to `true`/enabled when absent. Consent here needs the opposite default
/// (PRD §4: off until explicitly accepted), which `UserDefaults.bool(forKey:)`'s
/// absent-reads-false behavior already gives for free — reusing the wrong-polarity
/// pattern would risk a future refactor accidentally flipping the default.
struct CloudVoicePreferences {
    private let defaults: UserDefaults

    private enum Key {
        static let consentEnabled = "xyz.libravault.cloudtts.consentEnabled"
        static let selectedProvider = "xyz.libravault.cloudtts.selectedProvider"
        static let selectedVoiceID = "xyz.libravault.cloudtts.selectedVoiceID"
    }

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    /// Absent key correctly reads as `false` via `bool(forKey:)` — a never-configured
    /// install must default to consent OFF (PRD §4: "off by default — enabling it sends
    /// text to a vendor's servers"), the same "do not silently opt in" reasoning as
    /// Android's `CLOUD_VOICES_CONSENT_KEY` default.
    func loadConsentEnabled() -> Bool {
        defaults.bool(forKey: Key.consentEnabled)
    }

    func save(consentEnabled: Bool) {
        defaults.set(consentEnabled, forKey: Key.consentEnabled)
    }

    func loadSelectedProvider() -> CloudProviderId? {
        guard let raw = defaults.string(forKey: Key.selectedProvider) else { return nil }
        return CloudProviderId(rawValue: raw)
    }

    func save(selectedProvider: CloudProviderId?) {
        defaults.set(selectedProvider?.rawValue, forKey: Key.selectedProvider)
    }

    /// Scoped to Cloud TTS only — deliberately a SEPARATE key from
    /// `UserPreferencesPersistence`'s TTS engine type, not shared the way Android's
    /// `TtsPreferences.selectedVoiceFlow` is shared across all three engine types
    /// there. As of issue #506, the System engine has its own equivalent key
    /// (`UserPreferencesPersistence.selectedSystemVoiceIdentifier`) — also kept
    /// separate rather than merged into this one, so switching engines can never hand
    /// a cloud vendor's voice ID to `AVSpeechSynthesisVoice(identifier:)` (which would
    /// just fail to resolve, same as any other stale identifier — see
    /// `TTSEngineBridge.voice(for:)` — but there's no reason to invite the collision).
    /// `PocketTTSEngine`'s bundled voice still has no selection concept of its own to
    /// collide with either way.
    func loadSelectedVoiceID() -> String? {
        defaults.string(forKey: Key.selectedVoiceID)
    }

    func save(selectedVoiceID: String?) {
        defaults.set(selectedVoiceID, forKey: Key.selectedVoiceID)
    }
}
