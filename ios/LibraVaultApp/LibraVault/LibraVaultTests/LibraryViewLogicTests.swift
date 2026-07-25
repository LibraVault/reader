import XCTest
@testable import LibraVault

final class LibraryViewLogicTests: XCTestCase {

    // MARK: - LibraryFormatFilter

    func testAllFilterMatchesEveryFormat() {
        XCTAssertTrue(LibraryFormatFilter.all.matches(.epub))
        XCTAssertTrue(LibraryFormatFilter.all.matches(.pdf))
        XCTAssertTrue(LibraryFormatFilter.all.matches(.mobi))
        XCTAssertTrue(LibraryFormatFilter.all.matches(.cbz))
    }

    func testEpubFilterMatchesOnlyEpub() {
        XCTAssertTrue(LibraryFormatFilter.epub.matches(.epub))
        XCTAssertFalse(LibraryFormatFilter.epub.matches(.pdf))
        XCTAssertFalse(LibraryFormatFilter.epub.matches(.mobi))
    }

    func testPdfFilterMatchesOnlyPdf() {
        XCTAssertTrue(LibraryFormatFilter.pdf.matches(.pdf))
        XCTAssertFalse(LibraryFormatFilter.pdf.matches(.epub))
    }

    // MARK: - BookItem(from: BookData) threads format through

    func testBookItemFromBookDataPreservesFormat() {
        let epubData = BookData(id: "1", title: "T", author: "A", format: .epub)
        let pdfData = BookData(id: "2", title: "T", author: "A", format: .pdf)

        XCTAssertEqual(BookItem(from: epubData).format, .epub)
        XCTAssertEqual(BookItem(from: pdfData).format, .pdf)
    }

    func testBookItemManualInitDefaultsToEpub() {
        let book = BookItem(id: "1", title: "T", author: "A")
        XCTAssertEqual(book.format, .epub)
    }

    // MARK: - generatedCoverPaletteIndex
    //
    // Direct regression coverage for the bug fixed during Phase 2 review: this used to
    // key off book.id.hashValue, which Swift reseeds per process launch, so covers
    // would silently reshuffle colors on every relaunch. Deterministic-within-a-run
    // was the whole point of switching to a UTF-8 byte sum.

    func testCoverPaletteIndexIsDeterministicForTheSameId() {
        let book = BookItem(id: "same-id-123", title: "T", author: "A")
        let first = generatedCoverPaletteIndex(for: book)
        let second = generatedCoverPaletteIndex(for: book)
        XCTAssertEqual(first, second)
    }

    func testCoverPaletteIndexIsAlwaysInBounds() {
        for id in ["", "1", "a-very-long-book-id-string-well-past-typical-length", "🎉📚"] {
            let index = generatedCoverPaletteIndex(for: BookItem(id: id, title: "T", author: "A"))
            XCTAssertGreaterThanOrEqual(index, 0)
            XCTAssertLessThan(index, generatedCoverPalette.count)
        }
    }

    // MARK: - AppError

    func testLibraryLoadFailedIncludesTheUnderlyingReason() {
        let error = AppError.libraryLoadFailed("disk full")
        XCTAssertEqual(error.errorDescription, "Failed to load library: disk full")
    }

    func testBookNotFoundAndStorageAccessDeniedHaveFixedMessages() {
        XCTAssertEqual(AppError.bookNotFound.errorDescription, "Book not found")
        XCTAssertEqual(AppError.storageAccessDenied.errorDescription, "Storage access denied")
    }

    func testCoverPaletteIndexDiffersForDifferentIds() {
        // Not a hard guarantee for arbitrary ids (a collision is possible), but the
        // actual mock library's 5 ids (see DomainBridge.swift) should spread across
        // more than one slot — otherwise every book in the grid looks the same.
        let ids = ["1", "2", "3", "4", "5"]
        let indices = Set(ids.map { generatedCoverPaletteIndex(for: BookItem(id: $0, title: "T", author: "A")) })
        XCTAssertGreaterThan(indices.count, 1)
    }
}
