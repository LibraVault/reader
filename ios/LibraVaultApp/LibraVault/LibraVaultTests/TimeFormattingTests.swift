import XCTest
@testable import LibraVault

final class TimeFormattingTests: XCTestCase {

    func testFormatsUnderAMinute() {
        XCTAssertEqual(formatPlaybackTime(45), "0:45")
    }

    func testFormatsExactMinute() {
        XCTAssertEqual(formatPlaybackTime(60), "1:00")
    }

    func testPadsSecondsUnderTen() {
        XCTAssertEqual(formatPlaybackTime(65), "1:05")
    }

    func testFormatsOverAnHourAsMinutes() {
        // PlayerView/SleepTimerSheet only ever show m:ss, no hour component — matches
        // both prior private implementations this replaced.
        XCTAssertEqual(formatPlaybackTime(3661), "61:01")
    }

    func testClampsNegativeSecondsToZero() {
        XCTAssertEqual(formatPlaybackTime(-5), "0:00")
    }

    func testTruncatesFractionalSeconds() {
        XCTAssertEqual(formatPlaybackTime(90.9), "1:30")
    }

    // MARK: - formatPlaybackSpeed
    //
    // Regression coverage for the actual bug: the previous `%.2g` format (significant
    // figures, not decimal places) silently mis-displayed exactly the values the
    // 0.25-step sliders produce — 1.25 rounded to "1.3", 2.75 rounded to "2.7".

    func testFormatsQuarterStepValuesExactly() {
        XCTAssertEqual(formatPlaybackSpeed(1.25), "1.25×")
        XCTAssertEqual(formatPlaybackSpeed(2.75), "2.75×")
        XCTAssertEqual(formatPlaybackSpeed(0.75), "0.75×")
    }

    func testTrimsTrailingZerosOnWholeNumbers() {
        XCTAssertEqual(formatPlaybackSpeed(1.0), "1×")
        XCTAssertEqual(formatPlaybackSpeed(2.0), "2×")
    }

    func testFormatsHalfStepValues() {
        XCTAssertEqual(formatPlaybackSpeed(1.5), "1.5×")
    }
}
