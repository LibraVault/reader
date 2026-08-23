import XCTest
@testable import LibraVault

final class GoogleCloudTtsAdapterTests: XCTestCase {

    private var adapter: GoogleCloudTtsAdapter!

    override func setUp() {
        super.setUp()
        MockURLProtocol.reset()
        adapter = GoogleCloudTtsAdapter(session: MockURLProtocol.makeSession())
    }

    /// Unlike the other four vendors, Google's response is JSON with base64-encoded
    /// audio, not raw bytes — this is the one adapter that must decode it, so this is
    /// the single most important regression guard for this adapter.
    func testSynthesizeDecodesBase64AudioContentFromJSONResponse() async throws {
        let fakeAudio = Data([10, 20, 30])
        let responseJSON = try! JSONSerialization.data(withJSONObject: ["audioContent": fakeAudio.base64EncodedString()])
        MockURLProtocol.requestHandler = { _ in .init(statusCode: 200, body: responseJSON) }

        let result = try await adapter.synthesize(text: "hello", voiceID: "en-US-Wavenet-D", credentials: [.apiKey: "key-test"])

        XCTAssertEqual(result, fakeAudio)
    }

    func testSynthesizeSendsApiKeyAsQueryParameterNotAHeader() async throws {
        let responseJSON = try! JSONSerialization.data(withJSONObject: ["audioContent": Data([1]).base64EncodedString()])
        MockURLProtocol.requestHandler = { _ in .init(statusCode: 200, body: responseJSON) }

        _ = try await adapter.synthesize(text: "hi", voiceID: "en-US-Wavenet-D", credentials: [.apiKey: "key-test"])

        let recorded = MockURLProtocol.lastRequest!
        XCTAssertTrue(recorded.url!.absoluteString.contains("key=key-test"))
    }

    func testSynthesizeSendsLanguageCodeDerivedFromVoiceIDAndTheVoiceNameInTheBody() async throws {
        let responseJSON = try! JSONSerialization.data(withJSONObject: ["audioContent": Data([1]).base64EncodedString()])
        MockURLProtocol.requestHandler = { _ in .init(statusCode: 200, body: responseJSON) }

        _ = try await adapter.synthesize(text: "hi", voiceID: "en-US-Wavenet-D", credentials: [.apiKey: "key-test"])

        let recorded = MockURLProtocol.lastRequest!
        let body = try JSONSerialization.jsonObject(with: recorded.capturedHTTPBody()!) as! [String: Any]
        let voice = body["voice"] as! [String: Any]
        XCTAssertEqual(voice["languageCode"] as? String, "en-US")
        XCTAssertEqual(voice["name"] as? String, "en-US-Wavenet-D")
    }

    func testSynthesizeFailsClosedOnNon2xxResponse() async {
        MockURLProtocol.requestHandler = { _ in .init(statusCode: 500) }
        do {
            _ = try await adapter.synthesize(text: "text", voiceID: "en-US-Wavenet-D", credentials: [.apiKey: "key"])
            XCTFail("expected an error")
        } catch { /* pass */ }
    }

    /// A response missing `audioContent`, or with un-decodable base64, must fail
    /// closed rather than crash or return garbage bytes to the caller.
    func testSynthesizeFailsClosedOnUnparseableResponseBody() async {
        MockURLProtocol.requestHandler = { _ in .init(statusCode: 200, body: Data("not json".utf8)) }
        do {
            _ = try await adapter.synthesize(text: "text", voiceID: "en-US-Wavenet-D", credentials: [.apiKey: "key"])
            XCTFail("expected an error")
        } catch { /* pass */ }
    }

    func testValidateKeySucceedsOn200FromGetV1Voices() async throws {
        MockURLProtocol.requestHandler = { _ in .init(statusCode: 200) }

        try await adapter.validateKey(credentials: [.apiKey: "key-test"])

        let recorded = MockURLProtocol.lastRequest!
        XCTAssertEqual(recorded.httpMethod, "GET")
        XCTAssertTrue(recorded.url!.path.contains("v1/voices"))
    }
}
