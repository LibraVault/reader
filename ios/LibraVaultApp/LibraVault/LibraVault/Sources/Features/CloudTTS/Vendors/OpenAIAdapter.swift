import Foundation

/// OpenAI text-to-speech — verified against
/// https://platform.openai.com/docs/api-reference/audio/createSpeech. `POST
/// /v1/audio/speech`, `Authorization: Bearer` header, JSON body `{"model", "input",
/// "voice"}`, raw audio bytes back. Direct port of Android's `OpenAiAdapter`.
struct OpenAIAdapter: VendorTtsAdapter {
    private let session: URLSession
    private let testBaseURL: URL?

    init(session: URLSession = .shared, testBaseURL: URL? = nil) {
        self.session = session
        self.testBaseURL = testBaseURL
    }

    private var baseURL: URL {
        testBaseURL ?? URL(string: "https://\(CloudTtsFixedHosts.openAI)")!
    }

    func synthesize(text: String, voiceID: String, credentials: [CloudCredentialField: String]) async throws -> Data {
        let apiKey = try credentials.requiredField(.apiKey)
        let url = baseURL.appendingPathComponent("v1").appendingPathComponent("audio").appendingPathComponent("speech")

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
        request.httpBody = try VendorTtsAdapterHelpers.jsonBody([
            "model": "tts-1",
            "input": text,
            "voice": voiceID,
        ])

        return try await VendorTtsAdapterHelpers.executeOrFail(session: session, request: request, vendorName: "OpenAI")
    }

    func validateKey(credentials: [CloudCredentialField: String]) async throws {
        let apiKey = try credentials.requiredField(.apiKey)
        let url = baseURL.appendingPathComponent("v1").appendingPathComponent("models")

        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")

        _ = try await VendorTtsAdapterHelpers.executeOrFail(session: session, request: request, vendorName: "OpenAI key validation")
    }
}
