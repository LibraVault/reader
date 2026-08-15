package xyz.libravault.core.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.libravault.core.logger.LibravaultLogger
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages cover art extracted from file tags.
 *
 * Covers are stored in the app's private cache directory — never in shared
 * storage, never requiring any extra permissions.
 *
 * Naming: SHA-256 of the source file path → avoids collisions and makes
 * lookups O(1) without a separate index.
 */
@Singleton
class CoverArtCache @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: LibravaultLogger,
) {
    private val cacheDir: File
        get() = File(context.cacheDir, "covers").also { it.mkdirs() }

    /**
     * Saves raw image bytes (JPEG/PNG extracted from ID3 or OPF) to cache.
     * Returns the absolute path of the saved file, or null if saving failed.
     *
     * Images are downsampled to [MAX_COVER_PX] on the long edge before saving
     * to prevent large embedded art from bloating the cache.
     *
     * Failures (corrupt header, OOM during decode) are logged at W and result
     * in a null return — callers treat null as "no cover available" and skip
     * rendering.
     */
    suspend fun save(key: String, imageBytes: ByteArray): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val bitmap = decode(key, imageBytes) ?: return@runCatching null
                val file = File(cacheDir, "${keyHash(key)}.jpg")
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                }
                bitmap.recycle()
                file.absolutePath
            }.onFailure { e ->
                logger.w(TAG, "save: failed to decode cover for $key (${imageBytes.size}B): ${e.javaClass.simpleName}: ${e.message}")
            }.getOrNull()
        }

    /**
     * Decodes and downsamples [imageBytes] exactly like [save] — same
     * hardened [decode] step, same JPEG quality-85 convention — but returns
     * the result as bytes instead of writing anything to [cacheDir].
     *
     * For callers that must never let a plaintext copy of an image touch
     * disk (Encrypted Vault import — see `VaultStore.setCoverArt`'s doc
     * comment, which explicitly expects a caller to reuse this hardened
     * decode step rather than duplicate it). [logKey] is used only for the
     * failure log line, exactly like [save]'s `key`; it is never persisted
     * anywhere.
     */
    suspend fun downsampleToJpeg(imageBytes: ByteArray, logKey: String = "external"): ByteArray? =
        withContext(Dispatchers.IO) {
            runCatching {
                val bitmap = decode(logKey, imageBytes) ?: return@runCatching null
                val bytes = java.io.ByteArrayOutputStream().use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                    out.toByteArray()
                }
                bitmap.recycle()
                bytes
            }.onFailure { e ->
                logger.w(TAG, "downsampleToJpeg: failed to decode cover for $logKey (${imageBytes.size}B): ${e.javaClass.simpleName}: ${e.message}")
            }.getOrNull()
        }

    /** Returns cached cover path if it already exists — avoids re-extraction. */
    fun getCachedPath(key: String): String? {
        val file = File(cacheDir, "${keyHash(key)}.jpg")
        return if (file.exists()) file.absolutePath else null
    }

    /** Removes cover for a specific file (e.g. when file is deleted from library). */
    fun evict(key: String) {
        File(cacheDir, "${keyHash(key)}.jpg").delete()
    }

    /** Clears the entire cover cache. */
    fun clearAll() = cacheDir.listFiles()?.forEach { it.delete() }

    private fun decode(key: String, bytes: ByteArray): Bitmap? {
        // First pass: read dimensions only
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)

        // Guard against malformed headers that return 0 dimensions — without
        // this, maxOf(0, 0) → 0 → calculateSampleSize(0, _) returns 1, then
        // the second decode attempts to render a full-size corrupt image and
        // can crash native Skia on historical CVEs (CVE-2020-0103 class).
        if (opts.outWidth <= 0 || opts.outHeight <= 0) {
            logger.w(TAG, "decode: header reported 0×0 dimensions for $key (${bytes.size}B)")
            return null
        }

        val longEdge = maxOf(opts.outWidth, opts.outHeight)
        opts.inSampleSize = calculateSampleSize(longEdge, MAX_COVER_PX)
        opts.inJustDecodeBounds = false

        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    /**
     * Returns a power-of-two sample size that brings [actual] down toward
     * [target] on the long edge. Result is bounded to [1, MAX_SAMPLE_SIZE] —
     * a sample size > 16 allocates pixel buffers of fractional dimensions and
     * can produce OOM on adversarial multi-MB cover inputs.
     */
    internal fun calculateSampleSize(actual: Int, target: Int): Int {
        var size = 1
        while (actual / (size * 2) >= target && size < MAX_SAMPLE_SIZE) size *= 2
        return size
    }

    private fun keyHash(key: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "CoverArtCache"
        private const val MAX_COVER_PX   = 512
        private const val MAX_SAMPLE_SIZE = 16
    }
}
