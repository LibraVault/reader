package xyz.libravault.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import xyz.libravault.core.database.entity.HighlightEntity

@Dao
interface HighlightDao {
    @Query("SELECT * FROM highlights WHERE itemId = :itemId ORDER BY createdAt DESC")
    fun observeHighlights(itemId: Long): Flow<List<HighlightEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(highlight: HighlightEntity): Long

    @Query("DELETE FROM highlights WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE highlights SET note = :note WHERE id = :id")
    suspend fun updateNote(id: Long, note: String?)
}
