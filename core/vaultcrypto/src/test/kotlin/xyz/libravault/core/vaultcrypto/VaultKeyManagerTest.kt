package xyz.libravault.core.vaultcrypto

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class VaultKeyManagerTest {

    // Small params — correctness tests, not latency benchmarks (§8.4b covered latency on-device).
    private val fastParams = Argon2Params(memoryKiB = 8 * 1024, iterations = 1, parallelism = 1)

    @Test
    fun `unlocking with the correct PIN returns the same VMK that was created`() {
        val created = VaultKeyManager.create("1234".toCharArray(), fastParams)
        val unlocked = VaultKeyManager.unlockWithPin("1234".toCharArray(), created.material)
        assertArrayEquals(created.vmk, unlocked)
    }

    @Test
    fun `unlocking with the wrong PIN fails`() {
        val created = VaultKeyManager.create("1234".toCharArray(), fastParams)
        assertThrows<VaultAuthenticationException> {
            VaultKeyManager.unlockWithPin("9999".toCharArray(), created.material)
        }
    }

    @Test
    fun `unlocking with the recovery key returns the same VMK, independent of the PIN`() {
        val created = VaultKeyManager.create("1234".toCharArray(), fastParams)
        val unlocked = VaultKeyManager.unlockWithRecoveryKey(created.recoveryKey, created.material)
        assertArrayEquals(created.vmk, unlocked)
    }

    @Test
    fun `recovery key path works even if the KEK-wrapped blob is corrupted`() {
        // Simulates implementation plan §A.5's justifying scenario: the
        // Keystore-wrapped layer (added on top of wrappedVmkByKek in Phase 2,
        // outside this module) is lost or corrupted. The recovery path must
        // still work because it never depends on wrappedVmkByKek at all.
        val created = VaultKeyManager.create("1234".toCharArray(), fastParams)
        val corruptedMaterial = created.material.copy(
            wrappedVmkByKek = WrappedKey(
                nonce = created.material.wrappedVmkByKek.nonce,
                ciphertext = ByteArray(created.material.wrappedVmkByKek.ciphertext.size), // garbage
            ),
        )

        val unlocked = VaultKeyManager.unlockWithRecoveryKey(created.recoveryKey, corruptedMaterial)
        assertArrayEquals(created.vmk, unlocked)
    }

    @Test
    fun `wrong recovery key fails`() {
        val created = VaultKeyManager.create("1234".toCharArray(), fastParams)
        val wrongRecoveryKey = ByteArray(VaultFormat.RECOVERY_KEY_SIZE_BYTES)
        assertThrows<VaultAuthenticationException> {
            VaultKeyManager.unlockWithRecoveryKey(wrongRecoveryKey, created.material)
        }
    }

    @Test
    fun `a KEK-wrapped blob cannot be unwrapped as if it were the recovery-wrapped blob`() {
        // Proves the two AAD contexts (KEK_WRAP_AAD vs RECOVERY_WRAP_AAD) actually
        // separate the two wrappings, rather than one accidentally working for both.
        val created = VaultKeyManager.create("1234".toCharArray(), fastParams)
        assertThrows<VaultAuthenticationException> {
            KeyWrap.unwrap(
                created.recoveryKey,
                created.material.wrappedVmkByKek, // wrong blob for this AAD context
                "vaultcrypto:vmk-wrap:recovery:v1".toByteArray(Charsets.US_ASCII),
            )
        }
    }

    @Test
    fun `changePin re-wraps the VMK under a new PIN without touching the recovery wrap`() {
        val created = VaultKeyManager.create("1234".toCharArray(), fastParams)
        val newMaterial = VaultKeyManager.changePin(
            "1234".toCharArray(), "5678".toCharArray(), created.material, fastParams,
        )

        // New PIN works, old PIN doesn't.
        assertArrayEquals(created.vmk, VaultKeyManager.unlockWithPin("5678".toCharArray(), newMaterial))
        assertThrows<VaultAuthenticationException> {
            VaultKeyManager.unlockWithPin("1234".toCharArray(), newMaterial)
        }

        // Recovery wrap is untouched — same VMK, no re-generation needed.
        assertArrayEquals(
            created.vmk,
            VaultKeyManager.unlockWithRecoveryKey(created.recoveryKey, newMaterial),
        )
        assertEquals(created.material.wrappedVmkByRecovery, newMaterial.wrappedVmkByRecovery)
    }

    @Test
    fun `two vaults created independently never share a VMK or recovery key`() {
        val a = VaultKeyManager.create("1234".toCharArray(), fastParams)
        val b = VaultKeyManager.create("1234".toCharArray(), fastParams) // same PIN on purpose
        assertFalse(a.vmk.contentEquals(b.vmk))
        assertFalse(a.recoveryKey.contentEquals(b.recoveryKey))
    }

    private fun assertEquals(expected: Any?, actual: Any?) =
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual)
}
