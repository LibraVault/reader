import Foundation

/// Pure drag/commit logic for `PlayerView`'s scrub bar, extracted out of the
/// view so it can be unit-tested without SwiftUI or a real `AppState`.
///
/// Exists because of a principal-review-confirmed bug on PR #310 (#309):
/// the `Slider` used to be bound directly to `appState.seek(to:)`, and
/// `seek(to:)` now also syncs Now Playing info (a synchronous cover-art disk
/// read plus an IPC round-trip to `nowplayingd`) — SwiftUI calls a `Slider`'s
/// `value` binding continuously while the thumb is dragged, so that fired the
/// sync many times per second for the whole gesture. This type tracks the
/// drag's own live position separately, and only ever hands back a value to
/// commit on the drag-ended transition.
struct PlayerScrubberState: Equatable {
    private(set) var isDragging = false
    private(set) var dragValue: Double = 0

    /// What the slider thumb (and the elapsed-time label) should currently
    /// show: the live drag position while dragging, otherwise whatever
    /// `AppState` reports — so external changes (a skip button, a remote
    /// command from Control Center) still show up immediately whenever the
    /// user isn't actively touching the scrubber.
    func displayValue(elapsedSeconds: Double) -> Double {
        isDragging ? dragValue : elapsedSeconds
    }

    /// Feed from the `Slider`'s `value` binding setter, called on every drag
    /// frame. Cheap, local-only — no seek, no Now Playing sync.
    mutating func updateDragValue(_ value: Double) {
        dragValue = value
    }

    /// Mirrors `Slider`'s `onEditingChanged`.
    ///
    /// - Drag start (`isEditing == true`): seeds `dragValue` from the
    ///   current playback position (not a stale 0) and returns `nil` — commit
    ///   nothing yet.
    /// - Drag end (`isEditing == false`): returns the value the caller should
    ///   commit via `appState.seek(to:)` — the only point that should
    ///   actually seek/sync Now Playing.
    @discardableResult
    mutating func editingChanged(_ isEditing: Bool, elapsedSeconds: Double) -> Double? {
        isDragging = isEditing
        guard !isEditing else {
            dragValue = elapsedSeconds
            return nil
        }
        return dragValue
    }
}
