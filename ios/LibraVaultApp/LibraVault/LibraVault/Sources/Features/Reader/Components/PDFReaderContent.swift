import SwiftUI
import PDFKit

/// Renders real PDF pages on screen via PDFKit's `PDFView` — the on-screen
/// counterpart to PDFParser.swift's page-to-text extraction, which stays in use only
/// for "Read Aloud" TTS (AppState.startPlayback), never for what's displayed here.
/// Mirrors Android's PdfReaderScreen, which rasterizes real page bitmaps via
/// android.graphics.pdf.PdfRenderer instead of reflowing extracted text through the
/// shared EPUB/Markdown text styling — previously PDFs went through that same
/// reflow path on iOS too, which is why "reading" a PDF showed giant sans-serif
/// extracted text instead of the book's actual laid-out page.
///
/// PDFKit has no native SwiftUI view, so this bridges UIKit's `PDFView` in.
struct PDFReaderContent: UIViewRepresentable {
    let document: PDFDocument
    let mode: ReaderLayoutMode
    @Binding var currentPageIndex: Int
    let backgroundColor: UIColor
    /// Mirrors Android's PdfReaderScreen center-third tap — toggles the reader's
    /// chrome. Left/right-third taps are handled in the Coordinator directly (they
    /// only make sense in .paginated mode, since there's no discrete "page" to flip
    /// to while continuously scrolling — see ReaderTapZone's doc comment).
    let onCenterTap: () -> Void

    func makeUIView(context: Context) -> PDFView {
        let view = WidthFittingPDFView()
        // WidthFittingPDFView manages scaleFactor itself (fit-to-width, not
        // PDFKit's default fit-to-page) — autoScales would fight it every layout
        // pass, since PDFKit's own auto-fit logic also runs during layoutSubviews.
        view.autoScales = false
        view.backgroundColor = backgroundColor
        view.document = document
        applyDisplayMode(to: view, context: context)
        context.coordinator.observe(view)
        if let page = document.page(at: currentPageIndex) {
            view.go(to: page)
        }
        let tapRecognizer = UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.handleTap(_:)))
        tapRecognizer.numberOfTapsRequired = 1
        // PDFView installs its own double/triple-tap recognizers for tap-to-zoom
        // (already present on view.gestureRecognizers by this point, set up in its
        // own init). Without requiring ours to fail first, a plain single-tap
        // recognizer fires immediately on the first tap of any double-tap, since
        // UIKit doesn't know a second tap is coming — which broke double-tap-to-
        // zoom entirely (reported in the field on PDF landscape) rather than just
        // adding a harmless extra call to onCenterTap alongside the zoom.
        for recognizer in view.gestureRecognizers ?? [] {
            if let multiTap = recognizer as? UITapGestureRecognizer, multiTap.numberOfTapsRequired > 1 {
                tapRecognizer.require(toFail: multiTap)
            }
        }
        view.addGestureRecognizer(tapRecognizer)
        return view
    }

    func updateUIView(_ uiView: PDFView, context: Context) {
        if uiView.document !== document {
            uiView.document = document
        }
        if uiView.backgroundColor != backgroundColor {
            uiView.backgroundColor = backgroundColor
        }
        applyDisplayMode(to: uiView, context: context)
        context.coordinator.mode = mode
        context.coordinator.document = document

        // Only jump the view when currentPageIndex changed from *outside* (Prev/Next
        // buttons, bookmark restore) — the Coordinator's own page-change notification
        // already keeps this binding in sync with in-view swipes/scrolls, so without
        // this equality check every such update would fight the user's own gesture.
        if let visiblePage = uiView.currentPage,
           document.index(for: visiblePage) != currentPageIndex,
           let targetPage = document.page(at: currentPageIndex) {
            uiView.go(to: targetPage)
        }
    }

    func makeCoordinator() -> Coordinator {
        Coordinator(currentPageIndex: $currentPageIndex, mode: mode, document: document, onCenterTap: onCenterTap)
    }

    /// Reapplies displayMode/pageViewController only when `mode` actually changed —
    /// PDFView's usePageViewController(_:) swaps its internal view hierarchy, so
    /// calling it on every SwiftUI re-render (theme toggles, etc.) would reset scroll
    /// position and cause visible flicker.
    private func applyDisplayMode(to view: PDFView, context: Context) {
        guard context.coordinator.appliedMode != mode else { return }
        switch mode {
        case .paginated:
            view.displayMode = .singlePage
            view.usePageViewController(true)
        case .scrolling:
            view.usePageViewController(false)
            view.displayMode = .singlePageContinuous
        }
        context.coordinator.appliedMode = mode
    }

    final class Coordinator: NSObject {
        var appliedMode: ReaderLayoutMode?
        var mode: ReaderLayoutMode
        var document: PDFDocument?
        private let currentPageIndex: Binding<Int>
        private let onCenterTap: () -> Void
        private var pageChangedObserver: NSObjectProtocol?

        init(currentPageIndex: Binding<Int>, mode: ReaderLayoutMode, document: PDFDocument, onCenterTap: @escaping () -> Void) {
            self.currentPageIndex = currentPageIndex
            self.mode = mode
            self.document = document
            self.onCenterTap = onCenterTap
        }

        /// Mirrors Android's PdfPaginatedView/PdfScrollingView split: left/right
        /// thirds only flip pages in .paginated mode (there's no discrete "page" to
        /// flip to while continuously scrolling), but the center third always
        /// toggles the toolbar regardless of mode.
        @objc func handleTap(_ recognizer: UITapGestureRecognizer) {
            guard let view = recognizer.view else { return }
            let x = recognizer.location(in: view).x
            switch ReaderTapZone.classify(x: x, width: view.bounds.width) {
            case .previous:
                guard mode == .paginated else { return }
                if currentPageIndex.wrappedValue > 0 {
                    currentPageIndex.wrappedValue -= 1
                }
            case .next:
                guard mode == .paginated, let document else { return }
                if currentPageIndex.wrappedValue < document.pageCount - 1 {
                    currentPageIndex.wrappedValue += 1
                }
            case .center:
                onCenterTap()
            }
        }

        func observe(_ view: PDFView) {
            pageChangedObserver = NotificationCenter.default.addObserver(
                forName: .PDFViewPageChanged,
                object: view,
                queue: .main
            ) { [weak view, weak self] _ in
                guard let self, let view, let document = view.document,
                      let page = view.currentPage else { return }
                // PDFDocument.index(for:) returns NSNotFound (not nil) when the page
                // isn't part of this document — shouldn't happen since `page` just
                // came from this same `document`'s own currentPage, but guarded
                // rather than assumed.
                let index = document.index(for: page)
                guard index != NSNotFound else { return }
                self.currentPageIndex.wrappedValue = index
                // A new page can have different native dimensions than the last
                // one (mixed-size scans aren't common but do happen) — turning
                // pages doesn't change the view's own bounds, so
                // WidthFittingPDFView's layoutSubviews size-change guard wouldn't
                // otherwise catch this.
                (view as? WidthFittingPDFView)?.fitCurrentPageToWidth()
            }
        }

        deinit {
            if let pageChangedObserver {
                NotificationCenter.default.removeObserver(pageChangedObserver)
            }
        }
    }
}

