import XCTest
@testable import LibraVault

final class AmazonPollyAdapterTests: XCTestCase {

    private var adapter: AmazonPollyAdapter!
    private let baseURL = URL(string: "https://mock-polly.test")!
    private let credentials: [CloudCredentialField: String] = [
        .accessKeyID: "AKIAEXAMPLE",
        .secretAccessKey: "shh-secret",
        .region: "us-east-1",
    ]

    override func setUp() {
        super.setUp()
        MockURLProtocol.reset()
        adapter = AmazonPollyAdapter(session: MockURLProtocol.makeSession(), testBaseURL: baseURL)
    }

    func testSynthesizeSendsSigV4AuthorizationAndDateHeadersReturnsRawAudioBytes() async throws {
        let fakeAudio = Data([11, 12, 13])
        MockURLProtocol.requestHandler = { _ in .init(statusCode: 200, body: fakeAudio) }

        let result = try await adapter.synthesize(text: "hello", voiceID: "Joanna", credentials: credentials)

        XCTAssertEqual(result, fakeAudio)
        let recorded = MockURLProtocol.lastRequest!
        XCTAssertTrue(recorded.value(forHTTPHeaderField: "Authorization")?.hasPrefix("AWS4-HMAC-SHA256 Credential=AKIAEXAMPLE/") == true)
        XCTAssertNotNil(recorded.value(forHTTPHeaderField: "X-Amz-Date"))
        XCTAssertEqual(recorded.value(forHTTPHeaderField: "Content-Type"), "application/json")
    }

    func testSynthesizeSendsTextVoiceIdOutputFormatAndNeuralEngineInTheBody() async throws {
        MockURLProtocol.requestHandler = { _ in .init(statusCode: 200, body: Data([1])) }

        _ = try await adapter.synthesize(text: "hello world", voiceID: "Joanna", credentials: credentials)

        let recorded = MockURLProtocol.lastRequest!
        let body = try JSONSerialization.jsonObject(with: recorded.capturedHTTPBody()!) as! [String: Any]
        XCTAssertEqual(body["Text"] as? String, "hello world")
        XCTAssertEqual(body["VoiceId"] as? String, "Joanna")
        XCTAssertEqual(body["OutputFormat"] as? String, "mp3")
        XCTAssertEqual(body["Engine"] as? String, "neural")
    }

    func testSynthesizeFailsClosedOnNon2xxResponse() async {
        MockURLProtocol.requestHandler = { _ in .init(statusCode: 403) }
        do {
            _ = try await adapter.synthesize(text: "text", voiceID: "Joanna", credentials: credentials)
            XCTFail("expected an error")
        } catch { /* pass */ }
    }

    func testSynthesizeFailsClosedWhenSecretAccessKeyCredentialIsMissing() async {
        let incomplete: [CloudCredentialField: String] = [.accessKeyID: "AKIAEXAMPLE", .region: "us-east-1"]
        do {
            _ = try await adapter.synthesize(text: "text", voiceID: "Joanna", credentials: incomplete)
            XCTFail("expected .missingCredentials")
        } catch let error as CloudTtsProviderError {
            XCTAssertEqual(error, .missingCredentials(field: .secretAccessKey))
        } catch {
            XCTFail("unexpected error type: \(error)")
        }
    }

    func testValidateKeySendsAGetRequestToV1VoicesWithSigV4Headers() async throws {
        MockURLProtocol.requestHandler = { _ in .init(statusCode: 200) }

        try await adapter.validateKey(credentials: credentials)

        let recorded = MockURLProtocol.lastRequest!
        XCTAssertEqual(recorded.httpMethod, "GET")
        XCTAssertTrue(recorded.url!.path.contains("v1/voices"))
        XCTAssertNotNil(recorded.value(forHTTPHeaderField: "Authorization"))
        XCTAssertNotNil(recorded.value(forHTTPHeaderField: "X-Amz-Date"))
    }

    func testValidateKeyFailsOn403() async {
        MockURLProtocol.requestHandler = { _ in .init(statusCode: 403) }
        do {
            try await adapter.validateKey(credentials: credentials)
            XCTFail("expected an error")
        } catch { /* pass */ }
    }
}
