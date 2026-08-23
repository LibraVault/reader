package xyz.libravault.core.cloudtts.vendor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Structural/differential coverage, plus known-answer cross-checks (see the
 * two `known-answer` tests below) against `botocore` (the official AWS
 * Python SDK)'s own `SigV4Auth` for the exact two request shapes
 * `AmazonPollyAdapter` sends — see #466.
 */
class AwsSigV4SignerTest {

    private val fixedNow = Instant.parse("2026-08-23T12:00:00Z")

    private fun sign(secretAccessKey: String = "supersecret", payload: String = "{}") = AwsSigV4Signer.sign(
        method = "POST",
        host = "polly.us-east-1.amazonaws.com",
        canonicalUri = "/v1/speech",
        payload = payload,
        region = "us-east-1",
        accessKeyId = "AKIAEXAMPLE",
        secretAccessKey = secretAccessKey,
        extraSignedHeaders = mapOf("content-type" to "application/json"),
        now = fixedNow,
    )

    @Test
    fun `authorization header has the correct AWS4-HMAC-SHA256 format`() {
        val signed = sign()
        val expectedPrefix = "AWS4-HMAC-SHA256 Credential=AKIAEXAMPLE/20260823/us-east-1/polly/aws4_request, " +
            "SignedHeaders=content-type;host;x-amz-date, Signature="
        assertTrue(signed.authorizationHeader.startsWith(expectedPrefix), signed.authorizationHeader)
    }

    @Test
    fun `signature is a 64-character lowercase hex string`() {
        val signature = sign().authorizationHeader.substringAfter("Signature=")
        assertEquals(64, signature.length)
        assertTrue(signature.all { it in "0123456789abcdef" })
    }

    @Test
    fun `amzDate matches the fixed instant in ISO8601 basic format`() {
        assertEquals("20260823T120000Z", sign().amzDate)
    }

    @Test
    fun `signing is deterministic for identical inputs`() {
        assertEquals(sign().authorizationHeader, sign().authorizationHeader)
    }

    @Test
    fun `signature changes when the secret access key changes`() {
        assertNotEquals(sign(secretAccessKey = "secret-a").authorizationHeader, sign(secretAccessKey = "secret-b").authorizationHeader)
    }

    @Test
    fun `signature changes when the payload changes`() {
        assertNotEquals(sign(payload = "{\"a\":1}").authorizationHeader, sign(payload = "{\"a\":2}").authorizationHeader)
    }

    /**
     * Known-answer test, not just structural: expected value cross-checked against
     * `botocore` (the official AWS Python SDK)'s `SigV4Auth.canonical_request` /
     * `.string_to_sign` / `.signature`, called directly with a pinned timestamp
     * (bypassing `add_auth`'s `utcnow()`), for AWS's published example keypair
     * (`AKIAIOSFODNN7EXAMPLE` / `wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY`) and the
     * exact request shape `AmazonPollyAdapter.synthesize` sends. `botocore`'s
     * canonical request and string-to-sign matched this signer's byte-for-byte
     * before the final signature was even compared. See #466.
     */
    @Test
    fun `known-answer signature matches the official AWS SDK for the SynthesizeSpeech request shape`() {
        val signed = AwsSigV4Signer.sign(
            method = "POST",
            host = "polly.us-east-1.amazonaws.com",
            canonicalUri = "/v1/speech",
            payload = "{\"Text\":\"Hello world\",\"VoiceId\":\"Joanna\",\"OutputFormat\":\"mp3\",\"Engine\":\"neural\"}",
            region = "us-east-1",
            accessKeyId = "AKIAIOSFODNN7EXAMPLE",
            secretAccessKey = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
            extraSignedHeaders = mapOf("content-type" to "application/json"),
            now = Instant.parse("2015-08-30T12:36:00Z"),
        )

        assertEquals("20150830T123600Z", signed.amzDate)
        assertEquals(
            "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20150830/us-east-1/polly/aws4_request, " +
                "SignedHeaders=content-type;host;x-amz-date, " +
                "Signature=4d30b49509edca4827c3d5f7764f8b2906a03c284b330650374379d947282f24",
            signed.authorizationHeader,
        )
    }

    /**
     * Known-answer test for the `AmazonPollyAdapter.validateKey` request shape
     * (GET, no extra signed headers, empty payload) — see the class doc on the
     * test above for how the expected value was derived.
     */
    @Test
    fun `known-answer signature matches the official AWS SDK for the ListVoices request shape`() {
        val signed = AwsSigV4Signer.sign(
            method = "GET",
            host = "polly.eu-west-1.amazonaws.com",
            canonicalUri = "/v1/voices",
            payload = "",
            region = "eu-west-1",
            accessKeyId = "AKIAIOSFODNN7EXAMPLE",
            secretAccessKey = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
            now = Instant.parse("2023-01-15T09:30:00Z"),
        )

        assertEquals("20230115T093000Z", signed.amzDate)
        assertEquals(
            "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20230115/eu-west-1/polly/aws4_request, " +
                "SignedHeaders=host;x-amz-date, " +
                "Signature=3ae2b0f9f9373d144ea638e3be5a3db5d6dacaa702fc836c9c2a9f5dbc9c86df",
            signed.authorizationHeader,
        )
    }
}
