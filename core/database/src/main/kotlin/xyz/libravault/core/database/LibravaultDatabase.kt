package xyz.libravault.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import xyz.libravault.core.database.dao.BookmarkDao
import xyz.libravault.core.database.dao.CollectionDao
import xyz.libravault.core.database.dao.HighlightDao
import xyz.libravault.core.database.dao.LibraryItemDao
import xyz.libravault.core.database.dao.ProgressDao
import xyz.libravault.core.database.dao.VaultFolderDao
import xyz.libravault.core.database.entity.BookmarkEntity
import xyz.libravault.core.database.entity.CollectionEntity
import xyz.libravault.core.database.entity.CollectionItemCrossRef
import xyz.libravault.core.database.entity.HighlightEntity
import xyz.libravault.core.database.entity.LibraryItemEntity
import xyz.libravault.core.database.entity.ListeningProgressEntity
import xyz.libravault.core.database.entity.ReadingProgressEntity
import xyz.libravault.core.database.entity.VaultFolderEntity

@Database(
    entities = [
        VaultFolderEntity::class,
        LibraryItemEntity::class,
        ReadingProgressEntity::class,
        ListeningProgressEntity::class,
        BookmarkEntity::class,
        HighlightEntity::class,
        CollectionEntity::class,
        CollectionItemCrossRef::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class LibravaultDatabase : RoomDatabase() {
    abstract fun vaultFolderDao(): VaultFolderDao
    abstract fun libraryItemDao(): LibraryItemDao
    abstract fun progressDao(): ProgressDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun highlightDao(): HighlightDao
    abstract fun collectionDao(): CollectionDao

    companion object {
        const val DATABASE_NAME = "libravault.db"
    }
}
