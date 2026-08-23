import Foundation

/// The four-quadrant gate (PRD §4): a real subscription AND explicit consent are BOTH
/// required before any cloud TTS call is allowed. A pure function taking two plain
/// `Bool`s, not a class wrapping live `StoreKitBillingManager`/`CloudVoicePreferences`
/// instances — `StoreKitBillingManager.isSubscribed` needs a live `SKTestSession`/
/// `.storekit` config to exercise for real, a documented Xcode 26.5 `xcodebuild test`
/// CLI gap (see `StoreKitBillingManagerTests.skipIfStoreKitConfigUnavailableViaCLI`'s doc
/// comment). Extracting the actual AND logic into a function of two `Bool`s means
/// `CloudTtsGateTests` can exercise all four quadrants with neither a live StoreKit
/// session nor a real UserDefaults-backed `CloudVoicePreferences` — mirrors Android's
/// `CloudTtsGate` combining `SupportBillingClient.observeSubscriptionActive()` and
/// `TtsPreferences.consentFlow`, minus the `Flow`/reactive plumbing iOS doesn't need
/// here (both inputs are already synchronously readable: `isSubscribed` is a `@Published`
/// property already resolved by billing-manager init, `consentEnabled` is a plain
/// `UserDefaults` read).
enum CloudTtsGate {
    /// Explicitly `isSubscribed`, NOT `isSupporter` — a one-time tip alone (no active
    /// subscription) must never open this gate, matching the PRD-mandated distinction
    /// `StoreKitBillingManager.isSubscribed`'s own doc comment draws from `isSupporter`.
    static func canUseCloudTts(isSubscribed: Bool, consentEnabled: Bool) -> Bool {
        isSubscribed && consentEnabled
    }
}
