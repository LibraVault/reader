package xyz.libravault.core.storage

import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import xyz.libravault.core.storage.model.ScannedFile
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FileScanner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val vaultManager: VaultManager,
) {
    fun scanAll(vaultUris: List<Uri>): Flow<ScannedFile> = flow {
        for (uri in vaultUris) {
            vaultManager.scanFolder(uri).forEach { emit(it) }
        }
    }
}
