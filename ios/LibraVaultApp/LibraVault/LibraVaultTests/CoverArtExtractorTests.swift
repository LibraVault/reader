import XCTest
import AVFoundation
import UIKit
import ZIPFoundation
@testable import LibraVault

final class CoverArtExtractorTests: XCTestCase {
    private var tempDir: URL!
    private var cache: CoverArtCache!

    override func setUpWithError() throws {
        tempDir = FileManager.default.temporaryDirectory.appendingPathComponent("CoverArtExtractorTests-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
        cache = CoverArtCache()
        cache.clearAll()
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: tempDir)
        cache.clearAll()
    }

    // MARK: - EPUB fixtures

    /// Builds a real, minimal, valid EPUB — same `FileManager.zipItem` mechanism
    /// `EPUBParserTests` uses — optionally with a cover image, referenced either via
    /// the EPUB3 `<meta name="cover" content="ID">` convention or, when
    /// `declareCoverMeta` is false, only discoverable via a manifest item whose
    /// id/href mentions "cover" (CoverArtExtractor's fallback path).
    private func makeFixtureEPUB(coverImageBytes: Data?, coverManifestId: String = "cover-image", declareCoverMeta: Bool) throws -> URL {
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

        var manifestItems = "<item id=\"chap0\" href=\"chap0.xhtml\" media-type=\"application/xhtml+xml\"/>"
        var metadataExtra = ""
        if let coverImageBytes {
            try coverImageBytes.write(to: oebpsDir.appendingPathComponent("cover.jpg"))
            manifestItems += "<item id=\"\(coverManifestId)\" href=\"cover.jpg\" media-type=\"image/jpeg\"/>"
            if declareCoverMeta {
                metadataExtra = "<meta name=\"cover\" content=\"\(coverManifestId)\"/>"
            }
        }

        try """
        <?xml version="1.0"?>
        <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
          <metadata>\(metadataExtra)</metadata>
          <manifest>\(manifestItems)</manifest>
          <spine><itemref idref="chap0"/></spine>
        </package>
        """.write(to: oebpsDir.appendingPathComponent("content.opf"), atomically: true, encoding: .utf8)

        try "<html><body>Chapter</body></html>".write(
            to: oebpsDir.appendingPathComponent("chap0.xhtml"), atomically: true, encoding: .utf8
        )

        let epubURL = tempDir.appendingPathComponent("fixture-\(UUID().uuidString).epub")
        try FileManager().zipItem(at: sourceDir, to: epubURL, shouldKeepParent: false)
        return epubURL
    }

