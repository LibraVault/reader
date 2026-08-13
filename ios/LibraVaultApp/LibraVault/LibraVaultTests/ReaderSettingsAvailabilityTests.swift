import XCTest
@testable import LibraVault

/// Coverage for which reading-settings controls each book format gets.
///
/// Every case is asserted over the full `MediaFormat` set rather than the one or two
/// formats a rule "is about", because the bug these rules exist to prevent was exactly
/// an unconsidered format falling on the wrong side of a predicate: `showReadAloud`
/// originally read `format != .markdown`, silently leaving `.mobi` and `.cbz` — both
/// mapped by LibraryFileScanner — with a Read Aloud button that does nothing. Markdown
/// itself later gained a real chapter parser (#124) and moved to the "shown" side —
/// `showReadAloud` delegates to `BookContentProvider.supportsChapterParsing`, so that
/// one source-of-truth change is what these tests now assert, not a change to the
/// predicate's own logic here.
final class ReaderSettingsAvailabilityTests: XCTestCase {

    /// Every format the app can produce. Kept explicit (rather than derived) so adding a
    /// MediaFormat case makes these tests fail until its behaviour here is decided.
    private let allFormats: [MediaFormat] = [
        .pdf, .epub, .markdown, .mobi, .cbz, .mp3, .m4b, .aac, .flac, .ogg, .opus,
    ]

    // MARK: - showFontControls

    func testFontControlsShownForEveryFormatExceptPdf() {
        for format in allFormats {
            let expected = format != .pdf
            XCTAssertEqual(
                ReaderSettingsAvailability.showFontControls(for: format), expected,
                "\(format) font controls"
            )
        }
    }

    // MARK: - showReadAloud

    func testReadAloudShownOnlyForFormatsWithAChapterParser() {
        // Markdown joined EPUB/PDF here in #124 — MarkdownDocumentParser.chaptersForNarration
        // gives it a real chapter parser, so offering Read Aloud is no longer a dead
        // control the way it still is for mobi/cbz (neither has any parser at all).
        XCTAssertTrue(ReaderSettingsAvailability.showReadAloud(for: .epub))
        XCTAssertTrue(ReaderSettingsAvailability.showReadAloud(for: .pdf))
        XCTAssertTrue(ReaderSettingsAvailability.showReadAloud(for: .markdown))

        for format: MediaFormat in [.mobi, .cbz] {
            XCTAssertFalse(
                ReaderSettingsAvailability.showReadAloud(for: format),
                "\(format) has no chapter parser — offering Read Aloud would be a dead control"
            )
        }
    }

    /// The control and the action it triggers must agree: the button is offered exactly
    /// when `startPlayback` can actually narrate the book.
    ///
    /// Deliberately asserted against `startPlayback`'s observable *behaviour* — does the
    /// app end up playing? — rather than against `BookContentProvider.supportsChapterParsing`.
    /// Comparing to the latter would restate `showReadAloud`'s own one-line implementation
    /// and pass no matter how wrong both were; this fails if either side changes
    /// independently, which is the drift actually worth catching.
    @MainActor
    func testReadAloudVisibilityMatchesWhatStartPlaybackActuallyDoes() {
        for format in allFormats where !format.isAudio {
            let state = AppState(userPreferencesPersistence: isolatedPreferences())
            state.startPlayback(book: BookItem(id: "b", title: "T", author: "A", format: format))

            XCTAssertEqual(
                ReaderSettingsAvailability.showReadAloud(for: format), state.isPlaying,
                "\(format): Read Aloud is offered iff startPlayback can actually narrate it"
            )
        }
    }

    /// Isolated from `UserDefaults.standard` so constructing an AppState here can't leak
    /// playback preferences into other test classes (same reasoning as AppStatePlaybackTests).
    private func isolatedPreferences() -> UserPreferencesPersistence {
        UserPreferencesPersistence(
            defaults: UserDefaults(suiteName: "ReaderSettingsAvailabilityTests.\(UUID().uuidString)")!
        )
    }

    // MARK: - showLayoutMode

    func testLayoutModeHiddenOnlyForMarkdown() {
        XCTAssertFalse(
            ReaderSettingsAvailability.showLayoutMode(for: .markdown),
            "Markdown is one continuous scroll — the Paginated/Scrolling toggle has nothing to switch"
        )
        for format in allFormats where format != .markdown {
            XCTAssertTrue(
                ReaderSettingsAvailability.showLayoutMode(for: format), "\(format) layout mode"
            )
        }
    }

    // MARK: - Markdown, end to end

    /// Pinned as a single case, updated for #124: opening a .md file gets font
    /// controls and (now) Read Aloud, but not the layout toggle — Markdown is one
    /// continuous scroll with no pagination to switch, unrelated to whether it can be
    /// narrated.
    func testMarkdownGetsFontControlsAndReadAloudButNotLayoutMode() {
        XCTAssertTrue(ReaderSettingsAvailability.showFontControls(for: .markdown))
        XCTAssertTrue(ReaderSettingsAvailability.showReadAloud(for: .markdown))
        XCTAssertFalse(ReaderSettingsAvailability.showLayoutMode(for: .markdown))
    }
}
