package xyz.libravault.core.database.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import xyz.libravault.core.database.LibravaultDatabase
import xyz.libravault.core.database.MIGRATION_1_2
import xyz.libravault.core.database.MIGRATION_2_3
import xyz.libravault.core.database.dao.BookmarkDao
import xyz.libravault.core.database.dao.CollectionDao
import xyz.libravault.core.database.dao.HighlightDao
import xyz.libravault.core.database.dao.LibraryItemDao
import xyz.libravault.core.database.dao.ProgressDao
import xyz.libravault.core.database.dao.VaultFolderDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): LibravaultDatabase =
        Room.databaseBuilder(
            context,
            LibravaultDatabase::class.java,
            LibravaultDatabase.DATABASE_NAME,
        )
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .fallbackToDestructiveMigration()
            .build()

    @Provides fun provideVaultFolderDao(db: LibravaultDatabase): VaultFolderDao = db.vaultFolderDao()
    @Provides fun provideLibraryItemDao(db: LibravaultDatabase): LibraryItemDao = db.libraryItemDao()
    @Provides fun provideProgressDao(db: LibravaultDatabase): ProgressDao = db.progressDao()
    @Provides fun provideBookmarkDao(db: LibravaultDatabase): BookmarkDao = db.bookmarkDao()
    @Provides fun provideHighlightDao(db: LibravaultDatabase): HighlightDao = db.highlightDao()
    @Provides fun provideCollectionDao(db: LibravaultDatabase): CollectionDao = db.collectionDao()
}
