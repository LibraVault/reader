package xyz.libravault.core.vaultstore

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import xyz.libravault.core.vaultcrypto.VaultAuthenticationException
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

private const val ANDROID_KEYSTORE = "AndroidKeyStore"
private const val TRANSFORMATION = "AES/GCM/NoPadding"
private const val GCM_TAG_BITS = 128
private const val GCM_NONCE_BYTES = 12

/**
 * Real [HardwareKeyWrap] backed by a non-exportable Android Keystore AES key,
 * one per vault (keyed by [keyAlias]).
 *
 * PRD §7.1 requirements, all deliberate, none defaults to leave unexamined:
 *  - `setUserAuthenticationRequired(false)` — binding to the lock screen would
 *    invalidate the key on a PIN/pattern change, destroying the vault for a
 *    reason that has nothing to do with the vault's own PIN. See
 *    implementation plan §A.4 failure case (a).
 *  - `setInvalidatedByBiometricEnrollment(false)` — same reasoning for
 *    biometric re-enrollment, failure case (b).
 *  - StrongBox attempted first, with a graceful fallback to the TEE if the
 *    device doesn't offer StrongBox (most don't) — verified both are viable
 *    on-device in the Phase 0 spike (implementation plan §D.0.RESULTS: both
 *    Galaxy A12 and Pixel 6 reported `TRUSTED_ENVIRONMENT`; StrongBox only on
 *    the Pixel 6). NOT a silent fallback to a *software*-backed key: if
 *    neither StrongBox nor a TEE-backed key can be created, key generation
 *    fails loudly and [isHardwareBacked] is never trusted implicitly — see
 *    [create].
 */
class AndroidKeystoreHardwareKeyWrap private constructor(
    private val keyAlias: String,
    override val isHardwareBacked: Boolean,
) : HardwareKeyWrap {

    private fun loadKey() =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.getKey(keyAlias, null)

    override fun wrap(plaintext: ByteArray): WrappedBlob {
        val nonce = ByteArray(GCM_NONCE_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, loadKey(), GCMParameterSpec(GCM_TAG_BITS, nonce))
        return WrappedBlob(nonce, cipher.doFinal(plaintext))
    }

    override fun unwrap(wrapped: WrappedBlob): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, loadKey(), GCMParameterSpec(GCM_TAG_BITS, wrapped.nonce))
        return try {
            cipher.doFinal(wrapped.ciphertext)
        } catch (e: AEADBadTagException) {
            throw VaultAuthenticationException(e)
        } catch (e: BadPaddingException) {
            throw VaultAuthenticationException(e)
        }
    }

    companion object {
        /**
         * Generates a fresh Keystore key for [keyAlias] (any existing key under
         * that alias is replaced) and returns a wrapper around it.
         *
         * @throws KeystoreHardwareUnavailableException neither StrongBox nor a
         *   TEE-backed key could be created — this device cannot back the
         *   4-digit-PIN threat model at all (PRD §7.2's guarantee depends on
         *   this layer). Callers MUST NOT fall back to a software-backed key
         *   silently; the caller should require a passphrase on this device
         *   instead, or refuse to create the vault.
         */
        fun create(keyAlias: String): AndroidKeystoreHardwareKeyWrap {
            val strongBoxResult = runCatching { generateKey(keyAlias, strongBox = true) }
            val usedStrongBox = strongBoxResult.isSuccess
            if (!usedStrongBox) {
                generateKey(keyAlias, strongBox = false).getOrElse {
                    throw KeystoreHardwareUnavailableException(it)
                }
            }

            val securityLevel = readSecurityLevel(keyAlias)
            val isHardwareBacked = securityLevel == KeyProperties.SECURITY_LEVEL_STRONGBOX ||
                securityLevel == KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT

            if (!isHardwareBacked) {
                // Generated, but landed in software — do NOT silently accept it as
                // the offline-attack mitigation PRD §7.1 promises. Remove it and fail
                // loudly; the caller decides what to do next (passphrase, refuse).
                runCatching {
                    KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(keyAlias)
                }
                throw KeystoreHardwareUnavailableException(
                    IllegalStateException("Generated key reported security level $securityLevel, not hardware-backed"),
                )
            }

            return AndroidKeystoreHardwareKeyWrap(keyAlias, isHardwareBacked = true)
        }

        /**
         * Wraps an already-created Keystore key (e.g. on vault unlock, after
         * [create] ran once before).
         *
         * @throws KeystoreKeyLostException the key no longer exists — this is
         *   implementation plan §A.4 failure case (c), the scenario the
         *   recovery key (§A.5) exists to rescue. Distinct from
         *   [KeystoreHardwareUnavailableException] (no hardware Keystore at
         *   all) because the two need different handling upstream: this one
         *   means "fall back to the recovery key," not "this device can't
         *   support the feature."
         */
        fun forExistingKey(keyAlias: String): AndroidKeystoreHardwareKeyWrap {
            val exists = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.containsAlias(keyAlias)
            if (!exists) throw KeystoreKeyLostException(keyAlias)

            val level = readSecurityLevel(keyAlias)
            val hardwareBacked = level == KeyProperties.SECURITY_LEVEL_STRONGBOX ||
                level == KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT
            return AndroidKeystoreHardwareKeyWrap(keyAlias, hardwareBacked)
        }

        private fun generateKey(keyAlias: String, strongBox: Boolean): Result<Unit> = runCatching {
            val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            keyGen.init(
                KeyGenParameterSpec.Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setUserAuthenticationRequired(false)
                    .setInvalidatedByBiometricEnrollment(false)
                    .setIsStrongBoxBacked(strongBox)
                    .build(),
            )
            keyGen.generateKey()
            Unit
        }

        private fun readSecurityLevel(keyAlias: String): Int {
            val key = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.getKey(keyAlias, null) as SecretKey
            val info = SecretKeyFactory.getInstance(key.algorithm, ANDROID_KEYSTORE)
                .getKeySpec(key, KeyInfo::class.java) as KeyInfo
            return info.securityLevel
        }
    }
}

/** Neither StrongBox nor a TEE-backed key could be created on this device —
 * see [AndroidKeystoreHardwareKeyWrap.create]. */
class KeystoreHardwareUnavailableException(cause: Throwable) :
    Exception("No hardware-backed Keystore available on this device", cause)

/** This vault's Keystore key no longer exists — see [AndroidKeystoreHardwareKeyWrap.forExistingKey]. */
class KeystoreKeyLostException(keyAlias: String) :
    Exception("Keystore key '$keyAlias' no longer exists — recovery key required to unlock this vault")
