import XCTest
@testable import LibraVault

final class ReaderTapZoneTests: XCTestCase {

    func testLeftThirdIsPrevious() {
        XCTAssertEqual(ReaderTapZone.classify(x: 0, width: 300), .previous)
        XCTAssertEqual(ReaderTapZone.classify(x: 98, width: 300), .previous)
    }

    func testRightThirdIsNext() {
        XCTAssertEqual(ReaderTapZone.classify(x: 300, width: 300), .next)
        XCTAssertEqual(ReaderTapZone.classify(x: 202, width: 300), .next)
    }

    func testMiddleThirdIsCenter() {
        XCTAssertEqual(ReaderTapZone.classify(x: 150, width: 300), .center)
        XCTAssertEqual(ReaderTapZone.classify(x: 100, width: 300), .center)
        XCTAssertEqual(ReaderTapZone.classify(x: 200, width: 300), .center)
    }

    func testZeroWidthDefaultsToCenter() {
        XCTAssertEqual(ReaderTapZone.classify(x: 0, width: 0), .center)
    }
}
