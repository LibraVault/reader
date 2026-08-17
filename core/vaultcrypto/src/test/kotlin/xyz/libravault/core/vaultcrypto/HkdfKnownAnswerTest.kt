package xyz.libravault.core.vaultcrypto

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

/**
 * RFC 5869 Appendix A known-answer tests for [Hkdf].
 *
 * Until this landed, `Hkdf.kt` had **zero** test references anywhere in the
 * repo (docs/TEST_COVERAGE_PRD.md, S1). It was exercised only indirectly,
 * through vault round-trips — which is precisely the shape of coverage that
 * hides a broken KDF: a wrong-but-deterministic HKDF round-trips perfectly,
 * and every existing test still passes.
 *
 * `GoldenVaultInteropTest` now pins HKDF *transitively* (change it and the
 * golden fixture's ciphertext moves), and that proves Android and iOS agree
 * with **each other**. It cannot prove either agrees with the **standard**.
 * That is what this file is for: the expected outputs below come from the RFC,
 * not from our own implementation, so they would catch a shared misreading of
 * the spec that the golden fixture is blind to by construction.
 *
 * Vectors are RFC 5869 Appendix A cases 1–3, all SHA-256:
 *  - A.1 basic
 *  - A.2 longer inputs and outputs, spanning multiple HMAC blocks
 *  - A.3 zero-length salt and info, which is the branch most likely to be
 *    special-cased incorrectly
 *
 * https://www.rfc-editor.org/rfc/rfc5869
 */
class HkdfKnownAnswerTest {

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    // ── RFC 5869 A.1 — Basic test case with SHA-256 ───────────────────────────

    private val a1Ikm = hex("0b".repeat(22))
    private val a1Salt = hex("000102030405060708090a0b0c")
    private val a1Info = hex("f0f1f2f3f4f5f6f7f8f9")
    private val a1Prk = "077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5"
    private val a1Okm = "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf" +
        "34007208d5b887185865"

    @Test
    fun `RFC 5869 A_1 extract produces the specified PRK`() {
        assertEquals(a1Prk, Hkdf.extract(a1Salt, a1Ikm).hex())
    }

    @Test
    fun `RFC 5869 A_1 expand produces the specified OKM`() {
        val prk = Hkdf.extract(a1Salt, a1Ikm)
        assertEquals(a1Okm, Hkdf.expand(prk, a1Info, 42).hex())
    }

    @Test
    fun `RFC 5869 A_1 deriveKey matches extract-then-expand end to end`() {
        assertEquals(a1Okm, Hkdf.deriveKey(a1Salt, a1Ikm, a1Info, 42).hex())
    }

    // ── RFC 5869 A.2 — Longer inputs/outputs (multi-block expand) ─────────────

    private val a2Ikm = ByteArray(0x50) { it.toByte() }
    private val a2Salt = ByteArray(0x50) { (0x60 + it).toByte() }
    private val a2Info = ByteArray(0x50) { (0xb0 + it).toByte() }
    private val a2Prk = "06a6b88c5853361a06104c9ceb35b45cef760014904671014a193f40c15fc244"
    private val a2Okm = "b11e398dc80327a1c8e7f78c596a49344f012eda2d4efad8a050cc4c19afa97c" +
        "59045a99cac7827271cb41c65e590e09da3275600c2f09b8367793a9aca3db71" +
        "cc30c58179ec3e87c14c01d5c1f3434f1d87"

    /**
     * L = 82 needs three HMAC blocks, so this is the case that catches an
     * off-by-one in the expand counter or in the final partial-block copy —
     * neither of which a single-block derivation would reveal.
     */
    @Test
    fun `RFC 5869 A_2 derives 82 bytes across multiple HMAC blocks`() {
        assertEquals(a2Prk, Hkdf.extract(a2Salt, a2Ikm).hex())
        assertEquals(a2Okm, Hkdf.deriveKey(a2Salt, a2Ikm, a2Info, 82).hex())
    }

    // ── RFC 5869 A.3 — Zero-length salt and info ──────────────────────────────

    private val a3Ikm = hex("0b".repeat(22))
    private val a3Prk = "19ef24a32c717b167f33a91d6f648bdf96596776afdb6377ac434c1c293ccb04"
    private val a3Okm = "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d" +
        "9d201395faa4b61a96c8"

    @Test
    fun `RFC 5869 A_3 handles zero-length salt and info`() {
        assertEquals(a3Prk, Hkdf.extract(ByteArray(0), a3Ikm).hex())
        assertEquals(a3Okm, Hkdf.deriveKey(ByteArray(0), a3Ikm, ByteArray(0), 42).hex())
    }

    // ── Properties the vault relies on ────────────────────────────────────────

    /**
     * The whole reason [Hkdf] is used twice over the same VMK (file content key
     * and per-chunk nonce) is that different `info` must yield unrelated
     * output. If it did not, the nonce would be derivable from the content key.
     */
    @Test
    fun `different info values produce different keys from the same IKM`() {
        val a = Hkdf.deriveKey(a1Salt, a1Ikm, "info-a".toByteArray(), 32)
        val b = Hkdf.deriveKey(a1Salt, a1Ikm, "info-b".toByteArray(), 32)
        assertNotEquals(a.hex(), b.hex(), "HKDF info must provide domain separation")
    }

    @Test
    fun `different salts produce different keys from the same IKM`() {
        val a = Hkdf.deriveKey("salt-a".toByteArray(), a1Ikm, a1Info, 32)
        val b = Hkdf.deriveKey("salt-b".toByteArray(), a1Ikm, a1Info, 32)
        assertNotEquals(a.hex(), b.hex())
    }

    /** Derivation must be a pure function — the vault re-derives keys on every open. */
    @Test
    fun `derivation is deterministic across calls`() {
        assertEquals(
            Hkdf.deriveKey(a1Salt, a1Ikm, a1Info, 42).hex(),
            Hkdf.deriveKey(a1Salt, a1Ikm, a1Info, 42).hex(),
        )
    }

    /** A prefix of a longer derivation must equal the shorter derivation (HKDF is a stream). */
    @Test
    fun `shorter derivation is a prefix of a longer one`() {
        val long = Hkdf.deriveKey(a1Salt, a1Ikm, a1Info, 42)
        val short = Hkdf.deriveKey(a1Salt, a1Ikm, a1Info, 16)
        assertEquals(long.copyOf(16).hex(), short.hex())
    }
}
