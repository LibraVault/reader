import XCTest
@testable import LibraVault

@MainActor
final class AppStatePlaybackTests: XCTestCase {

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
        let state = AppState()
        state.startPlayback(book: BookItem(id: "1", title: "T", author: "A"), chapter: 2)

        XCTAssertEqual(state.nowPlayingBook?.id, "1")
        XCTAssertEqual(state.nowPlayingChapter, 2)
        XCTAssertTrue(state.isPlaying)
        XCTAssertEqual(state.elapsedSeconds, 0)
        XCTAssertGreaterThan(state.totalEstimatedSeconds, 0)
    }

    func testTogglePlaybackFlipsIsPlaying() {
        let state = AppState()
        state.startPlayback(book: BookItem(id: "1", title: "T", author: "A"))
        XCTAssertTrue(state.isPlaying)

        state.togglePlayback()
        XCTAssertFalse(state.isPlaying)

        state.togglePlayback()
        XCTAssertTrue(state.isPlaying)
    }

    func testTogglePlaybackDoesNothingWithoutNowPlayingBook() {
        let state = AppState()
        state.togglePlayback()
        XCTAssertFalse(state.isPlaying)
    }

    // MARK: - skipToChapter

    func testSkipToChapterClampsToValidRange() {
        let state = AppState()
        state.startPlayback(book: BookItem(id: "1", title: "T", author: "A"))

        state.skipToChapter(999)
        XCTAssertEqual(state.nowPlayingChapter, MockChapterContent.count)

        state.skipToChapter(-5)
        XCTAssertEqual(state.nowPlayingChapter, 1)
    }

    func testSkipToChapterResetsElapsedSeconds() {
        let state = AppState()
        state.startPlayback(book: BookItem(id: "1", title: "T", author: "A"))
        state.seek(to: 50)

        state.skipToChapter(3)

        XCTAssertEqual(state.elapsedSeconds, 0)
        XCTAssertEqual(state.nowPlayingChapter, 3)
    }

    // MARK: - seek / skipForward / skipBackward

    func testSeekClampsToValidRange() {
        let state = AppState()
        state.startPlayback(book: BookItem(id: "1", title: "T", author: "A"))
        let total = state.totalEstimatedSeconds

        state.seek(to: -10)
        XCTAssertEqual(state.elapsedSeconds, 0)

        state.seek(to: total + 1000)
        XCTAssertEqual(state.elapsedSeconds, total)
    }

    func testSkipForwardAndBackwardMoveElapsedSeconds() {
        let state = AppState()
        state.startPlayback(book: BookItem(id: "1", title: "T", author: "A"))
        state.seek(to: 100)

        state.skipForward(seconds: 30)
        XCTAssertEqual(state.elapsedSeconds, 130, accuracy: 0.01)

        state.skipBackward(seconds: 50)
        XCTAssertEqual(state.elapsedSeconds, 80, accuracy: 0.01)
    }

    // MARK: - stopPlayback

    func testStopPlaybackClearsState() {
        let state = AppState()
        state.startPlayback(book: BookItem(id: "1", title: "T", author: "A"))

        state.stopPlayback()

        XCTAssertNil(state.nowPlayingBook)
        XCTAssertFalse(state.isPlaying)
        XCTAssertEqual(state.elapsedSeconds, 0)
        XCTAssertEqual(state.totalEstimatedSeconds, 0)
    }

    // MARK: - Sleep timer

    func testScheduleSleepTimerSetsRemainingSeconds() {
        let state = AppState()
        state.scheduleSleepTimer(minutes: 5)
        XCTAssertEqual(state.sleepTimerRemainingSeconds, 300)
    }

    func testCancelSleepTimerClearsRemainingSeconds() {
        let state = AppState()
        state.scheduleSleepTimer(minutes: 5)
        state.cancelSleepTimer()
        XCTAssertNil(state.sleepTimerRemainingSeconds)
    }
}
