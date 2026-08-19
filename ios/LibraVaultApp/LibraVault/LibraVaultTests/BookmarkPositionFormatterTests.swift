import XCTest
@testable import LibraVault

final class BookmarkPositionFormatterTests: XCTestCase {

    // MARK: - Current EPUB format ("Locator:<chapterIndex>:<charOffset>")

    /// The exact case the pre-existing UI test testBookmarksSheetShowsAddedBookmark
    /// (LibraVaultUITests.swift) exercises: a bookmark added at the very start of
    /// the book (chapter index 0, offset 0) must display as "Chapter 1" — not the
    /// raw "Locator:0:0" ReaderView.addBookmark() now stores.
    func testLocatorAtChapterZeroOffsetZeroDisplaysAsChapterOne() {
        XCTAssertEqual(BookmarkPositionFormatter.displayText(for: "Locator:0:0"), "Chapter 1")
    }

    func testLocatorDisplaysOneBasedChapterNumberRegardlessOfCharOffset() {
        XCTAssertEqual(BookmarkPositionFormatter.displayText(for: "Locator:4:12345"), "Chapter 5")
    }

    func testLocatorWithMalformedChapterIndexFallsBackToRawString() {
        let malformed = "Locator:not-a-number:0"
        XCTAssertEqual(BookmarkPositionFormatter.displayText(for: malformed), malformed)
    }

    func testLocatorWithMissingComponentsFallsBackToRawString() {
        let malformed = "Locator:"
        XCTAssertEqual(BookmarkPositionFormatter.displayText(for: malformed), malformed)
    }

    // MARK: - Legacy / other formats — passed through unchanged

    /// Pre-#331 EPUB bookmarks, saved before the "Locator:" format existed — already
    /// human-readable as written, and must keep displaying exactly as before (this is
    /// the same string navigateToBookmark(_:)'s backward-compat fallback parses).
    func testLegacyChapterFormatPassesThroughUnchanged() {
        XCTAssertEqual(BookmarkPositionFormatter.displayText(for: "Chapter 3"), "Chapter 3")
    }

    func testPdfPageFormatPassesThroughUnchanged() {
        XCTAssertEqual(BookmarkPositionFormatter.displayText(for: "Page 12"), "Page 12")
    }

    func testMarkdownScrollFormatDisplaysAsGenericBookmarkLabel() {
        XCTAssertEqual(BookmarkPositionFormatter.displayText(for: "scroll:0.42"), "Bookmark")
    }

    func testUnrecognizedFormatFallsBackToRawString() {
        XCTAssertEqual(BookmarkPositionFormatter.displayText(for: "something-unexpected"), "something-unexpected")
    }
}
