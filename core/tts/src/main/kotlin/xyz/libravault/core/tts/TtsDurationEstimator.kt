package xyz.libravault.core.tts

/**
 * Estimates spoken duration for TTS text — there is no real audio stream to measure
 * against for synthesized speech, so the Read Aloud Player screen's scrubber (#138)
 * needs a wall-clock/word-count estimate instead, mirroring iOS's
 * `AppState.estimateDuration(for:speed:)`.
 */
object TtsDurationEstimator {

    // ~150 wpm is a common average narration/TTS pace — same base rate iOS uses,
    // kept identical so estimated chapter lengths feel consistent across platforms.
    private const val BASE_WORDS_PER_MINUTE = 150.0

    // Floor so a near-empty chapter (or a 0x/negative speed) never estimates to 0ms,
    // which would make the scrubber's total duration collapse to nothing.
    private const val MIN_DURATION_MS = 1_000L

    fun estimateDurationMs(text: String, speed: Float): Long {
        val wordCount = text.trim().split(Regex("\\s+")).count { it.isNotBlank() }
        val effectiveWpm = BASE_WORDS_PER_MINUTE * speed.coerceAtLeast(0.1f)
        val minutes = wordCount / effectiveWpm
        return (minutes * 60_000).toLong().coerceAtLeast(MIN_DURATION_MS)
    }
}
