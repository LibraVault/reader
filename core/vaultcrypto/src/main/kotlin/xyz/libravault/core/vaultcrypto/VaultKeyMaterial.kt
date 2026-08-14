package xyz.libravault.core.vaultcrypto

import java.security.SecureRandom

// AAD context strings distinguish the two wrappings of the VMK so one can never
// be substituted for the other even though both wrap the same key bytes.
private val KEK_WRAP_AAD = "vaultcrypto:vmk-wrap:kek:v1".toByteArray(Charsets.US_ASCII)
private val RECOVERY_WRAP_AAD = "vaultcrypto:vmk-wrap:recovery:v1".toByteArray(Charsets.US_ASCII)

/**
 * Everything needed to later unlock a vault, EXCEPT the secrets themselves
 * (PIN, recovery key, VMK) — this is what gets persisted in the vault header.
 *
 * Per PRD §8.2 point 2b, [wrappedVmkByKek] is wrapped *again* by a non-exportable
 * Android Keystore key in core:vaultstore (Phase 2) — that additional layer is
 * deliberately outside this module, which has no Android dependency at all.
 * [wrappedVmkByRecovery] deliberately bypasses the Keystore layer entirely, so
 * it can still rescue the vault if the Keystore key is ever lost (implementation
 * plan §A.5) — that's why it's a completely independent wrapping here, not
 * derived from or dependent on the KEK path in any way.
 */
data class VaultKeyMaterial(
    val argon2Salt: ByteArray,
    val argon2Params: Argon2Params,
    val wrappedVmkByKek: WrappedKey,
    val wrappedVmkByRecovery: WrappedKey,
) {
    override fun equals(other: Any?): Boolean =
        other is VaultKeyMaterial &&
            argon2Salt.contentEquals(other.argon2Salt) &&
            argon2Params == other.argon2Params &&
            wrappedVmkByKek == other.wrappedVmkByKek &&
            wrappedVmkByRecovery == other.wrappedVmkByRecovery

    override fun hashCode(): Int {
        var result = argon2Salt.contentHashCode()
        result = 31 * result + argon2Params.hashCode()
        result = 31 * result + wrappedVmkByKek.hashCode()
        result = 31 * result + wrappedVmkByRecovery.hashCode()
        return result
    }
}

/** Result of creating a brand-new vault: the persistable [material], the raw
 * [vmk] (ready to use immediately, e.g. to encrypt the first imported file),
 * and the [recoveryKey] that MUST be shown to the user exactly once — this
 * module does not retain or persist it anywhere. */
data class NewVault(
    val material: VaultKeyMaterial,
    val vmk: ByteArray,
    val recoveryKey: ByteArray,
)

/**
 * Creates and unlocks the key hierarchy described in PRD §8.2:
 *
 *   PIN ──Argon2id──► KEK ──┐
 *                           ├─► wrapped VMK (this is then wrapped AGAIN by
 *   (recovery key) ─────────┘    Android Keystore, in core:vaultstore/Phase 2)
 *
 * Callers on Android must additionally apply/remove the Keystore wrap around
 * [VaultKeyMaterial.wrappedVmkByKek] — that's out of scope for this pure-Kotlin
 * module by design (see implementation plan Phase 1 vs Phase 2 split).
 */
object VaultKeyManager {

    private val random = SecureRandom()

    fun create(pin: CharArray, argon2Params: Argon2Params = Argon2Params.DEFAULT): NewVault {
        val vmk = ByteArray(VaultFormat.VMK_SIZE_BYTES).also { random.nextBytes(it) }
        val recoveryKey = ByteArray(VaultFormat.RECOVERY_KEY_SIZE_BYTES).also { random.nextBytes(it) }
        val salt = ByteArray(VaultFormat.ARGON2_SALT_SIZE_BYTES).also { random.nextBytes(it) }

        val kek = Argon2idKdf.deriveKey(pin, salt, argon2Params)
        val wrappedByKek = try {
            KeyWrap.wrap(kek, vmk, KEK_WRAP_AAD)
        } finally {
            kek.fill(0)
        }
        val wrappedByRecovery = KeyWrap.wrap(recoveryKey, vmk, RECOVERY_WRAP_AAD)

        return NewVault(
            material = VaultKeyMaterial(salt, argon2Params, wrappedByKek, wrappedByRecovery),
            vmk = vmk,
            recoveryKey = recoveryKey,
        )
    }

    /** @throws VaultAuthenticationException on a wrong PIN. */
    fun unlockWithPin(pin: CharArray, material: VaultKeyMaterial): ByteArray {
        val kek = Argon2idKdf.deriveKey(pin, material.argon2Salt, material.argon2Params)
        return try {
            KeyWrap.unwrap(kek, material.wrappedVmkByKek, KEK_WRAP_AAD)
        } finally {
            kek.fill(0)
        }
    }

    /**
     * @throws VaultAuthenticationException on a wrong recovery key.
     *
     * Deliberately independent of [unlockWithPin] and of any Keystore state —
     * this is the path that rescues a vault whose Keystore-wrapped key was lost
     * (implementation plan §A.5). It must keep working even if the PIN path is
     * completely broken.
     */
    fun unlockWithRecoveryKey(recoveryKey: ByteArray, material: VaultKeyMaterial): ByteArray =
        KeyWrap.unwrap(recoveryKey, material.wrappedVmkByRecovery, RECOVERY_WRAP_AAD)

    /**
     * Re-wraps the VMK under a new PIN. Only the small [VaultKeyMaterial.wrappedVmkByKek]
     * blob changes — no file content is touched, which is the entire point of the
     * VMK indirection (PRD §8.2 point 2). [wrappedVmkByRecovery] is untouched:
     * changing the PIN does not require (and should not trigger) generating a
     * new recovery key.
     */
    fun changePin(
        oldPin: CharArray,
        newPin: CharArray,
        material: VaultKeyMaterial,
        newArgon2Params: Argon2Params = material.argon2Params,
    ): VaultKeyMaterial {
        val vmk = unlockWithPin(oldPin, material)
        try {
            val newSalt = ByteArray(VaultFormat.ARGON2_SALT_SIZE_BYTES).also { random.nextBytes(it) }
            val newKek = Argon2idKdf.deriveKey(newPin, newSalt, newArgon2Params)
            val newWrappedByKek = try {
                KeyWrap.wrap(newKek, vmk, KEK_WRAP_AAD)
            } finally {
                newKek.fill(0)
            }
            return material.copy(
                argon2Salt = newSalt,
                argon2Params = newArgon2Params,
                wrappedVmkByKek = newWrappedByKek,
            )
        } finally {
            vmk.fill(0)
        }
    }
}
