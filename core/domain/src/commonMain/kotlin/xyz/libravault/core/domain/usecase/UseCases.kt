package xyz.libravault.core.domain.usecase

import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import xyz.libravault.core.domain.model.Collection
import xyz.libravault.core.domain.model.LibraryItem
import xyz.libravault.core.domain.model.ListeningProgress
import xyz.libravault.core.domain.model.ReadingProgress
import xyz.libravault.core.domain.model.VaultFolder
import xyz.libravault.core.domain.repository.CollectionRepository
import xyz.libravault.core.domain.repository.LibraryRepository
import xyz.libravault.core.domain.repository.ProgressRepository
import xyz.libravault.core.domain.repository.VaultRepository

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
