import XCTest
@testable import LibraVault

/// Reader and Player both depend on this being consistent (see MockChapterContent.swift's
/// doc comment) — a bug here would silently desync the two features' idea of "chapter 3",
/// so it gets its own direct coverage rather than relying only on the UI tests that
/// happen to check specific chapter text appears on screen.
final class MockChapterContentTests: XCTestCase {

    func testCountMatchesChaptersArray() {
        XCTAssertEqual(MockChapterContent.count, MockChapterContent.chapters.count)
        XCTAssertEqual(MockChapterContent.count, 5)
    }

    func testTextForChapterReturnsMatchingChapter() {
        XCTAssertTrue(MockChapterContent.text(for: 1).hasPrefix("Chapter 1: The Beginning"))
        XCTAssertTrue(MockChapterContent.text(for: 5).hasPrefix("Chapter 5: The Choice"))
    }

    func testTextForChapterWrapsAroundPastTheLastChapter() {
        // ReaderView/AppState never actually call this out of range (both clamp first),
        // but text(for:) itself wraps via modulo — worth pinning down since a future
        // caller might rely on that instead of clamping.
        XCTAssertEqual(MockChapterContent.text(for: 6), MockChapterContent.text(for: 1))
    }

    func testTitleExtractsFirstLine() {
        XCTAssertEqual(MockChapterContent.title(for: 1), "Chapter 1: The Beginning")
        XCTAssertEqual(MockChapterContent.title(for: 3), "Chapter 3: The Discovery")
    }

    func testAllChapterTitlesAreDistinct() {
        let titles = (1...MockChapterContent.count).map(MockChapterContent.title(for:))
        XCTAssertEqual(Set(titles).count, titles.count)
    }
}
