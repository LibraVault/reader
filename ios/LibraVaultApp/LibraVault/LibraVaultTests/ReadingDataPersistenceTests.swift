import XCTest
@testable import LibraVault

final class ReadingDataPersistenceTests: XCTestCase {

    private func makeIsolatedDefaults() -> UserDefaults {
        let suiteName = "ReadingDataPersistenceTests.\(UUID().uuidString)"
        return UserDefaults(suiteName: suiteName)!
    }

    // MARK: - Bookmarks

    func testLoadBookmarksReturnsEmptyWhenNothingSaved() {
        let persistence = ReadingDataPersistence(defaults: makeIsolatedDefaults())
        XCTAssertEqual(persistence.loadBookmarks(), [:])
    }

    func testSaveThenLoadRoundTripsBookmarks() {
        let defaults = makeIsolatedDefaults()
        let bookmark = Bookmark(id: "b1", position: "chapter-2", note: "notable", createdAt: Date())

        ReadingDataPersistence(defaults: defaults).save(bookmarks: ["book-1": [bookmark]])

        let loaded = ReadingDataPersistence(defaults: defaults).loadBookmarks()
        XCTAssertEqual(loaded["book-1"]?.first?.id, "b1")
        XCTAssertEqual(loaded["book-1"]?.first?.note, "notable")
    }

    // MARK: - Highlights

    func testLoadHighlightsReturnsEmptyWhenNothingSaved() {
        let persistence = ReadingDataPersistence(defaults: makeIsolatedDefaults())
        XCTAssertEqual(persistence.loadHighlights(), [:])
    }

    func testSaveThenLoadRoundTripsHighlights() {
        let defaults = makeIsolatedDefaults()
        let highlight = Highlight(id: "h1", position: "para-4", text: "quote", colorHex: "FFFF00", note: nil, createdAt: Date())

        ReadingDataPersistence(defaults: defaults).save(highlights: ["book-1": [highlight]])

        let loaded = ReadingDataPersistence(defaults: defaults).loadHighlights()
        XCTAssertEqual(loaded["book-1"]?.first?.text, "quote")
    }

    // MARK: - Progress

    func testLoadProgressReturnsEmptyWhenNothingSaved() {
        let persistence = ReadingDataPersistence(defaults: makeIsolatedDefaults())
        XCTAssertEqual(persistence.loadProgress(), [:])
    }

    func testSaveThenLoadRoundTripsProgress() {
        let defaults = makeIsolatedDefaults()

        ReadingDataPersistence(defaults: defaults).save(progress: ["book-1": 0.42])

        XCTAssertEqual(ReadingDataPersistence(defaults: defaults).loadProgress()["book-1"], 0.42)
    }
}
