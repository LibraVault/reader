import XCTest
@testable import LibraVault

final class BookmarksSheetTests: XCTestCase {

    // #331: ReaderView started storing EPUB bookmark positions as
    // "Locator:<chapterIndex>:<charOffset>" instead of "Chapter N" — displayLabel(for:)
    // is what keeps the bookmarks list showing a human-readable chapter number instead
    // of that raw locator string.
    func testLocatorPositionDisplaysAsOneBasedChapterNumber() {
        XCTAssertEqual(BookmarksSheet.displayLabel(for: "Locator:0:0"), "Chapter 1")
        XCTAssertEqual(BookmarksSheet.displayLabel(for: "Locator:4:18832"), "Chapter 5")
    }

    func testMalformedLocatorPositionFallsBackToRawString() {
        XCTAssertEqual(BookmarksSheet.displayLabel(for: "Locator:not-a-number:0"), "Locator:not-a-number:0")
        XCTAssertEqual(BookmarksSheet.displayLabel(for: "Locator:"), "Locator:")
    }

    func testNonLocatorPositionsPassThroughUnchanged() {
        XCTAssertEqual(BookmarksSheet.displayLabel(for: "Page 5"), "Page 5")
        XCTAssertEqual(BookmarksSheet.displayLabel(for: "Chapter 3"), "Chapter 3")
        XCTAssertEqual(BookmarksSheet.displayLabel(for: "scroll:0.42"), "scroll:0.42")
    }
}
