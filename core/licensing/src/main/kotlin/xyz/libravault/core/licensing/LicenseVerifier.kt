package xyz.libravault.core.licensing

import android.util.Base64
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/**
 * Verifies LibraVault Pro license keys entirely offline.
 *
 * Key format (wire): base32( payload || '|' || signature )
 * payload  = "pro:v1:<uuid>" as UTF-8 bytes
 * signature = raw 64-byte Ed25519 signature of payload
 *
 * Forks under GPLv3 must generate their own keypair (scripts/gen_keypair.py)
 * and replace PUBLIC_KEY_B64 below.
 */
object LicenseVerifier {

    // Replace with your own Ed25519 public key (32 bytes, base64 encoded).
    private const val PUBLIC_KEY_B64 =
        "REPLACE_WITH_YOUR_BASE64_ED25519_PUBLIC_KEY_32_BYTES"

    private val SEPARATOR = '|'.code.toByte()

    sealed class Result {
        data class Valid(val tier: String, val tokenId: String) : Result()
        data class Invalid(val reason: String) : Result()
    }

    fun verify(licenseKey: String): Result {
        val cleaned = licenseKey.trim().replace("-", "").replace(" ", "").uppercase()
        if (cleaned.isEmpty()) return Result.Invalid("Empty key")

        val raw = try {
            decodeBase32(cleaned)
        } catch (e: Exception) {
            return Result.Invalid("Malformed key encoding")
        }

        val sepIdx = raw.indexOfLast { it == SEPARATOR }
        if (sepIdx < 0 || sepIdx == raw.size - 1) {
            return Result.Invalid("Malformed key structure")
        }
        val payload   = raw.copyOfRange(0, sepIdx)
        val signature = raw.copyOfRange(sepIdx + 1, raw.size)

        if (signature.size != 64) return Result.Invalid("Invalid signature length")

        val publicKeyBytes = Base64.decode(PUBLIC_KEY_B64, Base64.NO_WRAP)
        val publicKey      = Ed25519PublicKeyParameters(publicKeyBytes, 0)
        val verifier       = Ed25519Signer().apply {
            init(false, publicKey)
            update(payload, 0, payload.size)
        }

        if (!verifier.verifySignature(signature)) return Result.Invalid("Signature does not verify")

        val payloadStr = String(payload, Charsets.UTF_8)
        val parts      = payloadStr.split(":")
        if (parts.size != 3 || parts[0] != "pro" || parts[1] != "v1") {
            return Result.Invalid("Unsupported payload format")
        }

        return Result.Valid(tier = parts[0], tokenId = parts[2])
    }

    // RFC 4648 base32 decode (uppercase, padding optional).
    private fun decodeBase32(s: String): ByteArray {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val out      = java.io.ByteArrayOutputStream()
        var buffer   = 0
        var bits     = 0
        for (c in s) {
            if (c == '=') break
            val idx = alphabet.indexOf(c)
            if (idx < 0) throw IllegalArgumentException("Bad base32 char: $c")
            buffer = (buffer shl 5) or idx
            bits  += 5
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }
}
