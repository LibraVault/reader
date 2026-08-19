import Foundation

/// Loads/saves the Settings-configured reading/playback defaults to UserDefaults.
/// Mirrors Android's UserPreferencesRepository (SharedPreferences-backed) — without
/// this, AppState's defaultReadingTheme/defaultPlaybackSpeed/skipDurationSeconds were
/// session-only and reset to their compiled-in defaults on every relaunch. Kept
/// separate from AppState (a plain struct, not an ObservableObject) so it's trivially
/// testable against an isolated UserDefaults suite instead of the real `.standard`
/// defaults, matching FolderPersistence's pattern.
struct UserPreferencesPersistence {
    private let defaults: UserDefaults

    private enum Key {
        static let readingTheme = "xyz.libravault.defaultReadingTheme"
        static let playbackSpeed = "xyz.libravault.defaultPlaybackSpeed"
        static let skipDurationSeconds = "xyz.libravault.skipDurationSeconds"
        static let ttsEngineType = "xyz.libravault.ttsEngineType"
        static let miniPlayerAutoHideEnabled = "xyz.libravault.miniPlayerAutoHideEnabled"
    }

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    func loadReadingTheme() -> ReadingTheme {
        guard let raw = defaults.string(forKey: Key.readingTheme), let theme = ReadingTheme(rawValue: raw) else {
            return .dark
        }
        return theme
    }

    func save(readingTheme: ReadingTheme) {
        defaults.set(readingTheme.rawValue, forKey: Key.readingTheme)
    }

    /// UserDefaults.double(forKey:) returns 0 for an absent key, which is why a plain
    /// "0 means unset" check can't distinguish "never saved" from "actually saved as 0"
    /// — the latter never legitimately happens for these two values (1.0/30 defaults,
    /// no UI path sets them to 0), so it's safe here.
    func loadPlaybackSpeed() -> Double {
        let value = defaults.double(forKey: Key.playbackSpeed)
        return value == 0 ? 1.0 : value
    }

    func save(playbackSpeed: Double) {
        defaults.set(playbackSpeed, forKey: Key.playbackSpeed)
    }

    func loadSkipDurationSeconds() -> Double {
        let value = defaults.double(forKey: Key.skipDurationSeconds)
        return value == 0 ? 30 : value
    }

    func save(skipDurationSeconds: Double) {
        defaults.set(skipDurationSeconds, forKey: Key.skipDurationSeconds)
    }

    func loadTTSEngineType() -> TTSEngineType {
        guard let raw = defaults.string(forKey: Key.ttsEngineType), let type = TTSEngineType(rawValue: raw) else {
            return .system
        }
        return type
    }

    func save(ttsEngineType: TTSEngineType) {
        defaults.set(ttsEngineType.rawValue, forKey: Key.ttsEngineType)
    }

    /// Whether the mini-player collapses to a small hint strip after a few seconds
    /// idle (see MiniPlayerBar). `object(forKey:)` (not `bool(forKey:)`, which
    /// returns `false` for an absent key) so a never-configured install defaults to
    /// enabled rather than reading as an explicit opt-out.
    func loadMiniPlayerAutoHideEnabled() -> Bool {
        guard let value = defaults.object(forKey: Key.miniPlayerAutoHideEnabled) as? Bool else { return true }
        return value
    }

    func save(miniPlayerAutoHideEnabled: Bool) {
        defaults.set(miniPlayerAutoHideEnabled, forKey: Key.miniPlayerAutoHideEnabled)
    }
}
