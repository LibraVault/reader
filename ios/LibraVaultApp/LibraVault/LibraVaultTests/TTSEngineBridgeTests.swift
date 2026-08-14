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

    // MARK: - Language-matched voice selection
    //
    // Regression coverage for a real field bug: left unset, AVSpeechUtterance.voice
    // defaults to the *device's* system language rather than the language of the
    // text actually being spoken — an English book was read in a Dutch voice on a
    // Dutch-locale iPad. detectedLanguageCode is the pure, unit-testable half of
    // that fix (voice selection itself depends on the real device/Simulator voice
    // catalog, which CI doesn't control — see voice(for:)'s doc comment).

    func testDetectedLanguageCodeRecognizesEnglish() {
        let text = """
        It was the best of times, it was the worst of times, it was the age of
        wisdom, it was the age of foolishness, it was the epoch of belief, it was
        the epoch of incredulity.
        """
        XCTAssertEqual(TTSEngineBridge.detectedLanguageCode(for: text), "en")
    }

    func testDetectedLanguageCodeRecognizesDutch() {
        let text = """
        Het was de beste tijd, het was de slechtste tijd, het was het tijdperk van
        wijsheid, het was het tijdperk van dwaasheid, het was het seizoen van het
        licht, het was het seizoen van de duisternis.
        """
        XCTAssertEqual(TTSEngineBridge.detectedLanguageCode(for: text), "nl")
    }

    func testDetectedLanguageCodeRecognizesFrench() {
        let text = """
        C'était le meilleur des temps, c'était le pire des temps, c'était l'âge de
        la sagesse, c'était l'âge de la sottise, c'était l'époque de la croyance,
        c'était l'époque de l'incrédulité.
        """
        XCTAssertEqual(TTSEngineBridge.detectedLanguageCode(for: text), "fr")
    }

    func testDetectedLanguageCodeReturnsNilForEmptyText() {
        XCTAssertNil(TTSEngineBridge.detectedLanguageCode(for: ""))
    }

    /// Whatever the Simulator's installed voice catalog looks like, a voice
    /// selected for a given language should never claim to speak a *different*
    /// language — the specific field failure this fix targets.
    func testVoiceForTextNeverReturnsAMismatchedLanguage() {
        let english = "The quick brown fox jumps over the lazy dog near the riverbank at dawn."
        if let voice = TTSEngineBridge.voice(for: english) {
            XCTAssertTrue(voice.language.hasPrefix("en"), "got \(voice.language) for English text")
        }
    }

    func testVoiceForEmptyTextIsNil() {
        XCTAssertNil(TTSEngineBridge.voice(for: ""))
    }
}