/// PDFKit's `autoScales` fits the *whole page* inside the view, preserving aspect
/// ratio — correct in portrait, but in landscape a portrait-oriented page's height
/// becomes the limiting dimension, leaving empty space on both sides instead of
/// filling the screen (reported in the field: "landscape PDF doesn't use full
/// width"). This instead always scales the current page to fill the view's full
/// width, matching Android's PdfReaderScreen (which rasterizes each page as a
/// bitmap sized to the view's width via android.graphics.pdf.PdfRenderer).
///
/// Only recomputes on an actual bounds *size* change (rotation, split-view resize),
/// not on every layoutSubviews pass — that also fires during an in-progress pinch
/// gesture, when bounds.size is unchanged but scaleFactor is exactly what the user
/// is actively adjusting; recomputing then would fight the gesture and make manual
/// zoom feel stuck at the width-fit scale.
final class WidthFittingPDFView: PDFView {
    private var lastLayoutSize: CGSize = .zero

    override func layoutSubviews() {
        super.layoutSubviews()
        guard bounds.size != lastLayoutSize else { return }
        lastLayoutSize = bounds.size
        fitCurrentPageToWidth()
    }

    /// Not private — Coordinator.observe's page-change handler calls this directly
    /// too (see its own doc comment for why).
    func fitCurrentPageToWidth() {
        guard let page = currentPage, bounds.width > 0 else { return }
        let pageWidth = page.bounds(for: displayBox).width
        guard pageWidth > 0 else { return }
        scaleFactor = bounds.width / pageWidth
    }
}
