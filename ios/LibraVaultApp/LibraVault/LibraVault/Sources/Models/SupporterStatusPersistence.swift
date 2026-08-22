import Foundation

/// Local cache of the "Supporter" entitlement StoreKit's `Transaction.currentEntitlements`
/// is the real source of truth for (see `StoreKitBillingManager`). This exists purely so
/// `AppState.isSupporter`/SettingsView's "★ You're a Supporter" line doesn't flicker to
/// hidden for the instant between app launch and the manager's first async entitlement
/// check finishing — and, just as importantly, so a one-time tip purchase is remembered
/// across launches at all: consumable purchases (unlike subscriptions) never appear in
/// `Transaction.currentEntitlements`, so without this cache a tip-only supporter would
/// lose their badge on every relaunch.
///
/// Mirrors `UserPreferencesPersistence`'s exact shape: a plain (non-ObservableObject)
/// struct, injectable `UserDefaults` instance for test isolation, string keys namespaced
/// `"xyz.libravault.*"`, a `load()`/`save(_:)` pair.
struct SupporterStatusPersistence {
    private let defaults: UserDefaults

    private enum Key {
        static let isSupporter = "xyz.libravault.isSupporter"
    }

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    /// Absent key correctly reads as `false` via `bool(forKey:)` — unlike
    /// `UserPreferencesPersistence.loadMiniPlayerAutoHideEnabled`, a never-purchased
    /// install should default to "not a supporter", not the reverse.
    func loadIsSupporter() -> Bool {
        defaults.bool(forKey: Key.isSupporter)
    }

    func save(isSupporter: Bool) {
        defaults.set(isSupporter, forKey: Key.isSupporter)
    }
}
