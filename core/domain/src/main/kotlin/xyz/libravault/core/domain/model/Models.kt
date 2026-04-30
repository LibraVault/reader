package xyz.libravault.core.domain.model

import java.time.Instant

enum class MediaFormat {
    EPUB, PDF, MP3, M4B, OGG, FLAC, OPUS, AAC;

    fun isAudio() = this in setOf(MP3, M4B, OGG, FLAC, OPUS, AAC)
}

data class VaultFolder(
    val id: Long = 0,
    val uri: String,
    val displayName: String,
    val addedAt: Instant = Instant.now(),
)

data class LibraryItem(
    val id: Long = 0,
    val vaultFolderId: Long,
    val filePath: String,
    val title: String,
    val author: String,
    val narrator: String? = null,
    val series: String? = null,
    val seriesIndex: Float? = null,
    val format: MediaFormat,
    val coverArtPath: String? = null,
    val durationMs: Long? = null,       // audio only
    val pageCount: Int? = null,         // PDF only
    val addedAt: Instant = Instant.now(),
)

data class ReadingProgress(
    val itemId: Long,
    val positionCfi: String? = null,    // EPUB CFI
    val pageIndex: Int? = null,         // PDF page
    val lastReadAt: Instant = Instant.now(),
)

data class ListeningProgress(
    val itemId: Long,
    val positionMs: Long,
    val chapterIndex: Int = 0,
    val lastListenedAt: Instant = Instant.now(),
    val playbackSpeed: Float = 1.0f,
)

data class Bookmark(
    val id: Long = 0,
    val itemId: Long,
    val positionRef: String,            // CFI for EPUB, "page:N" for PDF, "ms:N" for audio
    val label: String? = null,
    val createdAt: Instant = Instant.now(),
)

data class BookmarkWithItemInfo(
    val bookmark: Bookmark,
    val itemTitle: String,
    val itemAuthor: String,
    val itemFormat: MediaFormat,
)

data class Collection(
    val id: Long = 0,
    val name: String,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
)

data class CollectionItem(
    val collectionId: Long,
    val itemId: Long,
    val addedAt: Instant = Instant.now(),
    val orderIndex: Int = 0,
)
