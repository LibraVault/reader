import XCTest
@testable import LibraVault

@MainActor
final class AppStatePlaybackTests: XCTestCase {

    // Isolated from the real UserDefaults.standard — AppState persists
    // defaultPlaybackSpeed/defaultReadingTheme/skipDurationSeconds now, so without
    // this, one test's `state.defaultPlaybackSpeed = 2.5` leaks into every other
    // test in this file that constructs a plain AppState() expecting the compiled
    // default of 1.0 (same reasoning as AppStateSettingsTests/AppStateVaultTests).
    private func makeIsolatedPersistence() -> UserPreferencesPersistence {
        UserPreferencesPersistence(defaults: UserDefaults(suiteName: "AppStatePlaybackTests.\(UUID().uuidString)")!)
    }

    // MARK: - estimateDuration

    func testEstimateDurationScalesInverselyWithSpeed() {
        let text = "one two three four five six seven eight nine ten"
        let normal = AppState.estimateDuration(for: text, speed: 1.0)
        let doubleSpeed = AppState.estimateDuration(for: text, speed: 2.0)
        XCTAssertEqual(doubleSpeed, normal / 2, accuracy: 0.01)
    }

    func testEstimateDurationNeverGoesBelowOneSecond() {
        XCTAssertEqual(AppState.estimateDuration(for: "", speed: 1.0), 1.0)
    }

    // MARK: - startPlayback / togglePlayback

    func testStartPlaybackSetsNowPlayingState() {
        let state = AppState(userPreferencesPersistence: makeIsolatedPersistence())
        state.startPlayback(book: BookItem(id: "1", title: "T", author: "A"), chapter: 2)

        XCTAssertEqual(state.nowPlayingBook?.id, "1")
        XCTAssertEqual(state.nowPlayingChapter, 2)
        XCTAssertTrue(state.isPlaying)
        XCTAssertEqual(state.elapsedSeconds, 0)
        XCTAssertGreaterThan(state.totalEstimatedSeconds, 0)
    }

    func testChangingSpeedMidPlaybackRecomputesDurationAndPreservesProgress() {
        let state = AppState(userPreferencesPersistence: makeIsolatedPersistence())
        state.startPlayback(book: BookItem(id: "1", title: "T", author: "A"))
        let originalTotal = state.totalEstimatedSeconds
        state.seek(to: originalTotal / 2)

        state.playbackSpeed = 2.0

        // Doubling speed halves the estimate for the same remaining text.
        XCTAssertEqual(state.totalEstimatedSeconds, originalTotal / 2, accuracy: 0.01)
        // Still halfway through the chapter — just against the new, shorter total.
        XCTAssertEqual(state.elapsedSeconds, state.totalEstimatedSeconds / 2, accuracy: 0.01)
    }

    // MARK: - defaultPlaybackSpeed vs. live playbackSpeed
    //
    // These two are deliberately separate properties (see AppState.swift's comment on
    // defaultPlaybackSpeed) — a regression here would mean adjusting the "Default
    // speed" preference in Settings silently changes the pace of whatever's already
    // playing in the background, which is exactly the bug this was written to catch.

    func testStartingANewBookSeedsSpeedFromTheDefault() {
        let state = AppState(userPreferencesPersistence: makeIsolatedPersistence())
        state.defaultPlaybackSpeed = 1.5

        state.startPlayback(book: BookItem(id: "1", title: "T", author: "A"))

        XCTAssertEqual(state.playbackSpeed, 1.5)
    }

    func testAdvancingChapterOfTheSameBookDoesNotResetSpeedToDefault() {
        let state = AppState(userPreferencesPersistence: makeIsolatedPersistence())
        state.defaultPlaybackSpeed = 1.0
        let book = BookItem(id: "1", title: "T", author: "A")
        state.startPlayback(book: book)
        state.playbackSpeed = 1.75 // listener bumps it up mid-session

        state.skipToChapter(2)

        XCTAssertEqual(state.playbackSpeed, 1.75, "advancing a chapter of the same book shouldn't reset the listener's in-session speed choice")
    }

    func testStartingADifferentBookReSeedsFromTheDefault() {
        let state = AppState(userPreferencesPersistence: makeIsolatedPersistence())
        state.defaultPlaybackSpeed = 1.0
        state.startPlayback(book: BookItem(id: "1", title: "T", author: "A"))
        state.playbackSpeed = 2.0 // bumped up for book 1

        state.startPlayback(book: BookItem(id: "2", title: "T2", author: "A2"))

        XCTAssertEqual(state.playbackSpeed, 1.0, "a genuinely new book should start at the default, not whatever the previous book was left at")
    }

