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

    // MARK: - parseBlocks (block model, #356)

    /// The acceptance-criteria case: a chapter with headings, a styled paragraph,
    /// both list kinds, and an image parses into the exact expected block sequence —
    /// including the image splitting its wrapping paragraph in two around it.
    func testParseBlocksProducesExpectedSequenceForHeadingsParagraphsListsImages() {
        let html = """
        <html><body>
        <h1>Chapter One</h1>
        <p>Some <b>bold</b> and <i>italic</i> text.</p>
        <ul><li>Apple</li><li>Banana</li></ul>
        <ol><li>First</li><li>Second</li></ol>
        <p>Caption: <img src="pic.png" alt="A cat"/></p>
        </body></html>
        """

        let blocks = EPUBParser.parseBlocks(fromHTML: Data(html.utf8))

        XCTAssertEqual(blocks, [
            .heading(level: 1, text: [EPUBInlineRun(text: "Chapter One", bold: false, italic: false)]),
            .paragraph(text: [
                EPUBInlineRun(text: "Some ", bold: false, italic: false),
                EPUBInlineRun(text: "bold", bold: true, italic: false),
                EPUBInlineRun(text: " and ", bold: false, italic: false),
                EPUBInlineRun(text: "italic", bold: false, italic: true),
                EPUBInlineRun(text: " text.", bold: false, italic: false),
            ]),
            .unorderedList(items: [
                [EPUBInlineRun(text: "Apple", bold: false, italic: false)],
                [EPUBInlineRun(text: "Banana", bold: false, italic: false)],
            ]),
            .orderedList(items: [
                [EPUBInlineRun(text: "First", bold: false, italic: false)],
                [EPUBInlineRun(text: "Second", bold: false, italic: false)],
            ]),
            .paragraph(text: [EPUBInlineRun(text: "Caption: ", bold: false, italic: false)]),
            .image(url: "pic.png", altText: "A cat"),
        ])
    }

    /// An `<img>` doesn't need a wrapping `<p>` — real EPUB markup often places it
    /// directly under a `<div>`/`<body>`.
    func testParseBlocksCapturesStandaloneImageNotWrappedInParagraph() {
        let html = """
        <html><body><h1>Chapter</h1><img src="fig1.png" alt="Figure 1"/><p>After image.</p></body></html>
        """

        let blocks = EPUBParser.parseBlocks(fromHTML: Data(html.utf8))

        XCTAssertEqual(blocks, [
            .heading(level: 1, text: [EPUBInlineRun(text: "Chapter", bold: false, italic: false)]),
            .image(url: "fig1.png", altText: "Figure 1"),
            .paragraph(text: [EPUBInlineRun(text: "After image.", bold: false, italic: false)]),
        ])
    }

    /// An empty `<ul>`/`<ol>` (no `<li>` children) is dropped rather than emitted as a
    /// block with zero items.
    func testParseBlocksSkipsEmptyLists() {
        let html = "<html><body><p>Before.</p><ul></ul><p>After.</p></body></html>"

        let blocks = EPUBParser.parseBlocks(fromHTML: Data(html.utf8))

        XCTAssertEqual(blocks, [
            .paragraph(text: [EPUBInlineRun(text: "Before.", bold: false, italic: false)]),
            .paragraph(text: [EPUBInlineRun(text: "After.", bold: false, italic: false)]),
        ])
    }

    func testParseBlocksReturnsEmptyForNoData() {
        XCTAssertEqual(EPUBParser.parseBlocks(fromHTML: Data()), [])
    }

    // MARK: - parseBlocks fallback (issue #108 regression guard)

    /// The exact malformed-XHTML shape that broke `NSAttributedString`'s importer
    /// (issue #108) must still produce a single, non-empty fallback paragraph block —
    /// never a blank page — when the block-model path is used instead. Unlike
    /// `testPlainTextIsNeverEmptyForDocumentWithProse` (whose sibling comment notes
    /// WebKit's HTML importer rejecting `&nbsp;` is an OS-version-dependent detail,
    /// not something this suite can pin down), this test's fallback trigger IS
    /// deterministic: `&nbsp;` is not one of XML's five predefined entities, isn't
    /// declared by an internal/external DTD subset here, and `XMLParser` — a strict,
    /// non-HTML XML parser (libxml2) — treats an undeclared general entity reference
    /// as a hard well-formedness violation on every platform version, not a "maybe".
    func testParseBlocksFallsBackToSingleParagraphForMalformedXHTML() {
        let blocks = EPUBParser.parseBlocks(fromHTML: Data(Self.awkwardXHTML.utf8))

        guard blocks.count == 1, case let .paragraph(runs) = blocks[0] else {
            XCTFail("expected a single fallback paragraph block, got \(blocks)")
            return
        }
        XCTAssertEqual(runs.count, 1, "fallback block should be one flat run, got \(runs)")
        XCTAssertTrue(runs[0].text.contains("Foreword"))
        XCTAssertTrue(runs[0].text.contains("Second para."))
        XCTAssertFalse(runs[0].text.contains("<"), "tags leaked into fallback block text: \(runs[0].text)")
        XCTAssertFalse(runs[0].text.contains("Chapter 8"), "<title> leaked into fallback block text: \(runs[0].text)")
    }

    /// Well-formed XML that nonetheless yields nothing the block walker recognises
    /// (text with no wrapping `<p>`/heading/`<li>`) must still fall back to readable
    /// prose rather than an empty block list — the "parsed fine, structured badly"
    /// counterpart to the malformed-markup case above.
    func testParseBlocksFallsBackWhenWellFormedButNoRecognizedBlocks() {
        let html = "<html><body><div>Loose text with no wrapping tag.</div></body></html>"

        let blocks = EPUBParser.parseBlocks(fromHTML: Data(html.utf8))

        guard blocks.count == 1, case let .paragraph(runs) = blocks[0] else {
            XCTFail("expected a single fallback paragraph block, got \(blocks)")
            return
        }
        XCTAssertTrue(runs.first?.text.contains("Loose text with no wrapping tag.") == true)
    }

    /// A well-formed but entirely empty body (whitespace only) has genuinely nothing
    /// to show — this is not the #108 blank-page bug, which was prose disappearing,
    /// not an already-empty chapter staying empty.
    func testParseBlocksReturnsEmptyForBodyWithNoProse() {
        let blocks = EPUBParser.parseBlocks(fromHTML: Data("<html><body>   </body></html>".utf8))

        XCTAssertEqual(blocks, [])
    }
}
