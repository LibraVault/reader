import XCTest
@testable import LibraVault

@MainActor
final class DomainBridgeTests: XCTestCase {

    private var bridge: LibravaultDomainBridge {
        LibravaultDomainBridge.shared
    }

    override func setUp() async throws {
        try await bridge.initialize()
    }

    func testInitializeLoadsMockLibrary() {
        XCTAssertEqual(bridge.allBooks.count, 5)
        XCTAssertTrue(bridge.allBooks.contains { $0.title == "The Great Gatsby" })
    }

    func testLoadBookReturnsMatchingBook() async throws {
        let book = try await bridge.loadBook(id: "2")
        XCTAssertEqual(book.title, "1984")
        XCTAssertEqual(book.author, "George Orwell")
    }

    func testLoadBookThrowsForUnknownId() async {
        do {
            _ = try await bridge.loadBook(id: "does-not-exist")
            XCTFail("Expected DomainError.bookNotFound to be thrown")
        } catch DomainError.bookNotFound(let id) {
            XCTAssertEqual(id, "does-not-exist")
        } catch {
            XCTFail("Expected DomainError.bookNotFound, got \(error)")
        }
    }

    func testAddBookmarkAppendsBookmark() async throws {
        let before = bridge.bookmarks["1"]?.count ?? 0
        try await bridge.addBookmark(bookId: "1", position: "chapter-3")
        let after = bridge.bookmarks["1"]?.count ?? 0
        XCTAssertEqual(after, before + 1)
        XCTAssertEqual(bridge.bookmarks["1"]?.last?.position, "chapter-3")
    }

    func testAddHighlightAppendsHighlight() async throws {
        let before = bridge.highlights["1"]?.count ?? 0
        try await bridge.addHighlight(bookId: "1", position: "para-2", text: "notable quote")
        let after = bridge.highlights["1"]?.count ?? 0
        XCTAssertEqual(after, before + 1)
        XCTAssertEqual(bridge.highlights["1"]?.last?.text, "notable quote")
    }

    func testUpdateProgressStoresValue() async throws {
        try await bridge.updateProgress(bookId: "3", progress: 0.75)
        XCTAssertEqual(bridge.progress["3"], 0.75)
    }
}
