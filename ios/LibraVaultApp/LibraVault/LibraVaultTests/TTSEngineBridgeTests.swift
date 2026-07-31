import XCTest
import AVFoundation
@testable import LibraVault

/// AVSpeechSynthesizer itself isn't a practical thing to unit test (real speech
/// synthesis in a headless CI simulator), so this covers the one pure, unit-testable
/// piece of TTSEngineBridge: the playbackSpeed → AVSpeechUtterance.rate mapping.
/// Everything else (speak/stop/pause/resume actually producing audio) is manually
/// verified.
final class TTSEngineBridgeTests: XCTestCase {
    func testScaledRateAtNormalSpeedEqualsAppleDefaultRate() {
        XCTAssertEqual(TTSEngineBridge.scaledRate(for: 1.0), AVSpeechUtteranceDefaultSpeechRate)
    }

    func testScaledRateDoublesForDoubleSpeed() {
        let normal = TTSEngineBridge.scaledRate(for: 1.0)
        let doubled = TTSEngineBridge.scaledRate(for: 2.0)
        XCTAssertEqual(doubled, normal * 2, accuracy: 0.001)
    }

    // A speed of 0.01 alone isn't enough to hit the floor (0.5 * 0.01 = 0.005, still
    // above AVSpeechUtteranceMinimumSpeechRate's 0.0) — only a non-positive speed
    // actually exercises the clamp, since the unclamped result would otherwise go
    // negative.
    func testScaledRateClampsToAppleMinimumForNonPositiveSpeeds() {
        XCTAssertEqual(TTSEngineBridge.scaledRate(for: -1.0), AVSpeechUtteranceMinimumSpeechRate)
    }

    func testScaledRateClampsToAppleMaximumForVeryFastSpeeds() {
        XCTAssertEqual(TTSEngineBridge.scaledRate(for: 100.0), AVSpeechUtteranceMaximumSpeechRate)
    }
}
