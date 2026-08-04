package xyz.libravault.core.domain.usecase

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import xyz.libravault.core.domain.model.Collection
import xyz.libravault.core.domain.model.LibraryItem
import xyz.libravault.core.domain.model.ListeningProgress
import xyz.libravault.core.domain.model.ReadingProgress
import xyz.libravault.core.domain.model.VaultFolder
import xyz.libravault.core.domain.repository.CollectionRepository
import xyz.libravault.core.domain.repository.LibraryRepository
import xyz.libravault.core.domain.repository.ProgressRepository
import xyz.libravault.core.domain.repository.VaultRepository
import javax.inject.Inject

class AddVaultFolderUseCase @Inject constructor(
    private val vaultRepository: VaultRepository,
) {
    suspend operator fun invoke(uri: String, displayName: String): VaultFolder {
        // Silently return existing vault if this URI is already registered —
        // no error, no duplicate, user just continues normally
        val existing = vaultRepository.findByUri(uri)
        if (existing != null) return existing
        return vaultRepository.addVault(uri, displayName)
    }
}

class RemoveVaultFolderUseCase @Inject constructor(
    private val vaultRepository: VaultRepository,
    private val libraryRepository: LibraryRepository,
) {
    suspend operator fun invoke(vaultId: Long) {
        libraryRepository.deleteByVault(vaultId)
        vaultRepository.removeVault(vaultId)
    }
}

class ObserveVaultsUseCase @Inject constructor(
    private val vaultRepository: VaultRepository,
) {
    operator fun invoke(): Flow<List<VaultFolder>> = vaultRepository.observeVaults()
}

/** One-shot vault lookup by id — used by the Markdown reader to resolve relative image paths. */
class GetVaultFolderUseCase @Inject constructor(
    private val vaultRepository: VaultRepository,
) {
    suspend operator fun invoke(vaultId: Long): VaultFolder? =
        vaultRepository.observeVaults().first().find { it.id == vaultId }
}

class GetLibraryUseCase @Inject constructor(
    private val libraryRepository: LibraryRepository,
) {
    operator fun invoke(): Flow<List<LibraryItem>> = libraryRepository.observeAll()
}

class SearchLibraryUseCase @Inject constructor(
    private val libraryRepository: LibraryRepository,
) {
    suspend operator fun invoke(query: String): List<LibraryItem> =
        libraryRepository.search(query)
}

/**
 * Looks up the sibling file immediately before/after a given item within the same vault
 * folder, ordered by [LibraryItem.filePath]. Used as a "next/previous chapter" fallback for
 * audiobooks split across multiple physical files with no embedded chapter markers.
 */
class GetAdjacentLibraryItemUseCase @Inject constructor(
    private val libraryRepository: LibraryRepository,
) {
    suspend fun next(vaultFolderId: Long, filePath: String): LibraryItem? =
        libraryRepository.getNextItemInVault(vaultFolderId, filePath)

    suspend fun previous(vaultFolderId: Long, filePath: String): LibraryItem? =
        libraryRepository.getPreviousItemInVault(vaultFolderId, filePath)
}

class SaveReadingProgressUseCase @Inject constructor(
    private val progressRepository: ProgressRepository,
) {
    suspend operator fun invoke(progress: ReadingProgress) =
        progressRepository.saveReadingProgress(progress)
}

class SaveListeningProgressUseCase @Inject constructor(
    private val progressRepository: ProgressRepository,
) {
    suspend operator fun invoke(progress: ListeningProgress) =
        progressRepository.saveListeningProgress(progress)
}

class GetListeningProgressUseCase @Inject constructor(
    private val progressRepository: ProgressRepository,
) {
    suspend operator fun invoke(itemId: Long): ListeningProgress? =
        progressRepository.getListeningProgress(itemId)
}

class ObserveCurrentlyReadingUseCase @Inject constructor(
    private val progressRepository: ProgressRepository,
) {
    fun reading(limit: Int = 8): Flow<List<LibraryItem>> =
        progressRepository.observeContinueReading(limit)
    fun listening(limit: Int = 4): Flow<List<LibraryItem>> =
        progressRepository.observeContinueListening(limit)
}
