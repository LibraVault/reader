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

    /// A spine item as the OPF spells it (`href`) versus where its bytes actually live
    /// in the archive (`entryPath`, relative to the archive root). The two differ in
    /// real books more often than not — see `makeFixtureEPUB(items:)`.
    private struct SpineItem {
        let href: String
        let entryPath: String
        let body: String
    }

    /// Like `makeFixtureEPUB(chapterBodies:)`, but decouples the OPF href from the ZIP
    /// entry name so the resolution rules in `EPUBParser.resolveEntryPath` can be
    /// exercised against a genuinely-built archive rather than a stubbed lookup.
    private func makeFixtureEPUB(items: [SpineItem]) throws -> URL {
        let sourceDir = tempDir.appendingPathComponent("source-\(UUID().uuidString)", isDirectory: true)
        let metaInfDir = sourceDir.appendingPathComponent("META-INF", isDirectory: true)
        let oebpsDir = sourceDir.appendingPathComponent("OEBPS", isDirectory: true)
        try FileManager.default.createDirectory(at: metaInfDir, withIntermediateDirectories: true)
        try FileManager.default.createDirectory(at: oebpsDir, withIntermediateDirectories: true)

        try "application/epub+zip".write(to: sourceDir.appendingPathComponent("mimetype"), atomically: true, encoding: .utf8)

        try """
        <?xml version="1.0"?>
        <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
          <rootfiles>
            <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
          </rootfiles>
        </container>
        """.write(to: metaInfDir.appendingPathComponent("container.xml"), atomically: true, encoding: .utf8)

        let manifestItems = items.enumerated()
            .map { "<item id=\"chap\($0.offset)\" href=\"\($0.element.href)\" media-type=\"application/xhtml+xml\"/>" }
            .joined()
        let spineItems = items.indices.map { "<itemref idref=\"chap\($0)\"/>" }.joined()
        try """
        <?xml version="1.0"?>
        <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
          <manifest>\(manifestItems)</manifest>
          <spine>\(spineItems)</spine>
        </package>
        """.write(to: oebpsDir.appendingPathComponent("content.opf"), atomically: true, encoding: .utf8)

        for item in items {
            let fileURL = sourceDir.appendingPathComponent(item.entryPath)
            try FileManager.default.createDirectory(
                at: fileURL.deletingLastPathComponent(),
                withIntermediateDirectories: true
            )
            try item.body.write(to: fileURL, atomically: true, encoding: .utf8)
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

    // MARK: - Blank pages (issue #108)

    /// The reported symptom: a spine item resolved to no entry, came back as empty
    /// `Data()`, and rendered as a completely blank page between two readable ones.
    func testParseReadsPercentEncodedHrefs() throws {
        let epubURL = try makeFixtureEPUB(items: [
            SpineItem(href: "chap0.xhtml", entryPath: "OEBPS/chap0.xhtml", body: "<html><body><p>Before.</p></body></html>"),
            SpineItem(
                href: "Text/chapter%2008.xhtml",
                entryPath: "OEBPS/Text/chapter 08.xhtml",
                body: "<html><body><p>The first time I met Shane Benzie.</p></body></html>"
            ),
            SpineItem(href: "chap2.xhtml", entryPath: "OEBPS/chap2.xhtml", body: "<html><body><p>After.</p></body></html>"),
        ])

        let chapters = try EPUBParser.parse(fileURL: epubURL)

        XCTAssertEqual(chapters.count, 3)
        XCTAssertTrue(chapters[1].text.contains("Shane Benzie"), "percent-encoded href rendered blank")
    }

    func testParseReadsHrefsWithParentDirectoryTraversal() throws {
        let epubURL = try makeFixtureEPUB(items: [
            SpineItem(
                href: "../Text/foreword.xhtml",
                entryPath: "Text/foreword.xhtml",
                body: "<html><body><p>Foreword body.</p></body></html>"
            ),
        ])

        let chapters = try EPUBParser.parse(fileURL: epubURL)

        XCTAssertEqual(chapters.count, 1)
        XCTAssertTrue(chapters[0].text.contains("Foreword body."))
    }

    func testParseIgnoresFragmentAndQueryOnHref() throws {
        let epubURL = try makeFixtureEPUB(items: [
            SpineItem(
                href: "chap0.xhtml#part1",
                entryPath: "OEBPS/chap0.xhtml",
                body: "<html><body><p>Fragment target.</p></body></html>"
            ),
        ])

        let chapters = try EPUBParser.parse(fileURL: epubURL)

        XCTAssertEqual(chapters.count, 1)
        XCTAssertTrue(chapters[0].text.contains("Fragment target."))
    }

    /// A flattened archive: the OPF still says `Text/…` but the producer wrote every
    /// document to the root. Accepted only because exactly one entry has that filename.
    func testParseFallsBackToUniqueFilenameMatch() throws {
        let epubURL = try makeFixtureEPUB(items: [
            SpineItem(
                href: "Text/misplaced.xhtml",
                entryPath: "elsewhere/misplaced.xhtml",
                body: "<html><body><p>Found anyway.</p></body></html>"
            ),
        ])

        let chapters = try EPUBParser.parse(fileURL: epubURL)

        XCTAssertEqual(chapters.count, 1)
        XCTAssertTrue(chapters[0].text.contains("Found anyway."))
    }

    /// An href that genuinely resolves to nothing still yields a chapter, so the spine
    /// stays the length the page indicator promises — it just has no text, which the
    /// reader now labels rather than showing an empty screen.
    func testParseKeepsSpinePositionForUnresolvableHref() throws {
        let epubURL = try makeFixtureEPUB(items: [
            SpineItem(href: "chap0.xhtml", entryPath: "OEBPS/chap0.xhtml", body: "<html><body><p>Real.</p></body></html>"),
            SpineItem(href: "gone.xhtml", entryPath: "OEBPS/unrelated.xhtml", body: "<html><body><p>Other.</p></body></html>"),
        ])

        let chapters = try EPUBParser.parse(fileURL: epubURL)

        XCTAssertEqual(chapters.count, 2)
        XCTAssertTrue(chapters[0].text.contains("Real."))
    }

    // MARK: - resolveEntryPath

    func testResolveEntryPathDecodesAndNormalizes() {
        XCTAssertEqual(EPUBParser.resolveEntryPath("Text/chapter%2008.xhtml").first, "Text/chapter 08.xhtml")
        XCTAssertEqual(EPUBParser.resolveEntryPath("OEBPS/../Text/foo.xhtml").first, "Text/foo.xhtml")
        XCTAssertEqual(EPUBParser.resolveEntryPath("OEBPS/./foo.xhtml").first, "OEBPS/foo.xhtml")
        XCTAssertEqual(EPUBParser.resolveEntryPath("foo.xhtml?x=1#frag").first, "foo.xhtml")
        XCTAssertTrue(EPUBParser.resolveEntryPath("").isEmpty)
    }

    /// A `..` that would escape the archive root is dropped, not kept as a literal
    /// component that could never match an entry.
    func testResolveEntryPathClampsTraversalAtRoot() {
        XCTAssertEqual(EPUBParser.resolveEntryPath("../../foo.xhtml").first, "foo.xhtml")
    }

    /// An entry name may legitimately contain a `%`, so the raw spelling stays a
    /// candidate alongside the decoded one.
    func testResolveEntryPathKeepsRawSpellingAsCandidate() {
        XCTAssertTrue(EPUBParser.resolveEntryPath("100%25.xhtml").contains("100%25.xhtml"))
        XCTAssertTrue(EPUBParser.resolveEntryPath("100%25.xhtml").contains("100%.xhtml"))
    }

    // MARK: - plainText fallback

    private static let awkwardXHTML = """
    <?xml version="1.0" encoding="utf-8"?>
    <!DOCTYPE html>
    <html xmlns="http://www.w3.org/1999/xhtml"><head><style>p { color: red }</style></head><body>
    <h1>Foreword</h1><p>Held&nbsp;up his elastic toy &amp; grinned.</p><p>Second para.</p>
    </body></html>
    """

    /// The fallback taken when `NSAttributedString`'s importer answers `nil` — tested
    /// directly, since whether the importer *actually* rejects a given document is a
    /// WebKit implementation detail that varies by OS version. What must hold is that
    /// this path yields readable prose rather than the empty string that produced a
    /// blank page (issue #108).
    func testStrippingTagsProducesProseWithoutMarkup() {
        let text = EPUBParser.strippingTags(from: Data(Self.awkwardXHTML.utf8))

        XCTAssertTrue(text.contains("Foreword"))
        XCTAssertTrue(text.contains("Held up his elastic toy & grinned."))
        XCTAssertTrue(text.contains("Second para."))
        XCTAssertFalse(text.contains("<"), "tags leaked into reader text: \(text)")
        XCTAssertFalse(text.contains("&nbsp;"))
        XCTAssertFalse(text.contains("color: red"), "stylesheet leaked into reader text: \(text)")
    }

    /// Block boundaries have to survive as newlines, or paragraphs run together into
    /// one unreadable wall of text.
    func testStrippingTagsSeparatesBlockElements() {
        let text = EPUBParser.strippingTags(from: Data("<p>One.</p><p>Two.</p>".utf8))

        XCTAssertEqual(text, "One.\nTwo.")
    }

    /// Whichever path `plainText` takes, a document containing prose must never come
    /// back empty — that is the blank page, restated as an invariant.
    func testPlainTextIsNeverEmptyForDocumentWithProse() {
        XCTAssertFalse(EPUBParser.plainText(fromHTML: Data(Self.awkwardXHTML.utf8)).isEmpty)
    }

    func testPlainTextReturnsEmptyForNoData() {
        XCTAssertEqual(EPUBParser.plainText(fromHTML: Data()), "")
    }
}
