import PDFKit
import XCTest
@testable import LibraVault

/// Regression coverage for the field-reported bug: landscape PDF pages didn't fill
/// the screen width, since PDFKit's own `autoScales` fits the *whole page* (letterboxed
/// on the sides in landscape) rather than the width. See WidthFittingPDFView's doc
/// comment for the fix.
final class WidthFittingPDFViewTests: XCTestCase {

    /// A real, valid single-page PDF via `UIGraphicsPDFRenderer` — same approach
    /// PDFParserTests uses — sized like a typical portrait book page so a landscape
    /// (wide) view bounds genuinely differs from a fit-to-page scale.
    private func makeFixtureDocument(pageWidth: CGFloat = 612, pageHeight: CGFloat = 792) -> PDFDocument {
        let pageBounds = CGRect(x: 0, y: 0, width: pageWidth, height: pageHeight)
        let renderer = UIGraphicsPDFRenderer(bounds: pageBounds)
        let data = renderer.pdfData { context in
            context.beginPage()
            ("Fixture page" as NSString).draw(at: CGPoint(x: 20, y: 20), withAttributes: [.font: UIFont.systemFont(ofSize: 18)])
        }
        return PDFDocument(data: data)!
    }

    func testScalesToFillLandscapeWidth() {
        let document = makeFixtureDocument()
        let view = WidthFittingPDFView()
        view.document = document
        view.frame = CGRect(x: 0, y: 0, width: 800, height: 400)
        view.layoutIfNeeded()

        let pageWidth = document.page(at: 0)!.bounds(for: view.displayBox).width
        XCTAssertEqual(view.scaleFactor, 800 / pageWidth, accuracy: 0.01)
    }

    func testRefitsAfterRotationBoundsChange() {
        let document = makeFixtureDocument()
        let view = WidthFittingPDFView()
        view.document = document
        view.frame = CGRect(x: 0, y: 0, width: 400, height: 800)
        view.layoutIfNeeded()
        let portraitScale = view.scaleFactor

        view.frame = CGRect(x: 0, y: 0, width: 800, height: 400)
        view.layoutIfNeeded()

        XCTAssertNotEqual(view.scaleFactor, portraitScale)
        let pageWidth = document.page(at: 0)!.bounds(for: view.displayBox).width
        XCTAssertEqual(view.scaleFactor, 800 / pageWidth, accuracy: 0.01)
    }

    func testFitCurrentPageToWidthRecomputesForADifferentlySizedPage() {
        let view = WidthFittingPDFView()
        view.document = makeFixtureDocument()
        view.frame = CGRect(x: 0, y: 0, width: 800, height: 400)
        view.layoutIfNeeded()

        // Simulate a page-change to a page with different native dimensions —
        // Coordinator.observe's page-change handler calls this directly, since
        // turning pages doesn't change the view's own bounds (layoutSubviews'
        // size-change guard wouldn't otherwise catch it).
        view.document = makeFixtureDocument(pageWidth: 400, pageHeight: 300)
        view.fitCurrentPageToWidth()

        XCTAssertEqual(view.scaleFactor, 800 / 400, accuracy: 0.01)
    }
}
