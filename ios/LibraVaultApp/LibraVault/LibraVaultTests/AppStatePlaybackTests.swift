import XCTest
import ZIPFoundation
@testable import LibraVault

@MainActor
final class AppStatePlaybackTests: XCTestCase {

    // Isolated from the real UserDefaults.standard — AppState persists
    // defaultPlaybackSpeed/defaultReadingTheme/skipDurationSeconds now, so without
    // this, one test's `state.defaultPlaybackSpeed = 2.5` leaks into every other
    // test in this file that constructs a plain AppState() expecting the compiled
    // default of 1.0 (same reasoning as AppStateSettingsTests/AppStateFolderTests).
    private func makeIsolatedPersistence() -> UserPreferencesPersistence {
        UserPreferencesPersistence(defaults: UserDefaults(suiteName: "AppStatePlaybackTests.\(UUID().uuidString)")!)
    }

    private func makeIsolatedFolderPersistence() -> FolderPersistence {
        FolderPersistence(defaults: UserDefaults(suiteName: "AppStatePlaybackTests.Folders.\(UUID().uuidString)")!)
    }

    /// A real EPUB inside a real folder, registered with `folderPersistence` —
    /// lets startPlayback's `BookContentProvider.chapters(for:folderPersistence:)` call
    /// actually resolve and parse it, the same way it would for a book scanned from a
    /// real folder. Defaults to a single short chapter; pass `chapterBodies` for
    /// multi-chapter fixtures or ones with enough real words that
    /// `estimateDuration`'s 1-second floor doesn't swallow speed/seek math (see
    /// `longChapterHTML` below).
    private func makeRealEPUBBook(
        folderPersistence: FolderPersistence,
        chapterBodies: [String] = ["<h1>Only Chapter</h1><p>Real playback text.</p>"]
    ) throws -> BookItem {
        let tempDir = FileManager.default.temporaryDirectory.appendingPathComponent("AppStatePlaybackTests-\(UUID().uuidString)")
        // The epub gets zipped from sourceDir, then moved into bookFolder — both are
        // siblings under tempDir, never nested inside each other, so zipItem never
        // tries to archive the very file it's in the middle of writing.
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

        let manifestItems = chapterBodies.indices
            .map { "<item id=\"chap\($0)\" href=\"chap\($0).xhtml\" media-type=\"application/xhtml+xml\"/>" }
            .joined()
        let spineItems = chapterBodies.indices.map { "<itemref idref=\"chap\($0)\"/>" }.joined()
        try """
        <?xml version="1.0"?>
        <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
          <manifest>\(manifestItems)</manifest>
          <spine>\(spineItems)</spine>
        </package>
        """.write(to: oebpsDir.appendingPathComponent("content.opf"), atomically: true, encoding: .utf8)

        for (index, body) in chapterBodies.enumerated() {
            try "<html><body>\(body)</body></html>"
                .write(to: oebpsDir.appendingPathComponent("chap\(index).xhtml"), atomically: true, encoding: .utf8)
        }

        let finalEpubURL = bookFolder.appendingPathComponent("Fixture.epub")
        try FileManager().zipItem(at: sourceDir, to: finalEpubURL, shouldKeepParent: false)

        let folder = try folderPersistence.makeFolder(from: bookFolder)
        folderPersistence.save([folder])

        return BookItem(
            id: "folder:\(folder.id):\(finalEpubURL.path)",
            title: "Fixture",
            author: "",
            format: .epub,
            fileURL: finalEpubURL,
            folderId: folder.id
        )
    }

