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

    /// `urlPathAllowed` minus "/" — deliberately excludes it so a voiceID containing
    /// one gets percent-encoded to "%2F" (one opaque path segment) rather than left as
    /// a literal separator. `URL.appendingPathComponent(_:)` does NOT do this: it
    /// treats "/" in its argument as a real path separator (that's the whole point of
    /// the method — appending "a/b/c" in one call is the same as three separate
    /// appends), the opposite of Kotlin's `addPathSegment` (singular), which does
    /// percent-encode the whole string as one segment. This was a REAL bug, not a
    /// hypothetical one: the regression test below failed for real in CI against
    /// `api.elevenlabs.io/v1/text-to-speech/abc/../admin` — the exact path-traversal
    /// shape it exists to catch — before this fix.
    private static let pathSegmentAllowedCharacters: CharacterSet = {
        var allowed = CharacterSet.urlPathAllowed
        allowed.remove(charactersIn: "/")
        return allowed
    }()

    private func synthesizeURL(voiceID: String) throws -> URL {
        guard var components = URLComponents(url: baseURL, resolvingAgainstBaseURL: false),
              let encodedVoiceID = voiceID.addingPercentEncoding(withAllowedCharacters: Self.pathSegmentAllowedCharacters)
        else {
            throw CloudTtsProviderError.invalidResponse
        }
        // percentEncodedPath is stored/used as-is, unlike appendingPathComponent —
        // exactly what's needed once encodedVoiceID is already correctly escaped.
        components.percentEncodedPath = "/v1/text-to-speech/\(encodedVoiceID)"
        guard let url = components.url else {
            throw CloudTtsProviderError.invalidResponse
        }
        return url
    }

    func synthesize(text: String, voiceID: String, credentials: [CloudCredentialField: String]) async throws -> Data {
        let apiKey = try credentials.requiredField(.apiKey)
        let url = try synthesizeURL(voiceID: voiceID)

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
