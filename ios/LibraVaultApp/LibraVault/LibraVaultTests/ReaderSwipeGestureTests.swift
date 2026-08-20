import XCTest
@testable import LibraVault

final class ReaderSwipeGestureTests: XCTestCase {

    func testLeftSwipePastThresholdIsNext() {
        XCTAssertEqual(ReaderSwipeGesture.classify(translation: CGSize(width: -80, height: 0)), .next)
    }

    func testRightSwipePastThresholdIsPrevious() {
        XCTAssertEqual(ReaderSwipeGesture.classify(translation: CGSize(width: 80, height: 0)), .previous)
    }

    func testShortHorizontalDragBelowThresholdIsNone() {
        XCTAssertEqual(ReaderSwipeGesture.classify(translation: CGSize(width: -30, height: 0)), .none)
    }

    func testMostlyVerticalDragIsNone() {
        // A text-selection drag tracking down through lines of text: far more
        // vertical travel than horizontal, even though the horizontal component
        // alone clears the distance threshold.
        XCTAssertEqual(ReaderSwipeGesture.classify(translation: CGSize(width: -70, height: 200)), .none)
    }

    func testDiagonalDragWithMoreHorizontalThanVerticalStillCounts() {
        XCTAssertEqual(ReaderSwipeGesture.classify(translation: CGSize(width: -90, height: 20)), .next)
    }

    func testEqualHorizontalAndVerticalIsNone() {
        XCTAssertEqual(ReaderSwipeGesture.classify(translation: CGSize(width: -70, height: 70)), .none)
    }

    func testZeroTranslationIsNone() {
        XCTAssertEqual(ReaderSwipeGesture.classify(translation: .zero), .none)
    }
}
