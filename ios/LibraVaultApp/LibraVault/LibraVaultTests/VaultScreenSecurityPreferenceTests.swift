import XCTest
@testable import LibraVault

final class VaultScreenSecurityPreferenceTests: XCTestCase {

    private func makeIsolatedDefaults() -> UserDefaults {
        let suiteName = "VaultScreenSecurityPreferenceTests.\(UUID().uuidString)"
        return UserDefaults(suiteName: suiteName)!
    }

    /// A never-configured install must default to *on* — the safer default,
    /// matching Android's `UserPreferences.screenSecurityEnabled`. Uses
    /// `object(forKey:)` rather than `bool(forKey:)` internally specifically
    /// so this doesn't regress to reading `false` for an absent key.
    func testIsEnabledDefaultsToTrueWhenNothingSaved() {
        XCTAssertTrue(VaultScreenSecurityPreference.isEnabled(defaults: makeIsolatedDefaults()))
    }

    func testSetEnabledFalseThenIsEnabledRoundTrips() {
        let defaults = makeIsolatedDefaults()
        VaultScreenSecurityPreference.setEnabled(false, defaults: defaults)
        XCTAssertFalse(VaultScreenSecurityPreference.isEnabled(defaults: defaults))
    }

    func testSetEnabledTrueThenIsEnabledRoundTrips() {
        let defaults = makeIsolatedDefaults()
        VaultScreenSecurityPreference.setEnabled(false, defaults: defaults)
        VaultScreenSecurityPreference.setEnabled(true, defaults: defaults)
        XCTAssertTrue(VaultScreenSecurityPreference.isEnabled(defaults: defaults))
    }
}
