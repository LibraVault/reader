import XCTest
@testable import LibraVault

/// Verifies `RealCloudTtsProvider` dispatches to the correct vendor adapter for each
/// `CloudProviderId` — since the dispatcher builds its adapters against the REAL fixed
/// hosts (no test-base-URL override at this layer, unlike each individual adapter),
/// this asserts dispatch correctness by checking which real host each provider's
/// request actually goes to, doubling as a sanity check that the dispatcher wires the
/// real `CloudTtsFixedHosts` values, not placeholders.
final class RealCloudTtsProviderTests: XCTestCase {

    private var provider: RealCloudTtsProvider!

    override func setUp() {
        super.setUp()
        MockURLProtocol.reset()
        provider = RealCloudTtsProvider(session: MockURLProtocol.makeSession())
    }

    func testElevenLabsDispatchesToTheElevenLabsHost() async throws {
        MockURLProtocol.requestHandler = { _ in .init(statusCode: 200, body: Data([1])) }
        _ = try await provider.synthesize(provider: .elevenLabs, text: "hi", voiceID: "v", credentials: [.apiKey: "key"])
        XCTAssertEqual(MockURLProtocol.lastRequest!.url!.host, CloudTtsFixedHosts.elevenLabs)
    }

    func testOpenAIDispatchesToTheOpenAIHost() async throws {
        MockURLProtocol.requestHandler = { _ in .init(statusCode: 200, body: Data([1])) }
        _ = try await provider.synthesize(provider: .openAI, text: "hi", voiceID: "v", credentials: [.apiKey: "key"])
        XCTAssertEqual(MockURLProtocol.lastRequest!.url!.host, CloudTtsFixedHosts.openAI)
    }

    func testGoogleCloudTTSDispatchesToTheGoogleHost() async throws {
        let responseJSON = try! JSONSerialization.data(withJSONObject: ["audioContent": Data([1]).base64EncodedString()])
        MockURLProtocol.requestHandler = { _ in .init(statusCode: 200, body: responseJSON) }
        _ = try await provider.synthesize(provider: .googleCloudTTS, text: "hi", voiceID: "en-US-Wavenet-D", credentials: [.apiKey: "key"])
        XCTAssertEqual(MockURLProtocol.lastRequest!.url!.host, CloudTtsFixedHosts.googleCloudTTS)
    }

    func testAzureSpeechDispatchesToTheRegionScopedSpeechHost() async throws {
        MockURLProtocol.requestHandler = { _ in .init(statusCode: 200, body: Data([1])) }
        _ = try await provider.synthesize(
            provider: .azureSpeech, text: "hi", voiceID: "en-US-JennyNeural",
            credentials: [.apiKey: "key", .region: "eastus"]
        )
        XCTAssertEqual(MockURLProtocol.lastRequest!.url!.host, CloudTtsFixedHosts.azureSpeechHost(region: "eastus"))
    }

    func testAmazonPollyDispatchesToTheRegionScopedPollyHost() async throws {
        MockURLProtocol.requestHandler = { _ in .init(statusCode: 200, body: Data([1])) }
        _ = try await provider.synthesize(
            provider: .amazonPolly, text: "hi", voiceID: "Joanna",
            credentials: [.accessKeyID: "AKIAEXAMPLE", .secretAccessKey: "shh", .region: "us-east-1"]
        )
        XCTAssertEqual(MockURLProtocol.lastRequest!.url!.host, CloudTtsFixedHosts.pollyHost(region: "us-east-1"))
    }

    func testValidateKeyDispatchesToTheSameProviderAsSynthesize() async throws {
        MockURLProtocol.requestHandler = { _ in .init(statusCode: 200) }
        try await provider.validateKey(provider: .openAI, credentials: [.apiKey: "key"])
        XCTAssertEqual(MockURLProtocol.lastRequest!.url!.host, CloudTtsFixedHosts.openAI)
    }
}
