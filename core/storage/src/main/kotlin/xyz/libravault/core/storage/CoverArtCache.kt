package xyz.libravault.core.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
) {
    private val cacheDir: File
        get() = File(context.cacheDir, "covers").also { it.mkdirs() }

    /**
     * Saves raw image bytes (JPEG/PNG extracted from ID3 or OPF) to cache.
     * Returns the absolute path of the saved file, or null if saving failed.
     *
     * Images are downsampled to [MAX_COVER_PX] on the long edge before saving
     * to prevent large embedded art from bloating the cache.
     */
    suspend fun save(key: String, imageBytes: ByteArray): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val bitmap = decode(imageBytes) ?: return@runCatching null
                val file = File(cacheDir, "${keyHash(key)}.jpg")
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                }
                bitmap.recycle()
                file.absolutePath
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

    private fun decode(bytes: ByteArray): Bitmap? {
        // First pass: read dimensions only
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)

        // Calculate downsample factor
        val longEdge = maxOf(opts.outWidth, opts.outHeight)
        opts.inSampleSize = calculateSampleSize(longEdge, MAX_COVER_PX)
        opts.inJustDecodeBounds = false

        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    private fun calculateSampleSize(actual: Int, target: Int): Int {
        var size = 1
        while (actual / (size * 2) >= target) size *= 2
        return size
    }

    private fun keyHash(key: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val MAX_COVER_PX = 512
    }
}
