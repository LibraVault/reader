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
}
