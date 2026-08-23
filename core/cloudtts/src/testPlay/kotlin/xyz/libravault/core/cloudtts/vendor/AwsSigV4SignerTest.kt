package xyz.libravault.core.cloudtts.vendor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Structural/differential coverage, not known-answer: this implementation
 * was written directly from AWS's published SigV4 algorithm (see
 * AwsSigV4Signer's class doc), but no trustworthy verbatim AWS test-vector
 * fixture was available in this environment to assert an exact expected
 * signature against — asserting a wrong hardcoded value would be worse than
 * asserting nothing, since it would look like verification without being
 * any. These tests instead verify the properties that must hold regardless:
 * correct format, determinism, and sensitivity to every signed input. Real
 * live-request verification is tracked separately (see the class doc).
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
}
