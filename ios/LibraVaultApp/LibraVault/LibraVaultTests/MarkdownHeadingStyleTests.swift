import XCTest
@testable import LibraVault

final class MarkdownHeadingStyleTests: XCTestCase {

    // Regression coverage for #314: a linear (fixed pt step) scale made H1 vs H2
    // barely distinguishable and let H5 collide exactly with H6.

    func testEachLevelIsStrictlyLargerThanTheNextSmallest() {
        let sizes = (1...6).map { MarkdownHeadingStyle.headingSize(for: $0, fontSize: 1.0) }
        for (larger, smaller) in zip(sizes, sizes.dropFirst()) {
            XCTAssertGreaterThan(larger, smaller)
        }
    }

    func testH5AndH6NoLongerCollide() {
        let h5 = MarkdownHeadingStyle.headingSize(for: 5, fontSize: 1.0)
        let h6 = MarkdownHeadingStyle.headingSize(for: 6, fontSize: 1.0)
        XCTAssertNotEqual(h5, h6)
        XCTAssertGreaterThan(h5, h6)
    }

    func testConsecutiveLevelsKeepAConstantRelativeStep() {
        // Geometric scale: the ratio between any two adjacent levels should be the
        // same constant (scaleRatio), unlike the old fixed-pt-step scale where the
        // relative jump shrank as the base size grew.
        let sizes = (1...6).map { MarkdownHeadingStyle.headingSize(for: $0, fontSize: 1.0) }
        let ratios = zip(sizes, sizes.dropFirst()).map { $0 / $1 }
        for ratio in ratios {
            XCTAssertEqual(ratio, MarkdownHeadingStyle.scaleRatio, accuracy: 0.0001)
        }
    }

    func testH1IsMoreThanTwentyPercentLargerThanH2() {
        let h1 = MarkdownHeadingStyle.headingSize(for: 1, fontSize: 1.0)
        let h2 = MarkdownHeadingStyle.headingSize(for: 2, fontSize: 1.0)
        XCTAssertGreaterThanOrEqual(h1 / h2, 1.2)
    }

    func testScalesLinearlyWithFontSizeSetting() {
        let base = MarkdownHeadingStyle.headingSize(for: 3, fontSize: 1.0)
        let doubled = MarkdownHeadingStyle.headingSize(for: 3, fontSize: 2.0)
        XCTAssertEqual(doubled, base * 2, accuracy: 0.0001)
    }

    func testOutOfRangeLevelsClampToTheNearestValidLevel() {
        let h1 = MarkdownHeadingStyle.headingSize(for: 1, fontSize: 1.0)
        let h6 = MarkdownHeadingStyle.headingSize(for: 6, fontSize: 1.0)
        XCTAssertEqual(MarkdownHeadingStyle.headingSize(for: 0, fontSize: 1.0), h1, accuracy: 0.0001)
        XCTAssertEqual(MarkdownHeadingStyle.headingSize(for: -3, fontSize: 1.0), h1, accuracy: 0.0001)
        XCTAssertEqual(MarkdownHeadingStyle.headingSize(for: 7, fontSize: 1.0), h6, accuracy: 0.0001)
        XCTAssertEqual(MarkdownHeadingStyle.headingSize(for: 99, fontSize: 1.0), h6, accuracy: 0.0001)
    }
}
