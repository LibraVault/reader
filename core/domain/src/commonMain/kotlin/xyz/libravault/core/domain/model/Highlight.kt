package xyz.libravault.core.domain.model

import java.time.Instant

data class Highlight(
    val id: Long = 0,
    val itemId: Long,
    val positionRef: String,
    val highlightedText: String,
    val colorHex: String = "#FFE066",
    val note: String? = null,
    val createdAt: Instant = Instant.now(),
)
