package xyz.libravault.core.vaultstore

/**
 * The additional, hardware-backed wrapping layer around
 * [xyz.libravault.core.vaultcrypto.VaultKeyMaterial.wrappedVmkByKek] — PRD
 * §7.1, implementation plan §A.4. This is what removes the offline
 * brute-force path against a 4-digit PIN entirely: without it, an attacker who
 * copies `filesDir` could enumerate 10,000 PIN candidates against Argon2id
 * offline; with it, the key material never leaves secure hardware, so
 * guessing has to go through the rate-limited [VaultStore.unlockWithPin] path
 * on the live device.
 *
 * Deliberately an interface, not a call straight to `AndroidKeyStore`: the
 * repo has direct prior experience with the alternative going wrong —
 * `ProStateManager` (core:licensing) hardcodes Keystore/
 * `EncryptedSharedPreferences` access and is consequently untestable in plain
 * JVM unit tests. [VaultStore]'s orchestration logic (create/unlock/lock/
 * import) is tested against [FakeHardwareKeyWrap][xyz.libravault.core.vaultstore.testing]
 * on the JVM; only [AndroidKeystoreHardwareKeyWrap] itself needs a real device
 * to verify (done in the Phase 0 spike; see implementation plan §D.0.RESULTS
 * and the still-owed manual lock-screen-change/re-enrollment check, §A.4).
 */
interface HardwareKeyWrap {

    /** Encrypts [plaintext] under a hardware-backed key. */
    fun wrap(plaintext: ByteArray): WrappedBlob

    /** @throws xyz.libravault.core.vaultcrypto.VaultAuthenticationException if [wrapped] doesn't verify. */
    fun unwrap(wrapped: WrappedBlob): ByteArray

    /**
     * Whether the wrapping key is actually inside secure hardware (a TEE or
     * StrongBox), reported at wrap time. PRD §7.1: "do not silently downgrade"
     * — a caller that gets `false` back should require a passphrase instead of
     * a 4-digit PIN on this device rather than proceeding as if the hardware
     * guarantee were in place, since a 4-digit PIN is only defensible with it.
     */
    val isHardwareBacked: Boolean
}

/** Nonce/ciphertext pair from a [HardwareKeyWrap.wrap] call — deliberately its
 * own type, not reusing [xyz.libravault.core.vaultcrypto.WrappedKey], since
 * that type is `internal` to core:vaultcrypto and not visible here. */
data class WrappedBlob(val nonce: ByteArray, val ciphertext: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is WrappedBlob && nonce.contentEquals(other.nonce) && ciphertext.contentEquals(other.ciphertext)

    override fun hashCode(): Int = 31 * nonce.contentHashCode() + ciphertext.contentHashCode()
}

/**
 * Creates or loads a [HardwareKeyWrap] for a given alias. Split into two
 * operations (rather than one "get or create") because they have genuinely
 * different failure modes: [createNew] can fail with
 * [KeystoreHardwareUnavailableException] (no hardware-backed Keystore on this
 * device — see [AndroidKeystoreHardwareKeyWrap.create]), which is only a
 * meaningful thing to check at vault-creation time, not on every unlock.
 */
interface HardwareKeyWrapFactory {
    fun createNew(keyAlias: String): HardwareKeyWrap
    fun forExisting(keyAlias: String): HardwareKeyWrap
}

/** Real, Android Keystore-backed factory. */
class AndroidHardwareKeyWrapFactory : HardwareKeyWrapFactory {
    override fun createNew(keyAlias: String): HardwareKeyWrap = AndroidKeystoreHardwareKeyWrap.create(keyAlias)
    override fun forExisting(keyAlias: String): HardwareKeyWrap = AndroidKeystoreHardwareKeyWrap.forExistingKey(keyAlias)
}
