import Foundation

/// Azure AI Speech text-to-speech — verified against Microsoft Learn's "Text to speech
/// API reference (REST)". Region-specific host (`{region}.tts.speech.microsoft.com`,
/// PRD explicitly calls this out — see `CloudTtsFixedHosts`), `Ocp-Apim-Subscription-Key`
/// header (not a bearer token), SSML request body (`application/ssml+xml`), raw audio
/// bytes back. `X-Microsoft-OutputFormat` selects the audio codec/bitrate.
///
/// Key validation uses a *different* host — Azure's short-lived-token issuance endpoint
/// (`{region}.api.cognitive.microsoft.com`) — because it's a cheap auth-only call,
/// unlike the synthesis endpoint. Direct port of Android's `AzureSpeechAdapter`.
struct AzureSpeechAdapter: VendorTtsAdapter {
    private let session: URLSession
    private let testSpeechURL: URL?
    private let testTokenURL: URL?

    init(session: URLSession = .shared, testSpeechURL: URL? = nil, testTokenURL: URL? = nil) {
        self.session = session
        self.testSpeechURL = testSpeechURL
        self.testTokenURL = testTokenURL
    }

    // testSpeechURL/testTokenURL are BASE overrides (like every other adapter's
    // testBaseURL) — the real path is always appended on top, never skipped, so a test
    // pointed at MockURLProtocol still exercises the real path-building logic.
    private func speechURL(region: String) -> URL {
        (testSpeechURL ?? URL(string: "https://\(CloudTtsFixedHosts.azureSpeechHost(region: region))")!)
            .appendingPathComponent("cognitiveservices").appendingPathComponent("v1")
    }

    private func tokenURL(region: String) -> URL {
        (testTokenURL ?? URL(string: "https://\(CloudTtsFixedHosts.azureTokenHost(region: region))")!)
            .appendingPathComponent("sts").appendingPathComponent("v1.0").appendingPathComponent("issueToken")
    }

    func synthesize(text: String, voiceID: String, credentials: [CloudCredentialField: String]) async throws -> Data {
        let apiKey = try credentials.requiredField(.apiKey)
        let region = try credentials.requiredField(.region)
        let locale = VendorTtsAdapterHelpers.localeFromVoiceID(voiceID)
        // Both text AND voiceID are caller-supplied — both must be XML-escaped, or a
        // voiceID containing an apostrophe/ampersand breaks (or, worst case, injects
        // into) the SSML document.
        let ssml = "<speak version='1.0' xml:lang='\(locale.escapingXML())'>" +
            "<voice name='\(voiceID.escapingXML())'>\(text.escapingXML())</voice></speak>"

        var request = URLRequest(url: speechURL(region: region))
        request.httpMethod = "POST"
        request.setValue("application/ssml+xml", forHTTPHeaderField: "Content-Type")
        request.setValue(apiKey, forHTTPHeaderField: "Ocp-Apim-Subscription-Key")
        request.setValue("audio-16khz-32kbitrate-mono-mp3", forHTTPHeaderField: "X-Microsoft-OutputFormat")
        request.httpBody = Data(ssml.utf8)

        return try await VendorTtsAdapterHelpers.executeOrFail(session: session, request: request, vendorName: "Azure AI Speech")
    }

    func validateKey(credentials: [CloudCredentialField: String]) async throws {
        let apiKey = try credentials.requiredField(.apiKey)
        let region = try credentials.requiredField(.region)

        var request = URLRequest(url: tokenURL(region: region))
        request.httpMethod = "POST"
        request.setValue(apiKey, forHTTPHeaderField: "Ocp-Apim-Subscription-Key")
        request.httpBody = Data()

        _ = try await VendorTtsAdapterHelpers.executeOrFail(session: session, request: request, vendorName: "Azure AI Speech key validation")
    }
}

private extension String {
    func escapingXML() -> String {
        replacingOccurrences(of: "&", with: "&amp;")
            .replacingOccurrences(of: "<", with: "&lt;")
            .replacingOccurrences(of: ">", with: "&gt;")
            .replacingOccurrences(of: "\"", with: "&quot;")
            .replacingOccurrences(of: "'", with: "&apos;")
    }
}
