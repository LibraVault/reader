import Foundation

/// Reads/writes the user's "Screen Security" setting — mirrors Android's
/// `VaultScreenSecurityPreference` (`feature:vault`, `SharedPreferences`-backed)
/// for the general, toggle-gated Screen Security spike (#204).
///
/// Deliberately its own tiny type rather than folded into
/// `UserPreferencesPersistence` — that type backs `AppState`'s reading/
/// playback defaults, read once at launch; this is read directly by vault
/// content screens (`EncryptedVaultContentsView`) each time one appears,
/// with no `AppState` dependency, matching Android's own separation between
/// `feature:vault`'s preference object and `feature:settings`'s
/// `UserPreferencesRepository`.
///
/// Default is on — matches Android's `UserPreferences.screenSecurityEnabled`
/// default, and this repo's existing convention
/// (`UserPreferencesPersistence.loadMiniPlayerAutoHideEnabled`) of reading
/// via `object(forKey:)` rather than `bool(forKey:)` (which returns `false`,
/// not the intended default, for a never-configured key) so a fresh install
/// gets the safer default, not the weaker one.
enum VaultScreenSecurityPreference {
    private static let key = "xyz.libravault.vaultScreenSecurityEnabled"

    static func isEnabled(defaults: UserDefaults = .standard) -> Bool {
        guard let value = defaults.object(forKey: key) as? Bool else { return true }
        return value
    }

    static func setEnabled(_ enabled: Bool, defaults: UserDefaults = .standard) {
        defaults.set(enabled, forKey: key)
    }
}
