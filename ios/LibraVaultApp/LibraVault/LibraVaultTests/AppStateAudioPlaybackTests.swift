import XCTest
@testable import LibraVault

/// Covers AppState's audio-format playback branch (book.format.isAudio), routed
/// through AudioPlaybackEngine — previously untested. Every existing
/// AppStatePlaybackTests case uses .epub books, which route through the TTS/timer
/// branch instead, so this branch had zero direct coverage. Uses
/// FakeAudioPlaybackEngine throughout (never the real AudioPlaybackEngine) to avoid
/// the real AVFoundation CI-Simulator hang risk.
@MainActor
final class AppStateAudioPlaybackTests: XCTestCase {

    private func makeIsolatedPersistence() -> UserPreferencesPersistence {
        UserPreferencesPersistence(defaults: UserDefaults(suiteName: "AppStateAudioPlaybackTests.\(UUID().uuidString)")!)
    }

    private func makeIsolatedFolderPersistence() -> FolderPersistence {
        FolderPersistence(defaults: UserDefaults(suiteName: "AppStateAudioPlaybackTests.Folders.\(UUID().uuidString)")!)
    }

    /// A real folder containing an arbitrary file at a real path — the fake
    /// engine never actually decodes it, so its contents don't matter, only that
    /// `folderPersistence.makeFolder(from:)` can create a real bookmark for it (which,
    /// per its own doc comment, requires a real on-disk resource) and that
    /// `fileURL`/`folderId` round-trip correctly through startAudioPlayback's lookup.
    private func makeAudioBook(folderPersistence: FolderPersistence, format: MediaFormat = .mp3) throws -> BookItem {
        let audioFolder = FileManager.default.temporaryDirectory
            .appendingPathComponent("AppStateAudioPlaybackTests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: audioFolder, withIntermediateDirectories: true)
        let fileURL = audioFolder.appendingPathComponent("track.mp3")
        try Data("not real audio, the fake engine never decodes this".utf8).write(to: fileURL)

        let folder = try folderPersistence.makeFolder(from: audioFolder)
        folderPersistence.save([folder])

        return BookItem(
            id: "folder:\(folder.id):\(fileURL.path)",
            title: "Audiobook",
            author: "Author",
            format: format,
            fileURL: fileURL,
            folderId: folder.id
        )
    }

    // MARK: - startPlayback

    func testStartPlaybackForAnAudioBookPlaysTheRealFileAtTheCurrentSpeed() throws {
        let folderPersistence = makeIsolatedFolderPersistence()
        let engine = FakeAudioPlaybackEngine()
        engine.duration = 42
        let state = AppState(folderPersistence: folderPersistence, userPreferencesPersistence: makeIsolatedPersistence(), audioEngine: engine)
        let book = try makeAudioBook(folderPersistence: folderPersistence)

        state.startPlayback(book: book)

        XCTAssertEqual(engine.playedFileURL, book.fileURL)
        XCTAssertEqual(engine.playedRate, Float(state.playbackSpeed))
        XCTAssertTrue(state.isPlaying)
        XCTAssertEqual(state.totalEstimatedSeconds, 42)
        XCTAssertEqual(state.elapsedSeconds, 0)
    }

    func testStartPlaybackForAnAudioBookReportsOneChapterNamedAfterTheBook() throws {
        let folderPersistence = makeIsolatedFolderPersistence()
        let state = AppState(folderPersistence: folderPersistence, userPreferencesPersistence: makeIsolatedPersistence(), audioEngine: FakeAudioPlaybackEngine())
        let book = try makeAudioBook(folderPersistence: folderPersistence)

        state.startPlayback(book: book)

        XCTAssertEqual(state.nowPlayingChapterCount, 1)
        XCTAssertEqual(state.nowPlayingChapterTitles, ["Audiobook"])
    }

    func testStartPlaybackGivesUpGracefullyWhenTheBookHasNoFileReference() {
        let state = AppState(userPreferencesPersistence: makeIsolatedPersistence(), audioEngine: FakeAudioPlaybackEngine())
        let bookWithNoFile = BookItem(id: "1", title: "T", author: "A", format: .mp3)

        state.startPlayback(book: bookWithNoFile)

        XCTAssertFalse(state.isPlaying)
        XCTAssertNil(state.nowPlayingBook)
    }

    func testStartPlaybackGivesUpGracefullyWhenTheEngineThrows() throws {
        let folderPersistence = makeIsolatedFolderPersistence()
        let engine = FakeAudioPlaybackEngine()
        engine.errorToThrowOnPlay = NSError(domain: "test", code: 1)
        let state = AppState(folderPersistence: folderPersistence, userPreferencesPersistence: makeIsolatedPersistence(), audioEngine: engine)
        let book = try makeAudioBook(folderPersistence: folderPersistence)

        state.startPlayback(book: book)

        XCTAssertFalse(state.isPlaying)
        XCTAssertNil(state.nowPlayingBook)
    }

    func testResumingTheSameAudioBookCallsResumeInsteadOfReplaying() throws {
        let folderPersistence = makeIsolatedFolderPersistence()
        let engine = FakeAudioPlaybackEngine()
        let state = AppState(folderPersistence: folderPersistence, userPreferencesPersistence: makeIsolatedPersistence(), audioEngine: engine)
        let book = try makeAudioBook(folderPersistence: folderPersistence)
        state.startPlayback(book: book)
        state.togglePlayback() // pause
        XCTAssertEqual(engine.pauseCallCount, 1)

        state.startPlayback(book: book) // same book, e.g. re-tapping the mini-player

        XCTAssertEqual(engine.resumeCallCount, 1, "same book should resume, not tear down and replay")
    }

    // MARK: - togglePlayback

    func testTogglePlaybackForAnAudioBookPausesAndResumesTheEngine() throws {
        let folderPersistence = makeIsolatedFolderPersistence()
        let engine = FakeAudioPlaybackEngine()
        let state = AppState(folderPersistence: folderPersistence, userPreferencesPersistence: makeIsolatedPersistence(), audioEngine: engine)
        let book = try makeAudioBook(folderPersistence: folderPersistence)
        state.startPlayback(book: book)

        state.togglePlayback()
        XCTAssertFalse(state.isPlaying)
        XCTAssertEqual(engine.pauseCallCount, 1)

        state.togglePlayback()
        XCTAssertTrue(state.isPlaying)
        XCTAssertEqual(engine.resumeCallCount, 1)
    }

    // MARK: - seek / skipForward / skipBackward

    func testSeekForAnAudioBookSetsTheEnginesElapsedPosition() throws {
        let folderPersistence = makeIsolatedFolderPersistence()
        let engine = FakeAudioPlaybackEngine()
        engine.duration = 100
        let state = AppState(folderPersistence: folderPersistence, userPreferencesPersistence: makeIsolatedPersistence(), audioEngine: engine)
        let book = try makeAudioBook(folderPersistence: folderPersistence)
        state.startPlayback(book: book)

        state.seek(to: 30)

        XCTAssertEqual(engine.elapsed, 30)
        XCTAssertEqual(state.elapsedSeconds, 30)
    }

    // MARK: - playbackSpeed

    func testChangingSpeedForAnAudioBookAppliesItLiveWithoutRecomputingDuration() throws {
        let folderPersistence = makeIsolatedFolderPersistence()
        let engine = FakeAudioPlaybackEngine()
        engine.duration = 100
        let state = AppState(folderPersistence: folderPersistence, userPreferencesPersistence: makeIsolatedPersistence(), audioEngine: engine)
        let book = try makeAudioBook(folderPersistence: folderPersistence)
        state.startPlayback(book: book)
        let totalBefore = state.totalEstimatedSeconds

        state.playbackSpeed = 2.0

        XCTAssertEqual(engine.setRateCalls, [2.0])
        XCTAssertEqual(state.totalEstimatedSeconds, totalBefore, "audio duration comes from the real file, not a word-count estimate — it shouldn't be rescaled on speed change")
    }

    // MARK: - stopPlayback / onFinished

    func testStopPlaybackStopsTheEngine() throws {
        let folderPersistence = makeIsolatedFolderPersistence()
        let engine = FakeAudioPlaybackEngine()
        let state = AppState(folderPersistence: folderPersistence, userPreferencesPersistence: makeIsolatedPersistence(), audioEngine: engine)
        let book = try makeAudioBook(folderPersistence: folderPersistence)
        state.startPlayback(book: book)
        let stopCountAfterStarting = engine.stopCallCount // startPlayback itself calls stop() once, as a defensive reset before play(), for any new session

        state.stopPlayback()

        XCTAssertEqual(engine.stopCallCount, stopCountAfterStarting + 1)
        XCTAssertNil(state.nowPlayingBook)
    }

    func testEngineFinishingOnItsOwnStopsPlayback() throws {
        let folderPersistence = makeIsolatedFolderPersistence()
        let engine = FakeAudioPlaybackEngine()
        let state = AppState(folderPersistence: folderPersistence, userPreferencesPersistence: makeIsolatedPersistence(), audioEngine: engine)
        let book = try makeAudioBook(folderPersistence: folderPersistence)
        state.startPlayback(book: book)

        engine.onFinished?()
        // AppState hops onto the main actor via Task { @MainActor ... } for this
        // callback — give the run loop a turn to actually process it.
        let expectation = expectation(description: "onFinished processed")
        DispatchQueue.main.async { expectation.fulfill() }
        wait(for: [expectation], timeout: 1.0)

        XCTAssertNil(state.nowPlayingBook)
        XCTAssertFalse(state.isPlaying)
    }

    // MARK: - Sleep timer expiry

    // Regression test for issue #89: sleep-timer expiry used to call stopPlayback(),
    // which unconditionally cleared nowPlayingBook and produced an empty "nothing
    // playing" screen instead of fading out and pausing. Calls
    // handleSleepTimerExpired() directly (the method the real countdown Timer calls
    // at zero) rather than waiting out a real countdown, but the fade-out itself is a
    // real 3-second Timer (see AppState.startSleepFadeOut), so this test genuinely
    // waits that long for it to finish.
    func testSleepTimerExpiryFadesOutAudioThenPausesWithoutClearingNowPlayingBook() throws {
        let folderPersistence = makeIsolatedFolderPersistence()
        let engine = FakeAudioPlaybackEngine()
        let state = AppState(folderPersistence: folderPersistence, userPreferencesPersistence: makeIsolatedPersistence(), audioEngine: engine)
        let book = try makeAudioBook(folderPersistence: folderPersistence)
        state.startPlayback(book: book)

        state.handleSleepTimerExpired()

        // Immediately after expiry: still fading, not torn down.
        XCTAssertNotNil(state.nowPlayingBook, "sleep timer should pause, not fully tear down playback")
        XCTAssertEqual(engine.pauseCallCount, 0, "should still be fading, not paused yet")

        let expectation = expectation(description: "sleep timer fade-out completes")
        DispatchQueue.main.asyncAfter(deadline: .now() + 3.5) { expectation.fulfill() }
        wait(for: [expectation], timeout: 5.0)

        XCTAssertEqual(engine.pauseCallCount, 1)
        XCTAssertEqual(engine.volume, 1.0, "volume should be restored after the fade, not left silent for the next resume")
        XCTAssertFalse(state.isPlaying)
        XCTAssertNotNil(state.nowPlayingBook, "sleep timer should pause, not fully tear down playback")
    }

    func testTogglingPlaybackMidFadeCancelsTheFadeAndRestoresVolume() throws {
        let folderPersistence = makeIsolatedFolderPersistence()
        let engine = FakeAudioPlaybackEngine()
        let state = AppState(folderPersistence: folderPersistence, userPreferencesPersistence: makeIsolatedPersistence(), audioEngine: engine)
        let book = try makeAudioBook(folderPersistence: folderPersistence)
        state.startPlayback(book: book)

        state.handleSleepTimerExpired()
        state.togglePlayback() // pause manually mid-fade

        XCTAssertEqual(engine.volume, 1.0, "a manual pause mid-fade shouldn't leave volume silenced")
        XCTAssertEqual(engine.pauseCallCount, 1)
    }
}
