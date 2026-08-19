import XCTest
@testable import LibraVault

/// Covers `PlayerScrubberState`, extracted from `PlayerView` per the QA-agent
/// finding on PR #310 (#309): the scrub-bar fix for the principal-review
/// jank bug (dragging the slider fired `appState.seek(to:)` — and therefore
/// a Now Playing sync — on every frame) shipped with no test that would fail
/// on the pre-fix behavior. These tests fail against a naive "just bind the
/// slider straight to `appState.seek(to:)`" implementation, and pass against
/// the fixed drag/commit split.
final class PlayerScrubberStateTests: XCTestCase {
    func testDisplayValueTracksPlaybackWhenNotDragging() {
        var state = PlayerScrubberState()
        XCTAssertEqual(state.displayValue(elapsedSeconds: 42), 42)
        XCTAssertEqual(state.displayValue(elapsedSeconds: 99), 99)
    }

    func testDragStartSeedsFromCurrentPlaybackPositionNotStaleZero() {
        var state = PlayerScrubberState()
        let committed = state.editingChanged(true, elapsedSeconds: 123)
        XCTAssertNil(committed, "starting a drag should not commit a seek")
        XCTAssertEqual(state.displayValue(elapsedSeconds: 0), 123, "should show the seeded drag value, not the live elapsed value")
    }

    func testDraggingUpdatesDisplayValueWithoutTouchingPlaybackPosition() {
        var state = PlayerScrubberState()
        state.editingChanged(true, elapsedSeconds: 10)
        state.updateDragValue(50)
        // Playback itself hasn't moved (still reports 10), but the display
        // should follow the drag, not the stale playback position.
        XCTAssertEqual(state.displayValue(elapsedSeconds: 10), 50)
        state.updateDragValue(75)
        XCTAssertEqual(state.displayValue(elapsedSeconds: 10), 75)
    }

    func testDragEndReturnsExactlyOneValueToCommit() {
        var state = PlayerScrubberState()
        state.editingChanged(true, elapsedSeconds: 10)
        state.updateDragValue(50)
        state.updateDragValue(60)
        state.updateDragValue(70)
        let committed = state.editingChanged(false, elapsedSeconds: 10)
        XCTAssertEqual(committed, 70, "should commit the last dragged-to value, not the seeded start")
    }

    func testAfterDragEndsDisplayFollowsLivePlaybackAgain() {
        var state = PlayerScrubberState()
        state.editingChanged(true, elapsedSeconds: 10)
        state.updateDragValue(70)
        state.editingChanged(false, elapsedSeconds: 10)
        // A remote command (e.g. a Control Center skip) moves playback —
        // the scrubber should reflect that immediately once not dragging.
        XCTAssertEqual(state.displayValue(elapsedSeconds: 200), 200)
    }

    func testUpdateDragValueBeforeAnyDragStartsDoesNotAffectDisplay() {
        var state = PlayerScrubberState()
        // Defensive: a stray set-closure call with isDragging still false
        // (shouldn't happen via the real Slider, but the type shouldn't rely
        // on that) must not leak into the displayed value.
        state.updateDragValue(999)
        XCTAssertEqual(state.displayValue(elapsedSeconds: 5), 5)
    }

    func testFullDragGestureCommitsExactlyOnce() {
        // Regression guard for the actual bug: simulates a whole drag
        // gesture and counts how many times a value would be committed —
        // the pre-fix behavior (binding straight to seek) would commit on
        // every one of the intermediate `updateDragValue` calls too.
        var state = PlayerScrubberState()
        var commits: [Double] = []

        func commitIfNeeded(_ value: Double?) {
            if let value { commits.append(value) }
        }

        commitIfNeeded(state.editingChanged(true, elapsedSeconds: 30))
        for value: Double in [31, 35, 40, 44, 48] {
            state.updateDragValue(value)
        }
        commitIfNeeded(state.editingChanged(false, elapsedSeconds: 30))

        XCTAssertEqual(commits, [48], "exactly one commit, at drag end, with the final drag value")
    }
}
