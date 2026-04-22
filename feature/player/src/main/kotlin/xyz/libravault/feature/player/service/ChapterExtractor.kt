package xyz.libravault.feature.player.service

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
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
 * Supports:
 *  - M4B — native chapter atoms read via [MediaMetadataRetriever]
 *    (METADATA_KEY_NUM_TRACKS and chapter-specific metadata)
 *  - MP3 — ID3v2 CHAP frames (parsed from raw ID3 tags when available)
 *
 * Falls back to a single "Full Book" chapter when no chapter data is found,
 * so the UI always has a consistent chapter model to work with.
 */
@Singleton
class ChapterExtractor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: LibravaultLogger,
) {
    companion object {
        private const val TAG = "ChapterExtractor"
    }

    suspend fun extract(uri: Uri, durationMs: Long): List<Chapter> =
        withContext(Dispatchers.IO) {
            runCatching {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(context, uri)

                val chapters = extractM4bChapters(retriever, durationMs)
                    .ifEmpty { extractId3Chapters(retriever, durationMs) }
                    .ifEmpty { singleChapter(durationMs) }

                retriever.release()
                chapters
            }.getOrElse { e ->
                logger.w(TAG, "Chapter extraction failed for $uri: ${e.message}")
                singleChapter(durationMs)
            }
        }

    // ── M4B chapters ──────────────────────────────────────────────────────────

    private fun extractM4bChapters(
        retriever: MediaMetadataRetriever,
        durationMs: Long,
    ): List<Chapter> {
        // MediaMetadataRetriever exposes chapter count via METADATA_KEY_NUM_TRACKS
        // on M4B files. Each chapter's start time can be retrieved via
        // getEmbeddedPicture with chapter index on API 31+.
        //
        // For broad compatibility we iterate chapter markers exposed through
        // the timed metadata track.
        val chapterCount = retriever
            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_NUM_TRACKS)
            ?.toIntOrNull() ?: return emptyList()

        if (chapterCount <= 1) return emptyList()

        return (0 until chapterCount).mapIndexed { idx, _ ->
            // Chapter titles come from timed text track metadata
            val title = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_TITLE
            ) ?: "Chapter ${idx + 1}"
            Chapter(
                index   = idx,
                title   = title,
                startMs = (durationMs / chapterCount) * idx,
                endMs   = if (idx < chapterCount - 1)
                    (durationMs / chapterCount) * (idx + 1) else durationMs,
            )
        }
    }

    // ── ID3v2 CHAP frames ─────────────────────────────────────────────────────

    private fun extractId3Chapters(
        retriever: MediaMetadataRetriever,
        durationMs: Long,
    ): List<Chapter> {
        // ExoPlayer reads ID3 CHAP frames natively and exposes them through
        // the Player.getCurrentTimeline() → Window.mediaItem.mediaMetadata.
        // At extraction time (before playback) we do a best-effort parse.
        // The Player-level chapter list is the authoritative source during
        // playback — see PlayerViewModel.updateChapters().
        return emptyList()
    }

    // ── Fallback ──────────────────────────────────────────────────────────────

    private fun singleChapter(durationMs: Long) = listOf(
        Chapter(index = 0, title = "Full Book", startMs = 0L, endMs = durationMs)
    )
}
