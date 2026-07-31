import XCTest
import ZIPFoundation
@testable import LibraVault

final class EPUBParserTests: XCTestCase {
    private var tempDir: URL!

    override func setUpWithError() throws {
        tempDir = FileManager.default.temporaryDirectory.appendingPathComponent("EPUBParserTests-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: tempDir)
    }

    /// Builds a real, minimal, valid EPUB — zipped with the same `FileManager.zipItem`
    /// mechanism a real EPUB is packaged with — containing one XHTML chapter per
    /// entry in `chapterBodies`, in spine order.
    private func makeFixtureEPUB(chapterBodies: [String]) throws -> URL {
        let sourceDir = tempDir.appendingPathComponent("source-\(UUID().uuidString)", isDirectory: true)
        let oebpsDir = sourceDir.appendingPathComponent("OEBPS", isDirectory: true)
        let metaInfDir = sourceDir.appendingPathComponent("META-INF", isDirectory: true)
        try FileManager.default.createDirectory(at: oebpsDir, withIntermediateDirectories: true)
        try FileManager.default.createDirectory(at: metaInfDir, withIntermediateDirectories: true)

        try "application/epub+zip".write(to: sourceDir.appendingPathComponent("mimetype"), atomically: true, encoding: .utf8)

        try """
        <?xml version="1.0"?>
        <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
          <rootfiles>
            <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
          </rootfiles>
        </container>
        """.write(to: metaInfDir.appendingPathComponent("container.xml"), atomically: true, encoding: .utf8)

        let manifestItems = chapterBodies.indices
            .map { "<item id=\"chap\($0)\" href=\"chap\($0).xhtml\" media-type=\"application/xhtml+xml\"/>" }
            .joined()
        let spineItems = chapterBodies.indices.map { "<itemref idref=\"chap\($0)\"/>" }.joined()
        try """
        <?xml version="1.0"?>
        <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
          <manifest>\(manifestItems)</manifest>
          <spine>\(spineItems)</spine>
        </package>
        """.write(to: oebpsDir.appendingPathComponent("content.opf"), atomically: true, encoding: .utf8)

        for (index, body) in chapterBodies.enumerated() {
            try "<html><body>\(body)</body></html>".write(
                to: oebpsDir.appendingPathComponent("chap\(index).xhtml"),
                atomically: true,
                encoding: .utf8
            )
        }

        let epubURL = tempDir.appendingPathComponent("fixture-\(UUID().uuidString).epub")
        try FileManager().zipItem(at: sourceDir, to: epubURL, shouldKeepParent: false)
        return epubURL
    }

    func testParseReturnsChaptersInSpineOrder() throws {
        let epubURL = try makeFixtureEPUB(chapterBodies: [
            "<h1>Chapter One</h1><p>First chapter text.</p>",
            "<h1>Chapter Two</h1><p>Second chapter text.</p>",
        ])

        let chapters = try EPUBParser.parse(fileURL: epubURL)

        XCTAssertEqual(chapters.count, 2)
        XCTAssertTrue(chapters[0].text.contains("First chapter text."))
        XCTAssertTrue(chapters[1].text.contains("Second chapter text."))
    }

    func testParseStripsHTMLTagsFromChapterText() throws {
        let epubURL = try makeFixtureEPUB(chapterBodies: [
            "<h1>Title</h1><p>Some <b>bold</b> text.</p>",
        ])

        let chapters = try EPUBParser.parse(fileURL: epubURL)

        XCTAssertEqual(chapters.count, 1)
        XCTAssertFalse(chapters[0].text.contains("<"))
        XCTAssertTrue(chapters[0].text.contains("bold"))
    }

    func testParseThrowsForMissingContainer() throws {
        let sourceDir = tempDir.appendingPathComponent("empty-source", isDirectory: true)
        try FileManager.default.createDirectory(at: sourceDir, withIntermediateDirectories: true)
        try "not an epub".write(to: sourceDir.appendingPathComponent("readme.txt"), atomically: true, encoding: .utf8)
        let epubURL = tempDir.appendingPathComponent("not-an-epub.epub")
        try FileManager().zipItem(at: sourceDir, to: epubURL, shouldKeepParent: false)

        XCTAssertThrowsError(try EPUBParser.parse(fileURL: epubURL)) { error in
            XCTAssertEqual(error as? EPUBParser.ParseError, .entryNotFound("META-INF/container.xml"))
        }
    }

    func testParseThrowsForInvalidArchive() throws {
        let notAZipURL = tempDir.appendingPathComponent("not-a-zip.epub")
        try Data("this is not a zip file".utf8).write(to: notAZipURL)

        XCTAssertThrowsError(try EPUBParser.parse(fileURL: notAZipURL)) { error in
            XCTAssertEqual(error as? EPUBParser.ParseError, .invalidArchive)
        }
    }
}
