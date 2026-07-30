import XCTest
@testable import LibraVault

@MainActor
final class AppStateSettingsTests: XCTestCase {

    // Isolated from the real UserDefaults.standard so these tests can assert on
    // "nothing saved yet" defaults without depending on (or polluting) whatever a
    // previous test run left behind — same reasoning as AppStateVaultTests.
    private func makeIsolatedPersistence() -> UserPreferencesPersistence {
        UserPreferencesPersistence(defaults: UserDefaults(suiteName: "AppStateSettingsTests.\(UUID().uuidString)")!)
    }

    func testDefaultReadingThemeDefaultsToDark() {
        XCTAssertEqual(AppState(userPreferencesPersistence: makeIsolatedPersistence()).defaultReadingTheme, .dark)
    }

    func testDefaultReadingThemeIsSettable() {
        let state = AppState(userPreferencesPersistence: makeIsolatedPersistence())
        state.defaultReadingTheme = .sepia
        XCTAssertEqual(state.defaultReadingTheme, .sepia)
    }

    func testDefaultPlaybackSpeedDefaultsTo1() {
        XCTAssertEqual(AppState(userPreferencesPersistence: makeIsolatedPersistence()).defaultPlaybackSpeed, 1.0)
    }

    func testDefaultPlaybackSpeedIsSettable() {
        let state = AppState(userPreferencesPersistence: makeIsolatedPersistence())
        state.defaultPlaybackSpeed = 1.75
        XCTAssertEqual(state.defaultPlaybackSpeed, 1.75)
    }

    func testSkipDurationSecondsDefaultsTo30() {
        XCTAssertEqual(AppState(userPreferencesPersistence: makeIsolatedPersistence()).skipDurationSeconds, 30)
    }

    func testSkipDurationSecondsIsSettable() {
        let state = AppState(userPreferencesPersistence: makeIsolatedPersistence())
        state.skipDurationSeconds = 15
        XCTAssertEqual(state.skipDurationSeconds, 15)
    }

    /// Regression guard for the actual wiring, not just the property: Settings'
    /// "Skip duration" chips are only meaningful if Player's transport buttons
    /// genuinely read this value rather than a hardcoded 30s (see PlayerView.swift).
    func testSkipDurationFeedsSkipForwardAndBackward() {
        let state = AppState(userPreferencesPersistence: makeIsolatedPersistence())
        state.startPlayback(book: BookItem(id: "1", title: "T", author: "A"))
        state.seek(to: 100)
        state.skipDurationSeconds = 15

        state.skipForward(seconds: state.skipDurationSeconds)
        XCTAssertEqual(state.elapsedSeconds, 115, accuracy: 0.01)

        state.skipBackward(seconds: state.skipDurationSeconds)
        XCTAssertEqual(state.elapsedSeconds, 100, accuracy: 0.01)
    }

    /// Regression guard for the actual bug being fixed: these three settings used to
    /// be pure in-memory @Published state, reset to their compiled-in defaults on
    /// every relaunch despite Settings presenting them as saved preferences.
    func testSettingsPersistAcrossAppStateInstances() {
        let persistence = makeIsolatedPersistence()

        let state = AppState(userPreferencesPersistence: persistence)
        state.defaultReadingTheme = .light
        state.defaultPlaybackSpeed = 2.0
        state.skipDurationSeconds = 45

        let reloaded = AppState(userPreferencesPersistence: persistence)
        XCTAssertEqual(reloaded.defaultReadingTheme, .light)
        XCTAssertEqual(reloaded.defaultPlaybackSpeed, 2.0)
        XCTAssertEqual(reloaded.skipDurationSeconds, 45)
    }
}
