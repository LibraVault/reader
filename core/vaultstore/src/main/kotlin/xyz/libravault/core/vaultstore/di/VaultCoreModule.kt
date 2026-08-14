package xyz.libravault.core.vaultstore.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import xyz.libravault.core.vaultstore.AndroidHardwareKeyWrapFactory
import xyz.libravault.core.vaultstore.HardwareKeyWrapFactory
import java.io.File
import javax.inject.Qualifier
import javax.inject.Singleton

/** Root directory under which every Encrypted Vault gets its own
 * subdirectory (named by [xyz.libravault.core.vaultstore.VaultRegistryEntryDto.id]).
 * App-private storage, never a SAF tree — deliberately distinct from a
 * "Folder" (`core.storage.VaultManager`'s SAF-based, unencrypted concept). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class VaultsRootDir

/**
 * Real, Android-backed DI wiring for `core:vaultstore`'s otherwise
 * Context-free classes. Kept in this module (not `feature:vault`) so the
 * "engine" and its one real implementation ship together — `feature:vault`
 * only ever consumes [HardwareKeyWrapFactory]/[VaultsRootDir] via injection,
 * never constructs [AndroidHardwareKeyWrapFactory] itself.
 */
@Module
@InstallIn(SingletonComponent::class)
object VaultCoreModule {

    @Provides
    @Singleton
    fun provideHardwareKeyWrapFactory(): HardwareKeyWrapFactory = AndroidHardwareKeyWrapFactory()

    @Provides
    @Singleton
    @VaultsRootDir
    fun provideVaultsRootDir(@ApplicationContext context: Context): File = File(context.filesDir, "vaults")
}
