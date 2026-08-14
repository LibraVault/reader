package xyz.libravault.core.vaultcontent

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.util.data.ReadError
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.libravault.core.vaultcrypto.VaultAuthenticationException
import xyz.libravault.core.vaultcrypto.VaultCryptoException
import java.security.SecureRandom

/**
 * Runs on Robolectric (JVM, no emulator) — needed because [VaultReadiumResource]
 * constructs a Readium `AbsoluteUrl`, which wraps a real `android.net.Uri`
 * internally and throws "not mocked" under AGP's default stub android.jar.
 * Same setup as core:ui's `LibravaultThemeTest`.
 *
 * Note: [org.readium.r2.shared.util.resource.Resource] extends Readium's OWN
 * `Closeable` interface, not `java.io.Closeable`/`kotlin.io.Closeable` — so
 * Kotlin's stdlib `.use { }` doesn't apply here; these tests close manually.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class VaultReadiumResourceTest {

    @Test
    fun `length reports the plaintext size`() = runTest {
        val plain = ByteArray(1000).also { SecureRandom().nextBytes(it) }
        val vault = TestVaultFile.encrypt(plain)
        val resource = VaultReadiumResource(vault.openReader(), "abc123")
        try {
            assertEquals(1000L, resource.length().getOrNull())
        } finally {
            resource.close()
        }
    }

    @Test
    fun `read with a range returns exactly the requested bytes`() = runTest {
        val plain = ByteArray(500).also { SecureRandom().nextBytes(it) }
        val vault = TestVaultFile.encrypt(plain)
        val resource = VaultReadiumResource(vault.openReader(), "abc123")
        try {
            val result = resource.read(100L..199L)
            assertArrayEquals(plain.copyOfRange(100, 200), result.getOrNull())
        } finally {
            resource.close()
        }
    }

    @Test
    fun `read with a range spanning a chunk boundary is correct`() = runTest {
        val plain = ByteArray(200).also { SecureRandom().nextBytes(it) }
        val vault = TestVaultFile.encrypt(plain, chunkSize = 64)
        val resource = VaultReadiumResource(vault.openReader(), "abc123")
        try {
            val result = resource.read(60L..70L)
            assertArrayEquals(plain.copyOfRange(60, 71), result.getOrNull())
        } finally {
            resource.close()
        }
    }

    @Test
    fun `read with a null range returns the whole resource`() = runTest {
        val plain = ByteArray(300).also { SecureRandom().nextBytes(it) }
        val vault = TestVaultFile.encrypt(plain)
        val resource = VaultReadiumResource(vault.openReader(), "abc123")
        try {
            assertArrayEquals(plain, resource.read(null).getOrNull())
        } finally {
            resource.close()
        }
    }

    @Test
    fun `sourceUrl is a stable vault-scheme URL derived from the file id`() {
        val vault = TestVaultFile.encrypt(ByteArray(10))
        val resource = VaultReadiumResource(vault.openReader(), "deadbeef")
        try {
            assertTrue(resource.sourceUrl.toString().contains("deadbeef"))
        } finally {
            resource.close()
        }
    }

    @Test
    fun `header tamper fails at reader construction, not silently`() {
        val plain = ByteArray(200).also { SecureRandom().nextBytes(it) }
        val vault = TestVaultFile.encrypt(plain, chunkSize = 64)
        vault.corruptByteAt(35) // inside the header — invalidates every chunk (core:vaultcrypto §8.2)

        // core:vaultcrypto's VaultFileReader eagerly authenticates chunk 0 in its
        // constructor (found during Phase 1 review) — a resource can never be built
        // around an already-tampered file to begin with.
        val ex = assertThrows(VaultCryptoException::class.java) { vault.openReader() }
        assertTrue(ex is VaultAuthenticationException)
    }

    @Test
    fun `tampering a later chunk surfaces as ReadError_Decoding on read, not a crash`() = runTest {
        val plain = ByteArray(200).also { SecureRandom().nextBytes(it) }
        val vault = TestVaultFile.encrypt(plain, chunkSize = 64)
        vault.openReader().close() // succeeds — chunk 0 (only) is authenticated so far

        // Corrupt a byte inside chunk 1's ciphertext: HEADER_SIZE_BYTES(30) + chunkSize(64) + TAG(16).
        vault.corruptByteAt(30 + 64 + 16 + 5)

        val resource = VaultReadiumResource(vault.openReader(), "abc")
        try {
            val result = resource.read(70L..80L) // lands in the corrupted chunk 1
            assertTrue(result.isFailure)
            assertTrue(result.failureOrNull() is ReadError.Decoding)
        } finally {
            resource.close()
        }
    }
}
