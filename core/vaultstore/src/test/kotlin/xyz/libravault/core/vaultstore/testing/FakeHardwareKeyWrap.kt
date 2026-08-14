package xyz.libravault.core.vaultstore.testing

import xyz.libravault.core.vaultcrypto.VaultAuthenticationException
import xyz.libravault.core.vaultstore.HardwareKeyWrap
import xyz.libravault.core.vaultstore.HardwareKeyWrapFactory
import xyz.libravault.core.vaultstore.KeystoreHardwareUnavailableException
import xyz.libravault.core.vaultstore.KeystoreKeyLostException
import xyz.libravault.core.vaultstore.WrappedBlob
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * JVM-only fake for [HardwareKeyWrapFactory] — this is what lets [VaultStore][
 * xyz.libravault.core.vaultstore.VaultStore]'s create/unlock/lock orchestration
 * be unit-tested without a device or Robolectric, per the repo lesson this
 * module was built to not repeat: `ProStateManager` (core:licensing) hardcodes
 * real Keystore access and is untestable in plain JVM tests as a result.
 *
 * Behaves like real hardware-backed Keystore keys for the properties tests
 * care about (deterministic per-alias key, AEAD wrap/unwrap, clean failure on
 * a wrong/missing key) without touching `android.security.keystore` at all.
 */
class FakeHardwareKeyWrapFactory : HardwareKeyWrapFactory {

    private val keysByAlias = mutableMapOf<String, ByteArray>()

    /** Set true to simulate a device with no hardware-backed Keystore at all —
     * exercises the [KeystoreHardwareUnavailableException] path. */
    var simulateHardwareUnavailable: Boolean = false

    override fun createNew(keyAlias: String): HardwareKeyWrap {
        if (simulateHardwareUnavailable) {
            throw KeystoreHardwareUnavailableException(IllegalStateException("fake: hardware unavailable"))
        }
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        keysByAlias[keyAlias] = key
        return FakeHardwareKeyWrap(key)
    }

    override fun forExisting(keyAlias: String): HardwareKeyWrap {
        val key = keysByAlias[keyAlias] ?: throw KeystoreKeyLostException(keyAlias)
        return FakeHardwareKeyWrap(key)
    }

    /** Simulates losing the Keystore key while the vault's files survive —
     * implementation plan §A.4 failure case (c), the scenario the recovery
     * key (§A.5) exists to rescue. */
    fun forgetKey(keyAlias: String) {
        keysByAlias.remove(keyAlias)
    }
}

private class FakeHardwareKeyWrap(private val key: ByteArray) : HardwareKeyWrap {

    override val isHardwareBacked: Boolean = true // fakes always report success; unavailability is a factory-level concern

    override fun wrap(plaintext: ByteArray): WrappedBlob {
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        return WrappedBlob(nonce, cipher.doFinal(plaintext))
    }

    override fun unwrap(wrapped: WrappedBlob): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, wrapped.nonce))
        return try {
            cipher.doFinal(wrapped.ciphertext)
        } catch (e: AEADBadTagException) {
            throw VaultAuthenticationException(e)
        } catch (e: BadPaddingException) {
            throw VaultAuthenticationException(e)
        }
    }
}
