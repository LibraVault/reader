package xyz.libravault.core.cloudtts.vendor

import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * AWS Signature Version 4 request signing, for Amazon Polly (the one vendor
 * here whose real API isn't a bearer-token REST call — verified against
 * AWS's own SigV4 documentation, not assumed). Deliberately scoped to
 * exactly what this module's two Polly calls (`POST /v1/speech`,
 * `GET /v1/voices`) need — a small, focused signer, not a general-purpose
 * AWS SDK reimplementation.
 *
 * Implemented directly from AWS's published algorithm
 * (https://docs.aws.amazon.com/general/latest/gr/sigv4-calculate-signature.html),
 * covered by structural/differential unit tests (correct format, deterministic
 * for a fixed input, sensitive to every input changing), and cross-checked with
 * known-answer tests against `botocore` (the official AWS Python SDK)'s own
 * `SigV4Auth`, byte-for-byte, for both real call shapes this module sends — see
 * `AwsSigV4SignerTest`'s `known-answer` tests and #466. That verifies this
 * algorithm against an independent, official implementation for fixed inputs;
 * it is not a substitute for a real live request against Polly, which requires
 * AWS credentials this environment doesn't have.
 */
internal object AwsSigV4Signer {

    private const val ALGORITHM = "AWS4-HMAC-SHA256"
    private val AMZ_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
    private val DATE_STAMP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC)

    data class SignedRequest(
        val amzDate: String,
        val authorizationHeader: String,
    )

    /**
     * @param canonicalUri absolute path, e.g. "/v1/speech" — must already be
     *   URI-encoded per AWS's rules (none of this module's paths need
     *   encoding, so that step is deliberately not implemented here).
     * @param extraSignedHeaders additional headers (beyond `host`) to
     *   include in both the canonical request and the actual outgoing
     *   request — lowercase names, e.g. "content-type" to "application/json".
     *   Must be exactly the set of non-host, non-x-amz-date headers the
     *   caller will actually send.
     */
    fun sign(
        method: String,
        host: String,
        canonicalUri: String,
        payload: String,
        region: String,
        accessKeyId: String,
        secretAccessKey: String,
        extraSignedHeaders: Map<String, String> = emptyMap(),
        now: Instant = Instant.now(),
    ): SignedRequest {
        val amzDate = AMZ_DATE_FORMAT.format(now)
        val dateStamp = DATE_STAMP_FORMAT.format(now)

        val allHeaders = (extraSignedHeaders + mapOf("host" to host, "x-amz-date" to amzDate))
            .toSortedMap()
        val canonicalHeaders = allHeaders.entries.joinToString("") { (name, value) -> "${name}:${value.trim()}\n" }
        val signedHeaders = allHeaders.keys.joinToString(";")
        val hashedPayload = sha256Hex(payload)

        val canonicalRequest = listOf(
            method,
            canonicalUri,
            "", // no query string for either call this module makes
            canonicalHeaders,
            signedHeaders,
            hashedPayload,
        ).joinToString("\n")

        val credentialScope = "$dateStamp/$region/polly/aws4_request"
        val stringToSign = listOf(
            ALGORITHM,
            amzDate,
            credentialScope,
            sha256Hex(canonicalRequest),
        ).joinToString("\n")

        val signingKey = deriveSigningKey(secretAccessKey, dateStamp, region, "polly")
        val signature = hmacSha256Hex(signingKey, stringToSign)

        val authorizationHeader = "$ALGORITHM " +
            "Credential=$accessKeyId/$credentialScope, " +
            "SignedHeaders=$signedHeaders, " +
            "Signature=$signature"

        return SignedRequest(amzDate = amzDate, authorizationHeader = authorizationHeader)
    }

    private fun deriveSigningKey(secretAccessKey: String, dateStamp: String, region: String, service: String): ByteArray {
        val kSecret = "AWS4$secretAccessKey".toByteArray(Charsets.UTF_8)
        val kDate = hmacSha256(kSecret, dateStamp)
        val kRegion = hmacSha256(kDate, region)
        val kService = hmacSha256(kRegion, service)
        return hmacSha256(kService, "aws4_request")
    }

    private fun hmacSha256(key: ByteArray, data: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
    }

    private fun hmacSha256Hex(key: ByteArray, data: String): String = hmacSha256(key, data).toHex()

    private fun sha256Hex(data: String): String =
        MessageDigest.getInstance("SHA-256").digest(data.toByteArray(Charsets.UTF_8)).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
