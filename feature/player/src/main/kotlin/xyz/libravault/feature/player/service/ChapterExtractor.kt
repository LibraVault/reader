package xyz.libravault.feature.player.service

import android.content.Context
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
 * Android's [MediaMetadataRetriever] does not expose per-chapter metadata in a
 * portable way — `METADATA_KEY_NUM_TRACKS` is not chapter count, and there is
 * no per-chapter start-time API at the metadata level.
 *
 * **Pre-playback:** always returns a single "Full Book" fallback chapter.
 * The chapter index is derived from the total duration.
 *
 * **During playback:** [PlayerViewModel.updateChapters] should call
 * `Player.currentTimeline.windowCount` and `Player.getCurrentTimelineWindow`
 * for real ExoPlayer-level chapter data (read from M4B chapter atoms and
 * ID3 CHAP frames). This file-level extraction is a best-effort preload only.
 *
 * Falls back to a single "Full Book" chapter so the UI always has a
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

    suspend fun extract(uri: Uri, durationMs: Long): List<Chapter> =
        withContext(Dispatchers.IO) {
            runCatching {
                // Android MediaMetadataRetriever lacks a portable per-chapter API.
                // Real chapter data is only available through ExoPlayer's timeline
                // during playback. For the pre-playback snapshot, return a single
                // fallback chapter.
                singleChapter(durationMs)
            }.getOrElse { e ->
                logger.w(TAG, "Chapter extraction failed for $uri: ${e.message}")
                singleChapter(durationMs)
            }
        }

    // ── Fallback ──────────────────────────────────────────────────────────────

    private fun singleChapter(durationMs: Long) = listOf(
        Chapter(index = 0, title = "Full Book", startMs = 0L, endMs = durationMs)
    )
}
