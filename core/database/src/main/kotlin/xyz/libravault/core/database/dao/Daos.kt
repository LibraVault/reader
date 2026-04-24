package xyz.libravault.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import xyz.libravault.core.database.entity.BookmarkEntity
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
        WHERE title LIKE '%' || :query || '%'
           OR author LIKE '%' || :query || '%'
           OR COALESCE(narrator, '') LIKE '%' || :query || '%'
           OR COALESCE(series, '') LIKE '%' || :query || '%'
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

    /** Most recently read text item (EPUB or PDF) */
    @Query("""
        SELECT li.* FROM library_items li
        INNER JOIN reading_progress rp ON li.id = rp.itemId
        WHERE li.format IN ('EPUB', 'PDF')
        ORDER BY rp.lastReadAt DESC
        LIMIT 1
    """)
    fun observeCurrentBook(): Flow<LibraryItemEntity?>

    /** Most recently listened audiobook */
    @Query("""
        SELECT li.* FROM library_items li
        INNER JOIN listening_progress lp ON li.id = lp.itemId
        WHERE li.format IN ('MP3', 'M4B')
        ORDER BY lp.lastListenedAt DESC
        LIMIT 1
    """)
    fun observeCurrentAudiobook(): Flow<LibraryItemEntity?>
}

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks WHERE itemId = :itemId ORDER BY createdAt DESC")
    fun observeBookmarks(itemId: Long): Flow<List<BookmarkEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(bookmark: BookmarkEntity): Long

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun deleteById(id: Long)
}
