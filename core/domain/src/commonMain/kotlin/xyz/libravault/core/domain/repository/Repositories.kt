package xyz.libravault.core.domain.repository

import kotlinx.coroutines.flow.Flow
import xyz.libravault.core.domain.model.Bookmark
import xyz.libravault.core.domain.model.BookmarkWithItemInfo
import xyz.libravault.core.domain.model.Collection
import xyz.libravault.core.domain.model.Highlight
import xyz.libravault.core.domain.model.LibraryItem
import xyz.libravault.core.domain.model.ListeningProgress
import xyz.libravault.core.domain.model.MediaFormat
import xyz.libravault.core.domain.model.ReadingProgress
import xyz.libravault.core.domain.model.VaultFolder

interface VaultRepository {
    fun observeVaults(): Flow<List<VaultFolder>>
    suspend fun findByUri(uri: String): VaultFolder?
    suspend fun addVault(uri: String, displayName: String): VaultFolder
    suspend fun removeVault(id: Long)
}

interface LibraryRepository {
    fun observeAll(): Flow<List<LibraryItem>>
    fun observeByVault(vaultId: Long): Flow<List<LibraryItem>>
    fun observeByFormat(format: MediaFormat): Flow<List<LibraryItem>>
    fun observeRecentlyAccessed(limit: Int = 10): Flow<List<LibraryItem>>
    suspend fun getItemById(id: Long): LibraryItem?
    suspend fun findByPath(path: String): LibraryItem?
    suspend fun search(query: String): List<LibraryItem>
    suspend fun upsert(item: LibraryItem): Long
    suspend fun deleteItem(id: Long)
    suspend fun deleteByVault(vaultId: Long)

    /**
     * Nulls out `coverArtPath` for every library item. Intended to be
     * invoked after `CoverArtCache.clearAll()` so the next scan's
     * enrichment gate (which keys off `coverArtPath == null`) re-extracts
     * covers instead of trusting stale absolute paths whose backing
     * files were just deleted.
     */
    suspend fun clearCoverArtPaths()
}

interface ProgressRepository {
    suspend fun getReadingProgress(itemId: Long): ReadingProgress?
    suspend fun saveReadingProgress(progress: ReadingProgress)
    suspend fun getListeningProgress(itemId: Long): ListeningProgress?
    suspend fun saveListeningProgress(progress: ListeningProgress)
    fun observeContinueReading(limit: Int): Flow<List<LibraryItem>>
    fun observeContinueListening(limit: Int): Flow<List<LibraryItem>>
}

interface BookmarkRepository {
    fun observeBookmarks(itemId: Long): Flow<List<Bookmark>>
    fun observeAllBookmarksWithItem(): Flow<List<BookmarkWithItemInfo>>
    suspend fun addBookmark(bookmark: Bookmark): Long
    suspend fun deleteBookmark(id: Long)
    suspend fun updateBookmarkNote(id: Long, note: String?)
}

interface HighlightRepository {
    fun observeHighlights(itemId: Long): Flow<List<Highlight>>
    suspend fun addHighlight(highlight: Highlight): Long
    suspend fun deleteHighlight(id: Long)
    suspend fun updateHighlightNote(id: Long, note: String?)
}

interface CollectionRepository {
    fun observeAll(): Flow<List<Collection>>
    suspend fun getById(id: Long): Collection?
    suspend fun create(name: String, itemIds: Set<Long> = emptySet()): Collection
    suspend fun addItems(collectionId: Long, itemIds: Set<Long>)
    suspend fun removeItems(collectionId: Long, itemIds: Set<Long>)
    suspend fun delete(id: Long)
}
