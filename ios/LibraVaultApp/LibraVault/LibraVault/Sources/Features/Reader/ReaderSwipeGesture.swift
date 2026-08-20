import CoreGraphics

/// Classifies a completed drag's translation into a page-turn direction — the swipe
/// counterpart to `ReaderTapZone`'s tap classification, kept as its own pure type for
/// the same reason: independently unit-testable without a real `DragGesture.Value`
/// (SwiftUI doesn't let you construct one).
///
/// Used by `ReaderView.paginatedContent` (issue #348 — EPUB paginated mode had tap
/// zones but no swipe). A translation only counts as a page-turn swipe if it clears
/// `minimumHorizontalDistance` *and* is more horizontal than vertical — the latter is
/// what keeps a text-selection drag (finger tracking down through lines, mostly
/// vertical travel) from being misread as a swipe.
enum ReaderSwipeGesture: Equatable {
    case previous
    case next
    case none

    static let minimumHorizontalDistance: CGFloat = 60

    static func classify(translation: CGSize) -> ReaderSwipeGesture {
        let horizontal = translation.width
        let vertical = translation.height
        guard abs(horizontal) >= minimumHorizontalDistance else { return .none }
        guard abs(horizontal) > abs(vertical) else { return .none }
        return horizontal < 0 ? .next : .previous
    }
}
