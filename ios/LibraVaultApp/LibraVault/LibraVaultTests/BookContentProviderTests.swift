import XCTest
import UIKit
import PDFKit
import ZIPFoundation
@testable import LibraVault

final class BookContentProviderTests: XCTestCase {
    private var tempDir: URL!

    override func setUpWithError() throws {
        tempDir = FileManager.default.temporaryDirectory.appendingPathComponent("BookContentProviderTests-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: tempDir)
    }

    private func makeIsolatedPersistence() -> VaultPersistence {
        VaultPersistence(defaults: UserDefaults(suiteName: "BookContentProviderTests.\(UUID().uuidString)")!)
    }

    /// A minimal, valid, one-chapter EPUB placed inside a real vault folder, plus the
    /// persistence + vault + book plumbing needed to reopen it the way ReaderView does.
    private func makeVaultAndBook(persistence: VaultPersistence) throws -> (vault: Vault, book: BookItem) {
        let vaultFolder = tempDir.appendingPathComponent("vault-\(UUID().uuidString)", isDirectory: true)
        let oebpsDir = vaultFolder.appendingPathComponent("OEBPS", isDirectory: true)
        let metaInfDir = vaultFolder.appendingPathComponent("META-INF", isDirectory: true)
        try FileManager.default.createDirectory(at: oebpsDir, withIntermediateDirectories: true)
        try FileManager.default.createDirectory(at: metaInfDir, withIntermediateDirectories: true)

        try """
        <?xml version="1.0"?>
        <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
          <rootfiles>
            <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
          </rootfiles>
        </container>
        """.write(to: metaInfDir.appendingPathComponent("container.xml"), atomically: true, encoding: .utf8)

        try """
        <?xml version="1.0"?>
        <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
          <manifest><item id="chap0" href="chap0.xhtml" media-type="application/xhtml+xml"/></manifest>
          <spine><itemref idref="chap0"/></spine>
        </package>
        """.write(to: oebpsDir.appendingPathComponent("content.opf"), atomically: true, encoding: .utf8)

        try "<html><body><p>Real chapter text.</p></body></html>"
            .write(to: oebpsDir.appendingPathComponent("chap0.xhtml"), atomically: true, encoding: .utf8)

        let epubURL = tempDir.appendingPathComponent("Fixture-\(UUID().uuidString).epub")
        try FileManager().zipItem(at: vaultFolder, to: epubURL, shouldKeepParent: false)

        // The vault must point at the *directory containing* the epub (mirroring a
        // real "Add Vault" pick), not the epub itself, and the epub file needs to live
        // inside it for LibraryFileScanner-style access — so re-home it one level up.
        let realVaultFolder = tempDir.appendingPathComponent("realvault-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: realVaultFolder, withIntermediateDirectories: true)
        let finalEpubURL = realVaultFolder.appendingPathComponent("Fixture.epub")
        try FileManager.default.moveItem(at: epubURL, to: finalEpubURL)

        let vault = try persistence.makeVault(from: realVaultFolder)
        persistence.save([vault])

        let book = BookItem(
            id: "vault:\(vault.id):\(finalEpubURL.path)",
            title: "Fixture",
            author: "",
            format: .epub,
            fileURL: finalEpubURL,
            vaultId: vault.id
        )
        return (vault, book)
    }

    func testChaptersReturnsRealParsedContentForEPUB() throws {
        let persistence = makeIsolatedPersistence()
        let (_, book) = try makeVaultAndBook(persistence: persistence)

        let chapters = try BookContentProvider.chapters(for: book, vaultPersistence: persistence)

        XCTAssertEqual(chapters.count, 1)
        XCTAssertTrue(chapters[0].text.contains("Real chapter text."))
    }

    /// A real, single-page PDF (drawn via UIGraphicsPDFRenderer, the standard Apple
    /// PDF-authoring API) placed inside a real vault folder, mirroring
    /// makeVaultAndBook's EPUB setup — proves the .pdf branch of the format switch
    /// actually reaches PDFParser, not just that other formats are rejected.
    private func makePDFVaultAndBook(persistence: VaultPersistence) throws -> (vault: Vault, book: BookItem) {
        let vaultFolder = tempDir.appendingPathComponent("pdf-vault-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: vaultFolder, withIntermediateDirectories: true)

        let pdfURL = vaultFolder.appendingPathComponent("Fixture.pdf")
        let renderer = UIGraphicsPDFRenderer(bounds: CGRect(x: 0, y: 0, width: 612, height: 792))
        try renderer.writePDF(to: pdfURL) { context in
            context.beginPage()
            ("Real PDF page text." as NSString).draw(
                at: CGPoint(x: 20, y: 20),
                withAttributes: [.font: UIFont.systemFont(ofSize: 18)]
            )
        }

        let vault = try persistence.makeVault(from: vaultFolder)
        persistence.save([vault])

        let book = BookItem(
            id: "vault:\(vault.id):\(pdfURL.path)",
            title: "Fixture",
            author: "",
            format: .pdf,
            fileURL: pdfURL,
            vaultId: vault.id
        )
        return (vault, book)
    }

    func testChaptersReturnsRealParsedContentForPDF() throws {
        let persistence = makeIsolatedPersistence()
        let (_, book) = try makePDFVaultAndBook(persistence: persistence)

        let chapters = try BookContentProvider.chapters(for: book, vaultPersistence: persistence)

        XCTAssertEqual(chapters.count, 1)
        XCTAssertTrue(chapters[0].text.contains("Real PDF page text."))
    }

    // MARK: - openPDFDocument

    /// Proves the real page-rendering path (PDFReaderContent's PDFKit PDFView) can
    /// actually open a vault PDF and read pages back through it — the regression this
    /// guards against is PDFs silently falling back to the extracted-text reflow path
    /// (BookContentProvider.chapters/PDFParser) that used to be the *only* on-screen
    /// PDF experience on iOS.
    func testOpenPDFDocumentReturnsARealReadableDocument() throws {
        let persistence = makeIsolatedPersistence()
        let (_, book) = try makePDFVaultAndBook(persistence: persistence)

        let (document, endAccess) = try BookContentProvider.openPDFDocument(for: book, vaultPersistence: persistence)
        defer { endAccess() }

        XCTAssertEqual(document.pageCount, 1)
        XCTAssertNotNil(document.page(at: 0))
    }

    /// The vault's security scope must still be open immediately after
    /// openPDFDocument returns — PDFKit reads page data lazily, not upfront like
    /// chapters(for:) — so a page opened *after* return must still be fully readable
    /// (PDFPage.string, which requires the file's data) until endAccess() is called.
    func testOpenPDFDocumentKeepsVaultAccessOpenUntilEndAccessIsCalled() throws {
        let persistence = makeIsolatedPersistence()
        let (_, book) = try makePDFVaultAndBook(persistence: persistence)

        let (document, endAccess) = try BookContentProvider.openPDFDocument(for: book, vaultPersistence: persistence)

        let text = document.page(at: 0)?.string
        XCTAssertEqual(text?.contains("Real PDF page text."), true)

        endAccess()
    }

    func testOpenPDFDocumentThrowsUnsupportedFormatForNonPDF() throws {
        let persistence = makeIsolatedPersistence()
        let (_, epubBook) = try makeVaultAndBook(persistence: persistence)

        XCTAssertThrowsError(try BookContentProvider.openPDFDocument(for: epubBook, vaultPersistence: persistence)) { error in
            XCTAssertEqual(error as? BookContentProvider.ContentError, .unsupportedFormat)
        }
    }

    func testOpenPDFDocumentThrowsMissingFileReferenceWhenFileURLIsNil() {
        let book = BookItem(id: "1", title: "T", author: "A", format: .pdf)

        XCTAssertThrowsError(try BookContentProvider.openPDFDocument(for: book, vaultPersistence: makeIsolatedPersistence())) { error in
            XCTAssertEqual(error as? BookContentProvider.ContentError, .missingFileReference)
        }
    }

    func testOpenPDFDocumentThrowsVaultUnavailableWhenVaultIsNotPersisted() {
        let book = BookItem(
            id: "1", title: "T", author: "A", format: .pdf,
            fileURL: URL(fileURLWithPath: "/tmp/x.pdf"), vaultId: "does-not-exist"
        )

        XCTAssertThrowsError(try BookContentProvider.openPDFDocument(for: book, vaultPersistence: makeIsolatedPersistence())) { error in
            XCTAssertEqual(error as? BookContentProvider.ContentError, .vaultUnavailable)
        }
    }

    func testOpenPDFDocumentThrowsInvalidDocumentForMalformedFile() throws {
        let persistence = makeIsolatedPersistence()
        let vaultFolder = tempDir.appendingPathComponent("bad-pdf-vault-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: vaultFolder, withIntermediateDirectories: true)
        let pdfURL = vaultFolder.appendingPathComponent("not-a-pdf.pdf")
        try Data("this is not a pdf".utf8).write(to: pdfURL)

        let vault = try persistence.makeVault(from: vaultFolder)
        persistence.save([vault])
        let book = BookItem(id: "1", title: "T", author: "A", format: .pdf, fileURL: pdfURL, vaultId: vault.id)

        XCTAssertThrowsError(try BookContentProvider.openPDFDocument(for: book, vaultPersistence: persistence)) { error in
            XCTAssertEqual(error as? PDFParser.ParseError, .invalidDocument)
        }
    }

    func testChaptersThrowsUnsupportedFormatForMobi() {
        let book = BookItem(id: "1", title: "T", author: "A", format: .mobi, fileURL: URL(fileURLWithPath: "/tmp/x.mobi"), vaultId: "v1")

        XCTAssertThrowsError(try BookContentProvider.chapters(for: book, vaultPersistence: makeIsolatedPersistence())) { error in
            XCTAssertEqual(error as? BookContentProvider.ContentError, .unsupportedFormat)
        }
    }

    func testChaptersThrowsUnsupportedFormatForCbz() {
        let book = BookItem(id: "1", title: "T", author: "A", format: .cbz, fileURL: URL(fileURLWithPath: "/tmp/x.cbz"), vaultId: "v1")

        XCTAssertThrowsError(try BookContentProvider.chapters(for: book, vaultPersistence: makeIsolatedPersistence())) { error in
            XCTAssertEqual(error as? BookContentProvider.ContentError, .unsupportedFormat)
        }
    }

    func testChaptersThrowsUnsupportedFormatForAudio() {
        let book = BookItem(id: "1", title: "T", author: "A", format: .mp3, fileURL: URL(fileURLWithPath: "/tmp/x.mp3"), vaultId: "v1")

        XCTAssertThrowsError(try BookContentProvider.chapters(for: book, vaultPersistence: makeIsolatedPersistence())) { error in
            XCTAssertEqual(error as? BookContentProvider.ContentError, .unsupportedFormat)
        }
    }

    func testChaptersThrowsMissingFileReferenceWhenFileURLIsNil() {
        let book = BookItem(id: "1", title: "T", author: "A", format: .epub)

        XCTAssertThrowsError(try BookContentProvider.chapters(for: book, vaultPersistence: makeIsolatedPersistence())) { error in
            XCTAssertEqual(error as? BookContentProvider.ContentError, .missingFileReference)
        }
    }

    func testChaptersThrowsVaultUnavailableWhenVaultIsNotPersisted() {
        let book = BookItem(
            id: "1", title: "T", author: "A", format: .epub,
            fileURL: URL(fileURLWithPath: "/tmp/x.epub"), vaultId: "does-not-exist"
        )

        XCTAssertThrowsError(try BookContentProvider.chapters(for: book, vaultPersistence: makeIsolatedPersistence())) { error in
            XCTAssertEqual(error as? BookContentProvider.ContentError, .vaultUnavailable)
        }
    }

    // MARK: - markdownSource / markdownAssetData

    /// A vault folder containing a Markdown file plus a sibling image and a
    /// subfolder image, mirroring the real relative-reference scenarios
    /// MarkdownAssetResolver's Android counterpart is tested against.
    private func makeMarkdownVaultAndBook(persistence: VaultPersistence) throws -> (vault: Vault, book: BookItem) {
        let vaultFolder = tempDir.appendingPathComponent("md-vault-\(UUID().uuidString)", isDirectory: true)
        let imagesDir = vaultFolder.appendingPathComponent("images", isDirectory: true)
        try FileManager.default.createDirectory(at: imagesDir, withIntermediateDirectories: true)

        let markdownURL = vaultFolder.appendingPathComponent("notes.md")
        try "# Title\n\n![](./sibling.png)\n\n![](images/nested.png)".write(to: markdownURL, atomically: true, encoding: .utf8)
        try Data([0x01, 0x02, 0x03]).write(to: vaultFolder.appendingPathComponent("sibling.png"))
        try Data([0x04, 0x05]).write(to: imagesDir.appendingPathComponent("nested.png"))

        let vault = try persistence.makeVault(from: vaultFolder)
        persistence.save([vault])

        let book = BookItem(
            id: "vault:\(vault.id):\(markdownURL.path)",
            title: "notes",
            author: "",
            format: .markdown,
            fileURL: markdownURL,
            vaultId: vault.id
        )
        return (vault, book)
    }

    func testMarkdownSourceReturnsRealFileContent() throws {
        let persistence = makeIsolatedPersistence()
        let (_, book) = try makeMarkdownVaultAndBook(persistence: persistence)

        let source = try BookContentProvider.markdownSource(for: book, vaultPersistence: persistence)

        XCTAssertTrue(source.contains("# Title"))
    }

    func testMarkdownAssetDataResolvesASiblingFile() throws {
        let persistence = makeIsolatedPersistence()
        let (_, book) = try makeMarkdownVaultAndBook(persistence: persistence)

        let data = try BookContentProvider.markdownAssetData(for: book, relativePath: "./sibling.png", vaultPersistence: persistence)

        XCTAssertEqual(data, Data([0x01, 0x02, 0x03]))
    }

    func testMarkdownAssetDataResolvesANestedSubfolderFile() throws {
        let persistence = makeIsolatedPersistence()
        let (_, book) = try makeMarkdownVaultAndBook(persistence: persistence)

        let data = try BookContentProvider.markdownAssetData(for: book, relativePath: "images/nested.png", vaultPersistence: persistence)

        XCTAssertEqual(data, Data([0x04, 0x05]))
    }

    func testMarkdownAssetDataThrowsForAMissingFile() throws {
        let persistence = makeIsolatedPersistence()
        let (_, book) = try makeMarkdownVaultAndBook(persistence: persistence)

        XCTAssertThrowsError(try BookContentProvider.markdownAssetData(for: book, relativePath: "missing.png", vaultPersistence: persistence))
    }

    func testMarkdownAssetDataRejectsHttpUrls() throws {
        let persistence = makeIsolatedPersistence()
        let (_, book) = try makeMarkdownVaultAndBook(persistence: persistence)

        XCTAssertThrowsError(
            try BookContentProvider.markdownAssetData(for: book, relativePath: "https://example.com/img.png", vaultPersistence: persistence)
        ) { error in
            XCTAssertEqual(error as? BookContentProvider.ContentError, .unsupportedFormat)
        }
    }
}
