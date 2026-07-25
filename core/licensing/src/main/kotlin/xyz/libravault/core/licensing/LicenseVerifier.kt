package xyz.libravault.core.licensing

import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/**
 * Verifies LibraVault Pro license keys entirely offline.
 *
 * Key format (wire): base32( payload || '|' || signature )
 * payload  = "pro:v1:<uuid>" as UTF-8 bytes
 * signature = raw 64-byte Ed25519 signature of payload
 *
 * Forks under GPLv3 must generate their own keypair (tools/gen_keypair.py)
 * and replace PUBLIC_KEY_B64 below.
 */
object LicenseVerifier {

    /**
     * Replace with your own Ed25519 public key (32 bytes, base64 encoded).
     *
     * The placeholder literal is detected at runtime and causes every
     * [verify] call to short-circuit with [Result.Invalid]. This prevents
     * an accidental production release where the placeholder public key
     * would reject all valid signatures (functionally correct) but also
     * mask a silent misconfiguration during development.
     */
    const val PUBLIC_KEY_B64 =
        "REPLACE_WITH_YOUR_BASE64_ED25519_PUBLIC_KEY_32_BYTES"

    private const val PLACEHOLDER_PREFIX = "REPLACE_WITH_"

    private val SEPARATOR = '|'.code.toByte()

    /** Pro-tier enum — currently only [PRO] is issued. Add future tiers here. */
    enum class ProTier { PRO }

    sealed class Result {
        data class Valid(val tier: ProTier, val tokenId: String) : Result()
        data class Invalid(val reason: String) : Result()
    }

    fun verify(licenseKey: String): Result =
        verifyWithKey(licenseKey, PUBLIC_KEY_B64)

    /**
     * Test seam — same as [verify] but accepts the public key as an argument
     * so unit tests can exercise the full Ed25519 roundtrip against a known
     * test keypair without modifying the production constant. Production code
     * calls [verify]; tests call this.
     */
    internal fun verifyWithKey(licenseKey: String, publicKeyB64: String): Result {
        if (publicKeyB64.startsWith(PLACEHOLDER_PREFIX)) {
            return Result.Invalid("Server public key not configured")
        }

        val cleaned = licenseKey.trim().replace("-", "").replace(" ", "").uppercase()
        if (cleaned.isEmpty()) return Result.Invalid("Empty key")

        val raw = try {
            decodeBase32(cleaned)
        } catch (e: Exception) {
            return Result.Invalid("Malformed key encoding")
        }

        val sepIdx = raw.indexOf(SEPARATOR)
        if (sepIdx < 0 || sepIdx == raw.size - 1) {
            return Result.Invalid("Malformed key structure")
        }
        val payload   = raw.copyOfRange(0, sepIdx)
        val signature = raw.copyOfRange(sepIdx + 1, raw.size)

        if (signature.size != 64) return Result.Invalid("Invalid signature length")

        val publicKeyBytes = java.util.Base64.getDecoder().decode(publicKeyB64)
        val publicKey      = Ed25519PublicKeyParameters(publicKeyBytes, 0)
        val verifier       = Ed25519Signer().apply {
            init(false, publicKey)
            update(payload, 0, payload.size)
        }

        if (!verifier.verifySignature(signature)) return Result.Invalid("Signature does not verify")

        val payloadStr = String(payload, Charsets.UTF_8)
        val parts      = payloadStr.split(":")
        if (parts.size != 3) return Result.Invalid("Unsupported payload format")
        // Explicit branch — review finding #15: previously folded into a single
        // inequality check that could never trigger because the constructor
        // also required 'pro'/'v1'. Splitting out makes future format evolution
        // (e.g. v2, "pro-lifetime") a deliberate edit instead of an accidental one.
        when {
            parts[0] != "pro" -> return Result.Invalid("Unknown tier prefix")
            parts[1] != "v1"  -> return Result.Invalid("Unsupported key version")
        }

        return Result.Valid(tier = ProTier.PRO, tokenId = parts[2])
    }

    // RFC 4648 base32 decode (uppercase, padding optional).
    private fun decodeBase32(s: String): ByteArray {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val out      = java.io.ByteArrayOutputStream()
        var buffer   = 0L
        var bits     = 0
        for (c in s) {
            if (c == '=') break
            val idx = alphabet.indexOf(c)
            if (idx < 0) throw IllegalArgumentException("Bad base32 char: $c")
            buffer = (buffer shl 5) or idx.toLong()
            bits  += 5
            // Drop the bits we just emitted so the next iteration's shift
            // doesn't accumulate garbage in the high bits of `buffer`.
            if (bits >= 8) {
                bits -= 8
                out.write(((buffer shr bits) and 0xFF).toInt())
                buffer = buffer and ((1L shl bits) - 1L)
            }
        }
        return out.toByteArray()
    }
}
