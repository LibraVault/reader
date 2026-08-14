package xyz.libravault.core.vaultcontent

import android.system.ErrnoException
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.security.SecureRandom

/**
 * Tests [VaultProxyFdCallback]'s own delegation logic directly — the part
 * that can carry a real bug (an off-by-one in the byte copy, wrong exception
 * mapping). The surrounding FUSE plumbing ([VaultProxyFdHost.open], real
 * `StorageManager`/kernel behavior) needs a physical device to verify, same
 * as the Phase 0 spike — not attempted here.
 */
class VaultProxyFdCallbackTest {

    @Test
    fun `onGetSize reports the plaintext size`() {
        val plain = ByteArray(12345)
        val vault = TestVaultFile.encrypt(plain)
        val callback = VaultProxyFdCallback(vault.openReader())
        assertEquals(12345L, callback.onGetSize())
    }

    @Test
    fun `onRead fills the output buffer at offset 0 and returns the byte count`() {
        val plain = ByteArray(500).also { SecureRandom().nextBytes(it) }
        val vault = TestVaultFile.encrypt(plain)
        val callback = VaultProxyFdCallback(vault.openReader())

        val buffer = ByteArray(20)
        val n = callback.onRead(100L, 20, buffer)
        assertEquals(20, n)
        assertArrayEquals(plain.copyOfRange(100, 120), buffer)
    }

    @Test
    fun `onRead maps a decryption failure to ErrnoException, not the raw crypto exception`() {
        val plain = ByteArray(200).also { SecureRandom().nextBytes(it) }
        val vault = TestVaultFile.encrypt(plain, chunkSize = 64)
        vault.openReader().close()
        vault.corruptByteAt(30 + 64 + 16 + 5) // inside chunk 1

        val callback = VaultProxyFdCallback(vault.openReader())
        assertThrows(ErrnoException::class.java) {
            callback.onRead(70L, 10, ByteArray(10))
        }
    }

    @Test
    fun `onRelease closes the underlying reader`() {
        // Two chunks, and read from the SECOND one after release: chunk 0 is
        // eagerly cached by VaultFileReader's constructor (core:vaultcrypto),
        // so reading chunk 0 again wouldn't touch the (by-then-closed)
        // underlying file at all and would prove nothing.
        val plain = ByteArray(150)
        val vault = TestVaultFile.encrypt(plain, chunkSize = 64)
        val reader = vault.openReader()
        val callback = VaultProxyFdCallback(reader)

        callback.onRelease()

        assertThrows(java.io.IOException::class.java) { reader.readAt(70, 10) }
    }
}
