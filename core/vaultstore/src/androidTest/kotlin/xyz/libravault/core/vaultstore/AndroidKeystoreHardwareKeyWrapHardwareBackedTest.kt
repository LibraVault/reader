package xyz.libravault.core.vaultstore

import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import xyz.libravault.core.vaultcrypto.VaultAuthenticationException
import java.security.KeyStore
import java.util.UUID
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory

private const val ANDROID_KEYSTORE = "AndroidKeyStore"

/**
 * Hardware-only counterpart to [AndroidKeystoreHardwareKeyWrapTest] — issue #283.
 *
 * The two [AndroidKeystoreHardwareKeyWrap.create] cases that class explicitly
 * left uncovered because they need `create()` to actually land on real
 * StrongBox/TEE hardware rather than being rejected:
 *
 *  1. [createSucceedsAndReportsHardwareBackedOnRealHardware] — `create()`'s
 *     actual happy path, not a pre-seeded key going through `forExistingKey()`.
 *  2. [createReplacesAnExistingKeyUnderTheSameAlias] — a second `create()`
 *     call under the same alias must invalidate a blob wrapped under the
 *     first key, not merge with or extend it.
 *
 * `ui-tests.yml`'s `google_apis` x86_64 emulator always reports
 * `SECURITY_LEVEL_SOFTWARE` (probe result, issue #253 comment), so `create()`
 * throws [KeystoreHardwareUnavailableException] there instead of reaching
 * either case here — see `AndroidKeystoreHardwareKeyWrapTest`'s kdoc. This
 * class only runs meaningfully on a real device/Test Lab physical device via
 * `android-keystore-hardware-test.yml`; on the emulator both tests here would
 * fail loudly (not silently pass) since `create()` throws before either
 * assertion is reached.
 *
 * Each test seeds its own uniquely-aliased key and deletes it in [tearDown],
 * since state would otherwise leak between tests on a persistent device.
 */
@RunWith(AndroidJUnit4::class)
class AndroidKeystoreHardwareKeyWrapHardwareBackedTest {

    private val aliasesToClean = mutableListOf<String>()

    @After
    fun tearDown() {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        aliasesToClean.forEach { alias -> runCatching { keyStore.deleteEntry(alias) } }
        aliasesToClean.clear()
    }

    @Test
    fun createSucceedsAndReportsHardwareBackedOnRealHardware() {
        val alias = uniqueAlias()
        aliasesToClean += alias

        val wrap = AndroidKeystoreHardwareKeyWrap.create(alias)

        assertTrue(
            "create() succeeded but isHardwareBacked was false on real hardware",
            wrap.isHardwareBacked,
        )

        // Cross-check against the Keystore's own KeyInfo directly, rather than
        // trusting the property under test to grade itself.
        val securityLevel = readSecurityLevel(alias)
        assertTrue(
            "Keystore reported security level $securityLevel, expected STRONGBOX or TRUSTED_ENVIRONMENT",
            securityLevel == KeyProperties.SECURITY_LEVEL_STRONGBOX ||
                securityLevel == KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT,
        )

        // create()'s own returned instance actually works, not just a key that
        // happens to exist under the alias afterwards.
        val plaintext = ByteArray(32) { it.toByte() }
        val blob = wrap.wrap(plaintext)
        assertArrayEquals(plaintext, wrap.unwrap(blob))
    }

    @Test
    fun createReplacesAnExistingKeyUnderTheSameAlias() {
        val alias = uniqueAlias()
        aliasesToClean += alias

        val firstWrap = AndroidKeystoreHardwareKeyWrap.create(alias)
        val plaintext = ByteArray(32) { 3 }
        val blobUnderFirstKey = firstWrap.wrap(plaintext)

        // Same alias, second call — create()'s kdoc promises "any existing key
        // under that alias is replaced", not merged with or additive to it.
        val secondWrap = AndroidKeystoreHardwareKeyWrap.create(alias)

        // The blob wrapped before the second create() call must no longer
        // unwrap — proof the underlying key material actually changed, not
        // just that a new wrapper object was returned. Both wrap() calls load
        // the key fresh by alias (no in-memory key caching), so this checks
        // the replacement through the same instance the blob came from.
        assertThrows(VaultAuthenticationException::class.java) {
            firstWrap.unwrap(blobUnderFirstKey)
        }

        // And the replacement key is itself fully functional, not left broken.
        val blobUnderSecondKey = secondWrap.wrap(plaintext)
        assertArrayEquals(plaintext, secondWrap.unwrap(blobUnderSecondKey))
    }

    private fun uniqueAlias() = "androidTest-vault-hw-${UUID.randomUUID()}"

    private fun readSecurityLevel(alias: String): Int {
        val key = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.getKey(alias, null) as SecretKey
        val info = SecretKeyFactory.getInstance(key.algorithm, ANDROID_KEYSTORE)
            .getKeySpec(key, KeyInfo::class.java) as KeyInfo
        return info.securityLevel
    }
}
