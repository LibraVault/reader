import Foundation

/// Amazon Polly text-to-speech — verified against AWS's SynthesizeSpeech API docs. The
/// one vendor here that isn't a bearer-token REST call: AWS Signature Version 4
/// (SigV4), region-specific host (`polly.{region}.amazonaws.com`), JSON body. See
/// `AwsSigV4Signer`'s class doc for how the signing implementation itself was verified.
/// Direct port of Android's `AmazonPollyAdapter`.
struct AmazonPollyAdapter: VendorTtsAdapter {
    private let session: URLSession
    private let testBaseURL: URL?

    init(session: URLSession = .shared, testBaseURL: URL? = nil) {
        self.session = session
        self.testBaseURL = testBaseURL
    }

    private func baseURL(region: String) -> URL {
        testBaseURL ?? URL(string: "https://\(CloudTtsFixedHosts.pollyHost(region: region))")!
    }

    func synthesize(text: String, voiceID: String, credentials: [CloudCredentialField: String]) async throws -> Data {
        let accessKeyID = try credentials.requiredField(.accessKeyID)
        let secretAccessKey = try credentials.requiredField(.secretAccessKey)
        let region = try credentials.requiredField(.region)

        let payloadData = try VendorTtsAdapterHelpers.jsonBody([
            "Text": text,
            "VoiceId": voiceID,
            "OutputFormat": "mp3",
            "Engine": "neural",
        ])
        let payload = String(decoding: payloadData, as: UTF8.self)

        let url = baseURL(region: region).appendingPathComponent("v1").appendingPathComponent("speech")
        let signed = AwsSigV4Signer.sign(
            method: "POST",
            host: url.host ?? "",
            canonicalUri: "/v1/speech",
            payload: payload,
            region: region,
            accessKeyID: accessKeyID,
            secretAccessKey: secretAccessKey,
            extraSignedHeaders: ["content-type": "application/json"]
        )

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue(signed.amzDate, forHTTPHeaderField: "X-Amz-Date")
        request.setValue(signed.authorizationHeader, forHTTPHeaderField: "Authorization")
        request.httpBody = payloadData

        return try await VendorTtsAdapterHelpers.executeOrFail(session: session, request: request, vendorName: "Amazon Polly")
    }

    func validateKey(credentials: [CloudCredentialField: String]) async throws {
        let accessKeyID = try credentials.requiredField(.accessKeyID)
        let secretAccessKey = try credentials.requiredField(.secretAccessKey)
        let region = try credentials.requiredField(.region)

        let url = baseURL(region: region).appendingPathComponent("v1").appendingPathComponent("voices")
        let signed = AwsSigV4Signer.sign(
            method: "GET",
            host: url.host ?? "",
            canonicalUri: "/v1/voices",
            payload: "",
            region: region,
            accessKeyID: accessKeyID,
            secretAccessKey: secretAccessKey
        )

        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.setValue(signed.amzDate, forHTTPHeaderField: "X-Amz-Date")
        request.setValue(signed.authorizationHeader, forHTTPHeaderField: "Authorization")

        _ = try await VendorTtsAdapterHelpers.executeOrFail(session: session, request: request, vendorName: "Amazon Polly key validation")
    }
}
