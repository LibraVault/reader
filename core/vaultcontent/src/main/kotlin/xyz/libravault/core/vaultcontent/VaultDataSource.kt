@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package xyz.libravault.core.vaultcontent

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import xyz.libravault.core.vaultcrypto.VaultCryptoException
import xyz.libravault.core.vaultcrypto.VaultFileReader
import java.io.IOException

/**
 * Exposes a [VaultFileReader] as a Media3 [DataSource] — the primitive
 * audiobook playback from an Encrypted Vault needs (implementation plan Phase
 * 3, §B.1). ExoPlayer never needs a file descriptor for audio the way
 * `PdfRenderer` does for PDF: this is a plain in-process `DataSource`, which
 * is also why the Phase 0 spike's original FUSE-throughput worry (seeking a
 * 400 MB `.m4b`) turned out not to apply to audio at all — see
 * implementation plan §B.1.
 *
 * **Deliberately not wired into [PlayerModule][
 * xyz.libravault.feature.player.service.PlayerModule]'s `provideExoPlayer` in
 * this phase** — doing that for real needs a URI scheme plus a registry of
 * currently-open vaults for [DataSource.Factory] to resolve against, which
 * only makes sense once real UI (Phase 5) exists to populate it. Until then,
 * a caller that already holds an open [VaultFileReader] can build a
 * `MediaSource` directly from [Factory] (e.g.
 * `ProgressiveMediaSource.Factory(VaultDataSource.Factory { reader })`)
 * without needing scheme-based resolution at all.
 */
class VaultDataSource(private val reader: VaultFileReader) : BaseDataSource(/* isNetwork = */ false) {

    private var readPosition: Long = 0L
    private var bytesRemaining: Long = 0L
    private var openedUri: Uri? = null

    override fun open(dataSpec: DataSpec): Long {
        openedUri = dataSpec.uri
        transferInitializing(dataSpec)

        val totalLength = reader.plainSize
        if (dataSpec.position > totalLength) {
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }
        readPosition = dataSpec.position
        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            dataSpec.length
        } else {
            totalLength - dataSpec.position
        }
        if (bytesRemaining < 0) throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)

        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        val toRead = minOf(length.toLong(), bytesRemaining).toInt()
        val bytes = try {
            reader.readAt(readPosition, toRead)
        } catch (e: VaultCryptoException) {
            throw IOException("Vault read failed at position $readPosition", e)
        }
        if (bytes.isEmpty()) return C.RESULT_END_OF_INPUT

        System.arraycopy(bytes, 0, buffer, offset, bytes.size)
        readPosition += bytes.size
        bytesRemaining -= bytes.size
        bytesTransferred(bytes.size)
        return bytes.size
    }

    override fun getUri(): Uri? = openedUri

    override fun close() {
        openedUri?.let { transferEnded() }
        openedUri = null
        reader.close()
    }

    /** Builds a fresh [VaultDataSource] — and, via [readerProvider], a fresh
     * [VaultFileReader] — per `createDataSource()` call, since neither is
     * thread-safe and Media3 may open/close a track's `DataSource` more than
     * once (retries, re-buffering). One [readerProvider] per file, not one
     * shared reader reused across instances. */
    class Factory(private val readerProvider: () -> VaultFileReader) : DataSource.Factory {
        override fun createDataSource(): DataSource = VaultDataSource(readerProvider())
    }
}