    // format.scale = 1 pins pixel dimensions to the point size given — see the
    // matching comment in CoverArtCacheTests.makeFixtureImage for why an unpinned
    // renderer would be a trap (harmless here since these tests don't assert on
    // exact pixel size, but worth keeping consistent).
    private func makeFixtureCoverImage() -> Data {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: 40, height: 60), format: format)
        return renderer.image { context in
            UIColor.blue.setFill()
            context.fill(CGRect(x: 0, y: 0, width: 40, height: 60))
        }.jpegData(compressionQuality: 1.0)!
    }

    func testExtractsEpub3CoverViaManifestMeta() async throws {
        let epubURL = try makeFixtureEPUB(coverImageBytes: makeFixtureCoverImage(), declareCoverMeta: true)
        let book = BookData(id: "epub-1", title: "T", author: "A", format: .epub, fileURL: epubURL)

        let result = await CoverArtExtractor.extractCoverPath(for: book, cache: cache)

        XCTAssertNotNil(result)
    }

    func testFallsBackToManifestItemNamedCoverWhenNoMeta() async throws {
        let epubURL = try makeFixtureEPUB(coverImageBytes: makeFixtureCoverImage(), coverManifestId: "cover-img", declareCoverMeta: false)
        let book = BookData(id: "epub-2", title: "T", author: "A", format: .epub, fileURL: epubURL)

        let result = await CoverArtExtractor.extractCoverPath(for: book, cache: cache)

        XCTAssertNotNil(result)
    }

    func testReturnsNilForEpubWithNoCover() async throws {
        let epubURL = try makeFixtureEPUB(coverImageBytes: nil, declareCoverMeta: false)
        let book = BookData(id: "epub-3", title: "T", author: "A", format: .epub, fileURL: epubURL)

        let result = await CoverArtExtractor.extractCoverPath(for: book, cache: cache)

        XCTAssertNil(result)
    }

    func testCachesResultOnSecondCall() async throws {
        let epubURL = try makeFixtureEPUB(coverImageBytes: makeFixtureCoverImage(), declareCoverMeta: true)
        let book = BookData(id: "epub-cache", title: "T", author: "A", format: .epub, fileURL: epubURL)

        let first = await CoverArtExtractor.extractCoverPath(for: book, cache: cache)
        let second = await CoverArtExtractor.extractCoverPath(for: book, cache: cache)

        XCTAssertEqual(first, second)
    }

    // MARK: - PDF (page 1 thumbnail)

    /// Real, valid PDF via `UIGraphicsPDFRenderer` — same fixture-building approach as
    /// `PDFParserTests`.
    private func makeFixturePDF() throws -> URL {
        let pageBounds = CGRect(x: 0, y: 0, width: 612, height: 792)
        let renderer = UIGraphicsPDFRenderer(bounds: pageBounds)
        let fileURL = tempDir.appendingPathComponent("fixture-\(UUID().uuidString).pdf")
        try renderer.writePDF(to: fileURL) { context in
            context.beginPage()
            ("Cover page" as NSString).draw(at: CGPoint(x: 20, y: 20), withAttributes: [.font: UIFont.systemFont(ofSize: 18)])
        }
        return fileURL
    }

    func testExtractsPdfFirstPageAsCover() async throws {
        let pdfURL = try makeFixturePDF()
        let book = BookData(id: "pdf-1", title: "T", author: "A", format: .pdf, fileURL: pdfURL)

        let result = await CoverArtExtractor.extractCoverPath(for: book, cache: cache)

        let url = try XCTUnwrap(result)
        let data = try Data(contentsOf: url)
        XCTAssertNotNil(UIImage(data: data))
    }

    // MARK: - Audio (embedded artwork via AVAsset common metadata)

    /// A real, valid, silent AAC/M4A file written via `AVAudioFile` — same technique
    /// `AudioPlaybackEngineTests.makeFixtureWAV` uses for WAV, targeting AAC/M4A here
    /// so the extension maps to `MediaFormat.aac` (see `LibraryFileScanner.extensionFormats`).
    private func makeFixtureM4A(seconds: Double) throws -> URL {
        let format = AVAudioFormat(standardFormatWithSampleRate: 44100, channels: 1)!
        let frameCount = AVAudioFrameCount(44100 * seconds)
        let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: frameCount)!
        buffer.frameLength = frameCount

        let fileURL = tempDir.appendingPathComponent("fixture-\(UUID().uuidString).m4a")
        let aacSettings: [String: Any] = [
            AVFormatIDKey: kAudioFormatMPEG4AAC,
            AVSampleRateKey: 44100,
            AVNumberOfChannelsKey: 1,
        ]
        let audioFile = try AVAudioFile(forWriting: fileURL, settings: aacSettings)
        try audioFile.write(from: buffer)
        return fileURL
    }

    /// Confirms the metadata-only `AVAsset` read this depends on actually runs against
    /// a real, valid audio file in this project's CI Simulator without hanging — the
    /// class of concern feedback-ios-avfoundation-ci-hang documents for other
    /// AVFoundation entry points (AVAudioSession/AVSpeechSynthesizer activation). Since
    /// `commonMetadata` comes back empty for this fixture, this also exercises the
    /// `availableMetadataFormats`/`loadMetadata(for:)` ID3/iTunes fallback path added
    /// for the field-reported "MP3 audiobook cover art missing" gap, confirming it
    /// runs clean (no hang, no crash) on a real file that simply has no artwork in
    /// any keyspace, rather than only on the untested happy path.
    /// No embedded artwork in this fixture, so nil is the correct result here;
    /// fabricating real ID3/M4A embedded cover art for a fixture is disproportionate
    /// for what this test needs to prove — see CoverArtExtractor.extractAudioCover's
    /// doc comment for why this call is CI-safe in the first place. Verifying the
    /// fallback actually *finds* an ID3 APIC frame needs a real tagged MP3, which
    /// isn't practical to fabricate as a unit-test fixture (AVAudioFile can't encode
    /// MP3) — that path needs on-device confirmation.
    func testReturnsNilForAudioWithNoEmbeddedArtwork() async throws {
        let fileURL = try makeFixtureM4A(seconds: 0.2)
        let book = BookData(id: "audio-1", title: "T", author: "A", format: .aac, fileURL: fileURL)

        let result = await CoverArtExtractor.extractCoverPath(for: book, cache: cache)

        XCTAssertNil(result)
    }

    // MARK: - Non-cover formats / missing file

    func testReturnsNilForMarkdown() async {
        let book = BookData(id: "md-1", title: "T", author: "A", format: .markdown, fileURL: tempDir.appendingPathComponent("x.md"))

        let result = await CoverArtExtractor.extractCoverPath(for: book, cache: cache)

        XCTAssertNil(result)
    }

    func testReturnsNilWhenFileURLIsMissing() async {
        let book = BookData(id: "no-file", title: "T", author: "A", format: .epub)

        let result = await CoverArtExtractor.extractCoverPath(for: book, cache: cache)

        XCTAssertNil(result)
    }
}
