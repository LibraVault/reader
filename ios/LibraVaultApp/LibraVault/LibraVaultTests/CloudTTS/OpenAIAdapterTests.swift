import XCTest
@testable import LibraVault

final class OpenAIAdapterTests: XCTestCase {

    private var adapter: OpenAIAdapter!

    override func setUp() {
        super.setUp()
        MockURLProtocol.reset()
        adapter = OpenAIAdapter(session: MockURLProtocol.makeSession())
    }

    func testSynthesizeSendsBearerHeaderAndModelInputVoiceBodyReturnsRawAudioBytes() async throws {
        let fakeAudio = Data([9, 8, 7])
        MockURLProtocol.requestHandler = { _ in .init(statusCode: 200, body: fakeAudio) }

        let result = try await adapter.synthesize(text: "hello world", voiceID: "alloy", credentials: [.apiKey: "sk-test"])

        XCTAssertEqual(result, fakeAudio)
        let recorded = MockURLProtocol.lastRequest!
        XCTAssertEqual(recorded.value(forHTTPHeaderField: "Authorization"), "Bearer sk-test")
        XCTAssertTrue(recorded.url!.path.contains("v1/audio/speech"))

        let body = try JSONSerialization.jsonObject(with: recorded.capturedHTTPBody()!) as! [String: Any]
        XCTAssertEqual(body["model"] as? String, "tts-1")
        XCTAssertEqual(body["input"] as? String, "hello world")
        XCTAssertEqual(body["voice"] as? String, "alloy")
    }

    func testSynthesizeFailsClosedOnNon2xxResponse() async {
        MockURLProtocol.requestHandler = { _ in .init(statusCode: 500) }
        do {
            _ = try await adapter.synthesize(text: "text", voiceID: "alloy", credentials: [.apiKey: "sk-test"])
            XCTFail("expected an error")
        } catch { /* pass */ }
    }

    func testSynthesizeFailsClosedWhenApiKeyCredentialIsMissing() async {
        do {
            _ = try await adapter.synthesize(text: "text", voiceID: "alloy", credentials: [:])
            XCTFail("expected .missingCredentials")
        } catch let error as CloudTtsProviderError {
            XCTAssertEqual(error, .missingCredentials(field: .apiKey))
        } catch {
            XCTFail("unexpected error type: \(error)")
        }
    }

    func testValidateKeySucceedsOn200FromGetV1Models() async throws {
        MockURLProtocol.requestHandler = { _ in .init(statusCode: 200) }

        try await adapter.validateKey(credentials: [.apiKey: "sk-test"])

        let recorded = MockURLProtocol.lastRequest!
        XCTAssertEqual(recorded.httpMethod, "GET")
        XCTAssertTrue(recorded.url!.path.contains("v1/models"))
        XCTAssertEqual(recorded.value(forHTTPHeaderField: "Authorization"), "Bearer sk-test")
    }

    func testValidateKeyFailsOn401() async {
        MockURLProtocol.requestHandler = { _ in .init(statusCode: 401) }
        do {
            try await adapter.validateKey(credentials: [.apiKey: "sk-bad"])
            XCTFail("expected an error")
        } catch { /* pass */ }
    }
}
