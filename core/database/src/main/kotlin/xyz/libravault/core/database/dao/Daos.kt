package xyz.libravault.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import xyz.libravault.core.database.entity.BookmarkEntity
import xyz.libravault.core.database.entity.BookmarkWithItem
import xyz.libravault.core.database.entity.CollectionEntity
import xyz.libravault.core.database.entity.CollectionItemCrossRef
import xyz.libravault.core.database.entity.LibraryItemEntity
import xyz.libravault.core.database.entity.ListeningProgressEntity
import xyz.libravault.core.database.entity.ReadingProgressEntity
import xyz.libravault.core.database.entity.VaultFolderEntity

@Dao
interface VaultFolderDao {
    @Query("SELECT * FROM vault_folders ORDER BY addedAt ASC")
    fun observeAll(): Flow<List<VaultFolderEntity>>

    @Query("SELECT * FROM vault_folders WHERE uri = :uri LIMIT 1")
    suspend fun findByUri(uri: String): VaultFolderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(folder: VaultFolderEntity): Long

    @Query("DELETE FROM vault_folders WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface LibraryItemDao {
    @Query("SELECT * FROM library_items ORDER BY title ASC")
    fun observeAll(): Flow<List<LibraryItemEntity>>

    @Query("SELECT * FROM library_items WHERE vaultFolderId = :vaultId ORDER BY title ASC")
    fun observeByVault(vaultId: Long): Flow<List<LibraryItemEntity>>

    @Query("SELECT * FROM library_items WHERE format = :format ORDER BY title ASC")
    fun observeByFormat(format: String): Flow<List<LibraryItemEntity>>

    /**
     * Recently accessed: join with both progress tables, take latest lastReadAt / lastListenedAt.
     */
    @Query("""
        SELECT li.* FROM library_items li
        LEFT JOIN reading_progress rp ON li.id = rp.itemId
        LEFT JOIN listening_progress lp ON li.id = lp.itemId
        ORDER BY MAX(COALESCE(rp.lastReadAt, 0), COALESCE(lp.lastListenedAt, 0)) DESC
        LIMIT :limit
    """)
    fun observeRecentlyAccessed(limit: Int): Flow<List<LibraryItemEntity>>

    @Query("""
        SELECT * FROM library_items
        WHERE title LIKE '%' || :query || '%' ESCAPE '\'
           OR author LIKE '%' || :query || '%' ESCAPE '\'
           OR COALESCE(narrator, '') LIKE '%' || :query || '%' ESCAPE '\'
           OR COALESCE(series, '') LIKE '%' || :query || '%' ESCAPE '\'
        ORDER BY title ASC
    """)
    suspend fun search(query: String): List<LibraryItemEntity>

    @Upsert
    suspend fun upsert(item: LibraryItemEntity): Long

    @Query("DELETE FROM library_items WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM library_items WHERE vaultFolderId = :vaultId")
    suspend fun deleteByVault(vaultId: Long)

    @Query("SELECT * FROM library_items WHERE id = :id LIMIT 1")
    suspend fun getItemById(id: Long): LibraryItemEntity?

    @Query("SELECT * FROM library_items WHERE filePath = :path LIMIT 1")
    suspend fun findByPath(path: String): LibraryItemEntity?
}

@Dao
interface ProgressDao {
    @Query("SELECT * FROM reading_progress WHERE itemId = :itemId")
    suspend fun getReadingProgress(itemId: Long): ReadingProgressEntity?

    @Upsert
    suspend fun upsertReadingProgress(progress: ReadingProgressEntity)

    @Query("SELECT * FROM listening_progress WHERE itemId = :itemId")
    suspend fun getListeningProgress(itemId: Long): ListeningProgressEntity?

    @Upsert
    suspend fun upsertListeningProgress(progress: ListeningProgressEntity)

    /**
     * Up to [limit] most recently read text items (EPUB or PDF), in progress order.
     * Used to populate the Continue row on the library screen.
     */
    @Query("""
        SELECT li.* FROM library_items li
        INNER JOIN reading_progress rp ON li.id = rp.itemId
        WHERE li.format IN ('EPUB', 'PDF')
        ORDER BY rp.lastReadAt DESC
        LIMIT :limit
    """)
    fun observeContinueReading(limit: Int): Flow<List<LibraryItemEntity>>

    /**
     * Up to [limit] most recently listened audiobooks, in progress order.
     */
    @Query("""
        SELECT li.* FROM library_items li
        INNER JOIN listening_progress lp ON li.id = lp.itemId
        WHERE li.format IN ('MP3', 'M4B', 'OGG', 'FLAC', 'OPUS', 'AAC')
        ORDER BY lp.lastListenedAt DESC
        LIMIT :limit
    """)
    fun observeContinueListening(limit: Int): Flow<List<LibraryItemEntity>>
}

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks WHERE itemId = :itemId ORDER BY createdAt ASC")
    fun observeBookmarks(itemId: Long): Flow<List<BookmarkEntity>>

    @Query("""
        SELECT b.*, li.title AS itemTitle, li.author AS itemAuthor, li.format AS itemFormat
        FROM bookmarks b
        INNER JOIN library_items li ON li.id = b.itemId
        ORDER BY b.createdAt ASC
    """)
    fun observeAllBookmarks(): Flow<List<BookmarkWithItem>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(bookmark: BookmarkEntity): Long

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE bookmarks SET note = :note WHERE id = :id")
    suspend fun updateNote(id: Long, note: String?)
}

@Dao
interface CollectionDao {
    @Query("SELECT * FROM collections ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<CollectionEntity>>

    @Query("SELECT * FROM collections WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): CollectionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(collection: CollectionEntity): Long

    @Query("SELECT itemId FROM collection_items WHERE collectionId = :collectionId")
    suspend fun getItemIds(collectionId: Long): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addItems(items: List<CollectionItemCrossRef>)

    @Query("DELETE FROM collection_items WHERE collectionId = :collectionId AND itemId IN (:itemIds)")
    suspend fun removeItems(collectionId: Long, itemIds: List<Long>)

    @Query("DELETE FROM collections WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE collections SET name = :name, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateName(id: Long, name: String, updatedAt: Long)
}
