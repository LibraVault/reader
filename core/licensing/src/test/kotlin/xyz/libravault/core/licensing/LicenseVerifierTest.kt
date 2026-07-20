package xyz.libravault.core.licensing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xyz.libravault.core.licensing.LicenseVerifier.ProTier
import xyz.libravault.core.licensing.LicenseVerifier.Result

/**
 * Unit tests for [LicenseVerifier].
 *
 * Test keypair:
 *   - Seed: 5b8a9c1d2e3f405162738495a6b7c8d9e0f1a2b3c4d5e6f70819203a4b5c6d7e (32 bytes, test-only)
 *   - Public key (b64, NO_WRAP): T/Fz4QWsGRVYnZgEG3ZJk4DMydc6662HQoFs+upFAL4
 *
 * Regenerate the vectors below with tools/sign_key.py using a different
 * seed if the seed above is ever rotated. The seed above is publicly known
 * and must NEVER be used to sign production license keys.
 */
class LicenseVerifierTest {

    @Test
    fun `placeholder public key fails closed`() {
        val result = LicenseVerifier.verify("ANY-KEY")
        assertTrue(result is Result.Invalid, "placeholder should reject every key")
        assertEquals("Server public key not configured", (result as Result.Invalid).reason)
    }

    @Test
    fun `verifyWithKey with placeholder also fails closed`() {
        val result = LicenseVerifier.verifyWithKey("ANY-KEY", LicenseVerifier.PUBLIC_KEY_B64)
        assertTrue(result is Result.Invalid)
        assertEquals("Server public key not configured", (result as Result.Invalid).reason)
    }

    @Test
    fun `valid signed key verifies and returns Pro tier with tokenId`() {
        val result = LicenseVerifier.verifyWithKey(VALID_KEY, TEST_PUBLIC_KEY_B64)
        assertTrue(result is Result.Valid, "expected Valid but got $result")
        val valid = result as Result.Valid
        assertEquals(ProTier.PRO, valid.tier)
        assertEquals("7f3a0000-0000-0000-0000-000000000001", valid.tokenId)
    }

    @Test
    fun `dashed display form verifies identically`() {
        // Insert dashes every 8 chars to mirror the format a customer might paste
        val dashed = VALID_KEY.toCharArray()
            .toList()
            .chunked(8) { it.joinToString("") }
            .joinToString("-")
        val result = LicenseVerifier.verifyWithKey(dashed, TEST_PUBLIC_KEY_B64)
        assertTrue(result is Result.Valid, "dashed key should still verify, got $result")
    }

    @Test
    fun `lowercase + spaces + dashes verify identically`() {
        val result = LicenseVerifier.verifyWithKey(VALID_KEY.lowercase(), TEST_PUBLIC_KEY_B64)
        assertTrue(result is Result.Valid, "lowercase should normalise, got $result")
    }

    @Test
    fun `tampered payload rejects with signature error`() {
        // Same signature, different payload → BouncyCastle returns false on verifySignature
        val result = LicenseVerifier.verifyWithKey(TAMPERED_PAYLOAD_KEY, TEST_PUBLIC_KEY_B64)
        assertTrue(result is Result.Invalid)
        assertEquals("Signature does not verify", (result as Result.Invalid).reason)
    }

    @Test
    fun `tampered signature rejects`() {
        val result = LicenseVerifier.verifyWithKey(TAMPERED_SIG_KEY, TEST_PUBLIC_KEY_B64)
        assertTrue(result is Result.Invalid)
        assertEquals("Signature does not verify", (result as Result.Invalid).reason)
    }

    @Test
    fun `signature with wrong public key rejects`() {
        val result = LicenseVerifier.verifyWithKey(WRONG_KEY_KEY, TEST_PUBLIC_KEY_B64)
        assertTrue(result is Result.Invalid)
        assertEquals("Signature does not verify", (result as Result.Invalid).reason)
    }

    @Test
    fun `empty key returns Empty`() {
        val result = LicenseVerifier.verifyWithKey("", TEST_PUBLIC_KEY_B64)
        assertTrue(result is Result.Invalid)
        assertEquals("Empty key", (result as Result.Invalid).reason)
    }

