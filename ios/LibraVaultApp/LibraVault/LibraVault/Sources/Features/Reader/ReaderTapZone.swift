import CoreGraphics

/// Classifies a tap's horizontal position into left/right/center thirds — mirrors
/// Android's PdfPaginatedView (feature/reader/pdf/PdfReaderScreen.kt), which hardcodes
/// the same 0.33/0.67 width boundaries for left-edge-prev / right-edge-next /
/// center-toggle-toolbar. Shared by both ReaderView's SwiftUI gesture handlers (EPUB)
/// and PDFReaderContent's UIKit UITapGestureRecognizer (PDF) so the boundary is
/// defined once and is independently unit-testable.
enum ReaderTapZone: Equatable {
    case previous
    case next
    case center

    static func classify(x: CGFloat, width: CGFloat) -> ReaderTapZone {
        guard width > 0 else { return .center }
        if x < width * 0.33 { return .previous }
        if x > width * 0.67 { return .next }
        return .center
    }
}
