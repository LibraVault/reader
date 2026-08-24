import XCTest
@testable import LibraVault

final class UserPreferencesPersistenceTests: XCTestCase {

    private func makeIsolatedDefaults() -> UserDefaults {
        let suiteName = "UserPreferencesPersistenceTests.\(UUID().uuidString)"
        return UserDefaults(suiteName: suiteName)!
    }

    // MARK: - Reading theme

    func testLoadReadingThemeDefaultsToDarkWhenNothingSaved() {
        let persistence = UserPreferencesPersistence(defaults: makeIsolatedDefaults())
        XCTAssertEqual(persistence.loadReadingTheme(), .dark)
    }

    func testSaveThenLoadRoundTripsReadingTheme() {
        let defaults = makeIsolatedDefaults()
        UserPreferencesPersistence(defaults: defaults).save(readingTheme: .sepia)
        XCTAssertEqual(UserPreferencesPersistence(defaults: defaults).loadReadingTheme(), .sepia)
    }

    func testSaveThenLoadRoundTripsSystemReadingTheme() {
        let defaults = makeIsolatedDefaults()
        UserPreferencesPersistence(defaults: defaults).save(readingTheme: .system)
        XCTAssertEqual(UserPreferencesPersistence(defaults: defaults).loadReadingTheme(), .system)
    }

    // MARK: - Playback speed

    func testLoadPlaybackSpeedDefaultsTo1WhenNothingSaved() {
        let persistence = UserPreferencesPersistence(defaults: makeIsolatedDefaults())
        XCTAssertEqual(persistence.loadPlaybackSpeed(), 1.0)
    }

    func testSaveThenLoadRoundTripsPlaybackSpeed() {
        let defaults = makeIsolatedDefaults()
        UserPreferencesPersistence(defaults: defaults).save(playbackSpeed: 1.75)
        XCTAssertEqual(UserPreferencesPersistence(defaults: defaults).loadPlaybackSpeed(), 1.75)
    }

    // MARK: - Skip duration

    func testLoadSkipDurationSecondsDefaultsTo30WhenNothingSaved() {
        let persistence = UserPreferencesPersistence(defaults: makeIsolatedDefaults())
        XCTAssertEqual(persistence.loadSkipDurationSeconds(), 30)
    }

    func testSaveThenLoadRoundTripsSkipDurationSeconds() {
        let defaults = makeIsolatedDefaults()
        UserPreferencesPersistence(defaults: defaults).save(skipDurationSeconds: 15)
        XCTAssertEqual(UserPreferencesPersistence(defaults: defaults).loadSkipDurationSeconds(), 15)
    }

    // MARK: - TTS engine type

    func testLoadTTSEngineTypeDefaultsToSystemWhenNothingSaved() {
        let persistence = UserPreferencesPersistence(defaults: makeIsolatedDefaults())
        XCTAssertEqual(persistence.loadTTSEngineType(), .system)
    }

    func testSaveThenLoadRoundTripsTTSEngineType() {
        let defaults = makeIsolatedDefaults()
        UserPreferencesPersistence(defaults: defaults).save(ttsEngineType: .pocket)
        XCTAssertEqual(UserPreferencesPersistence(defaults: defaults).loadTTSEngineType(), .pocket)
    }

    func testLoadTTSEngineTypeDefaultsToSystemForAGarbageStoredValue() {
        // Defensive against a future enum case rename leaving a stale raw
        // value in a user's UserDefaults - must not crash, must fall back.
        let defaults = makeIsolatedDefaults()
        defaults.set("not-a-real-engine", forKey: "xyz.libravault.ttsEngineType")
        XCTAssertEqual(UserPreferencesPersistence(defaults: defaults).loadTTSEngineType(), .system)
    }

    // MARK: - Selected system voice identifier

    func testLoadSelectedSystemVoiceIdentifierDefaultsToNilWhenNothingSaved() {
        let persistence = UserPreferencesPersistence(defaults: makeIsolatedDefaults())
        XCTAssertNil(persistence.loadSelectedSystemVoiceIdentifier())
    }

    func testSaveThenLoadRoundTripsSelectedSystemVoiceIdentifier() {
        let defaults = makeIsolatedDefaults()
        UserPreferencesPersistence(defaults: defaults).save(selectedSystemVoiceIdentifier: "com.apple.voice.compact.en-US.Samantha")
        XCTAssertEqual(
            UserPreferencesPersistence(defaults: defaults).loadSelectedSystemVoiceIdentifier(),
            "com.apple.voice.compact.en-US.Samantha"
        )
    }

    func testSaveNilSelectedSystemVoiceIdentifierClearsAPreviouslySavedValue() {
        let defaults = makeIsolatedDefaults()
        let persistence = UserPreferencesPersistence(defaults: defaults)
        persistence.save(selectedSystemVoiceIdentifier: "com.apple.voice.compact.en-US.Samantha")
        persistence.save(selectedSystemVoiceIdentifier: nil)
        XCTAssertNil(persistence.loadSelectedSystemVoiceIdentifier())
    }

    // MARK: - Mini-player auto-hide

    /// Defaults to *enabled* on a never-configured install — the reverse of
    /// `bool(forKey:)`'s own "absent key reads as false" default, which is why
    /// this reads via `object(forKey:)` instead. See the doc comment on
    /// `loadMiniPlayerAutoHideEnabled` for why that distinction matters here.
    func testLoadMiniPlayerAutoHideEnabledDefaultsToTrueWhenNothingSaved() {
        let persistence = UserPreferencesPersistence(defaults: makeIsolatedDefaults())
        XCTAssertTrue(persistence.loadMiniPlayerAutoHideEnabled())
    }

    func testSaveThenLoadRoundTripsMiniPlayerAutoHideEnabledDisabled() {
        let defaults = makeIsolatedDefaults()
        UserPreferencesPersistence(defaults: defaults).save(miniPlayerAutoHideEnabled: false)
        XCTAssertFalse(UserPreferencesPersistence(defaults: defaults).loadMiniPlayerAutoHideEnabled())
    }

    func testSaveThenLoadRoundTripsMiniPlayerAutoHideEnabledEnabled() {
        let defaults = makeIsolatedDefaults()
        UserPreferencesPersistence(defaults: defaults).save(miniPlayerAutoHideEnabled: false)
        UserPreferencesPersistence(defaults: defaults).save(miniPlayerAutoHideEnabled: true)
        XCTAssertTrue(UserPreferencesPersistence(defaults: defaults).loadMiniPlayerAutoHideEnabled())
    }
}
