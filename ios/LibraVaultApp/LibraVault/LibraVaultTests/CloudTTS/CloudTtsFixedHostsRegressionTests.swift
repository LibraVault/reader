import XCTest
@testable import LibraVault

/// PRD §7: "Regression test pinning the five fixed endpoint hosts, so a future change
/// can't silently add a sixth or turn one into a user-configurable value without that
/// being a deliberate, reviewed diff." Every assertion here failing is the point —
/// changing any of these values must show up as a diff to THIS file in code review, not
/// just a diff buried inside an adapter. Direct port of Android's
/// `CloudTtsFixedHostsRegressionTest`.
final class CloudTtsFixedHostsRegressionTests: XCTestCase {

    func testElevenLabsHostIsPinned() {
        XCTAssertEqual(CloudTtsFixedHosts.elevenLabs, "api.elevenlabs.io")
    }

    func testOpenAIHostIsPinned() {
        XCTAssertEqual(CloudTtsFixedHosts.openAI, "api.openai.com")
    }

    func testGoogleCloudTTSHostIsPinned() {
        XCTAssertEqual(CloudTtsFixedHosts.googleCloudTTS, "texttospeech.googleapis.com")
    }

    func testAzureSpeechHostPatternIsPinnedToTheMicrosoftCognitiveServicesDomain() {
        XCTAssertEqual(CloudTtsFixedHosts.azureSpeechHost(region: "eastus"), "eastus.tts.speech.microsoft.com")
        XCTAssertEqual(CloudTtsFixedHosts.azureTokenHost(region: "eastus"), "eastus.api.cognitive.microsoft.com")
    }

    func testAmazonPollyHostPatternIsPinnedToTheAWSDomain() {
        XCTAssertEqual(CloudTtsFixedHosts.pollyHost(region: "us-east-1"), "polly.us-east-1.amazonaws.com")
    }
}
