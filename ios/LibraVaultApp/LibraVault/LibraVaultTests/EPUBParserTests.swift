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
    /// entry in `chapterBodies`, in spine order. `includeEncryption` writes a stub
    /// `META-INF/encryption.xml`, the OCF marker for a DRM-protected archive (issue #351).
    private func makeFixtureEPUB(chapterBodies: [String], includeEncryption: Bool = false) throws -> URL {
        let sourceDir = tempDir.appendingPathComponent("source-\(UUID().uuidString)", isDirectory: true)
        let oebpsDir = sourceDir.appendingPathComponent("OEBPS", isDirectory: true)
        let metaInfDir = sourceDir.appendingPathComponent("META-INF", isDirectory: true)
        try FileManager.default.createDirectory(at: oebpsDir, withIntermediateDirectories: true)
        try FileManager.default.createDirectory(at: metaInfDir, withIntermediateDirectories: true)

        try "application/epub+zip".write(to: sourceDir.appendingPathComponent("mimetype"), atomically: true, encoding: .utf8)

        if includeEncryption {
            try """
            <?xml version="1.0"?>
            <encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <EncryptedData xmlns="http://www.w3.org/2001/04/xmlenc#">
                <EncryptionMethod Algorithm="http://ns.adobe.com/adept"/>
              </EncryptedData>
            </encryption>
            """.write(to: metaInfDir.appendingPathComponent("encryption.xml"), atomically: true, encoding: .utf8)
        }

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
    /// `extraFiles` (archive-root-relative path -> bytes) drops non-XHTML entries into
    /// the archive too, e.g. an image an `<img src>` references.
    private func makeFixtureEPUB(items: [SpineItem], extraFiles: [String: Data] = [:]) throws -> URL {
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

        for (path, data) in extraFiles {
            let fileURL = sourceDir.appendingPathComponent(path)
            try FileManager.default.createDirectory(
                at: fileURL.deletingLastPathComponent(),
                withIntermediateDirectories: true
            )
            try data.write(to: fileURL)
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

    /// A DRM-protected EPUB (Adobe ADEPT, LCP, …) must fail cleanly with `.drmProtected`
    /// rather than being parsed as if its ciphertext spine items were plaintext XHTML —
    /// the reported symptom was garbled/overlapping text instead of an honest error
    /// (issue #351).
    func testParseThrowsDrmProtectedWhenEncryptionXmlIsPresent() throws {
        let epubURL = try makeFixtureEPUB(
            chapterBodies: ["<h1>Chapter One</h1><p>Ciphertext masquerading as prose.</p>"],
            includeEncryption: true
        )

        XCTAssertThrowsError(try EPUBParser.parse(fileURL: epubURL)) { error in
            XCTAssertEqual(error as? EPUBParser.ParseError, .drmProtected)
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

    /// `container.xml`'s `full-path` is percent-encoded by the same rules as an OPF
    /// href, and failing to resolve it loses the *whole book* rather than one page.
    func testParseReadsPercentEncodedOPFPath() throws {
        let sourceDir = tempDir.appendingPathComponent("pct-opf", isDirectory: true)
        let packageDir = sourceDir.appendingPathComponent("OEBPS 2", isDirectory: true)
        let metaInfDir = sourceDir.appendingPathComponent("META-INF", isDirectory: true)
        try FileManager.default.createDirectory(at: packageDir, withIntermediateDirectories: true)
        try FileManager.default.createDirectory(at: metaInfDir, withIntermediateDirectories: true)

        try """
        <?xml version="1.0"?>
        <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
          <rootfiles>
            <rootfile full-path="OEBPS%202/content.opf" media-type="application/oebps-package+xml"/>
          </rootfiles>
        </container>
        """.write(to: metaInfDir.appendingPathComponent("container.xml"), atomically: true, encoding: .utf8)

        try """
        <?xml version="1.0"?>
        <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
          <manifest><item id="c0" href="chap0.xhtml" media-type="application/xhtml+xml"/></manifest>
          <spine><itemref idref="c0"/></spine>
        </package>
        """.write(to: packageDir.appendingPathComponent("content.opf"), atomically: true, encoding: .utf8)

        try "<html><body><p>Whole book loaded.</p></body></html>"
            .write(to: packageDir.appendingPathComponent("chap0.xhtml"), atomically: true, encoding: .utf8)

        let epubURL = tempDir.appendingPathComponent("pct-opf.epub")
        try FileManager().zipItem(at: sourceDir, to: epubURL, shouldKeepParent: false)

        let chapters = try EPUBParser.parse(fileURL: epubURL)

        XCTAssertEqual(chapters.count, 1)
        XCTAssertTrue(chapters[0].text.contains("Whole book loaded."))
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

    /// Minified on purpose — no whitespace between `</head>` and `<h1>`, so anything
    /// leaking out of `<head>` mashes into the first heading rather than landing on a
    /// line of its own where it would be easy to miss.
    private static let awkwardXHTML = """
    <?xml version="1.0" encoding="utf-8"?>
    <!DOCTYPE html>
    <html xmlns="http://www.w3.org/1999/xhtml"><head><title>Chapter 8</title>\
    <style>p { color: red }</style></head><body><h1>Foreword</h1>\
    <p>Held&nbsp;up his elastic toy &amp; grinned.</p><p>Second para.</p></body></html>
    """

    /// The fallback taken when `NSAttributedString`'s importer answers `nil` — tested
    /// directly, since whether the importer *actually* rejects a given document is a
    /// WebKit implementation detail that varies by OS version. What must hold is that
    /// this path yields readable prose rather than the empty string that produced a
    /// blank page (issue #108).
    func testStrippingTagsProducesProseWithoutMarkup() {
        let text = EPUBParser.strippingTags(from: Data(Self.awkwardXHTML.utf8))

        XCTAssertTrue(text.contains("Foreword"))
        XCTAssertTrue(text.contains("Held\u{00A0}up his elastic toy & grinned."))
        XCTAssertTrue(text.contains("Second para."))
        XCTAssertFalse(text.contains("<"), "tags leaked into reader text: \(text)")
        XCTAssertFalse(text.contains("&nbsp;"))
        XCTAssertFalse(text.contains("color: red"), "stylesheet leaked into reader text: \(text)")
    }

    /// `<head>` holds no prose, but `<title>` is text and used to survive tag removal —
    /// landing at the top of the page, glued to the first heading in a minified
    /// document, and then getting picked up as the chapter title too.
    func testStrippingTagsDropsHeadContent() {
        let text = EPUBParser.strippingTags(from: Data(Self.awkwardXHTML.utf8))

        XCTAssertFalse(text.contains("Chapter 8"), "<title> leaked into reader text: \(text)")
        XCTAssertTrue(text.hasPrefix("Foreword"), "expected prose to start at the heading: \(text)")
    }

    func testStrippingTagsDropsComments() {
        let text = EPUBParser.strippingTags(from: Data("<!-- nav > here --><p>Real prose.</p>".utf8))

        XCTAssertEqual(text, "Real prose.")
    }

    /// An unescaped `>` inside a quoted attribute value is legal XML. Scanning to the
    /// first `>` left the remainder of the attribute (`3">`) sitting in the prose.
    func testStrippingTagsHandlesGreaterThanInsideAttributeValue() {
        let text = EPUBParser.strippingTags(from: Data(#"<p title="5 > 3">Real prose.</p>"#.utf8))

        XCTAssertEqual(text, "Real prose.")
    }

    /// This path runs precisely on entity-heavy documents, so leaving entities encoded
    /// would trade a blank page for a page of visible `&#8217;`.
    func testStrippingTagsDecodesNamedAndNumericEntities() {
        let text = EPUBParser.strippingTags(
            from: Data("<p>Benzie&#8217;s toy&mdash;a caf&eacute; in Bekoji&#xa0;awaited &#x2018;form&#8217;.</p>".utf8)
        )

        XCTAssertEqual(text, "Benzie\u{2019}s toy—a café in Bekoji\u{00A0}awaited \u{2018}form\u{2019}.")
    }

    /// `&amp;` is decoded last so an escaped entity survives as literal text rather
    /// than being decoded twice.
    func testStrippingTagsDoesNotDoubleDecodeEscapedAmpersand() {
        XCTAssertEqual(EPUBParser.strippingTags(from: Data("<p>&amp;lt; and &amp;</p>".utf8)), "&lt; and &")
    }

    /// An unrecognised entity is left verbatim rather than silently dropped — visible
    /// beats missing when prose is at stake.
    func testStrippingTagsLeavesUnknownEntitiesVerbatim() {
        XCTAssertEqual(EPUBParser.strippingTags(from: Data("<p>&notarealentity; here</p>".utf8)), "&notarealentity; here")
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

    // MARK: - parseBlocks (#356)

    func testParseBlocksParsesHeadingsParagraphsListsAndImages() {
        let xhtml = """
        <html><body>
        <h1>Chapter One</h1>
        <p>Some <b>bold</b> text.</p>
        <ul><li>First item</li><li>Second item</li></ul>
        <p><img src="cover.jpg" alt="Cover art"/></p>
        </body></html>
        """

        let blocks = EPUBParser.parseBlocks(fromHTML: Data(xhtml.utf8))

        XCTAssertEqual(blocks, [
            .heading(level: 1, text: [MarkdownInlineRun(text: "Chapter One", bold: false, italic: false, code: false)]),
            .paragraph(text: [
                MarkdownInlineRun(text: "Some ", bold: false, italic: false, code: false),
                MarkdownInlineRun(text: "bold", bold: true, italic: false, code: false),
                MarkdownInlineRun(text: " text.", bold: false, italic: false, code: false),
            ]),
            .unorderedList(items: [
                [.paragraph(text: [MarkdownInlineRun(text: "First item", bold: false, italic: false, code: false)])],
                [.paragraph(text: [MarkdownInlineRun(text: "Second item", bold: false, italic: false, code: false)])],
            ]),
            .image(url: "cover.jpg", altText: "Cover art"),
        ])
    }

    /// `<ol start>`, `<blockquote>`, `<hr>`, and a standalone (not paragraph-wrapped)
    /// `<img>` — the rest of the XHTML subset the block model needs to carry through.
    func testParseBlocksParsesOrderedListStartBlockquoteAndThematicBreak() {
        let xhtml = """
        <html><body>
        <ol start="3"><li>Third</li></ol>
        <blockquote><p>A quote.</p></blockquote>
        <hr/>
        <img src="pic.png" alt="A pic"/>
        </body></html>
        """

        let blocks = EPUBParser.parseBlocks(fromHTML: Data(xhtml.utf8))

        XCTAssertEqual(blocks, [
            .orderedList(
                items: [[.paragraph(text: [MarkdownInlineRun(text: "Third", bold: false, italic: false, code: false)])]],
                start: 3
            ),
            .blockQuote(blocks: [
                .paragraph(text: [MarkdownInlineRun(text: "A quote.", bold: false, italic: false, code: false)]),
            ]),
            .thematicBreak,
            .image(url: "pic.png", altText: "A pic"),
        ])
    }

    /// Regression test for #108, restated for the block model: the entity-heavy,
    /// undeclared-entity XHTML that makes `XMLParser` fail outright must still produce
    /// a single readable block — via `strippingTags`'s existing fallback — rather than
    /// an empty array, which would render as a blank page.
    func testParseBlocksFallsBackToSingleParagraphForMalformedXHTML() {
        let blocks = EPUBParser.parseBlocks(fromHTML: Data(Self.awkwardXHTML.utf8))

        XCTAssertEqual(blocks.count, 1)
        guard case let .paragraph(text) = blocks[0] else {
            return XCTFail("expected a single fallback paragraph block, got \(blocks)")
        }
        let joined = text.map(\.text).joined()
        XCTAssertTrue(joined.contains("Foreword"))
        XCTAssertTrue(joined.contains("Held\u{00A0}up his elastic toy & grinned."))
        XCTAssertTrue(joined.contains("Second para."))
    }

    func testParseBlocksIsNeverEmptyForDocumentWithProse() {
        XCTAssertFalse(EPUBParser.parseBlocks(fromHTML: Data(Self.awkwardXHTML.utf8)).isEmpty)
    }

    func testParseBlocksReturnsEmptyForNoData() {
        XCTAssertEqual(EPUBParser.parseBlocks(fromHTML: Data()), [])
    }

    // MARK: - BookChapter blocks + resolved images (#357)

    /// A 1x1 PNG — real bytes, not a placeholder, so an equality check against the
    /// resolved dictionary is meaningful.
    private static let onePixelPNG = Data([
        0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
        0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x08, 0x02, 0x00, 0x00, 0x00, 0x90, 0x77, 0x53,
        0xDE, 0x00, 0x00, 0x00, 0x0C, 0x49, 0x44, 0x41, 0x54, 0x08, 0xD7, 0x63, 0xF8, 0xCF, 0xC0, 0x00,
        0x00, 0x03, 0x01, 0x01, 0x00, 0x18, 0xDD, 0x8D, 0xB0, 0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E,
        0x44, 0xAE, 0x42, 0x60, 0x82,
    ])

    func testParseCarriesBlocksAndResolvesChapterImages() throws {
        let epubURL = try makeFixtureEPUB(
            items: [
                SpineItem(
                    href: "Text/chap0.xhtml",
                    entryPath: "OEBPS/Text/chap0.xhtml",
                    body: "<html><body><h1>Cover</h1><p><img src=\"images/cover.png\" alt=\"Cover art\"/></p></body></html>"
                ),
            ],
            extraFiles: ["OEBPS/Text/images/cover.png": Self.onePixelPNG]
        )

        let chapters = try EPUBParser.parse(fileURL: epubURL)

        XCTAssertEqual(chapters.count, 1)
        XCTAssertEqual(chapters[0].blocks, [
            .heading(level: 1, text: [MarkdownInlineRun(text: "Cover", bold: false, italic: false, code: false)]),
            .image(url: "images/cover.png", altText: "Cover art"),
        ])
        XCTAssertEqual(chapters[0].images["images/cover.png"], Self.onePixelPNG)
    }

    /// Plain-text-only EPUBs (the overwhelming majority today) must still load fine —
    /// blocks present, image dict simply empty, not a load failure.
    func testParsePlainTextOnlyEPUBHasBlocksAndEmptyImageDict() throws {
        let epubURL = try makeFixtureEPUB(chapterBodies: [
            "<h1>Chapter One</h1><p>No images here.</p>",
        ])

        let chapters = try EPUBParser.parse(fileURL: epubURL)

        XCTAssertEqual(chapters.count, 1)
        XCTAssertFalse(chapters[0].blocks.isEmpty)
        XCTAssertTrue(chapters[0].images.isEmpty)
    }

    /// An `<img src>` that doesn't resolve to any archive entry (moved/renamed asset)
    /// is skipped, not a whole-chapter load failure — mirrors
    /// `loadMarkdownImages`'s same per-image tolerance.
    func testParseSkipsUnresolvableChapterImageWithoutFailingTheChapter() throws {
        let epubURL = try makeFixtureEPUB(items: [
            SpineItem(
                href: "chap0.xhtml",
                entryPath: "OEBPS/chap0.xhtml",
                body: "<html><body><p><img src=\"missing.png\" alt=\"Gone\"/></p></body></html>"
            ),
        ])

        let chapters = try EPUBParser.parse(fileURL: epubURL)

        XCTAssertEqual(chapters.count, 1)
        XCTAssertEqual(chapters[0].blocks, [.image(url: "missing.png", altText: "Gone")])
        XCTAssertTrue(chapters[0].images.isEmpty)
    }

    /// An image nested inside a list item must still resolve — a flat, top-level-only
    /// scan of `blocks` would miss it.
    func testParseResolvesImageNestedInsideList() throws {
        let epubURL = try makeFixtureEPUB(
            items: [
                SpineItem(
                    href: "chap0.xhtml",
                    entryPath: "OEBPS/chap0.xhtml",
                    body: "<html><body><ul><li><img src=\"icon.png\" alt=\"Icon\"/></li></ul></body></html>"
                ),
            ],
            extraFiles: ["OEBPS/icon.png": Self.onePixelPNG]
        )

        let chapters = try EPUBParser.parse(fileURL: epubURL)

        XCTAssertEqual(chapters.count, 1)
        XCTAssertEqual(chapters[0].images["icon.png"], Self.onePixelPNG)
    }

    // MARK: - Bookmark/locator compatibility with the block model (#361)

    /// The literal regression this issue guards against: adding `blocks`/`images`
    /// (#357) to `BookChapter` must not change `.text` — that's still what
    /// `TextPaginator` and `ReaderView.navigateToLocator` resolve saved
    /// `"Locator:<chapterIndex>:<charOffset>"` bookmarks against (EPUB rendering
    /// hasn't moved onto blocks yet, see #360), so an existing bookmark keeps
    /// resolving exactly as it did before #357 landed.
    func testBlocksAndImagesDoNotChangeChapterText() throws {
        let epubURL = try makeFixtureEPUB(
            items: [
                SpineItem(
                    href: "Text/chap0.xhtml",
                    entryPath: "OEBPS/Text/chap0.xhtml",
                    body: "<html><body><h1>Cover</h1><p><img src=\"images/cover.png\" alt=\"Cover art\"/></p><p>Real prose here.</p></body></html>"
                ),
            ],
            extraFiles: ["OEBPS/Text/images/cover.png": Self.onePixelPNG]
        )

        let chapters = try EPUBParser.parse(fileURL: epubURL)

        XCTAssertFalse(chapters[0].blocks.isEmpty, "test needs a real block model to be meaningful")
        XCTAssertFalse(chapters[0].images.isEmpty, "test needs a real resolved image to be meaningful")
        XCTAssertTrue(chapters[0].text.contains("Real prose here."))
        XCTAssertTrue(chapters[0].text.contains("Cover"))
    }

    /// `EPUBLocator.blockIndex(forCharOffset:in:)` walking real blocks from
    /// `EPUBParser.parse` — not just hand-built `MarkdownBlock`s in
    /// `EPUBLocatorTests` — catches a mismatch between how the parser actually nests
    /// real content (headings, paragraphs) and how the locator counts through it.
    func testLocatorResolvesAgainstRealParsedChapterBlocks() throws {
        let epubURL = try makeFixtureEPUB(items: [
            SpineItem(
                href: "chap0.xhtml",
                entryPath: "OEBPS/chap0.xhtml",
                body: "<html><body><h1>Chapter One</h1><p>First paragraph.</p><p>Second paragraph.</p></body></html>"
            ),
        ])

        let chapters = try EPUBParser.parse(fileURL: epubURL)
        let blocks = chapters[0].blocks
        XCTAssertEqual(blocks.count, 3, "fixture expected to produce heading + 2 paragraphs")

        XCTAssertEqual(EPUBLocator.blockIndex(forCharOffset: 0, in: blocks), 0, "offset 0 must land on the heading")
        XCTAssertEqual(
            EPUBLocator.blockIndex(forCharOffset: 100_000, in: blocks), 2,
            "an offset past everything must clamp to the last real block, not crash"
        )
    }
}
