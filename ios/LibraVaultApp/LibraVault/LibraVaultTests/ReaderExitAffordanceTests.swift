import XCTest
@testable import LibraVault

/// Coverage for #293: the fallback exit affordance must appear exactly when Markdown's
/// center-tap toggle could plausibly have failed to bring the toolbar (and back button)
/// back, and never for formats that aren't at risk.
final class ReaderExitAffordanceTests: XCTestCase {

    /// Every format the app can produce — see ReaderSettingsAvailabilityTests for why
    /// this is kept explicit rather than derived.
    private let allFormats: [MediaFormat] = [
        .pdf, .epub, .markdown, .mobi, .cbz, .mp3, .m4b, .aac, .flac, .ogg, .opus,
    ]

    func testShownForMarkdownWhenToolbarIsHidden() {
        XCTAssertTrue(ReaderExitAffordance.isNeeded(format: .markdown, showToolbar: false))
    }

    func testHiddenForMarkdownWhenToolbarIsVisible() {
        XCTAssertFalse(ReaderExitAffordance.isNeeded(format: .markdown, showToolbar: true))
    }

    func testHiddenForEveryOtherFormatRegardlessOfToolbarState() {
        for format in allFormats where format != .markdown {
            XCTAssertFalse(
                ReaderExitAffordance.isNeeded(format: format, showToolbar: true),
                "\(format) toolbar visible"
            )
            XCTAssertFalse(
                ReaderExitAffordance.isNeeded(format: format, showToolbar: false),
                "\(format) toolbar hidden"
            )
        }
    }
}
