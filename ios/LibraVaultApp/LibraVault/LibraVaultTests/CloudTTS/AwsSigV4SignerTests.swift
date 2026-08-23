import XCTest
@testable import LibraVault

/// Structural/differential coverage, plus known-answer cross-checks (see the two
/// `testKnownAnswer*` tests below) against `botocore` (the official AWS Python SDK)'s
/// own `SigV4Auth`, for the exact two request shapes `AmazonPollyAdapter` sends.
///
/// These are the SAME expected values already cross-checked against `botocore` for
/// Android's `AwsSigV4SignerTest` (see #466) — reused here rather than re-deriving a
/// second set, so both platforms' signers are verified against the identical
/// independent reference rather than each needing its own separate check. If this
/// signer's output matches these pinned values, and Android's identical signer matches
/// the same values, the two platforms are provably computing the same algorithm.
final class AwsSigV4SignerTests: XCTestCase {

    private let fixedNow = ISO8601DateFormatter().date(from: "2026-08-23T12:00:00Z")!

    private func sign(secretAccessKey: String = "supersecret", payload: String = "{}") -> AwsSigV4Signer.SignedRequest {
        AwsSigV4Signer.sign(
            method: "POST",
            host: "polly.us-east-1.amazonaws.com",
            canonicalUri: "/v1/speech",
            payload: payload,
            region: "us-east-1",
            accessKeyID: "AKIAEXAMPLE",
            secretAccessKey: secretAccessKey,
            extraSignedHeaders: ["content-type": "application/json"],
            now: fixedNow
        )
    }

    func testAuthorizationHeaderHasCorrectFormat() {
        let signed = sign()
        let expectedPrefix = "AWS4-HMAC-SHA256 Credential=AKIAEXAMPLE/20260823/us-east-1/polly/aws4_request, " +
            "SignedHeaders=content-type;host;x-amz-date, Signature="
        XCTAssertTrue(signed.authorizationHeader.hasPrefix(expectedPrefix), signed.authorizationHeader)
    }

    func testSignatureIs64CharacterLowercaseHex() {
        let signature = sign().authorizationHeader.components(separatedBy: "Signature=").last!
        XCTAssertEqual(signature.count, 64)
        XCTAssertTrue(signature.allSatisfy { "0123456789abcdef".contains($0) })
    }

    func testAmzDateMatchesFixedInstantInISO8601BasicFormat() {
        XCTAssertEqual(sign().amzDate, "20260823T120000Z")
    }

    func testSigningIsDeterministicForIdenticalInputs() {
        XCTAssertEqual(sign().authorizationHeader, sign().authorizationHeader)
    }

    func testSignatureChangesWhenSecretAccessKeyChanges() {
        XCTAssertNotEqual(
            sign(secretAccessKey: "secret-a").authorizationHeader,
            sign(secretAccessKey: "secret-b").authorizationHeader
        )
    }

    func testSignatureChangesWhenPayloadChanges() {
        XCTAssertNotEqual(
            sign(payload: "{\"a\":1}").authorizationHeader,
            sign(payload: "{\"a\":2}").authorizationHeader
        )
    }

    /// Known-answer test for the `AmazonPollyAdapter.synthesize` request shape — see
    /// this file's class doc for how the expected value was derived (cross-checked
    /// against `botocore` for Android's identical signer, #466).
    func testKnownAnswerSignatureMatchesOfficialAWSSDKForSynthesizeSpeechRequestShape() {
        let signed = AwsSigV4Signer.sign(
            method: "POST",
            host: "polly.us-east-1.amazonaws.com",
            canonicalUri: "/v1/speech",
            payload: "{\"Text\":\"Hello world\",\"VoiceId\":\"Joanna\",\"OutputFormat\":\"mp3\",\"Engine\":\"neural\"}",
            region: "us-east-1",
            accessKeyID: "AKIAIOSFODNN7EXAMPLE",
            secretAccessKey: "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
            extraSignedHeaders: ["content-type": "application/json"],
            now: ISO8601DateFormatter().date(from: "2015-08-30T12:36:00Z")!
        )

        XCTAssertEqual(signed.amzDate, "20150830T123600Z")
        XCTAssertEqual(
            signed.authorizationHeader,
            "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20150830/us-east-1/polly/aws4_request, " +
                "SignedHeaders=content-type;host;x-amz-date, " +
                "Signature=4d30b49509edca4827c3d5f7764f8b2906a03c284b330650374379d947282f24"
        )
    }

    /// Known-answer test for the `AmazonPollyAdapter.validateKey` request shape (GET,
    /// no extra signed headers, empty payload).
    func testKnownAnswerSignatureMatchesOfficialAWSSDKForListVoicesRequestShape() {
        let signed = AwsSigV4Signer.sign(
            method: "GET",
            host: "polly.eu-west-1.amazonaws.com",
            canonicalUri: "/v1/voices",
            payload: "",
            region: "eu-west-1",
            accessKeyID: "AKIAIOSFODNN7EXAMPLE",
            secretAccessKey: "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
            now: ISO8601DateFormatter().date(from: "2023-01-15T09:30:00Z")!
        )

        XCTAssertEqual(signed.amzDate, "20230115T093000Z")
        XCTAssertEqual(
            signed.authorizationHeader,
            "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20230115/eu-west-1/polly/aws4_request, " +
                "SignedHeaders=host;x-amz-date, " +
                "Signature=3ae2b0f9f9373d144ea638e3be5a3db5d6dacaa702fc836c9c2a9f5dbc9c86df"
        )
    }
}
