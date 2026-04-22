package xyz.libravault.core.database.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import xyz.libravault.core.database.dao.BookmarkDao
import xyz.libravault.core.database.dao.LibraryItemDao
import xyz.libravault.core.database.dao.ProgressDao
import xyz.libravault.core.database.dao.VaultFolderDao
import xyz.libravault.core.database.entity.BookmarkEntity
import xyz.libravault.core.database.entity.LibraryItemEntity
import xyz.libravault.core.database.entity.ListeningProgressEntity
import xyz.libravault.core.database.entity.ReadingProgressEntity
import xyz.libravault.core.database.entity.VaultFolderEntity
import xyz.libravault.core.domain.model.Bookmark
import xyz.libravault.core.domain.model.LibraryItem
import xyz.libravault.core.domain.model.ListeningProgress
import xyz.libravault.core.domain.model.MediaFormat
import xyz.libravault.core.domain.model.ReadingProgress
import xyz.libravault.core.domain.model.VaultFolder
import xyz.libravault.core.domain.repository.BookmarkRepository
import xyz.libravault.core.domain.repository.LibraryRepository
import xyz.libravault.core.domain.repository.ProgressRepository
import xyz.libravault.core.domain.repository.VaultRepository
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

// ── VaultRepository ──────────────────────────────────────────────────────────

@Singleton
class VaultRepositoryImpl @Inject constructor(
    private val dao: VaultFolderDao,
) : VaultRepository {

    override fun observeVaults(): Flow<List<VaultFolder>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun addVault(uri: String, displayName: String): VaultFolder {
        val entity = VaultFolderEntity(
            uri = uri,
            displayName = displayName,
            addedAt = System.currentTimeMillis(),
        )
        val id = dao.insert(entity)
        return entity.copy(id = id).toDomain()
    }

    override suspend fun removeVault(id: Long) = dao.deleteById(id)
}

// ── LibraryRepository ────────────────────────────────────────────────────────

@Singleton
class LibraryRepositoryImpl @Inject constructor(
    private val dao: LibraryItemDao,
) : LibraryRepository {

    override fun observeAll(): Flow<List<LibraryItem>> =
        dao.observeAll().map { it.map(LibraryItemEntity::toDomain) }

    override fun observeByVault(vaultId: Long): Flow<List<LibraryItem>> =
        dao.observeByVault(vaultId).map { it.map(LibraryItemEntity::toDomain) }

    override fun observeByFormat(format: MediaFormat): Flow<List<LibraryItem>> =
        dao.observeByFormat(format.name).map { it.map(LibraryItemEntity::toDomain) }

    override fun observeRecentlyAccessed(limit: Int): Flow<List<LibraryItem>> =
        dao.observeRecentlyAccessed(limit).map { it.map(LibraryItemEntity::toDomain) }

    override suspend fun search(query: String): List<LibraryItem> =
        dao.search(query).map(LibraryItemEntity::toDomain)

    override suspend fun getItemById(id: Long): LibraryItem? =
        dao.getItemById(id)?.toDomain()

    override suspend fun upsert(item: LibraryItem): Long =
        dao.upsert(item.toEntity())

    override suspend fun deleteItem(id: Long) =
        dao.deleteById(id)

    override suspend fun deleteByVault(vaultId: Long) =
        dao.deleteByVault(vaultId)
}

// ── ProgressRepository ───────────────────────────────────────────────────────

@Singleton
class ProgressRepositoryImpl @Inject constructor(
    private val dao: ProgressDao,
) : ProgressRepository {

    override suspend fun getReadingProgress(itemId: Long): ReadingProgress? =
        dao.getReadingProgress(itemId)?.toDomain()

    override suspend fun saveReadingProgress(progress: ReadingProgress) =
        dao.upsertReadingProgress(progress.toEntity())

    override suspend fun getListeningProgress(itemId: Long): ListeningProgress? =
        dao.getListeningProgress(itemId)?.toDomain()

    override suspend fun saveListeningProgress(progress: ListeningProgress) =
        dao.upsertListeningProgress(progress.toEntity())

    override fun observeCurrentBook(): Flow<LibraryItem?> =
        dao.observeCurrentBook().map { it?.toDomain() }

    override fun observeCurrentAudiobook(): Flow<LibraryItem?> =
        dao.observeCurrentAudiobook().map { it?.toDomain() }
}

// ── BookmarkRepository ───────────────────────────────────────────────────────

