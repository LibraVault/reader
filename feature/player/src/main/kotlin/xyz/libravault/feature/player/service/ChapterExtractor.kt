package xyz.libravault.feature.player.service

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.metadata.Chapter as Media3Chapter
import androidx.media3.inspector.MetadataRetriever
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.libravault.core.logger.LibravaultLogger
import javax.inject.Inject
import javax.inject.Singleton

data class Chapter(
    val index: Int,
    val title: String,
    val startMs: Long,
    val endMs: Long,
)

/**
 * Extracts chapter information from audio files.
 *
 * Uses [MetadataRetriever] (`media3-inspector`, added in Media3 1.11 — see
 * https://github.com/androidx/media/issues/2803) to read each track's static
 * [androidx.media3.common.Format.metadata] without starting playback. This
 * picks up chapter data embedded as either a Nero `chpl` atom or a QuickTime
 * chapter track (the common formats for M4B audiobooks), both exposed
 * uniformly as [Media3Chapter] entries — no manual MP4 box parsing needed.
 *
 * Falls back to a single "Full Book" chapter when a file has no chapter
 * metadata, or extraction fails for any reason, so the UI always has a
 * consistent chapter model to work with.
 */
@Singleton
class ChapterExtractor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: LibravaultLogger,
) {
    companion object {
        private const val TAG = "ChapterExtractor"
    }

    @OptIn(UnstableApi::class)
    suspend fun extract(uri: Uri, durationMs: Long): List<Chapter> =
        withContext(Dispatchers.IO) {
            runCatching {
                val mediaItem = MediaItem.fromUri(uri)
                // MetadataRetriever.get() blocks the calling thread until the future
                // completes — safe here since we're already off the main thread on
                // Dispatchers.IO, and it avoids a callback/suspendCancellableCoroutine
                // bridge for what is otherwise a single synchronous-shaped read.
                MetadataRetriever.Builder(context, mediaItem).build().use { retriever ->
                    val trackGroups = retriever.retrieveTrackGroups().get()
                    val entries = mutableListOf<Media3Chapter>()
                    for (i in 0 until trackGroups.length) {
                        val trackGroup = trackGroups[i]
                        for (j in 0 until trackGroup.length) {
                            val metadata = trackGroup.getFormat(j).metadata ?: continue
                            for (k in 0 until metadata.length()) {
                                (metadata.get(k) as? Media3Chapter)?.let(entries::add)
                            }
                        }
                    }
                    toChapters(entries, durationMs)
                }
            }.getOrElse { e ->
                logger.w(TAG, "Chapter extraction failed for $uri: ${e.message}")
                singleChapter(durationMs)
            }
        }

    // ── Mapping ──────────────────────────────────────────────────────────────

    /**
     * Converts raw [Media3Chapter] entries (unordered, one per embedded chapter
     * marker, with only a start time each) into our [Chapter] model, sorted with
     * derived end times. Falls back to a single "Full Book" chapter if none of
     * the entries have a usable start time within the file's duration.
     *
     * `internal` (rather than `private`) so [ChapterExtractorTest] can exercise the
     * mapping logic directly with real [Media3Chapter] entries, without needing a
     * real playable file for [MetadataRetriever] to read.
     */
    internal fun toChapters(entries: List<Media3Chapter>, durationMs: Long): List<Chapter> {
        val sorted = entries
            .filter { it.startTimeMs in 0..durationMs }
            .sortedBy { it.startTimeMs }
            .distinctBy { it.startTimeMs }
        if (sorted.isEmpty()) return singleChapter(durationMs)

        return sorted.mapIndexed { index, entry ->
            Chapter(
                index   = index,
                title   = entry.title?.value ?: "Chapter ${index + 1}",
                startMs = entry.startTimeMs,
                endMs   = sorted.getOrNull(index + 1)?.startTimeMs ?: durationMs,
            )
        }
    }

    // ── Fallback ──────────────────────────────────────────────────────────────

    private fun singleChapter(durationMs: Long) = listOf(
        Chapter(index = 0, title = "Full Book", startMs = 0L, endMs = durationMs)
    )
}
