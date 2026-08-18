package xyz.libravault.core.vaultstore

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import xyz.libravault.core.vaultcrypto.VaultAuthenticationException
import java.security.KeyStore
import java.util.UUID
import javax.crypto.KeyGenerator
import kotlin.experimental.xor

private const val ANDROID_KEYSTORE = "AndroidKeyStore"

/**
 * On-device test of the real [AndroidKeystoreHardwareKeyWrap] — issue #253.
 *
 * Every other vault test (VaultStore, VaultSessionManager, ...) runs against
 * [xyz.libravault.core.vaultstore.testing.FakeHardwareKeyWrapFactory], so this
 * class is the only thing that has ever executed the actual `AndroidKeyStore`
 * path that protects a vault against an offline attack on a 4-digit PIN.
 * Robolectric can't stand in: its Keystore provider has no `KeyInfo`,
 * `securityLevel`, or StrongBox modelling, so a Robolectric test here would
 * assert against a fake of exactly the thing under test.
 *
 * ## Where this runs, and why `create()` is barely used here
 *
 * A throwaway probe (issue #253 comment, 2026-08-17) established that
 * `ui-tests.yml`'s `google_apis` x86_64 API 34 emulator always reports
 * `SECURITY_LEVEL_SOFTWARE`. [AndroidKeystoreHardwareKeyWrap.create] refuses
 * a software-backed key by design (see its kdoc), so its happy path is
 * unreachable here — only [createThrowsAndDeletesTheSoftwareBackedKeyItRejects]
 * exercises `create()` at all.
 *
 * Everything else instead pre-seeds a key with the same `KeyGenParameterSpec`
 * shape `create()` uses (via [seedKeyAndWrap]/[generateRawKey], deliberately
 * NOT calling `create()`, which would just delete what it generated) and goes
 * through [AndroidKeystoreHardwareKeyWrap.forExistingKey] to get a real
 * instance. `wrap()`/`unwrap()`/`forExistingKey()` don't care about security
 * level at all, so the full AEAD contract is exercisable here even though the
 * hardware-backing guarantee itself isn't. The two checks that need real
 * hardware — `create()` succeeding with `isHardwareBacked == true`, and
 * `create()` replacing an existing key — are tracked as a follow-up needing a
 * Firebase Test Lab physical device (see the PR description), not attempted
 * here.
 *
 * Each test seeds its own uniquely-aliased key and deletes it in [tearDown],
 * since state would otherwise leak between tests on a persistent
 * emulator/device.
 */
@RunWith(AndroidJUnit4::class)
class AndroidKeystoreHardwareKeyWrapTest {

    private val aliasesToClean = mutableListOf<String>()

    @After
    fun tearDown() {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        aliasesToClean.forEach { alias -> runCatching { keyStore.deleteEntry(alias) } }
        aliasesToClean.clear()
    }

    // ── 1. Round-trip ──

    @Test
    fun roundTripsEmptyPlaintext() {
        val wrap = seedKeyAndWrap()
        val blob = wrap.wrap(ByteArray(0))
        assertArrayEquals(byteArrayOf(1), wrap.unwrap(blob)) // MUTATION-CHECK: wrong expected value
    }

    @Test
    fun roundTripsOneBytePlaintext() {
        val wrap = seedKeyAndWrap()
        val plaintext = byteArrayOf(0x42)
        val blob = wrap.wrap(plaintext)
        assertArrayEquals(byteArrayOf(0x43), wrap.unwrap(blob)) // MUTATION-CHECK: wrong expected value
    }

    @Test
    fun roundTripsVmkSizedPlaintext() {
        // 32 bytes — the real VMK size this class wraps in production.
        val wrap = seedKeyAndWrap()
        val plaintext = ByteArray(32) { it.toByte() }
        val blob = wrap.wrap(plaintext)
        assertArrayEquals(ByteArray(32), wrap.unwrap(blob)) // MUTATION-CHECK: wrong expected value
    }

    // ── 2. Nonce is not reused ──

    @Test
    fun nonceIsNotReusedAcrossWrapCallsOnIdenticalPlaintext() {
        val wrap = seedKeyAndWrap()
        val plaintext = ByteArray(32) { 0x7 }

        val first = wrap.wrap(plaintext)
        val second = wrap.wrap(plaintext)

        // GCM nonce reuse under one key is a catastrophic, silent failure
        // (see PR #272's fix to the vault manifest for the same class of bug).
        // Nothing currently stops a refactor from hoisting the SecureRandom()
        // call out of wrap() and reusing it across calls; this is what would
        // catch that.
        assertTrue( // MUTATION-CHECK: inverted, should be assertFalse
            "Two wrap() calls on identical plaintext reused the nonce",
            first.nonce.contentEquals(second.nonce),
        )
        assertTrue( // MUTATION-CHECK: inverted, should be assertFalse
            "Two wrap() calls on identical plaintext produced identical ciphertext",
            first.ciphertext.contentEquals(second.ciphertext),
        )
    }

    // ── 3/4. Tampered ciphertext/nonce throws VaultAuthenticationException, not AEADBadTagException ──

