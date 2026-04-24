package xyz.libravault.core.domain.repository

import kotlinx.coroutines.flow.Flow
import xyz.libravault.core.domain.model.Bookmark
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
    suspend fun search(query: String): List<LibraryItem>
    suspend fun upsert(item: LibraryItem): Long
    suspend fun deleteItem(id: Long)
    suspend fun deleteByVault(vaultId: Long)
}

interface ProgressRepository {
    suspend fun getReadingProgress(itemId: Long): ReadingProgress?
    suspend fun saveReadingProgress(progress: ReadingProgress)
    suspend fun getListeningProgress(itemId: Long): ListeningProgress?
    suspend fun saveListeningProgress(progress: ListeningProgress)
    fun observeCurrentBook(): Flow<LibraryItem?>
    fun observeCurrentAudiobook(): Flow<LibraryItem?>
}

interface BookmarkRepository {
    fun observeBookmarks(itemId: Long): Flow<List<Bookmark>>
    suspend fun addBookmark(bookmark: Bookmark): Long
    suspend fun deleteBookmark(id: Long)
}

interface HighlightRepository {
    fun observeHighlights(itemId: Long): Flow<List<Highlight>>
    suspend fun addHighlight(highlight: Highlight): Long
    suspend fun deleteHighlight(id: Long)
    suspend fun updateHighlightNote(id: Long, note: String?)
}
