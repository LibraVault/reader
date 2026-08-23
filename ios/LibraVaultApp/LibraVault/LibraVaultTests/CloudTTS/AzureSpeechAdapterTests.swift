import XCTest
@testable import LibraVault

final class AzureSpeechAdapterTests: XCTestCase {

    private var adapter: AzureSpeechAdapter!
    private let credentials: [CloudCredentialField: String] = [.apiKey: "azure-key", .region: "eastus"]

    // Distinct hostnames (not two separate MockURLProtocols — there's only one global
    // handler) so tests can tell which of the two real hosts (speech synthesis vs.
    // token issuance) a request actually went to, mirroring the two-MockWebServer
    // setup Android's AzureSpeechAdapterTest uses for the same reason.
    private let speechBase = URL(string: "https://mock-speech.test")!
    private let tokenBase = URL(string: "https://mock-token.test")!

    override func setUp() {
        super.setUp()
        MockURLProtocol.reset()
        adapter = AzureSpeechAdapter(session: MockURLProtocol.makeSession(), testSpeechURL: speechBase, testTokenURL: tokenBase)
    }

    func testSynthesizeSendsSSMLWithSubscriptionKeyHeaderAndOutputFormatReturnsRawAudioBytes() async throws {
        let fakeAudio = Data([5, 6, 7])
        MockURLProtocol.requestHandler = { _ in .init(statusCode: 200, body: fakeAudio) }

        let result = try await adapter.synthesize(text: "hello & goodbye", voiceID: "en-US-JennyNeural", credentials: credentials)

        XCTAssertEqual(result, fakeAudio)
        let recorded = MockURLProtocol.lastRequest!
        XCTAssertEqual(recorded.value(forHTTPHeaderField: "Ocp-Apim-Subscription-Key"), "azure-key")
        XCTAssertTrue(recorded.value(forHTTPHeaderField: "Content-Type")?.hasPrefix("application/ssml+xml") == true)
        XCTAssertNotNil(recorded.value(forHTTPHeaderField: "X-Microsoft-OutputFormat"))
        let ssml = String(decoding: recorded.capturedHTTPBody()!, as: UTF8.self)
        XCTAssertTrue(ssml.contains("en-US-JennyNeural"))
        XCTAssertTrue(ssml.contains("hello &amp; goodbye"), "text must be XML-escaped in the SSML body")
    }

    func testSynthesizeFailsClosedOnNon2xxResponse() async {
        MockURLProtocol.requestHandler = { _ in .init(statusCode: 401) }
        do {
            _ = try await adapter.synthesize(text: "text", voiceID: "en-US-JennyNeural", credentials: credentials)
            XCTFail("expected an error")
        } catch { /* pass */ }
    }

    func testQuoteInVoiceIDIsXMLEscapedNotInjectedRawIntoTheSSMLAttribute() async throws {
        MockURLProtocol.requestHandler = { _ in .init(statusCode: 200, body: Data([1])) }

        _ = try await adapter.synthesize(text: "hi", voiceID: "en-US-O'Brien'Neural", credentials: credentials)

        let ssml = String(decoding: MockURLProtocol.lastRequest!.capturedHTTPBody()!, as: UTF8.self)
        XCTAssertTrue(ssml.contains("name='en-US-O&apos;Brien&apos;Neural'"), ssml)
        XCTAssertFalse(ssml.contains("name='en-US-O'Brien'Neural'"), "unescaped apostrophe would break/inject into the SSML attribute")
    }

    func testSynthesizeFailsClosedWhenRegionCredentialIsMissing() async {
        do {
            _ = try await adapter.synthesize(text: "text", voiceID: "en-US-JennyNeural", credentials: [.apiKey: "azure-key"])
            XCTFail("expected .missingCredentials")
        } catch let error as CloudTtsProviderError {
            XCTAssertEqual(error, .missingCredentials(field: .region))
        } catch {
            XCTFail("unexpected error type: \(error)")
        }
    }

    func testValidateKeyCallsTheTokenIssuanceHostNotTheSpeechSynthesisHost() async throws {
        MockURLProtocol.requestHandler = { _ in .init(statusCode: 200, body: Data("fake-token".utf8)) }

        try await adapter.validateKey(credentials: credentials)

        let recorded = MockURLProtocol.lastRequest!
        XCTAssertEqual(recorded.value(forHTTPHeaderField: "Ocp-Apim-Subscription-Key"), "azure-key")
        XCTAssertEqual(recorded.url!.host, tokenBase.host)
        XCTAssertTrue(recorded.url!.path.contains("issueToken"))
    }
}
