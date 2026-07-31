import XCTest
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

    func testChaptersThrowsUnsupportedFormatForNonEPUB() {
        let book = BookItem(id: "1", title: "T", author: "A", format: .pdf, fileURL: URL(fileURLWithPath: "/tmp/x.pdf"), vaultId: "v1")

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
}
