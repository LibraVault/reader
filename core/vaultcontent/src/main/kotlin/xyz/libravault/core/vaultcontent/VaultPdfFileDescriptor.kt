package xyz.libravault.core.vaultcontent

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import xyz.libravault.core.vaultcrypto.VaultFileReader
import xyz.libravault.core.vaultcrypto.VaultFormat
import java.io.Closeable

/**
 * The primary PDF content-delivery path — validated on real hardware in the
 * Phase 0 spike (both a budget Galaxy A12 and a Pixel 6; implementation plan
 * §D.0.RESULTS): `PdfRenderer` accepts a FUSE-backed proxy fd built this way,
 * and because it decrypts lazily — only the chunks a page render actually
 * touches — it opens faster and uses no extra memory versus [VaultMemfdFallback].
 *
 * PDF is the *only* content-delivery consumer that needs a real file
 * descriptor at all (plan §B.1) — EPUB, audio, and metadata extraction all
 * have native random-access extension points ([VaultReadiumResource],
 * [VaultDataSource], [VaultMediaDataSource]) that need no fd.
 */
class VaultProxyFdCallback(private val reader: VaultFileReader) : ProxyFileDescriptorCallback() {

    override fun onGetSize(): Long = reader.plainSize

    override fun onRead(offset: Long, size: Int, data: ByteArray): Int = try {
        val bytes = reader.readAt(offset, size)
        System.arraycopy(bytes, 0, data, 0, bytes.size)
        bytes.size
    } catch (e: Exception) {
        // ProxyFileDescriptorCallback's contract is to signal I/O failure via
        // ErrnoException, not propagate our own exception types across the FUSE
        // boundary — the underlying VaultCryptoException (wrong VMK, tampered
        // data, truncation) is still the real cause, just not expressible here.
        throw ErrnoException("onRead", OsConstants.EIO)
    }

    /** Closes [reader] when the fd is released — whichever of open fd or
     * [reader] outlives the other would risk serving decrypted content past
     * the point the caller intended it to be reachable. */
    override fun onRelease() = reader.close()
}

/**
 * Opens [VaultProxyFdCallback]s on a dedicated handler thread — proxy fd
 * callbacks run synchronously on whatever thread services them, and must
 * never be the caller's own thread (would block `PdfRenderer` on IO/decrypt
 * work). One [VaultProxyFdHost] can serve multiple opens; [close] tears down
 * its thread and should be called when no more vault PDFs will be opened
 * through it (e.g. on vault lock), not per-file.
 */
class VaultProxyFdHost(context: Context) : Closeable {

    private val thread = HandlerThread("vault-proxy-fd").apply { start() }
    private val handler = Handler(thread.looper)
    private val storageManager = context.getSystemService(StorageManager::class.java)

    /** The returned fd owns [reader]: closing the fd triggers
     * [VaultProxyFdCallback.onRelease], which closes [reader] — callers should
     * not also close [reader] independently. */
    fun open(reader: VaultFileReader): ParcelFileDescriptor =
        storageManager.openProxyFileDescriptor(
            ParcelFileDescriptor.MODE_READ_ONLY,
            VaultProxyFdCallback(reader),
            handler,
        )

    override fun close() {
        thread.quitSafely()
    }
}

/**
 * Fallback PDF path (implementation plan §B.2) if the proxy fd disappoints on
 * a given device: decrypts the whole file into an anonymous in-memory fd via
 * `memfd_create`. Still never touches the filesystem — doesn't reintroduce
 * plaintext-on-disk — but costs RAM proportional to file size and pays that
 * cost eagerly at open time (measured on-device: ~1.6 s for a 39 MB PDF on
 * budget hardware, versus ~100 ms for the proxy fd — §D.0.RESULTS). Callers
 * should reserve this for PDF, not audio, given that cost.
 */
object VaultMemfdFallback {

    fun open(reader: VaultFileReader): ParcelFileDescriptor {
        val fd = Os.memfd_create("vault-content", 0)
        val total = reader.plainSize
        Os.ftruncate(fd, total)

        var written = 0L
        while (written < total) {
            val chunk = reader.readAt(written, VaultFormat.DEFAULT_CHUNK_SIZE)
            if (chunk.isEmpty()) break
            var offset = 0
            while (offset < chunk.size) {
                offset += Os.write(fd, chunk, offset, chunk.size - offset)
            }
            written += chunk.size
        }
        Os.lseek(fd, 0, OsConstants.SEEK_SET)
        return ParcelFileDescriptor.dup(fd)
    }
}
