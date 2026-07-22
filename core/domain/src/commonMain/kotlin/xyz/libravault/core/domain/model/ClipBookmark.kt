package xyz.libravault.core.domain.model

import java.time.Instant

/**
 * A clip bookmark marks a start and end position in an audio file,
 * allowing the user to replay a specific range — the audio equivalent
 * of a text highlight.
 *
 * Deferred from v1 player (M3) — scaffolded here in M4 for v1.1.
 * The Room entity and DAO are not yet wired; this model is used by
 * the UI placeholder in the player bookmarks sheet.
 */
data class ClipBookmark(
    val id: Long = 0,
    val itemId: Long,
    val startMs: Long,
    val endMs: Long,
    val label: String? = null,
    val createdAt: Instant = Instant.now(),
) {
    val durationMs: Long get() = endMs - startMs
}
