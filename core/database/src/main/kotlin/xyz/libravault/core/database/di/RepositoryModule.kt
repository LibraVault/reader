package xyz.libravault.core.database.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import xyz.libravault.core.database.repository.BookmarkRepositoryImpl
import xyz.libravault.core.database.repository.LibraryRepositoryImpl
import xyz.libravault.core.database.repository.ProgressRepositoryImpl
import xyz.libravault.core.database.repository.VaultRepositoryImpl
import xyz.libravault.core.domain.repository.BookmarkRepository
import xyz.libravault.core.domain.repository.LibraryRepository
import xyz.libravault.core.domain.repository.ProgressRepository
import xyz.libravault.core.domain.repository.VaultRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds @Singleton
    abstract fun bindVaultRepository(impl: VaultRepositoryImpl): VaultRepository

    @Binds @Singleton
    abstract fun bindLibraryRepository(impl: LibraryRepositoryImpl): LibraryRepository

    @Binds @Singleton
    abstract fun bindProgressRepository(impl: ProgressRepositoryImpl): ProgressRepository

    @Binds @Singleton
    abstract fun bindBookmarkRepository(impl: BookmarkRepositoryImpl): BookmarkRepository
}