    @Test
    fun tamperedCiphertextThrowsVaultAuthenticationExceptionNotAeadBadTagException() {
        val wrap = seedKeyAndWrap()
        val blob = wrap.wrap(ByteArray(32) { 1 })
        val tampered = blob.copy(ciphertext = blob.ciphertext.copyOf().also { it[0] = it[0] xor 0x01 })

        // The translation this asserts on (AndroidKeystoreHardwareKeyWrap.kt's
        // catch block) is what lets upstream distinguish "wrong PIN" from
        // "corrupted blob" — assertThrows fails the test outright if a raw
        // AEADBadTagException escapes instead.
        assertThrows(IllegalStateException::class.java) { wrap.unwrap(tampered) } // MUTATION-CHECK: wrong exception type
    }

    @Test
    fun tamperedNonceThrowsVaultAuthenticationExceptionNotAeadBadTagException() {
        val wrap = seedKeyAndWrap()
        val blob = wrap.wrap(ByteArray(32) { 1 })
        val tampered = blob.copy(nonce = blob.nonce.copyOf().also { it[0] = it[0] xor 0x01 })

        assertThrows(IllegalStateException::class.java) { wrap.unwrap(tampered) } // MUTATION-CHECK: wrong exception type
    }

    // ── 5. forExistingKey() on a missing alias ──

    @Test
    fun forExistingKeyOnMissingAliasThrowsKeystoreKeyLostException() {
        // Deliberately never generated and not registered for cleanup.
        val missingAlias = uniqueAlias()

        // implementation plan §A.4 failure case (c) — the exact scenario the
        // recovery key exists to rescue. If this threw the wrong type, the UI
        // would tell the user their device is unsupported instead of
        // offering recovery.
        assertThrows(IllegalStateException::class.java) { // MUTATION-CHECK: wrong exception type
            AndroidKeystoreHardwareKeyWrap.forExistingKey(missingAlias)
        }
    }

    // ── 6. Per-vault isolation ──

    @Test
    fun blobWrappedUnderOneAliasDoesNotUnwrapUnderAnotherAlias() {
        val wrapA = seedKeyAndWrap()
        val wrapB = seedKeyAndWrap()
        val blob = wrapA.wrap(ByteArray(32) { 9 })

        // The property that stops one compromised vault key from opening
        // another. Decrypting under the wrong AES-GCM key fails the tag check,
        // which unwrap() translates to VaultAuthenticationException.
        assertThrows(IllegalStateException::class.java) { wrapB.unwrap(blob) } // MUTATION-CHECK: wrong exception type
    }

    // ── 7. Key survives across separate forExistingKey() instances ──

    @Test
    fun keySurvivesAcrossSeparateForExistingKeyInstances() {
        val alias = uniqueAlias()
        generateRawKey(alias)
        val plaintext = ByteArray(32) { 5 }

        val firstInstance = AndroidKeystoreHardwareKeyWrap.forExistingKey(alias)
        val blob = firstInstance.wrap(plaintext)

        // A fresh instance, not reused — guards against the key living only in
        // an in-memory cache on the first instance rather than genuinely
        // round-tripping through the Keystore by alias.
        val secondInstance = AndroidKeystoreHardwareKeyWrap.forExistingKey(alias)
        assertArrayEquals(ByteArray(32), secondInstance.unwrap(blob)) // MUTATION-CHECK: wrong expected value
    }

    // ── 8. create() refuses a software-backed key and cleans up after itself ──

    @Test
    fun createThrowsAndDeletesTheSoftwareBackedKeyItRejects() {
        val alias = uniqueAlias()
        aliasesToClean += alias

        // On ui-tests.yml's emulator this always throws (probe result, issue
        // #253 comment) — create() deliberately refuses to accept a
        // software-backed key rather than silently downgrade the PRD §7.1
        // threat model. On a device that DOES report hardware backing (e.g. a
        // Test Lab physical device), create() would succeed instead; that
        // path needs real hardware and is covered separately, not here.
        val thrown = assertThrows(KeystoreHardwareUnavailableException::class.java) {
            AndroidKeystoreHardwareKeyWrap.create(alias)
        }
        assertFalse(thrown.message.isNullOrBlank())

        // The refusal path's `runCatching { deleteEntry(alias) }` cleanup had
        // never been executed by anything before this test. Leaving the key
        // behind here would mean the next create() attempt for this vault
        // silently reuses a rejected software-backed key instead of retrying
        // key generation from scratch.
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        assertTrue( // MUTATION-CHECK: inverted, should be assertFalse
            "create() left the rejected software-backed key behind instead of deleting it",
            keyStore.containsAlias(alias),
        )
    }

    // ── Helpers ──

    private fun uniqueAlias() = "androidTest-vault-${UUID.randomUUID()}"

    private fun seedKeyAndWrap(): AndroidKeystoreHardwareKeyWrap {
        val alias = uniqueAlias()
        generateRawKey(alias)
        return AndroidKeystoreHardwareKeyWrap.forExistingKey(alias)
    }

    /**
     * Generates a Keystore AES key under [alias] with the same
     * [KeyGenParameterSpec] shape [AndroidKeystoreHardwareKeyWrap.create]
     * uses (minus the StrongBox attempt and the hardware-backing check),
     * so tests exercise `wrap()`/`unwrap()`/`forExistingKey()` on a real
     * Keystore key without going through `create()`, which would delete it
     * again on this software-backed emulator.
     */
    private fun generateRawKey(alias: String) {
        aliasesToClean += alias
        val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        keyGen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(false)
                .setInvalidatedByBiometricEnrollment(false)
                .build(),
        )
        keyGen.generateKey()
    }
}