@Singleton
class BookmarkRepositoryImpl @Inject constructor(
    private val dao: BookmarkDao,
) : BookmarkRepository {

    override fun observeBookmarks(itemId: Long): Flow<List<Bookmark>> =
        dao.observeBookmarks(itemId).map { it.map(BookmarkEntity::toDomain) }

    override suspend fun addBookmark(bookmark: Bookmark): Long =
        dao.insert(bookmark.toEntity())

    override suspend fun deleteBookmark(id: Long) =
        dao.deleteById(id)
}

// ── Mappers ───────────────────────────────────────────────────────────────────

private fun VaultFolderEntity.toDomain() = VaultFolder(
    id          = id,
    uri         = uri,
    displayName = displayName,
    addedAt     = Instant.ofEpochMilli(addedAt),
)

private fun LibraryItemEntity.toDomain() = LibraryItem(
    id            = id,
    vaultFolderId = vaultFolderId,
    filePath      = filePath,
    title         = title,
    author        = author,
    narrator      = narrator,
    series        = series,
    seriesIndex   = seriesIndex,
    format        = MediaFormat.valueOf(format),
    coverArtPath  = coverArtPath,
    durationMs    = durationMs,
    pageCount     = pageCount,
    addedAt       = Instant.ofEpochMilli(addedAt),
)

private fun LibraryItem.toEntity() = LibraryItemEntity(
    id            = id,
    vaultFolderId = vaultFolderId,
    filePath      = filePath,
    title         = title,
    author        = author,
    narrator      = narrator,
    series        = series,
    seriesIndex   = seriesIndex,
    format        = format.name,
    coverArtPath  = coverArtPath,
    durationMs    = durationMs,
    pageCount     = pageCount,
    addedAt       = addedAt.toEpochMilli(),
)

private fun ReadingProgressEntity.toDomain() = ReadingProgress(
    itemId       = itemId,
    positionCfi  = positionCfi,
    pageIndex    = pageIndex,
    lastReadAt   = Instant.ofEpochMilli(lastReadAt),
)

private fun ReadingProgress.toEntity() = ReadingProgressEntity(
    itemId      = itemId,
    positionCfi = positionCfi,
    pageIndex   = pageIndex,
    lastReadAt  = lastReadAt.toEpochMilli(),
)

private fun ListeningProgressEntity.toDomain() = ListeningProgress(
    itemId           = itemId,
    positionMs       = positionMs,
    chapterIndex     = chapterIndex,
    lastListenedAt   = Instant.ofEpochMilli(lastListenedAt),
)

private fun ListeningProgress.toEntity() = ListeningProgressEntity(
    itemId         = itemId,
    positionMs     = positionMs,
    chapterIndex   = chapterIndex,
    lastListenedAt = lastListenedAt.toEpochMilli(),
)

private fun BookmarkEntity.toDomain() = Bookmark(
    id          = id,
    itemId      = itemId,
    positionRef = positionRef,
    label       = label,
    createdAt   = Instant.ofEpochMilli(createdAt),
)

private fun Bookmark.toEntity() = BookmarkEntity(
    id          = id,
    itemId      = itemId,
    positionRef = positionRef,
    label       = label,
    createdAt   = createdAt.toEpochMilli(),
)

// ── HighlightRepository ──────────────────────────────────────────────────────

@Singleton
class HighlightRepositoryImpl @Inject constructor(
    private val dao: xyz.libravault.core.database.dao.HighlightDao,
) : xyz.libravault.core.domain.repository.HighlightRepository {

    override fun observeHighlights(itemId: Long): kotlinx.coroutines.flow.Flow<List<xyz.libravault.core.domain.model.Highlight>> =
        dao.observeHighlights(itemId).map { it.map { e -> e.toDomain() } }

    override suspend fun addHighlight(highlight: xyz.libravault.core.domain.model.Highlight): Long =
        dao.insert(highlight.toEntity())

    override suspend fun deleteHighlight(id: Long) = dao.deleteById(id)

    override suspend fun updateHighlightNote(id: Long, note: String?) =
        dao.updateNote(id, note)
}

private fun xyz.libravault.core.database.entity.HighlightEntity.toDomain() =
    xyz.libravault.core.domain.model.Highlight(
        id              = id,
        itemId          = itemId,
        positionRef     = positionRef,
        highlightedText = highlightedText,
        colorHex        = colorHex,
        note            = note,
        createdAt       = java.time.Instant.ofEpochMilli(createdAt),
    )

private fun xyz.libravault.core.domain.model.Highlight.toEntity() =
    xyz.libravault.core.database.entity.HighlightEntity(
        id              = id,
        itemId          = itemId,
        positionRef     = positionRef,
        highlightedText = highlightedText,
        colorHex        = colorHex,
        note            = note,
        createdAt       = createdAt.toEpochMilli(),
    )
