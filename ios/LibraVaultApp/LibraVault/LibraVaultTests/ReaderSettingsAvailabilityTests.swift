import XCTest
@testable import LibraVault

/// Coverage for which reading-settings controls each book format gets.
///
/// Every case is asserted over the full `MediaFormat` set rather than the one or two
/// formats a rule "is about", because the bug these rules exist to prevent was exactly
/// an unconsidered format falling on the wrong side of a predicate: `showReadAloud`
/// originally read `format != .markdown`, silently leaving `.mobi` and `.cbz` — both
/// mapped by LibraryFileScanner — with a Read Aloud button that does nothing.
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
        XCTAssertTrue(ReaderSettingsAvailability.showReadAloud(for: .epub))
        XCTAssertTrue(ReaderSettingsAvailability.showReadAloud(for: .pdf))

        for format: MediaFormat in [.markdown, .mobi, .cbz] {
            XCTAssertFalse(
                ReaderSettingsAvailability.showReadAloud(for: format),
                "\(format) has no chapter parser — offering Read Aloud would be a dead control"
            )
        }
    }

    /// The control and the action must agree: anything `startPlayback` refuses must not
    /// render a button, and anything it accepts must. Asserted as an equivalence rather
    /// than two separate lists so the two can't drift apart.
    func testReadAloudVisibilityMatchesStartPlaybacksOwnGuard() {
        for format in allFormats where !format.isAudio {
            XCTAssertEqual(
                ReaderSettingsAvailability.showReadAloud(for: format),
                BookContentProvider.supportsChapterParsing(format),
                "\(format): the Read Aloud control and AppState.startPlayback's guard disagree"
            )
        }
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

    /// The originally-reported defect, pinned as a single case: opening a .md file must
    /// offer neither Read Aloud nor the layout toggle, while keeping font controls.
    func testMarkdownGetsFontControlsButNeitherReadAloudNorLayoutMode() {
        XCTAssertTrue(ReaderSettingsAvailability.showFontControls(for: .markdown))
        XCTAssertFalse(ReaderSettingsAvailability.showReadAloud(for: .markdown))
        XCTAssertFalse(ReaderSettingsAvailability.showLayoutMode(for: .markdown))
    }
}
