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
    /// there. iOS's on-device engines (`TTSEngineBridge`/`PocketTTSEngine`) don't have
    /// a "selected voice id" concept of their own to collide with (see
    /// `TTSEngineBridge.voice(for:)` — the system voice is auto-detected from text
    /// language, never user-picked), so there's no equivalent stale-carryover risk to
    /// guard against here the way `CloudVoicesSection`'s Android counterpart does.
    func loadSelectedVoiceID() -> String? {
        defaults.string(forKey: Key.selectedVoiceID)
    }

    func save(selectedVoiceID: String?) {
        defaults.set(selectedVoiceID, forKey: Key.selectedVoiceID)
    }
}
