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

    // MARK: - Preferred voice override (#506)

    func testResolvedVoiceFallsBackToAutomaticWhenNoPreferenceIsSet() async {
        let bridge = TTSEngineBridge()
        let english = "The quick brown fox jumps over the lazy dog near the riverbank at dawn."
        XCTAssertEqual(bridge.resolvedVoice(for: english)?.identifier, TTSEngineBridge.voice(for: english)?.identifier)
    }

    func testResolvedVoiceUsesThePreferredIdentifierWhenSet() async throws {
        guard let englishVoice = AVSpeechSynthesisVoice(language: "en-US") else {
            throw XCTSkip("No en-US voice installed on this runner")
        }
        let bridge = TTSEngineBridge()
        await bridge.setVoice(identifier: englishVoice.identifier)

        // Deliberately pass Dutch text - if the override weren't taking
        // priority, the automatic language-detected pick would return a
        // Dutch voice instead, not the preferred English one.
        let dutch = "Het was de beste tijd, het was de slechtste tijd."
        XCTAssertEqual(bridge.resolvedVoice(for: dutch)?.identifier, englishVoice.identifier)
    }

    func testResolvedVoiceFallsBackToAutomaticForAStalePreferredIdentifier() async {
        let bridge = TTSEngineBridge()
        await bridge.setVoice(identifier: "not-a-real-voice-identifier")

        let english = "The quick brown fox jumps over the lazy dog near the riverbank at dawn."
        XCTAssertEqual(bridge.resolvedVoice(for: english)?.identifier, TTSEngineBridge.voice(for: english)?.identifier)
    }

    func testSetVoiceNilClearsAPreviouslySetPreference() async throws {
        guard let englishVoice = AVSpeechSynthesisVoice(language: "en-US") else {
            throw XCTSkip("No en-US voice installed on this runner")
        }
        let bridge = TTSEngineBridge()
        await bridge.setVoice(identifier: englishVoice.identifier)
        await bridge.setVoice(identifier: nil)

        let english = "The quick brown fox jumps over the lazy dog near the riverbank at dawn."
        XCTAssertEqual(bridge.resolvedVoice(for: english)?.identifier, TTSEngineBridge.voice(for: english)?.identifier)
    }

    // MARK: - Segment-aware narration (#499 v2a Phase A)
    //
    // speak(segments:rate:)'s real work (SSML construction, the parse-failure
    // fallback) is guarded behind isRunningUnderXCTest the same way
    // speak(text:rate:) already is — real synthesis isn't practical to unit
    // test in a headless CI Simulator. SSMLRenderer itself (the pure logic
    // that would build the SSML string) is covered directly in
    // SSMLRendererTests; this just confirms the entry point doesn't crash on
    // the two edge inputs that matter here.

    func testSpeakSegmentsOnEmptyArrayDoesNotCrash() async {
        let bridge = TTSEngineBridge()
        await bridge.speak(segments: [], rate: 1.0)
    }

    func testSpeakSegmentsOnRealSegmentsDoesNotCrash() async {
        let bridge = TTSEngineBridge()
        await bridge.speak(
            segments: [
                NarrationSegment(text: "Chapter One", kind: .heading, pauseBefore: .paragraph),
                NarrationSegment(text: "Some emphasized text.", kind: .emphasis),
            ],
            rate: 1.0
        )
    }
}
