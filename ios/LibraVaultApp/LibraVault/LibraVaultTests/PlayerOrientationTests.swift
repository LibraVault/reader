import XCTest
@testable import LibraVault

/// Covers the actual routing condition PlayerView's GeometryReader uses to pick
/// between portraitContent and landscapeContent (see #164/#165) — a plain, no-UI
/// test since size comparison needs no Simulator/rendering, unlike the flaky
/// AVFoundation-backed player state (see feedback_ios_avfoundation_ci_hang).
final class PlayerOrientationTests: XCTestCase {

    func testWiderThanTallIsLandscape() {
        XCTAssertTrue(isLandscapeOrientation(size: CGSize(width: 844, height: 390)))
    }

    func testTallerThanWideIsNotLandscape() {
        XCTAssertFalse(isLandscapeOrientation(size: CGSize(width: 390, height: 844)))
    }

    func testSquareIsNotLandscape() {
        // Ties resolve to portrait — matches the existing portraitContent default
        // for e.g. the initial zero-size layout pass before GeometryReader settles.
        XCTAssertFalse(isLandscapeOrientation(size: CGSize(width: 500, height: 500)))
    }
}
