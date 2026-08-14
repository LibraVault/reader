package xyz.libravault.core.vaultcontent

import xyz.libravault.core.vaultcrypto.ChunkedVaultWriter
import xyz.libravault.core.vaultcrypto.VaultFileReader
import java.io.ByteArrayInputStream
import java.io.File
import java.security.SecureRandom
import kotlin.io.path.createTempFile

/**
 * Shared test helper: encrypts [plain] to a temp file, ready to open a
 * [VaultFileReader] over it — the same direct pattern core:vaultcrypto's own
 * tests use. The adapters in this module wrap [VaultFileReader] directly, not
 * [xyz.libravault.core.vaultstore.VaultStore], so tests don't need the full
 * vault lifecycle (PIN, Keystore) at all — just a decrypting reader to adapt.
 */
internal class TestVaultFile private constructor(val file: File, private val vmk: ByteArray, private val fileId: ByteArray) {

    fun openReader(): VaultFileReader = VaultFileReader(file, vmk, fileId)

    /** Corrupts one ciphertext byte — for tamper-detection tests. Must be
     * called before the returned reader's affected chunk is opened, since
     * [VaultFileReader] caches once-decrypted chunks. */
    fun corruptByteAt(offset: Int) {
        val bytes = file.readBytes()
        bytes[offset] = (bytes[offset].toInt() xor 0x01).toByte()
        file.writeBytes(bytes)
    }

    companion object {
        private val random = SecureRandom()

        fun encrypt(plain: ByteArray, chunkSize: Int = 64): TestVaultFile {
            val vmk = ByteArray(32).also { random.nextBytes(it) }
            val fileId = ByteArray(16).also { random.nextBytes(it) }
            val file = createTempFile(prefix = "vaultcontent-test").toFile().apply { deleteOnExit() }
            ChunkedVaultWriter.encrypt(
                vmk, fileId, plain.size.toLong(), ByteArrayInputStream(plain), file.outputStream(), chunkSize,
            )
            return TestVaultFile(file, vmk, fileId)
        }
    }
}
