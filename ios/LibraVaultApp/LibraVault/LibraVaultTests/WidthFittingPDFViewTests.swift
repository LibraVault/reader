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

    /// A two-page document where each page has different native dimensions —
    /// for tests exercising a scroll-triggered page change (`view.go(to:)`)
    /// *within* the same document, as Coordinator.observe's real
    /// .PDFViewPageChanged handler does, as opposed to a document swap (which
    /// WidthFittingPDFView.document's didSet already resets `lastFitScale` for).
    private func makeMultiPageFixtureDocument(page1: CGSize, page2: CGSize) -> PDFDocument {
        let document = PDFDocument()
        for (index, size) in [page1, page2].enumerated() {
            let pageBounds = CGRect(origin: .zero, size: size)
            let renderer = UIGraphicsPDFRenderer(bounds: pageBounds)
            let data = renderer.pdfData { context in
                context.beginPage()
                ("Fixture page \(index)" as NSString).draw(at: CGPoint(x: 20, y: 20), withAttributes: [.font: UIFont.systemFont(ofSize: 18)])
            }
            let page = PDFDocument(data: data)!.page(at: 0)!
            document.insert(page, at: index)
        }
        return document
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

    /// Regression coverage for the field-reported bug: after a double-tap zoom in
    /// landscape, scrolling to a new page (or PDFKit's page-changed notification
    /// firing for any other reason) snapped the zoom straight back to fit-width.
    /// Coordinator.observe calls fitCurrentPageToWidth() on every page change
    /// unconditionally, so this must be a no-op once the user has manually zoomed.
    func testManualZoomSurvivesPageChangedNotification() {
        let view = WidthFittingPDFView()
        view.document = makeFixtureDocument()
        view.frame = CGRect(x: 0, y: 0, width: 800, height: 400)
        view.layoutIfNeeded()

        // Simulate the user's own double-tap/pinch zoom, which PDFKit applies by
        // setting scaleFactor directly — nothing else in this view does that.
        let manualZoomScale = view.scaleFactor * 2
        view.scaleFactor = manualZoomScale

        // Simulate Coordinator.observe's page-changed handler, which calls this
        // directly on every .PDFViewPageChanged notification.
        view.fitCurrentPageToWidth()

        XCTAssertEqual(view.scaleFactor, manualZoomScale, accuracy: 0.01)
    }

    /// Regression coverage for the field-reported bug (build 29, after PR #88):
    /// zoom reverted to unzoomed a few pages into a continuous-scroll session, with
    /// no pinch/double-tap in between. Root cause: `fitCurrentPageToWidth()` stored
    /// the *requested* fit scale as `lastFitScale`, not the value PDFView actually
    /// applied — if PDFView's own `maxScaleFactor` clamp (which it can recompute
    /// per-page) ever produced a different applied scaleFactor, the next page
    /// change's drift check compared live `scaleFactor` against that wrong stored
    /// value, false-positived as "the user zoomed manually," and permanently
    /// stopped refitting.
    func testRefitsCorrectlyAfterAPriorClampedApplication() {
        let view = WidthFittingPDFView()
        view.document = makeMultiPageFixtureDocument(
            page1: CGSize(width: 612, height: 792),
            page2: CGSize(width: 400, height: 300)
        )
        view.frame = CGRect(x: 0, y: 0, width: 800, height: 400)
        // Force PDFKit to clamp the width-fit scale we ask for below, simulating
        // real-world per-page clamp recomputation rather than applying it verbatim.
        let page1Width = view.document!.page(at: 0)!.bounds(for: view.displayBox).width
        let intendedFitScale = 800 / page1Width
        view.maxScaleFactor = intendedFitScale * 0.5
        view.layoutIfNeeded()

        // The clamp actually took effect — otherwise this test isn't exercising
        // the scenario it's meant to.
        XCTAssertLessThan(view.scaleFactor, intendedFitScale)

        // No manual zoom occurred; scrolling to the next page (different native
        // dimensions, this time within the clamp range) should still refit
        // correctly rather than getting stuck at the previous page's clamped
        // scale, the way Coordinator.observe's real .PDFViewPageChanged handler
        // calls this on every scroll-past-boundary in continuous mode.
        view.maxScaleFactor = 10 // lift the artificial clamp for the new page
        view.go(to: view.document!.page(at: 1)!)
        view.fitCurrentPageToWidth()

        XCTAssertEqual(view.scaleFactor, 800 / 400, accuracy: 0.01)
    }

    /// Regression coverage for the field-reported bug: after a double-tap zoom,
    /// tapping once to toggle the reader's chrome (toolbar/status bar) changes the
    /// view's height but not its width, and used to be misread by layoutSubviews as
    /// a resize that needed refitting, wiping out the zoom.
    func testHeightOnlyBoundsChangeDoesNotResetManualZoom() {
        let view = WidthFittingPDFView()
        view.document = makeFixtureDocument()
        view.frame = CGRect(x: 0, y: 0, width: 800, height: 400)
        view.layoutIfNeeded()

        let manualZoomScale = view.scaleFactor * 2
        view.scaleFactor = manualZoomScale

        // Chrome toggle: height changes (toolbar/status bar showing or hiding),
        // width does not.
        view.frame = CGRect(x: 0, y: 0, width: 800, height: 350)
        view.layoutIfNeeded()

        XCTAssertEqual(view.scaleFactor, manualZoomScale, accuracy: 0.01)
    }
}
