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
}
