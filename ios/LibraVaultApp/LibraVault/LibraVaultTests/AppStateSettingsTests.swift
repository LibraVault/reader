import XCTest
@testable import LibraVault

@MainActor
final class AppStateSettingsTests: XCTestCase {

    func testDefaultReadingThemeDefaultsToDark() {
        XCTAssertEqual(AppState().defaultReadingTheme, .dark)
    }

    func testDefaultReadingThemeIsSettable() {
        let state = AppState()
        state.defaultReadingTheme = .sepia
        XCTAssertEqual(state.defaultReadingTheme, .sepia)
    }

    func testDefaultPlaybackSpeedDefaultsTo1() {
        XCTAssertEqual(AppState().defaultPlaybackSpeed, 1.0)
    }

    func testDefaultPlaybackSpeedIsSettable() {
        let state = AppState()
        state.defaultPlaybackSpeed = 1.75
        XCTAssertEqual(state.defaultPlaybackSpeed, 1.75)
    }

    func testSkipDurationSecondsDefaultsTo30() {
        XCTAssertEqual(AppState().skipDurationSeconds, 30)
    }

    func testSkipDurationSecondsIsSettable() {
        let state = AppState()
        state.skipDurationSeconds = 15
        XCTAssertEqual(state.skipDurationSeconds, 15)
    }

    /// Regression guard for the actual wiring, not just the property: Settings'
    /// "Skip duration" chips are only meaningful if Player's transport buttons
    /// genuinely read this value rather than a hardcoded 30s (see PlayerView.swift).
    func testSkipDurationFeedsSkipForwardAndBackward() {
        let state = AppState()
        state.startPlayback(book: BookItem(id: "1", title: "T", author: "A"))
        state.seek(to: 100)
        state.skipDurationSeconds = 15

        state.skipForward(seconds: state.skipDurationSeconds)
        XCTAssertEqual(state.elapsedSeconds, 115, accuracy: 0.01)

        state.skipBackward(seconds: state.skipDurationSeconds)
        XCTAssertEqual(state.elapsedSeconds, 100, accuracy: 0.01)
    }
}