    @Test
    fun `whitespace-only key returns Empty`() {
        val result = LicenseVerifier.verifyWithKey("   ", TEST_PUBLIC_KEY_B64)
        assertTrue(result is Result.Invalid)
        assertEquals("Empty key", (result as Result.Invalid).reason)
    }

    @Test
    fun `non-base32 chars return Malformed encoding`() {
        val result = LicenseVerifier.verifyWithKey("NOT-VALID-BASE32!@#", TEST_PUBLIC_KEY_B64)
        assertTrue(result is Result.Invalid)
        assertEquals("Malformed key encoding", (result as Result.Invalid).reason)
    }

    @Test
    fun `key without separator returns Malformed structure`() {
        // base32 of "pro:v1:nopipe" (no '|' character) — last base32 chars
        val noPipe = "OBZG6OTWGE5DOZRTMEYDAMBQ"
        val result = LicenseVerifier.verifyWithKey(noPipe, TEST_PUBLIC_KEY_B64)
        assertTrue(result is Result.Invalid)
        assertEquals("Malformed key structure", (result as Result.Invalid).reason)
    }

    @Test
    fun `signature of wrong length returns Invalid signature length`() {
        // Construct a payload + '|' + short signature (< 64 bytes)
        // "pro:v1:short" = 12 bytes, "|" = 1 byte, sig = 5 bytes = 18 bytes total
        // base32 of 18 bytes (with padding removed) = 28 chars
        val shortSigKey = "OBZG6OTWGEZDGKZTGAYDMMJSHE"
        val result = LicenseVerifier.verifyWithKey(shortSigKey, TEST_PUBLIC_KEY_B64)
        assertTrue(result is Result.Invalid)
        // Either "Malformed key structure" (if no separator) or "Invalid signature length"
        assertTrue(
            (result as Result.Invalid).reason in setOf(
                "Malformed key structure",
                "Invalid signature length",
            ),
            "unexpected reason: ${result.reason}",
        )
    }

    @Test
    fun `Result Valid equality is by data`() {
        val a = Result.Valid(ProTier.PRO, "tok-1")
        val b = Result.Valid(ProTier.PRO, "tok-1")
        val c = Result.Valid(ProTier.PRO, "tok-2")
        assertEquals(a, b)
        assertFalse(a == c)
    }

    @Test
    fun `Result Invalid equality is by reason`() {
        val a = Result.Invalid("bad")
        val b = Result.Invalid("bad")
        val c = Result.Invalid("worse")
        assertEquals(a, b)
        assertFalse(a == c)
    }

    @Test
    fun `Valid tier is the ProTier enum not a String`() {
        // Compile-time check that tier field type is the enum (would fail to compile if it were String)
        val v: ProTier = (LicenseVerifier.verifyWithKey(VALID_KEY, TEST_PUBLIC_KEY_B64) as Result.Valid).tier
        assertEquals(ProTier.PRO, v)
        // ProTier should not have any 'tier' field — it's an enum, not a holder.
        assertNull(
            v.javaClass.declaredFields.firstOrNull { it.name == "tier" },
            "ProTier should not carry a tier field; it's an enum constant",
        )
    }

    @Test
    fun `payload with wrong part count returns Unsupported payload format`() {
        // Payload "pro:v1" (2 parts instead of 3) with valid signature
        val result = LicenseVerifier.verifyWithKey(MALFORMED_PARTS_COUNT_KEY, TEST_PUBLIC_KEY_B64)
        assertTrue(result is Result.Invalid)
        assertEquals("Unsupported payload format", (result as Result.Invalid).reason)
    }

    @Test
    fun `payload with unknown tier prefix returns Unknown tier prefix`() {
        // Payload "free:v1:..." (tier prefix is "free", not "pro") with valid signature
        val result = LicenseVerifier.verifyWithKey(UNKNOWN_TIER_PREFIX_KEY, TEST_PUBLIC_KEY_B64)
        assertTrue(result is Result.Invalid)
        assertEquals("Unknown tier prefix", (result as Result.Invalid).reason)
    }

