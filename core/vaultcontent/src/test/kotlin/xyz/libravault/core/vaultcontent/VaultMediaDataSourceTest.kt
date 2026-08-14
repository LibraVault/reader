package xyz.libravault.core.vaultcontent

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.IOException
import java.security.SecureRandom

class VaultMediaDataSourceTest {

    @Test
    fun `getSize reports the plaintext size`() {
        val plain = ByteArray(1000).also { SecureRandom().nextBytes(it) }
        val vault = TestVaultFile.encrypt(plain)
        val source = VaultMediaDataSource(vault.openReader())
        assertEquals(1000L, source.size)
        source.close()
    }

    @Test
    fun `readAt fills the buffer at the requested offset and returns the byte count`() {
        val plain = ByteArray(500).also { SecureRandom().nextBytes(it) }
        val vault = TestVaultFile.encrypt(plain)
        val source = VaultMediaDataSource(vault.openReader())

        val buffer = ByteArray(20)
        val n = source.readAt(100, buffer, 5, 10) // write 10 bytes starting at buffer offset 5
        assertEquals(10, n)
        assertArrayEquals(plain.copyOfRange(100, 110), buffer.copyOfRange(5, 15))
        source.close()
    }

    @Test
    fun `readAt past the end of the file returns -1`() {
        val plain = ByteArray(50)
        val vault = TestVaultFile.encrypt(plain)
        val source = VaultMediaDataSource(vault.openReader())
        assertEquals(-1, source.readAt(50, ByteArray(10), 0, 10))
        assertEquals(-1, source.readAt(1000, ByteArray(10), 0, 10))
        source.close()
    }

    @Test
    fun `a tampered chunk surfaces as IOException, not a raw crypto exception`() {
        val plain = ByteArray(200).also { SecureRandom().nextBytes(it) }
        val vault = TestVaultFile.encrypt(plain, chunkSize = 64)
        vault.openReader().close() // chunk 0 authenticated; leave later chunks untouched for now
        vault.corruptByteAt(30 + 64 + 16 + 5) // inside chunk 1's ciphertext

        val source = VaultMediaDataSource(vault.openReader())
        assertThrows(IOException::class.java) {
            source.readAt(70, ByteArray(10), 0, 10)
        }
        source.close()
    }
}
