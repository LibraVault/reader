import XCTest
import ZIPFoundation
@testable import LibraVault

/// Covers AppState's Now Playing / Control Center integration (issue #309) — routed
/// through `nowPlayingManager` (see AppState.syncNowPlayingInfo/remotePlay/remotePause),
/// which every existing playback test leaves at its default `SystemNowPlayingManager()`
/// and therefore never exercises. Uses `FakeNowPlayingManager` throughout (never the
/// real `SystemNowPlayingManager`) — the real one talks to MPNowPlayingInfoCenter/
/// MPRemoteCommandCenter, which have no public way to read back what was set and
/// which this repo has chosen not to risk in the headless CI Simulator (see
/// SystemNowPlayingManager's own doc comment).
@MainActor
final class AppStateNowPlayingTests: XCTestCase {

    private func makeIsolatedPersistence() -> UserPreferencesPersistence {
        UserPreferencesPersistence(defaults: UserDefaults(suiteName: "AppStateNowPlayingTests.\(UUID().uuidString)")!)
    }

    private func makeIsolatedFolderPersistence() -> FolderPersistence {
        FolderPersistence(defaults: UserDefaults(suiteName: "AppStateNowPlayingTests.Folders.\(UUID().uuidString)")!)
    }

    /// Waits for one hop through the main actor's task queue — every
    /// `nowPlayingManager.onPlay`/`onPause`/`onSkipForward`/`onSkipBackward` closure
    /// AppState wires up hops via `Task { @MainActor ... }` (mirroring
    /// audioEngine.onProgress/onFinished), so a test that fires one of those closures
    /// synchronously needs to give the run loop a turn before asserting on the result.
    private func waitForMainActorHop() {
        let expectation = expectation(description: "main actor hop processed")
        DispatchQueue.main.async { expectation.fulfill() }
        wait(for: [expectation], timeout: 1.0)
    }

    /// A real 2-chapter EPUB inside a real folder — needed only for the
    /// chapter-title test below, which is the one behaviour here that actually
    /// depends on real chapter titles rather than just a book's title/author.
    /// Mirrors AppStatePlaybackTests.makeRealEPUBBook's shape.
    private func makeTwoChapterEPUBBook(folderPersistence: FolderPersistence) throws -> BookItem {
        let tempDir = FileManager.default.temporaryDirectory.appendingPathComponent("AppStateNowPlayingTests-\(UUID().uuidString)")
        let sourceDir = tempDir.appendingPathComponent("source", isDirectory: true)
        let bookFolder = tempDir.appendingPathComponent("folder", isDirectory: true)
        let oebpsDir = sourceDir.appendingPathComponent("OEBPS", isDirectory: true)
        let metaInfDir = sourceDir.appendingPathComponent("META-INF", isDirectory: true)
        try FileManager.default.createDirectory(at: oebpsDir, withIntermediateDirectories: true)
        try FileManager.default.createDirectory(at: metaInfDir, withIntermediateDirectories: true)
        try FileManager.default.createDirectory(at: bookFolder, withIntermediateDirectories: true)

        try """
        <?xml version="1.0"?>
        <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
          <rootfiles>
            <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
          </rootfiles>
        </container>
        """.write(to: metaInfDir.appendingPathComponent("container.xml"), atomically: true, encoding: .utf8)

        try """
        <?xml version="1.0"?>
        <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
          <manifest>
            <item id="chap0" href="chap0.xhtml" media-type="application/xhtml+xml"/>
            <item id="chap1" href="chap1.xhtml" media-type="application/xhtml+xml"/>
          </manifest>
          <spine>
            <itemref idref="chap0"/>
            <itemref idref="chap1"/>
          </spine>
        </package>
        """.write(to: oebpsDir.appendingPathComponent("content.opf"), atomically: true, encoding: .utf8)

        try "<html><body><h1>First Chapter</h1><p>Some real text.</p></body></html>"
            .write(to: oebpsDir.appendingPathComponent("chap0.xhtml"), atomically: true, encoding: .utf8)
        try "<html><body><h1>Second Chapter</h1><p>Some more real text.</p></body></html>"
            .write(to: oebpsDir.appendingPathComponent("chap1.xhtml"), atomically: true, encoding: .utf8)

        let finalEpubURL = bookFolder.appendingPathComponent("Fixture.epub")
        try FileManager().zipItem(at: sourceDir, to: finalEpubURL, shouldKeepParent: false)

        let folder = try folderPersistence.makeFolder(from: bookFolder)
        folderPersistence.save([folder])

        return BookItem(
            id: "folder:\(folder.id):\(finalEpubURL.path)",
            title: "Fixture",
            author: "Fixture Author",
            format: .epub,
            fileURL: finalEpubURL,
            folderId: folder.id
        )
    }