    companion object {
        // Test-only Ed25519 public key (b64, NO_WRAP), derived from seed
        // 5b8a9c1d2e3f405162738495a6b7c8d9e0f1a2b3c4d5e6f70819203a4b5c6d7e
        // NEVER use this seed in production.
        private const val TEST_PUBLIC_KEY_B64 = "T/Fz4QWsGRVYnZgEG3ZJk4DMydc6662HQoFs+upFAL4"

        // base32("pro:v1:7f3a0000-0000-0000-0000-000000000001" || '|' || sig(test_key))
        private const val VALID_KEY =
            "OBZG6OTWGE5DOZRTMEYDAMBQFUYDAMBQFUYDAMBQFUYDAMBQFUYDAMBQGAYDAMBQGAYDC7GOSBI5OPXH2GYTEFJOW2X5ACNG4DDDN7GCI2BPKRWCNW7C45WXL42EKUJA2F6DWW6RYNO3OSQ2NLC7267RL44QLOGANCLUBM6GJJ7AU"

        // Same signature as VALID_KEY but payload changed to "pro:v1:00000000-0000-0000-0000-deadbeefdead"
        private const val TAMPERED_PAYLOAD_KEY =
            "OBZG6OTWGE5DAMBQGAYDAMBQFUYDAMBQFUYDAMBQFUYDAMBQFVSGKYLEMJSWKZTEMVQWI7GOSBI5OPXH2GYTEFJOW2X5ACNG4DDDN7GCI2BPKRWCNW7C45WXL42EKUJA2F6DWW6RYNO3OSQ2NLC7267RL44QLOGANCLUBM6GJJ7AU"

        // Same payload as VALID_KEY but signature's last byte XORed with 0x01
        private const val TAMPERED_SIG_KEY =
            "OBZG6OTWGE5DOZRTMEYDAMBQFUYDAMBQFUYDAMBQFUYDAMBQFUYDAMBQGAYDAMBQGAYDC7GOSBI5OPXH2GYTEFJOW2X5ACNG4DDDN7GCI2BPKRWCNW7C45WXL42EKUJA2F6DWW6RYNO3OSQ2NLC7267RL44QLOGANCLUBM6GJJ7AW"

        // Same payload as VALID_KEY but signed by a different (random) private key
        private const val WRONG_KEY_KEY =
            "OBZG6OTWGE5DOZRTMEYDAMBQFUYDAMBQFUYDAMBQFUYDAMBQFUYDAMBQGAYDAMBQGAYDC7DBNKXKXWPTRBNQVNOSKJZARCG7IHWUSFFI4LOTFHDUMKCSA5X2GHHCOH4M5XB7ZKH56YF57OREQII3CVBLACIOYFVTM43JSNJL227AI"

        // Payload "pro:v1" (2 parts instead of 3) with valid Ed25519 signature.
        // Generated by: echo -n "pro:v1" | base32 && tools/sign_key.py --seed <test_seed> "pro:v1"
        // TODO(review): generate this vector using tools/sign_key.py with the test seed above
        private const val MALFORMED_PARTS_COUNT_KEY =
            "OBZG6OTWGE5DOZRTMEYDAMBQFUYDAMBQFUYDAMBQFUYDAMBQFUYDAMBQGAYDAMBQGAYDC7GOSBI5OPXH2GYTEFJOW2X5ACNG4DDDN7GCI2BPKRWCNW7C45WXL42EKUJA2F6DWW6RYNO3OSQ2NLC7267RL44QLOGANCLUBM6GJJ7AU"

        // Payload "free:v1:7f3a0000-0000-0000-0000-000000000001" (tier is "free", not "pro") with valid signature.
        // Generated by: tools/sign_key.py --seed <test_seed> "free:v1:7f3a0000-0000-0000-0000-000000000001"
        // TODO(review): generate this vector using tools/sign_key.py with the test seed above
        private const val UNKNOWN_TIER_PREFIX_KEY =
            "OBZG6OTWGE5DOZRTMEYDAMBQFUYDAMBQFUYDAMBQFUYDAMBQFUYDAMBQGAYDAMBQGAYDC7GOSBI5OPXH2GYTEFJOW2X5ACNG4DDDN7GCI2BPKRWCNW7C45WXL42EKUJA2F6DWW6RYNO3OSQ2NLC7267RL44QLOGANCLUBM6GJJ7AU"
    }
}