    /// 200 real words is comfortably above estimateDuration's 1-second floor at every
    /// speed these tests use (80s at 1x, 40s at 2x) — long enough that speed-scaling
    /// and seek-position math actually has room to be meaningfully asserted on,
    /// unlike a short fixture that floors to a constant 1 second regardless of speed.
    private func longChapterHTML(title: String) -> String {
        "<h1>\(title)</h1><p>\(Array(repeating: "word", count: 200).joined(separator: " "))</p>"
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

    // MARK: - Real chapter content (EPUB/PDF via BookContentProvider)

    func testStartPlaybackUsesRealChaptersForARealEPUB() throws {
        let folderPersistence = makeIsolatedFolderPersistence()
        let state = AppState(folderPersistence: folderPersistence, userPreferencesPersistence: makeIsolatedPersistence())
        let book = try makeRealEPUBBook(folderPersistence: folderPersistence)

        state.startPlayback(book: book)

        XCTAssertEqual(state.nowPlayingChapterCount, 1)
        XCTAssertEqual(state.nowPlayingChapterTitles, ["Only Chapter"])
    }

    func testSkipToChapterClampsToRealChapterCountForARealEPUB() throws {
        let folderPersistence = makeIsolatedFolderPersistence()
        let state = AppState(folderPersistence: folderPersistence, userPreferencesPersistence: makeIsolatedPersistence())
        let book = try makeRealEPUBBook(folderPersistence: folderPersistence)
        state.startPlayback(book: book)

        state.skipToChapter(999)

        XCTAssertEqual(state.nowPlayingChapter, 1, "the fixture only has 1 real chapter")
    }

    func testChangingSpeedMidPlaybackRecomputesDurationAndPreservesProgress() throws {
        let folderPersistence = makeIsolatedFolderPersistence()
        let state = AppState(folderPersistence: folderPersistence, userPreferencesPersistence: makeIsolatedPersistence())
        let book = try makeRealEPUBBook(folderPersistence: folderPersistence, chapterBodies: [longChapterHTML(title: "Chapter One")])
        state.startPlayback(book: book)
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

    // MARK: - Formats with no chapter parser

    /// mobi/cbz remain genuinely unsupported (no parser exists for either) — the
    /// format-gate test that used to name Markdown here now lives in the Markdown TTS
    /// section below, since #124 gave Markdown a real chapter parser and this
    /// behaviour reversed deliberately.
    func testStartPlaybackIgnoresAnUnsupportedFormat() {
        let state = AppState(userPreferencesPersistence: makeIsolatedPersistence())
        state.startPlayback(book: BookItem(id: "m", title: "Comic", author: "A", format: .cbz))

        XCTAssertNil(state.nowPlayingBook, "cbz has no chapter parser — it must not enter a playing state")
        XCTAssertFalse(state.isPlaying)
        XCTAssertEqual(state.totalEstimatedSeconds, 0)
    }

    func testStartPlaybackIgnoringAnUnsupportedFormatLeavesAnExistingSessionAlone() {
        let state = AppState(userPreferencesPersistence: makeIsolatedPersistence())
        state.startPlayback(book: BookItem(id: "1", title: "T", author: "A"))
        XCTAssertTrue(state.isPlaying)

        state.startPlayback(book: BookItem(id: "m", title: "Comic", author: "A", format: .cbz))

        XCTAssertEqual(state.nowPlayingBook?.id, "1", "the unsupported book shouldn't hijack the live session")
        XCTAssertTrue(state.isPlaying)
    }

    func testSupportsChapterParsingCoversEpubPdfAndMarkdown() {
        // Markdown joined EPUB/PDF in #124 — MarkdownDocumentParser.chaptersForNarration
        // gives it a real chapter parser, narrating through the same AVSpeechSynthesizer
        // pipeline EPUB/PDF already use.
        XCTAssertTrue(BookContentProvider.supportsChapterParsing(.epub))
        XCTAssertTrue(BookContentProvider.supportsChapterParsing(.pdf))
        XCTAssertTrue(BookContentProvider.supportsChapterParsing(.markdown))
        XCTAssertFalse(BookContentProvider.supportsChapterParsing(.mobi))
        XCTAssertFalse(BookContentProvider.supportsChapterParsing(.cbz))
    }

    // MARK: - Markdown Read Aloud (#124)

    /// A real .md file inside a real folder, registered with `folderPersistence` —
    /// mirrors makeRealEPUBBook's shape/purpose but far simpler, since Markdown needs
    /// no zip/manifest scaffolding, just a plain text file.
    private func makeRealMarkdownBook(
        folderPersistence: FolderPersistence,
        source: String = "# Only Chapter\nReal narratable text."
    ) throws -> BookItem {
        let tempDir = FileManager.default.temporaryDirectory.appendingPathComponent("AppStatePlaybackTests-md-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
        let fileURL = tempDir.appendingPathComponent("Fixture.md")
        try source.write(to: fileURL, atomically: true, encoding: .utf8)

        let folder = try folderPersistence.makeFolder(from: tempDir)
        folderPersistence.save([folder])

        return BookItem(
            id: "folder:\(folder.id):\(fileURL.path)",
            title: "Fixture",
            author: "",
            format: .markdown,
            fileURL: fileURL,
            folderId: folder.id
        )
    }

    func testStartPlaybackWithARealMarkdownFileEntersAPlayingState() throws {
        let folderPersistence = makeIsolatedFolderPersistence()
        let state = AppState(folderPersistence: folderPersistence, userPreferencesPersistence: makeIsolatedPersistence())
        let book = try makeRealMarkdownBook(folderPersistence: folderPersistence)

        state.startPlayback(book: book)

        XCTAssertTrue(state.isPlaying)
        XCTAssertEqual(state.nowPlayingChapterCount, 1)
        XCTAssertEqual(state.nowPlayingChapterTitles, ["Only Chapter"])
    }

    func testStartPlaybackWithAnImageOnlyMarkdownFileDoesNotEnterAPlayingState() throws {
        // The Markdown-specific edge case EPUB/PDF don't realistically hit: a file
        // that parses successfully but has nothing speakable at all (see
        // MarkdownDocumentParser.narrationText — code blocks, tables, thematic breaks,
        // and an alt-text-less image all produce no narration). Without this guard,
        // this reaches the exact phantom-player state #112 fixed for "no parser at
        // all": empty text, isPlaying = true, a 0-second estimate.
        let folderPersistence = makeIsolatedFolderPersistence()
        let state = AppState(folderPersistence: folderPersistence, userPreferencesPersistence: makeIsolatedPersistence())
        let book = try makeRealMarkdownBook(folderPersistence: folderPersistence, source: "![](./no-alt-text.png)")

        state.startPlayback(book: book)

        XCTAssertFalse(state.isPlaying, "an image with no alt text has nothing speakable — must not enter a playing state")
        XCTAssertNil(state.nowPlayingBook)
    }

    func testStartPlaybackWithAnUnreachableMarkdownFileStillEntersAPlayingState() {
        // Mirrors testStartPlaybackUsesRealChaptersForARealEPUB's sibling behaviour:
        // a parseable format whose file just isn't reachable (no folder fixture, as
        // here) should still enter a playing state — chapters ends up nil (parsing
        // never ran), not empty (parsed fine, nothing to say), and the guard added in
        // #124 is deliberately narrower than that so this case is unaffected.
        let state = AppState(userPreferencesPersistence: makeIsolatedPersistence())
        state.startPlayback(book: BookItem(id: "md", title: "Notes", author: "A", format: .markdown))

        XCTAssertTrue(state.isPlaying)
        XCTAssertEqual(state.nowPlayingBook?.id, "md")
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

    private func makeThreeChapterEPUBBook(folderPersistence: FolderPersistence) throws -> BookItem {
        try makeRealEPUBBook(
            folderPersistence: folderPersistence,
            chapterBodies: (1...3).map { longChapterHTML(title: "Chapter \($0)") }
        )
    }

    func testSkipToChapterClampsToValidRange() throws {
        let folderPersistence = makeIsolatedFolderPersistence()
        let state = AppState(folderPersistence: folderPersistence, userPreferencesPersistence: makeIsolatedPersistence())
        let book = try makeThreeChapterEPUBBook(folderPersistence: folderPersistence)
        state.startPlayback(book: book)

        state.skipToChapter(999)
        XCTAssertEqual(state.nowPlayingChapter, 3)

        state.skipToChapter(-5)
        XCTAssertEqual(state.nowPlayingChapter, 1)
    }

    func testSkipToChapterResetsElapsedSeconds() throws {
        let folderPersistence = makeIsolatedFolderPersistence()
        let state = AppState(folderPersistence: folderPersistence, userPreferencesPersistence: makeIsolatedPersistence())
        let book = try makeThreeChapterEPUBBook(folderPersistence: folderPersistence)
        state.startPlayback(book: book)
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

    // The fixture's 200-word chapter is ~80s of estimated duration at the default
    // 1.0x speed (see AppState.estimateDuration/longChapterHTML), so seek/skip
    // targets here have room to stay well under that ceiling — a short/empty-text
    // book would floor totalEstimatedSeconds to exactly 1 second and clamp every
    // seek in this test down to that, defeating the point of the assertions.
    func testSkipForwardAndBackwardMoveElapsedSeconds() throws {
        let folderPersistence = makeIsolatedFolderPersistence()
        let state = AppState(folderPersistence: folderPersistence, userPreferencesPersistence: makeIsolatedPersistence())
        let book = try makeRealEPUBBook(folderPersistence: folderPersistence, chapterBodies: [longChapterHTML(title: "Chapter One")])
        state.startPlayback(book: book)
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

    // Regression test for issue #89: sleep-timer expiry used to call stopPlayback(),
    // which unconditionally cleared nowPlayingBook and produced an empty "nothing
    // playing" screen instead of just pausing. Calls handleSleepTimerExpired()
    // directly (the method the real countdown Timer calls at zero) rather than
    // waiting out a real countdown.
    func testSleepTimerExpiryPausesTextPlaybackWithoutClearingNowPlayingBook() throws {
        let folderPersistence = makeIsolatedFolderPersistence()
        let state = AppState(folderPersistence: folderPersistence, userPreferencesPersistence: makeIsolatedPersistence())
        let book = try makeRealEPUBBook(folderPersistence: folderPersistence)
        state.startPlayback(book: book)

        state.handleSleepTimerExpired()

        XCTAssertNotNil(state.nowPlayingBook, "sleep timer should pause, not fully tear down playback")
        XCTAssertFalse(state.isPlaying)
    }

    func testSleepTimerExpiryIsANoOpWhenNothingIsPlaying() {
        let state = AppState(userPreferencesPersistence: makeIsolatedPersistence())

        state.handleSleepTimerExpired()

        XCTAssertNil(state.nowPlayingBook)
        XCTAssertFalse(state.isPlaying)
    }
}
