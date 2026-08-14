package xyz.libravault.core.vaultcontent

import android.media.MediaDataSource
import xyz.libravault.core.vaultcrypto.VaultCryptoException
import xyz.libravault.core.vaultcrypto.VaultFileReader
import java.io.IOException

/**
 * Exposes a [VaultFileReader] as an [android.media.MediaDataSource], the
 * random-access source `MediaMetadataRetriever.setDataSource(MediaDataSource)`
 * accepts — the primitive `MetadataExtractor` needs for chapter/duration
 * extraction from a vault-stored audiobook without decrypting the whole file
 * up front (implementation plan Phase 3, §B.1).
 *
 * Only [readAt]/[getSize]/[close] are exercised by this class's own logic and
 * are fully unit-testable directly (call them like any other method — no real
 * `MediaMetadataRetriever` or device involved). What genuinely needs a device
 * to verify is whether Android's native media framework accepts a
 * `MediaDataSource` backed by this data at all; that's a `MetadataExtractor`
 * integration concern for whenever Phase 3's wiring lands, not this class.
 */
class VaultMediaDataSource(private val reader: VaultFileReader) : MediaDataSource() {

    override fun getSize(): Long = reader.plainSize

    /** @return the number of bytes read, or -1 at end of stream — the contract
     * [MediaDataSource.readAt] requires. */
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position < 0 || position >= reader.plainSize) return -1
        val bytes = try {
            reader.readAt(position, size)
        } catch (e: VaultCryptoException) {
            throw IOException("Vault read failed at position $position", e)
        }
        if (bytes.isEmpty()) return -1
        System.arraycopy(bytes, 0, buffer, offset, bytes.size)
        return bytes.size
    }

    override fun close() = reader.close()
}
