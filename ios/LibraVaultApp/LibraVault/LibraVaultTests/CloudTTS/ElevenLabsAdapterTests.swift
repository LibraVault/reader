import XCTest
@testable import LibraVault

final class ElevenLabsAdapterTests: XCTestCase {

    private var adapter: ElevenLabsAdapter!

    override func setUp() {
        super.setUp()
        MockURLProtocol.reset()
        adapter = ElevenLabsAdapter(session: MockURLProtocol.makeSession())
    }

    func testSynthesizeSendsApiKeyHeaderAndVoiceIDInPathReturnsRawAudioBytes() async throws {
        let fakeAudio = Data([1, 2, 3, 4])
        MockURLProtocol.requestHandler = { _ in .init(statusCode: 200, body: fakeAudio) }

        let result = try await adapter.synthesize(text: "hello world", voiceID: "voice-123", credentials: [.apiKey: "sk-test"])

        XCTAssertEqual(result, fakeAudio)
        let recorded = MockURLProtocol.lastRequest!
        XCTAssertEqual(recorded.value(forHTTPHeaderField: "xi-api-key"), "sk-test")
        XCTAssertTrue(recorded.url!.path.contains("voice-123"))
        let bodyString = String(decoding: recorded.capturedHTTPBody()!, as: UTF8.self)
        XCTAssertTrue(bodyString.contains("hello world"))
    }

    func testSynthesizeFailsClosedOnNon2xxResponse() async {
        MockURLProtocol.requestHandler = { _ in .init(statusCode: 500) }

        do {
            _ = try await adapter.synthesize(text: "text", voiceID: "voice", credentials: [.apiKey: "sk-test"])
            XCTFail("expected an error")
        } catch {
            // any error is a pass — this only asserts it fails closed, not the shape
        }
    }

    /// A slash in voiceID must be percent-encoded as one opaque path segment, not
    /// split into extra path segments (path traversal risk) — mirrors Android's
    /// identical regression test for the same `addPathSegment` (singular) choice.
    func testSlashInVoiceIDIsPercentEncodedNotSplitIntoExtraPathSegments() async throws {
        MockURLProtocol.requestHandler = { _ in .init(statusCode: 200, body: Data("audio".utf8)) }

        _ = try await adapter.synthesize(text: "hi", voiceID: "abc/../admin", credentials: [.apiKey: "sk-test"])

        let recorded = MockURLProtocol.lastRequest!
        // Must NOT resolve to /v1/text-to-speech/admin (path traversal) or any extra
        // segment — the whole voiceID is one percent-encoded segment.
        XCTAssertTrue(recorded.url!.absoluteString.contains("abc%2F..%2Fadmin"), recorded.url!.absoluteString)
    }

    func testSynthesizeFailsClosedWhenApiKeyCredentialIsMissing() async {
        do {
            _ = try await adapter.synthesize(text: "text", voiceID: "voice", credentials: [:])
            XCTFail("expected .missingCredentials")
        } catch let error as CloudTtsProviderError {
            XCTAssertEqual(error, .missingCredentials(field: .apiKey))
        } catch {
            XCTFail("unexpected error type: \(error)")
        }
    }

    func testValidateKeySucceedsOn200FromGetV1User() async throws {
        MockURLProtocol.requestHandler = { _ in .init(statusCode: 200) }

        try await adapter.validateKey(credentials: [.apiKey: "sk-test"])

        let recorded = MockURLProtocol.lastRequest!
        XCTAssertEqual(recorded.httpMethod, "GET")
        XCTAssertTrue(recorded.url!.path.contains("v1/user"))
    }

    func testValidateKeyFailsOn401() async {
        MockURLProtocol.requestHandler = { _ in .init(statusCode: 401) }

        do {
            try await adapter.validateKey(credentials: [.apiKey: "sk-bad"])
            XCTFail("expected an error")
        } catch {
            // pass
        }
    }
}
