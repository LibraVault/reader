import XCTest
@testable import LibraVault

final class CloudVoicePreferencesTests: XCTestCase {

    private func makeIsolatedDefaults() -> UserDefaults {
        let suiteName = "CloudVoicePreferencesTests.\(UUID().uuidString)"
        return UserDefaults(suiteName: suiteName)!
    }

    func testLoadConsentEnabledDefaultsToFalseWhenNothingSaved() {
        let prefs = CloudVoicePreferences(defaults: makeIsolatedDefaults())
        XCTAssertFalse(prefs.loadConsentEnabled())
    }

    func testSaveThenLoadRoundTripsConsentEnabledTrue() {
        let defaults = makeIsolatedDefaults()
        CloudVoicePreferences(defaults: defaults).save(consentEnabled: true)
        XCTAssertTrue(CloudVoicePreferences(defaults: defaults).loadConsentEnabled())
    }

    func testSaveThenLoadRoundTripsConsentEnabledFalse() {
        let defaults = makeIsolatedDefaults()
        CloudVoicePreferences(defaults: defaults).save(consentEnabled: true)
        CloudVoicePreferences(defaults: defaults).save(consentEnabled: false)
        XCTAssertFalse(CloudVoicePreferences(defaults: defaults).loadConsentEnabled())
    }

    func testLoadSelectedProviderDefaultsToNilWhenNothingSaved() {
        let prefs = CloudVoicePreferences(defaults: makeIsolatedDefaults())
        XCTAssertNil(prefs.loadSelectedProvider())
    }

    func testSaveThenLoadRoundTripsSelectedProvider() {
        let defaults = makeIsolatedDefaults()
        CloudVoicePreferences(defaults: defaults).save(selectedProvider: .amazonPolly)
        XCTAssertEqual(CloudVoicePreferences(defaults: defaults).loadSelectedProvider(), .amazonPolly)
    }

    func testSaveNilSelectedProviderClearsIt() {
        let defaults = makeIsolatedDefaults()
        let prefs = CloudVoicePreferences(defaults: defaults)
        prefs.save(selectedProvider: .openAI)
        prefs.save(selectedProvider: nil)
        XCTAssertNil(prefs.loadSelectedProvider())
    }

    func testLoadSelectedVoiceIDDefaultsToNilWhenNothingSaved() {
        let prefs = CloudVoicePreferences(defaults: makeIsolatedDefaults())
        XCTAssertNil(prefs.loadSelectedVoiceID())
    }

    func testSaveThenLoadRoundTripsSelectedVoiceID() {
        let defaults = makeIsolatedDefaults()
        CloudVoicePreferences(defaults: defaults).save(selectedVoiceID: "alloy")
        XCTAssertEqual(CloudVoicePreferences(defaults: defaults).loadSelectedVoiceID(), "alloy")
    }
}
