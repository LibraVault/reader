package xyz.libravault.core.cloudtts.testing

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
 * JVM-only fake for [HardwareKeyWrapFactory], local to core:cloudtts's test
 * source set — this repo has no cross-module test-fixture sharing mechanism
 * (no `testFixtures` usage anywhere), so this is a deliberate, small
 * duplicate of core:vaultstore's own
 * `core/vaultstore/src/test/kotlin/xyz/libravault/core/vaultstore/testing/FakeHardwareKeyWrap.kt`,
 * not a divergent reimplementation — behavior is identical.
 */
class FakeHardwareKeyWrapFactory : HardwareKeyWrapFactory {

    private val keysByAlias = mutableMapOf<String, ByteArray>()

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

    fun forgetKey(keyAlias: String) {
        keysByAlias.remove(keyAlias)
    }
}

private class FakeHardwareKeyWrap(private val key: ByteArray) : HardwareKeyWrap {

    override val isHardwareBacked: Boolean = true

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