    func testChangingDefaultPlaybackSpeedDoesNotAffectAnAlreadyPlayingBook() {
        let state = AppState(userPreferencesPersistence: makeIsolatedPersistence())
        state.startPlayback(book: BookItem(id: "1", title: "T", author: "A"))
        state.playbackSpeed = 1.75

        state.defaultPlaybackSpeed = 2.5 // adjusting the Settings preference

        XCTAssertEqual(state.playbackSpeed, 1.75, "changing the preference for future sessions shouldn't touch the live one")
    }

    func testChangingSpeedWithoutActivePlaybackDoesNothing() {
        let state = AppState(userPreferencesPersistence: makeIsolatedPersistence())
        state.playbackSpeed = 2.0
        XCTAssertNil(state.nowPlayingBook)
        XCTAssertEqual(state.totalEstimatedSeconds, 0)
    }

    func testTogglePlaybackFlipsIsPlaying() {
        let state = AppState(userPreferencesPersistence: makeIsolatedPersistence())
        state.startPlayback(book: BookItem(id: "1", title: "T", author: "A"))
        XCTAssertTrue(state.isPlaying)

        state.togglePlayback()
        XCTAssertFalse(state.isPlaying)

        state.togglePlayback()
        XCTAssertTrue(state.isPlaying)
    }

    func testTogglePlaybackDoesNothingWithoutNowPlayingBook() {
        let state = AppState(userPreferencesPersistence: makeIsolatedPersistence())
        state.togglePlayback()
        XCTAssertFalse(state.isPlaying)
    }

    // MARK: - skipToChapter

    func testSkipToChapterClampsToValidRange() {
        let state = AppState(userPreferencesPersistence: makeIsolatedPersistence())
        state.startPlayback(book: BookItem(id: "1", title: "T", author: "A"))

        state.skipToChapter(999)
        XCTAssertEqual(state.nowPlayingChapter, MockChapterContent.count)

        state.skipToChapter(-5)
        XCTAssertEqual(state.nowPlayingChapter, 1)
    }

    func testSkipToChapterResetsElapsedSeconds() {
        let state = AppState(userPreferencesPersistence: makeIsolatedPersistence())
        state.startPlayback(book: BookItem(id: "1", title: "T", author: "A"))
        state.seek(to: 50)

        state.skipToChapter(3)

        XCTAssertEqual(state.elapsedSeconds, 0)
        XCTAssertEqual(state.nowPlayingChapter, 3)
    }

    // MARK: - seek / skipForward / skipBackward

    func testSeekClampsToValidRange() {
        let state = AppState(userPreferencesPersistence: makeIsolatedPersistence())
        state.startPlayback(book: BookItem(id: "1", title: "T", author: "A"))
        let total = state.totalEstimatedSeconds

        state.seek(to: -10)
        XCTAssertEqual(state.elapsedSeconds, 0)

        state.seek(to: total + 1000)
        XCTAssertEqual(state.elapsedSeconds, total)
    }

    // MockChapterContent's chapter 1 is ~34 words — at the default 1.0x speed
    // that's only ~13.6s of estimated duration (see AppState.estimateDuration), so
    // seek/skip targets here have to stay well under that ceiling instead of the
    // arbitrary 100s/130s this test used to assert against (which seek() would
    // always clamp down to totalEstimatedSeconds, well short of 130).
    func testSkipForwardAndBackwardMoveElapsedSeconds() {
        let state = AppState(userPreferencesPersistence: makeIsolatedPersistence())
        state.startPlayback(book: BookItem(id: "1", title: "T", author: "A"))
        state.seek(to: 5)

        state.skipForward(seconds: 3)
        XCTAssertEqual(state.elapsedSeconds, 8, accuracy: 0.01)

        state.skipBackward(seconds: 2)
        XCTAssertEqual(state.elapsedSeconds, 6, accuracy: 0.01)
    }

    // MARK: - stopPlayback

    func testStopPlaybackClearsState() {
        let state = AppState(userPreferencesPersistence: makeIsolatedPersistence())
        state.startPlayback(book: BookItem(id: "1", title: "T", author: "A"))

        state.stopPlayback()

        XCTAssertNil(state.nowPlayingBook)
        XCTAssertFalse(state.isPlaying)
        XCTAssertEqual(state.elapsedSeconds, 0)
        XCTAssertEqual(state.totalEstimatedSeconds, 0)
    }

    // MARK: - Sleep timer

    func testScheduleSleepTimerSetsRemainingSeconds() {
        let state = AppState(userPreferencesPersistence: makeIsolatedPersistence())
        state.scheduleSleepTimer(minutes: 5)
        XCTAssertEqual(state.sleepTimerRemainingSeconds, 300)
    }

    func testCancelSleepTimerClearsRemainingSeconds() {
        let state = AppState(userPreferencesPersistence: makeIsolatedPersistence())
        state.scheduleSleepTimer(minutes: 5)
        state.cancelSleepTimer()
        XCTAssertNil(state.sleepTimerRemainingSeconds)
    }
}
