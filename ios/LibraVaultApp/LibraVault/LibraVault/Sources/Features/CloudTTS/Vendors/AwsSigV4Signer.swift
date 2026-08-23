import CryptoKit
import Foundation

/// AWS Signature Version 4 request signing, for Amazon Polly (the one vendor here whose
/// real API isn't a bearer-token REST call). Direct Swift port of Android's
/// `AwsSigV4Signer` (core/cloudtts/vendor/AwsSigV4Signer.kt) — same algorithm, same
/// structure, same known-answer test vectors (see `AwsSigV4SignerTests`), so both
/// platforms are cross-checked against the identical `botocore` (the official AWS Python
/// SDK) reference values rather than each needing its own independent verification.
/// Deliberately scoped to exactly what this module's two Polly calls (`POST /v1/speech`,
/// `GET /v1/voices`) need — a small, focused signer, not a general-purpose AWS SDK
/// reimplementation. Uses `CryptoKit` (already a dependency here — see
/// `AesGcmCipher.swift`/`FileContentKey.swift`) for SHA-256/HMAC, not a hand-rolled
/// implementation.
enum AwsSigV4Signer {

    private static let algorithm = "AWS4-HMAC-SHA256"

    struct SignedRequest {
        let amzDate: String
        let authorizationHeader: String
    }

    /// - Parameters:
    ///   - canonicalUri: absolute path, e.g. "/v1/speech" — must already be URI-encoded
    ///     per AWS's rules (none of this module's paths need encoding, so that step is
    ///     deliberately not implemented here, matching the Kotlin signer).
    ///   - extraSignedHeaders: additional headers (beyond `host`) to include in both the
    ///     canonical request and the actual outgoing request — lowercase names, e.g.
    ///     "content-type" to "application/json". Must be exactly the set of non-host,
    ///     non-x-amz-date headers the caller will actually send.
    ///   - now: injectable for deterministic tests — defaults to the real current time
    ///     for production callers (`AmazonPollyAdapter`).
    static func sign(
        method: String,
        host: String,
        canonicalUri: String,
        payload: String,
        region: String,
        accessKeyID: String,
        secretAccessKey: String,
        extraSignedHeaders: [String: String] = [:],
        now: Date = Date()
    ) -> SignedRequest {
        let amzDate = amzDateFormatter.string(from: now)
        let dateStamp = dateStampFormatter.string(from: now)

        var allHeaders = extraSignedHeaders
        allHeaders["host"] = host
        allHeaders["x-amz-date"] = amzDate
        let sortedHeaders = allHeaders.sorted { $0.key < $1.key }
        let canonicalHeaders = sortedHeaders.map { name, value in
            "\(name):\(value.trimmingCharacters(in: .whitespaces))\n"
        }.joined()
        let signedHeaders = sortedHeaders.map(\.key).joined(separator: ";")
        let hashedPayload = sha256Hex(payload)

        let canonicalRequest = [
            method,
            canonicalUri,
            "", // no query string for either call this module makes
            canonicalHeaders,
            signedHeaders,
            hashedPayload,
        ].joined(separator: "\n")

        let credentialScope = "\(dateStamp)/\(region)/polly/aws4_request"
        let stringToSign = [
            algorithm,
            amzDate,
            credentialScope,
            sha256Hex(canonicalRequest),
        ].joined(separator: "\n")

        let signingKey = deriveSigningKey(secretAccessKey: secretAccessKey, dateStamp: dateStamp, region: region, service: "polly")
        let signature = hmacSHA256Hex(key: signingKey, data: stringToSign)

        let authorizationHeader = "\(algorithm) " +
            "Credential=\(accessKeyID)/\(credentialScope), " +
            "SignedHeaders=\(signedHeaders), " +
            "Signature=\(signature)"

        return SignedRequest(amzDate: amzDate, authorizationHeader: authorizationHeader)
    }

    // MARK: - Date formatting

    /// `en_US_POSIX` + fixed UTC time zone: locale-independent, unambiguous formatting —
    /// the same reasoning `Locale(identifier: "en_US_POSIX")` is Apple's own documented
    /// recommendation for any fixed-format (non-user-facing) date string, matching the
    /// Kotlin signer's `ZoneOffset.UTC`-anchored `DateTimeFormatter`.
    private static let amzDateFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(identifier: "UTC")
        formatter.dateFormat = "yyyyMMdd'T'HHmmss'Z'"
        return formatter
    }()

    private static let dateStampFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(identifier: "UTC")
        formatter.dateFormat = "yyyyMMdd"
        return formatter
    }()

    // MARK: - Signing key derivation

    private static func deriveSigningKey(secretAccessKey: String, dateStamp: String, region: String, service: String) -> SymmetricKey {
        let kSecret = SymmetricKey(data: Data("AWS4\(secretAccessKey)".utf8))
        let kDate = hmacSHA256(key: kSecret, data: dateStamp)
        let kRegion = hmacSHA256(key: SymmetricKey(data: kDate), data: region)
        let kService = hmacSHA256(key: SymmetricKey(data: kRegion), data: service)
        return SymmetricKey(data: hmacSHA256(key: SymmetricKey(data: kService), data: "aws4_request"))
    }

    private static func hmacSHA256(key: SymmetricKey, data: String) -> Data {
        Data(HMAC<SHA256>.authenticationCode(for: Data(data.utf8), using: key))
    }

    private static func hmacSHA256Hex(key: SymmetricKey, data: String) -> String {
        hmacSHA256(key: key, data: data).hexString
    }

    private static func sha256Hex(_ data: String) -> String {
        Data(SHA256.hash(data: Data(data.utf8))).hexString
    }
}

private extension Data {
    var hexString: String {
        map { String(format: "%02x", $0) }.joined()
    }
}
