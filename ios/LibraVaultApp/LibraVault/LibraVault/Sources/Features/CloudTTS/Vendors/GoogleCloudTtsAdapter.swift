import Foundation

/// Google Cloud Text-to-Speech — verified against
/// https://cloud.google.com/text-to-speech/docs/reference/rest/v1/text/synthesize.
/// `POST /v1/text:synthesize?key={apiKey}` (simple-API-key auth, not OAuth — the right
/// fit for a paste-your-key BYOK UX). Unlike the other four vendors, the response is
/// JSON with base64-encoded audio (`{"audioContent": "..."}`), not raw bytes — this is
/// the one adapter that must decode it. Direct port of Android's `GoogleCloudTtsAdapter`.
struct GoogleCloudTtsAdapter: VendorTtsAdapter {
    private struct SynthesizeResponse: Decodable {
        let audioContent: String
    }

    private let session: URLSession
    private let testBaseURL: URL?

    init(session: URLSession = .shared, testBaseURL: URL? = nil) {
        self.session = session
        self.testBaseURL = testBaseURL
    }

    private var baseURL: URL {
        testBaseURL ?? URL(string: "https://\(CloudTtsFixedHosts.googleCloudTTS)")!
    }

    func synthesize(text: String, voiceID: String, credentials: [CloudCredentialField: String]) async throws -> Data {
        let apiKey = try credentials.requiredField(.apiKey)
        let url = try urlWithKey(apiKey, pathComponents: ["v1", "text:synthesize"])

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try VendorTtsAdapterHelpers.jsonBody([
            "input": ["text": text],
            "voice": [
                "languageCode": VendorTtsAdapterHelpers.localeFromVoiceID(voiceID),
                "name": voiceID,
            ],
            "audioConfig": ["audioEncoding": "MP3"],
        ])

        let data = try await VendorTtsAdapterHelpers.executeOrFail(session: session, request: request, vendorName: "Google Cloud TTS")
        // ignoreUnknownKeys-equivalent for free: JSONDecoder only decodes the keys this
        // struct declares — Google's response may carry fields beyond audioContent
        // (e.g. timepoints) depending on the request, and a strict decoder that failed
        // on those would turn a technically-successful synthesis into a spurious
        // failure the moment Google's response shape gains a field this adapter
        // doesn't care about.
        let decoded = try JSONDecoder().decode(SynthesizeResponse.self, from: data)
        guard let audioData = Data(base64Encoded: decoded.audioContent) else {
            throw CloudTtsProviderError.invalidResponse
        }
        return audioData
    }

    func validateKey(credentials: [CloudCredentialField: String]) async throws {
        let apiKey = try credentials.requiredField(.apiKey)
        let url = try urlWithKey(apiKey, pathComponents: ["v1", "voices"])

        var request = URLRequest(url: url)
        request.httpMethod = "GET"

        _ = try await VendorTtsAdapterHelpers.executeOrFail(session: session, request: request, vendorName: "Google Cloud TTS key validation")
    }

    private func urlWithKey(_ apiKey: String, pathComponents: [String]) throws -> URL {
        var url = baseURL
        pathComponents.forEach { url.appendPathComponent($0) }
        guard var components = URLComponents(url: url, resolvingAgainstBaseURL: false) else {
            throw CloudTtsProviderError.invalidResponse
        }
        components.queryItems = [URLQueryItem(name: "key", value: apiKey)]
        guard let finalURL = components.url else {
            throw CloudTtsProviderError.invalidResponse
        }
        return finalURL
    }
}
