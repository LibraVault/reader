import Foundation

/// ElevenLabs text-to-speech — verified against
/// https://elevenlabs.io/docs/api-reference/text-to-speech/convert (not assumed).
/// `POST /v1/text-to-speech/{voice_id}`, header `xi-api-key` (not a bearer token), JSON
/// body `{"text": "..."}`, raw audio bytes back. Direct port of Android's
/// `ElevenLabsAdapter`.
struct ElevenLabsAdapter: VendorTtsAdapter {
    private let session: URLSession
    private let testBaseURL: URL?

    init(session: URLSession = .shared, testBaseURL: URL? = nil) {
        self.session = session
        self.testBaseURL = testBaseURL
    }

    private var baseURL: URL {
        testBaseURL ?? URL(string: "https://\(CloudTtsFixedHosts.elevenLabs)")!
    }

    func synthesize(text: String, voiceID: String, credentials: [CloudCredentialField: String]) async throws -> Data {
        let apiKey = try credentials.requiredField(.apiKey)
        // appendingPathComponent (not raw string interpolation into a path) percent-
        // encodes voiceID as one opaque segment, so a voiceID containing a "/" can't be
        // misread as extra path segments.
        let url = baseURL
            .appendingPathComponent("v1")
            .appendingPathComponent("text-to-speech")
            .appendingPathComponent(voiceID)

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue(apiKey, forHTTPHeaderField: "xi-api-key")
        request.httpBody = try VendorTtsAdapterHelpers.jsonBody(["text": text])

        return try await VendorTtsAdapterHelpers.executeOrFail(session: session, request: request, vendorName: "ElevenLabs")
    }

    func validateKey(credentials: [CloudCredentialField: String]) async throws {
        let apiKey = try credentials.requiredField(.apiKey)
        let url = baseURL.appendingPathComponent("v1").appendingPathComponent("user")

        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.setValue(apiKey, forHTTPHeaderField: "xi-api-key")

        _ = try await VendorTtsAdapterHelpers.executeOrFail(session: session, request: request, vendorName: "ElevenLabs key validation")
    }
}
