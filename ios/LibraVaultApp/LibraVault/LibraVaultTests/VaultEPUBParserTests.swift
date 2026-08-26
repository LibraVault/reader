import XCTest
@testable import LibraVault

/// `VaultEPUBParser` is `EPUBParser`'s in-memory (`Data`, not file `URL`)
/// counterpart — these tests build the same kind of real, zipped EPUB fixture
/// `EPUBParserTests` does, then read it into `Data` before parsing, so the
/// only thing under test really is different (in-memory archive access), not
/// the fixture-building itself.
final class VaultEPUBParserTests: XCTestCase {
    private var tempDir: URL!

    override func setUpWithError() throws {
        tempDir = FileManager.default.temporaryDirectory.appendingPathComponent("VaultEPUBParserTests-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: tempDir)
    }

    private func makeFixtureEPUBData(chapterBodies: [String]) throws -> Data {
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
        return try Data(contentsOf: epubURL)
    }

    func testParseReturnsChaptersInSpineOrder() throws {
        let data = try makeFixtureEPUBData(chapterBodies: [
            "<h1>Chapter One</h1><p>First chapter text.</p>",
            "<h1>Chapter Two</h1><p>Second chapter text.</p>",
        ])

        let chapters = try VaultEPUBParser.parse(data: data)

        XCTAssertEqual(chapters.count, 2)
        XCTAssertTrue(chapters[0].text.contains("First chapter text."))
        XCTAssertTrue(chapters[1].text.contains("Second chapter text."))
    }

    func testParseStripsHTMLTagsFromChapterText() throws {
        let data = try makeFixtureEPUBData(chapterBodies: [
            "<h1>Title</h1><p>Some <b>bold</b> text.</p>",
        ])

        let chapters = try VaultEPUBParser.parse(data: data)

        XCTAssertEqual(chapters.count, 1)
        XCTAssertFalse(chapters[0].text.contains("<"))
        XCTAssertTrue(chapters[0].text.contains("bold"))
    }

    /// Before #635, `VaultEPUBParser` never called `EPUBParser.parseBlocks` at all —
    /// `blocks`/`segments` stayed empty forever regardless of source markup, silently
    /// keeping vault EPUBs on the old flat-text-only narration path. This is the
    /// regression guard: a vault EPUB with real structure must produce non-empty
    /// `blocks` and `segments`, with emphasis preserved, the same as a non-vault one.
    func testParseCarriesBlocksAndNarrationSegments() throws {
        let data = try makeFixtureEPUBData(chapterBodies: [
            "<h1>Title</h1><p>Plain <b>bold</b> text.</p>",
        ])

        let chapters = try VaultEPUBParser.parse(data: data)

        XCTAssertEqual(chapters.count, 1)
        XCTAssertFalse(chapters[0].blocks.isEmpty)
        XCTAssertEqual(chapters[0].segments, [
            NarrationSegment(text: "Title", kind: .heading, pauseBefore: .paragraph),
            NarrationSegment(text: "Plain ", kind: .plain, pauseBefore: .paragraph),
            NarrationSegment(text: "bold", kind: .emphasis, pauseBefore: .none),
            NarrationSegment(text: " text.", kind: .plain, pauseBefore: .none),
        ])
    }

    func testParseThrowsForMissingContainer() throws {
        let sourceDir = tempDir.appendingPathComponent("empty-source", isDirectory: true)
        try FileManager.default.createDirectory(at: sourceDir, withIntermediateDirectories: true)
        try "not an epub".write(to: sourceDir.appendingPathComponent("readme.txt"), atomically: true, encoding: .utf8)
        let epubURL = tempDir.appendingPathComponent("not-an-epub.epub")
        try FileManager().zipItem(at: sourceDir, to: epubURL, shouldKeepParent: false)
        let data = try Data(contentsOf: epubURL)

        XCTAssertThrowsError(try VaultEPUBParser.parse(data: data)) { error in
            XCTAssertEqual(error as? EPUBParser.ParseError, .entryNotFound("META-INF/container.xml"))
        }
    }

    func testParseThrowsForInvalidArchive() throws {
        let data = Data("this is not a zip file".utf8)

        XCTAssertThrowsError(try VaultEPUBParser.parse(data: data)) { error in
            XCTAssertEqual(error as? EPUBParser.ParseError, .invalidArchive)
        }
    }
}
