import Foundation

/// Loads/saves the Settings-configured reading/playback defaults to UserDefaults.
/// Mirrors Android's UserPreferencesRepository (SharedPreferences-backed) — without
/// this, AppState's defaultReadingTheme/defaultPlaybackSpeed/skipDurationSeconds were
/// session-only and reset to their compiled-in defaults on every relaunch. Kept
/// separate from AppState (a plain struct, not an ObservableObject) so it's trivially
/// testable against an isolated UserDefaults suite instead of the real `.standard`
/// defaults, matching VaultPersistence's pattern.
struct UserPreferencesPersistence {
    private let defaults: UserDefaults

    private enum Key {
        static let readingTheme = "xyz.libravault.defaultReadingTheme"
        static let playbackSpeed = "xyz.libravault.defaultPlaybackSpeed"
        static let skipDurationSeconds = "xyz.libravault.skipDurationSeconds"
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
}
