package xyz.libravault.feature.vault

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.security.SecureRandom

class RecoveryKeyFormatTest {

    // RFC 4648 §10 test vectors, verified against Python's stdlib
    // `base64.b32encode` (not hand-derived), with the '=' padding stripped —
    // this class deliberately produces no padding.
    private val rfc4648Vectors = listOf(
        "" to "",
        "f" to "MY",
        "fo" to "MZXQ",
        "foo" to "MZXW6",
        "foob" to "MZXW6YQ",
        "fooba" to "MZXW6YTB",
        "foobar" to "MZXW6YTBOI",
    )

    @Test
    fun `toDisplayString matches RFC 4648 test vectors, ungrouped`() {
        for ((plain, expected) in rfc4648Vectors) {
            // toDisplayString groups in 4s with spaces; strip those back out
            // to compare against the raw RFC vector.
            val actual = RecoveryKeyFormat.toDisplayString(plain.toByteArray(Charsets.US_ASCII))
                .replace(" ", "")
            assertEquals(expected, actual, "encoding of '$plain'")
        }
    }

    @Test
    fun `parse decodes its own RFC 4648 test vectors`() {
        for ((plain, encoded) in rfc4648Vectors) {
            if (encoded.isEmpty()) continue // parse() rejects empty input, tested separately
            val decoded = RecoveryKeyFormat.parse(encoded)
            // Only the 32-byte-producing vectors are valid recovery keys; the
            // rest exist to pin the codec itself, not parse()'s length gate.
            if (plain.toByteArray(Charsets.US_ASCII).size == RecoveryKeyFormat.RECOVERY_KEY_SIZE_BYTES) {
                assertArrayEquals(plain.toByteArray(Charsets.US_ASCII), decoded)
            } else {
                assertNull(decoded, "expected length rejection for '$plain'")
            }
        }
    }

    @Test
    fun `round-trips a real 256-bit recovery key`() {
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }

        val displayed = RecoveryKeyFormat.toDisplayString(key)
        val parsed = RecoveryKeyFormat.parse(displayed)

        assertArrayEquals(key, parsed)
    }

    @Test
    fun `parse is case-insensitive and tolerates whitespace and stray punctuation`() {
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val displayed = RecoveryKeyFormat.toDisplayString(key)

        val messy = displayed.lowercase().replace(" ", "  -  ") + "  "

        assertArrayEquals(key, RecoveryKeyFormat.parse(messy))
    }

    @Test
    fun `parse rejects input that does not decode to 32 bytes`() {
        assertNull(RecoveryKeyFormat.parse("MZXW6YTB")) // valid Base32, wrong length
    }

    @Test
    fun `parse rejects empty or garbage input`() {
        assertNull(RecoveryKeyFormat.parse(""))
        assertNull(RecoveryKeyFormat.parse("   "))
        assertNull(RecoveryKeyFormat.parse("!!!===++++"))
    }

    @Test
    fun `toDisplayString groups in blocks of 4`() {
        val key = ByteArray(32) { 0 }
        val displayed = RecoveryKeyFormat.toDisplayString(key)

        displayed.split(" ").dropLast(1).forEach { group -> assertEquals(4, group.length) }
    }
}
