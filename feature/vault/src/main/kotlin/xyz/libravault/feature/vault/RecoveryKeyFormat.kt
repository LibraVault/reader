package xyz.libravault.feature.vault

import java.io.ByteArrayOutputStream

private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567" // RFC 4648 §6, no padding

/**
 * Renders/parses the 256-bit recovery key [VaultSessionManager.createVault]
 * returns (PRD §7.2, implementation plan §A.5) as text the user can
 * transcribe or type back in.
 *
 * Base32 (RFC 4648), not hex or Base64: its alphabet has no `0`/`O` or
 * `1`/`I` ambiguity, which matters for something meant to be handwritten or
 * read off a screen once. Grouped in blocks of 4 characters, matching how
 * 2FA/backup recovery codes are typically displayed.
 */
object RecoveryKeyFormat {

    const val RECOVERY_KEY_SIZE_BYTES = 32

    fun toDisplayString(recoveryKey: ByteArray): String =
        encode(recoveryKey).chunked(4).joinToString(" ")

    /** Accepts the grouped display form or a bare string, case-insensitively,
     * ignoring whitespace and any other stray characters (so a copy typed
     * with an extra space or a stray hyphen still parses).
     * @return the 32-byte key, or null if [input] doesn't decode to exactly
     *   [RECOVERY_KEY_SIZE_BYTES] bytes. */
    fun parse(input: String): ByteArray? {
        val cleaned = input.uppercase().filter { it in ALPHABET }
        if (cleaned.isEmpty()) return null
        val decoded = runCatching { decode(cleaned) }.getOrNull() ?: return null
        return decoded.takeIf { it.size == RECOVERY_KEY_SIZE_BYTES }
    }

    private fun encode(data: ByteArray): String {
        if (data.isEmpty()) return ""
        val sb = StringBuilder((data.size * 8 + 4) / 5)
        var buffer = 0
        var bitsLeft = 0
        for (b in data) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bitsLeft += 8
            while (bitsLeft >= 5) {
                bitsLeft -= 5
                sb.append(ALPHABET[(buffer shr bitsLeft) and 0x1F])
            }
        }
        if (bitsLeft > 0) {
            sb.append(ALPHABET[(buffer shl (5 - bitsLeft)) and 0x1F])
        }
        return sb.toString()
    }

    private fun decode(s: String): ByteArray {
        val out = ByteArrayOutputStream()
        var buffer = 0
        var bitsLeft = 0
        for (c in s) {
            val index = ALPHABET.indexOf(c)
            require(index >= 0) { "Invalid Base32 character: $c" }
            buffer = (buffer shl 5) or index
            bitsLeft += 5
            if (bitsLeft >= 8) {
                bitsLeft -= 8
                out.write((buffer shr bitsLeft) and 0xFF)
            }
        }
        return out.toByteArray()
    }
}
