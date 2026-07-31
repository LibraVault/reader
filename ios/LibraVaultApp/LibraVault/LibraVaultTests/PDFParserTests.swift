import XCTest
import UIKit
@testable import LibraVault

final class PDFParserTests: XCTestCase {
    private var tempDir: URL!

    override func setUpWithError() throws {
        tempDir = FileManager.default.temporaryDirectory.appendingPathComponent("PDFParserTests-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: tempDir)
    }

    /// Builds a real, valid multi-page PDF with real drawn text via
    /// `UIGraphicsPDFRenderer` — the standard Apple PDF-authoring API — so the parser
    /// is exercised against an actual PDF, not a synthetic stand-in.
    private func makeFixturePDF(pageTexts: [String]) throws -> URL {
        let pageBounds = CGRect(x: 0, y: 0, width: 612, height: 792)
        let renderer = UIGraphicsPDFRenderer(bounds: pageBounds)
        let fileURL = tempDir.appendingPathComponent("fixture-\(UUID().uuidString).pdf")

        try renderer.writePDF(to: fileURL) { context in
            for text in pageTexts {
                context.beginPage()
                (text as NSString).draw(
                    at: CGPoint(x: 20, y: 20),
                    withAttributes: [.font: UIFont.systemFont(ofSize: 18)]
                )
            }
        }
        return fileURL
    }

    func testParseReturnsOneChapterPerPage() throws {
        let pdfURL = try makeFixturePDF(pageTexts: ["First page text.", "Second page text."])

        let chapters = try PDFParser.parse(fileURL: pdfURL)

        XCTAssertEqual(chapters.count, 2)
        XCTAssertTrue(chapters[0].text.contains("First page text."))
        XCTAssertTrue(chapters[1].text.contains("Second page text."))
        XCTAssertEqual(chapters[0].title, "Page 1")
        XCTAssertEqual(chapters[1].title, "Page 2")
    }

    func testParseThrowsForInvalidDocument() throws {
        let notAPDFURL = tempDir.appendingPathComponent("not-a-pdf.pdf")
        try Data("this is not a pdf".utf8).write(to: notAPDFURL)

        XCTAssertThrowsError(try PDFParser.parse(fileURL: notAPDFURL)) { error in
            XCTAssertEqual(error as? PDFParser.ParseError, .invalidDocument)
        }
    }
}
