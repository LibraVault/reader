package xyz.libravault.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import xyz.libravault.core.database.dao.BookmarkDao
import xyz.libravault.core.database.dao.LibraryItemDao
import xyz.libravault.core.database.dao.ProgressDao
import xyz.libravault.core.database.dao.VaultFolderDao
import xyz.libravault.core.database.entity.BookmarkEntity
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
    ],
    version = 1,
    exportSchema = true,
)
abstract class LibravaultDatabase : RoomDatabase() {
    abstract fun vaultFolderDao(): VaultFolderDao
    abstract fun libraryItemDao(): LibraryItemDao
    abstract fun progressDao(): ProgressDao
    abstract fun bookmarkDao(): BookmarkDao

    companion object {
        const val DATABASE_NAME = "libravault.db"
    }
}
