package xyz.libravault.core.vaultcrypto

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Test

/**
 * AES-256-GCM known-answer test vector — proves [AesGcmCipher] wraps the
 * underlying JCA provider correctly against a published, independent source
 * of truth, not just "round-trips with itself."
 *
 * Vector: Project Wycheproof (C2SP/wycheproof), `testvectors_v1/aes_gcm_test.json`,
 * tcId 91 — 256-bit key / 96-bit IV / 128-bit tag group, `result: "valid"`.
 * Fetched and verified against the live file at
 * https://raw.githubusercontent.com/C2SP/wycheproof/main/testvectors_v1/aes_gcm_test.json
 * on 2026-08-14 — do not hand-edit these constants without re-verifying against
 * the source; a fabricated "known answer" is worse than no test at all.
 */
class AesGcmKnownAnswerTest {

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { i -> ((Character.digit(s[i * 2], 16) shl 4) + Character.digit(s[i * 2 + 1], 16)).toByte() }

    @Test
    fun `matches Wycheproof AES-256-GCM known-answer vector (tcId 91)`() {
        val key = hex("92ace3e348cd821092cd921aa3546374299ab46209691bc28b8752d17f123c20")
        val iv = hex("00112233445566778899aabb")
        val aad = hex("00000000ffffffff")
        val msg = hex("00010203040506070809")
        val expectedCt = hex("e27abdd2d2a53d2f136b")
        val expectedTag = hex("9a4a2579529301bcfb71c78d4060f52c")

        val ciphertextWithTag = AesGcmCipher().encrypt(key, iv, aad, msg)

        assertArrayEquals(expectedCt, ciphertextWithTag.copyOfRange(0, msg.size))
        assertArrayEquals(expectedTag, ciphertextWithTag.copyOfRange(msg.size, ciphertextWithTag.size))

        val decrypted = AesGcmCipher().decrypt(key, iv, aad, ciphertextWithTag)
        assertArrayEquals(msg, decrypted)
    }
}
