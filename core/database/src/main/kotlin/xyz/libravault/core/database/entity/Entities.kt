package xyz.libravault.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "vault_folders")
data class VaultFolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uri: String,
    val displayName: String,
    val addedAt: Long,          // epoch millis
)

@Entity(tableName = "collections")
data class CollectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "collection_items",
    primaryKeys = ["collectionId", "itemId"],
    foreignKeys = [
        ForeignKey(
            entity = CollectionEntity::class,
            parentColumns = ["id"],
            childColumns = ["collectionId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = LibraryItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["itemId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("collectionId"),
        Index("itemId"),
    ],
)
data class CollectionItemCrossRef(
    val collectionId: Long,
    val itemId: Long,
)

@Entity(
    tableName = "library_items",
    foreignKeys = [ForeignKey(
        entity = VaultFolderEntity::class,
        parentColumns = ["id"],
        childColumns = ["vaultFolderId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [
        Index("vaultFolderId"),
        Index("format"),
        Index("title"),
        Index("author"),
    ],
)
data class LibraryItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val vaultFolderId: Long,
    val filePath: String,
    val title: String,
    val author: String,
    val narrator: String?,
    val series: String?,
    val seriesIndex: Float?,
    val format: String,         // MediaFormat.name()
    val coverArtPath: String?,
    val durationMs: Long?,
    val pageCount: Int?,
    val addedAt: Long,
)

@Entity(tableName = "reading_progress")
data class ReadingProgressEntity(
    @PrimaryKey val itemId: Long,
    val positionCfi: String?,
    val pageIndex: Int?,
    val lastReadAt: Long,
)

@Entity(tableName = "listening_progress")
data class ListeningProgressEntity(
    @PrimaryKey val itemId: Long,
    val positionMs: Long,
    val chapterIndex: Int,
    val lastListenedAt: Long,
)

@Entity(
    tableName = "bookmarks",
    foreignKeys = [ForeignKey(
        entity = LibraryItemEntity::class,
        parentColumns = ["id"],
        childColumns = ["itemId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("itemId")],
)
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val itemId: Long,
    val positionRef: String,
    val label: String?,
    val createdAt: Long,
)