    /// A real folder containing an arbitrary file at a real path — mirrors
    /// AppStateAudioPlaybackTests.makeAudioBook. Used only for the remote skip tests
    /// below, which need a duration they control (via FakeAudioPlaybackEngine) rather
    /// than a TTS word-count estimate: a short/empty-text book floors
    /// totalEstimatedSeconds to exactly 1 second (see AppStatePlaybackTests'
    /// longChapterHTML comment) and would clamp every seek target down to that,
    /// defeating the point of these assertions.
    private func makeAudioBook(folderPersistence: FolderPersistence) throws -> BookItem {
        let audioFolder = FileManager.default.temporaryDirectory
            .appendingPathComponent("AppStateNowPlayingTests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: audioFolder, withIntermediateDirectories: true)
        let fileURL = audioFolder.appendingPathComponent("track.mp3")
        try Data("not real audio, the fake engine never decodes this".utf8).write(to: fileURL)

        let folder = try folderPersistence.makeFolder(from: audioFolder)
        folderPersistence.save([folder])

        return BookItem(
            id: "folder:\(folder.id):\(fileURL.path)",
            title: "Audiobook",
            author: "Author",
            format: .mp3,
            fileURL: fileURL,
            folderId: folder.id
        )
    }

    // MARK: - startPlayback

    func testStartPlaybackPublishesBookMetadataToNowPlayingInfo() throws {
        let nowPlaying = FakeNowPlayingManager()
        let state = AppState(userPreferencesPersistence: makeIsolatedPersistence(), nowPlayingManager: nowPlaying)

        state.startPlayback(book: BookItem(id: "1", title: "Dune", author: "Frank Herbert"))

        let snapshot = try XCTUnwrap(nowPlaying.lastUpdate)
        XCTAssertEqual(snapshot.title, "Dune")
        XCTAssertEqual(snapshot.artist, "Frank Herbert")
        XCTAssertTrue(snapshot.isPlaying)
        XCTAssertEqual(snapshot.elapsedSeconds, 0)
        XCTAssertNil(snapshot.chapterTitle, "a single-chapter book has nothing more specific to show than the title/artist already convey")
    }

    // startPlayback's `defer { syncNowPlayingInfo() }` runs on every exit path,
    // including this one — a harmless redundant clear() (nothing was playing before
    // either), not a real "now playing" publish, so the assertion here is on
    // `updateCalls`, not on whether syncNowPlayingInfo ran at all.
    func testStartPlaybackWithAnUnsupportedFormatNeverPublishesNowPlayingInfo() {
        let nowPlaying = FakeNowPlayingManager()
        let state = AppState(userPreferencesPersistence: makeIsolatedPersistence(), nowPlayingManager: nowPlaying)

        state.startPlayback(book: BookItem(id: "m", title: "Comic", author: "A", format: .cbz))

        XCTAssertTrue(nowPlaying.updateCalls.isEmpty, "cbz never enters a playing state, so there's nothing new for Control Center to show")
    }

    // Unlike the unsupported-format case above, an unsupported format arriving while
    // a *different* book is already playing must leave that session's Now Playing
    // info alone — the early-return guard fires before nowPlayingBook is touched, so
    // syncNowPlayingInfo's defer just republishes the still-playing book unchanged.
    func testStartPlaybackWithAnUnsupportedFormatLeavesAnExistingSessionsNowPlayingInfoAlone() {
        let nowPlaying = FakeNowPlayingManager()
        let state = AppState(userPreferencesPersistence: makeIsolatedPersistence(), nowPlayingManager: nowPlaying)
        state.startPlayback(book: BookItem(id: "1", title: "Dune", author: "Frank Herbert"))

        state.startPlayback(book: BookItem(id: "m", title: "Comic", author: "A", format: .cbz))

        XCTAssertEqual(nowPlaying.lastUpdate?.title, "Dune")
        XCTAssertEqual(nowPlaying.clearCallCount, 0)
    }

    func testStartingASecondBookPublishesItsOwnMetadata() {
        let nowPlaying = FakeNowPlayingManager()
        let state = AppState(userPreferencesPersistence: makeIsolatedPersistence(), nowPlayingManager: nowPlaying)
        state.startPlayback(book: BookItem(id: "1", title: "Dune", author: "Frank Herbert"))

        state.startPlayback(book: BookItem(id: "2", title: "Foundation", author: "Isaac Asimov"))

        XCTAssertEqual(nowPlaying.lastUpdate?.title, "Foundation")
        XCTAssertEqual(nowPlaying.lastUpdate?.artist, "Isaac Asimov")
    }

    func testMultiChapterBookPublishesTheCurrentChapterTitle() throws {
        let folderPersistence = makeIsolatedFolderPersistence()
        let nowPlaying = FakeNowPlayingManager()
        let state = AppState(folderPersistence: folderPersistence, userPreferencesPersistence: makeIsolatedPersistence(), nowPlayingManager: nowPlaying)
        let book = try makeTwoChapterEPUBBook(folderPersistence: folderPersistence)

        state.startPlayback(book: book)
        XCTAssertEqual(nowPlaying.lastUpdate?.chapterTitle, "First Chapter")

        state.skipToChapter(2)
        XCTAssertEqual(nowPlaying.lastUpdate?.chapterTitle, "Second Chapter")
    }

    // MARK: - togglePlayback

    func testTogglePlaybackPublishesTheNewIsPlayingState() {
        let nowPlaying = FakeNowPlayingManager()
        let state = AppState(userPreferencesPersistence: makeIsolatedPersistence(), nowPlayingManager: nowPlaying)
        state.startPlayback(book: BookItem(id: "1", title: "T", author: "A"))

        state.togglePlayback()
        XCTAssertEqual(nowPlaying.lastUpdate?.isPlaying, false)

        state.togglePlayback()
        XCTAssertEqual(nowPlaying.lastUpdate?.isPlaying, true)
    }

    // MARK: - seek / skipForward / skipBackward

    // Uses a real audio-book fixture rather than a plain BookItem: a text book with no
    // real content floors totalEstimatedSeconds to 1 second (see makeAudioBook's doc
    // comment), which would clamp `seek(to: 12)` down to 1 and defeat this assertion.
    func testSeekPublishesTheNewElapsedTime() throws {
        let folderPersistence = makeIsolatedFolderPersistence()
        let nowPlaying = FakeNowPlayingManager()
        let engine = FakeAudioPlaybackEngine()
        engine.duration = 100
        let state = AppState(folderPersistence: folderPersistence, userPreferencesPersistence: makeIsolatedPersistence(), audioEngine: engine, nowPlayingManager: nowPlaying)
        let book = try makeAudioBook(folderPersistence: folderPersistence)
        state.startPlayback(book: book)

        state.seek(to: 12)

        XCTAssertEqual(nowPlaying.lastUpdate?.elapsedSeconds, 12)
    }

    // MARK: - playbackSpeed

    func testChangingPlaybackSpeedPublishesTheNewRate() {
        let nowPlaying = FakeNowPlayingManager()
        let state = AppState(userPreferencesPersistence: makeIsolatedPersistence(), nowPlayingManager: nowPlaying)
        state.startPlayback(book: BookItem(id: "1", title: "T", author: "A"))

        state.playbackSpeed = 1.75

        XCTAssertEqual(nowPlaying.lastUpdate?.playbackRate, 1.75)
    }

    // MARK: - stopPlayback

    func testStopPlaybackClearsNowPlayingInfo() {
        let nowPlaying = FakeNowPlayingManager()
        let state = AppState(userPreferencesPersistence: makeIsolatedPersistence(), nowPlayingManager: nowPlaying)
        state.startPlayback(book: BookItem(id: "1", title: "T", author: "A"))

        state.stopPlayback()

        XCTAssertEqual(nowPlaying.clearCallCount, 1)
    }

    // MARK: - Sleep timer expiry (mirrors AppStatePlaybackTests' #89 regression coverage)

    func testSleepTimerExpiryPublishesThePausedState() throws {
        let folderPersistence = makeIsolatedFolderPersistence()
        let nowPlaying = FakeNowPlayingManager()
        let state = AppState(folderPersistence: folderPersistence, userPreferencesPersistence: makeIsolatedPersistence(), nowPlayingManager: nowPlaying)
        let book = try makeTwoChapterEPUBBook(folderPersistence: folderPersistence)
        state.startPlayback(book: book)

        state.handleSleepTimerExpired()

        XCTAssertEqual(nowPlaying.lastUpdate?.isPlaying, false)
        XCTAssertEqual(nowPlaying.clearCallCount, 0, "a sleep-timer pause shouldn't remove LibraVault from Control Center entirely — see issue #89")
    }

    // MARK: - Remote commands (play/pause/skip forward/skip backward)

    func testRemotePlayCommandResumesPausedPlayback() {
        let nowPlaying = FakeNowPlayingManager()
        let state = AppState(userPreferencesPersistence: makeIsolatedPersistence(), nowPlayingManager: nowPlaying)
        state.startPlayback(book: BookItem(id: "1", title: "T", author: "A"))
        state.togglePlayback() // pause
        XCTAssertFalse(state.isPlaying)

        nowPlaying.onPlay?()
        waitForMainActorHop()

        XCTAssertTrue(state.isPlaying)
    }

    func testRemotePlayCommandIsANoOpWhenAlreadyPlaying() {
        let nowPlaying = FakeNowPlayingManager()
        let state = AppState(userPreferencesPersistence: makeIsolatedPersistence(), nowPlayingManager: nowPlaying)
        state.startPlayback(book: BookItem(id: "1", title: "T", author: "A"))
        XCTAssertTrue(state.isPlaying)

        nowPlaying.onPlay?()
        waitForMainActorHop()

        XCTAssertTrue(state.isPlaying, "the play command firing while already playing must not toggle playback off")
    }

    func testRemotePauseCommandPausesActivePlayback() {
        let nowPlaying = FakeNowPlayingManager()
        let state = AppState(userPreferencesPersistence: makeIsolatedPersistence(), nowPlayingManager: nowPlaying)
        state.startPlayback(book: BookItem(id: "1", title: "T", author: "A"))

        nowPlaying.onPause?()
        waitForMainActorHop()

        XCTAssertFalse(state.isPlaying)
    }

    func testRemotePauseCommandIsANoOpWhenAlreadyPaused() {
        let nowPlaying = FakeNowPlayingManager()
        let state = AppState(userPreferencesPersistence: makeIsolatedPersistence(), nowPlayingManager: nowPlaying)
        state.startPlayback(book: BookItem(id: "1", title: "T", author: "A"))
        state.togglePlayback() // pause
        XCTAssertFalse(state.isPlaying)

        nowPlaying.onPause?()
        waitForMainActorHop()

        XCTAssertFalse(state.isPlaying, "the pause command firing while already paused must not toggle playback back on")
    }

    func testRemotePlayPauseCommandsAreNoOpsWithoutAnActiveSession() {
        let nowPlaying = FakeNowPlayingManager()
        let state = AppState(userPreferencesPersistence: makeIsolatedPersistence(), nowPlayingManager: nowPlaying)

        nowPlaying.onPlay?()
        nowPlaying.onPause?()
        waitForMainActorHop()

        XCTAssertNil(state.nowPlayingBook)
        XCTAssertFalse(state.isPlaying)
    }

    func testRemoteSkipForwardUsesTheLiveSkipDurationSeconds() throws {
        let folderPersistence = makeIsolatedFolderPersistence()
        let nowPlaying = FakeNowPlayingManager()
        let engine = FakeAudioPlaybackEngine()
        engine.duration = 100
        let state = AppState(folderPersistence: folderPersistence, userPreferencesPersistence: makeIsolatedPersistence(), audioEngine: engine, nowPlayingManager: nowPlaying)
        state.skipDurationSeconds = 45
        let book = try makeAudioBook(folderPersistence: folderPersistence)
        state.startPlayback(book: book)
        state.seek(to: 10)

        nowPlaying.onSkipForward?()
        waitForMainActorHop()

        XCTAssertEqual(state.elapsedSeconds, 55, accuracy: 0.01, "should skip by the live skipDurationSeconds (45), matching PlayerView's own skip button")
    }

    func testRemoteSkipBackwardUsesTheLiveSkipDurationSeconds() throws {
        let folderPersistence = makeIsolatedFolderPersistence()
        let nowPlaying = FakeNowPlayingManager()
        let engine = FakeAudioPlaybackEngine()
        engine.duration = 100
        let state = AppState(folderPersistence: folderPersistence, userPreferencesPersistence: makeIsolatedPersistence(), audioEngine: engine, nowPlayingManager: nowPlaying)
        state.skipDurationSeconds = 20
        let book = try makeAudioBook(folderPersistence: folderPersistence)
        state.startPlayback(book: book)
        state.seek(to: 30)

        nowPlaying.onSkipBackward?()
        waitForMainActorHop()

        XCTAssertEqual(state.elapsedSeconds, 10, accuracy: 0.01)
    }
}
