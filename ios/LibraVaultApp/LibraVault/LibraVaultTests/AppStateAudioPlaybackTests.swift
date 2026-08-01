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

    private func makeIsolatedVaultPersistence() -> VaultPersistence {
        VaultPersistence(defaults: UserDefaults(suiteName: "AppStateAudioPlaybackTests.Vaults.\(UUID().uuidString)")!)
    }

    /// A real vault folder containing an arbitrary file at a real path — the fake
    /// engine never actually decodes it, so its contents don't matter, only that
    /// `vaultPersistence.makeVault(from:)` can create a real bookmark for it (which,
    /// per its own doc comment, requires a real on-disk resource) and that
    /// `fileURL`/`vaultId` round-trip correctly through startAudioPlayback's lookup.
    private func makeAudioBook(vaultPersistence: VaultPersistence, format: MediaFormat = .mp3) throws -> BookItem {
        let vaultFolder = FileManager.default.temporaryDirectory
            .appendingPathComponent("AppStateAudioPlaybackTests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: vaultFolder, withIntermediateDirectories: true)
        let fileURL = vaultFolder.appendingPathComponent("track.mp3")
        try Data("not real audio, the fake engine never decodes this".utf8).write(to: fileURL)

        let vault = try vaultPersistence.makeVault(from: vaultFolder)
        vaultPersistence.save([vault])

        return BookItem(
            id: "vault:\(vault.id):\(fileURL.path)",
            title: "Audiobook",
            author: "Author",
            format: format,
            fileURL: fileURL,
            vaultId: vault.id
        )
    }

    // MARK: - startPlayback

    func testStartPlaybackForAnAudioBookPlaysTheRealFileAtTheCurrentSpeed() throws {
        let vaultPersistence = makeIsolatedVaultPersistence()
        let engine = FakeAudioPlaybackEngine()
        engine.duration = 42
        let state = AppState(vaultPersistence: vaultPersistence, userPreferencesPersistence: makeIsolatedPersistence(), audioEngine: engine)
        let book = try makeAudioBook(vaultPersistence: vaultPersistence)

        state.startPlayback(book: book)

        XCTAssertEqual(engine.playedFileURL, book.fileURL)
        XCTAssertEqual(engine.playedRate, Float(state.playbackSpeed))
        XCTAssertTrue(state.isPlaying)
        XCTAssertEqual(state.totalEstimatedSeconds, 42)
        XCTAssertEqual(state.elapsedSeconds, 0)
    }

    func testStartPlaybackForAnAudioBookReportsOneChapterNamedAfterTheBook() throws {
        let vaultPersistence = makeIsolatedVaultPersistence()
        let state = AppState(vaultPersistence: vaultPersistence, userPreferencesPersistence: makeIsolatedPersistence(), audioEngine: FakeAudioPlaybackEngine())
        let book = try makeAudioBook(vaultPersistence: vaultPersistence)

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
        let vaultPersistence = makeIsolatedVaultPersistence()
        let engine = FakeAudioPlaybackEngine()
        engine.errorToThrowOnPlay = NSError(domain: "test", code: 1)
        let state = AppState(vaultPersistence: vaultPersistence, userPreferencesPersistence: makeIsolatedPersistence(), audioEngine: engine)
        let book = try makeAudioBook(vaultPersistence: vaultPersistence)

        state.startPlayback(book: book)

        XCTAssertFalse(state.isPlaying)
        XCTAssertNil(state.nowPlayingBook)
    }

    func testResumingTheSameAudioBookCallsResumeInsteadOfReplaying() throws {
        let vaultPersistence = makeIsolatedVaultPersistence()
        let engine = FakeAudioPlaybackEngine()
        let state = AppState(vaultPersistence: vaultPersistence, userPreferencesPersistence: makeIsolatedPersistence(), audioEngine: engine)
        let book = try makeAudioBook(vaultPersistence: vaultPersistence)
        state.startPlayback(book: book)
        state.togglePlayback() // pause
        XCTAssertEqual(engine.pauseCallCount, 1)

        state.startPlayback(book: book) // same book, e.g. re-tapping the mini-player

        XCTAssertEqual(engine.resumeCallCount, 1, "same book should resume, not tear down and replay")
    }

    // MARK: - togglePlayback

    func testTogglePlaybackForAnAudioBookPausesAndResumesTheEngine() throws {
        let vaultPersistence = makeIsolatedVaultPersistence()
        let engine = FakeAudioPlaybackEngine()
        let state = AppState(vaultPersistence: vaultPersistence, userPreferencesPersistence: makeIsolatedPersistence(), audioEngine: engine)
        let book = try makeAudioBook(vaultPersistence: vaultPersistence)
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
        let vaultPersistence = makeIsolatedVaultPersistence()
        let engine = FakeAudioPlaybackEngine()
        engine.duration = 100
        let state = AppState(vaultPersistence: vaultPersistence, userPreferencesPersistence: makeIsolatedPersistence(), audioEngine: engine)
        let book = try makeAudioBook(vaultPersistence: vaultPersistence)
        state.startPlayback(book: book)

        state.seek(to: 30)

        XCTAssertEqual(engine.elapsed, 30)
        XCTAssertEqual(state.elapsedSeconds, 30)
    }

    // MARK: - playbackSpeed

    func testChangingSpeedForAnAudioBookAppliesItLiveWithoutRecomputingDuration() throws {
        let vaultPersistence = makeIsolatedVaultPersistence()
        let engine = FakeAudioPlaybackEngine()
        engine.duration = 100
        let state = AppState(vaultPersistence: vaultPersistence, userPreferencesPersistence: makeIsolatedPersistence(), audioEngine: engine)
        let book = try makeAudioBook(vaultPersistence: vaultPersistence)
        state.startPlayback(book: book)
        let totalBefore = state.totalEstimatedSeconds

        state.playbackSpeed = 2.0

        XCTAssertEqual(engine.setRateCalls, [2.0])
        XCTAssertEqual(state.totalEstimatedSeconds, totalBefore, "audio duration comes from the real file, not a word-count estimate — it shouldn't be rescaled on speed change")
    }

    // MARK: - stopPlayback / onFinished

    func testStopPlaybackStopsTheEngine() throws {
        let vaultPersistence = makeIsolatedVaultPersistence()
        let engine = FakeAudioPlaybackEngine()
        let state = AppState(vaultPersistence: vaultPersistence, userPreferencesPersistence: makeIsolatedPersistence(), audioEngine: engine)
        let book = try makeAudioBook(vaultPersistence: vaultPersistence)
        state.startPlayback(book: book)

        state.stopPlayback()

        XCTAssertEqual(engine.stopCallCount, 1)
        XCTAssertNil(state.nowPlayingBook)
    }

    func testEngineFinishingOnItsOwnStopsPlayback() throws {
        let vaultPersistence = makeIsolatedVaultPersistence()
        let engine = FakeAudioPlaybackEngine()
        let state = AppState(vaultPersistence: vaultPersistence, userPreferencesPersistence: makeIsolatedPersistence(), audioEngine: engine)
        let book = try makeAudioBook(vaultPersistence: vaultPersistence)
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
}
