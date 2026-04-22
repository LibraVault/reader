package xyz.libravault.core.storage

import android.net.Uri
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import xyz.libravault.core.domain.model.LibraryItem
import xyz.libravault.core.domain.model.MediaFormat
import xyz.libravault.core.domain.repository.LibraryRepository
import xyz.libravault.core.domain.repository.VaultRepository
import xyz.libravault.core.domain.scanner.LibraryScanner
import xyz.libravault.core.domain.scanner.ScanProgress
import xyz.libravault.core.logger.LibravaultLogger
import xyz.libravault.core.storage.model.MediaFormat as StorageFormat
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LibraryScannerImpl @Inject constructor(
    private val vaultRepository: VaultRepository,
    private val libraryRepository: LibraryRepository,
    private val fileScanner: FileScanner,
    private val metadataExtractor: MetadataExtractor,
    private val coverArtCache: CoverArtCache,
    private val logger: LibravaultLogger,
) : LibraryScanner {

    companion object {
        private const val TAG = "LibraryScanner"
    }

    override fun scan(): Flow<ScanProgress> = flow {
        emit(ScanProgress.Started)
        logger.i(TAG, "Scan started")

        runCatching {
            // ── 1. Collect vault URIs ────────────────────────────────────────
            val vaults = mutableListOf<Pair<Long, Uri>>()
            vaultRepository.observeVaults().collect { list ->
                vaults.addAll(list.map { it.id to Uri.parse(it.uri) })
                return@collect
            }

            if (vaults.isEmpty()) {
                emit(ScanProgress.Completed(0))
                return@runCatching
            }

            // ── 2. Scan and upsert ───────────────────────────────────────────
            val scannedPaths = mutableSetOf<String>()
            var count = 0

            fileScanner.scanAll(vaults.map { it.second }).collect { scannedFile ->
                val vaultId = vaults
                    .firstOrNull { (_, uri) ->
                        scannedFile.uri.toString().startsWith(uri.toString())
                    }?.first ?: return@collect

                val path = scannedFile.uri.toString()
                scannedPaths.add(path)

                runCatching {
                    val metadata = metadataExtractor.extract(scannedFile)
                    val item = LibraryItem(
                        vaultFolderId = vaultId,
                        filePath      = path,
                        title         = metadata.title,
                        author        = metadata.author,
                        narrator      = metadata.narrator,
                        series        = metadata.series,
                        seriesIndex   = metadata.seriesIndex,
                        format        = scannedFile.format.toDomain(),
                        coverArtPath  = metadata.coverArtPath,
                        durationMs    = metadata.durationMs,
                        pageCount     = metadata.pageCount,
                    )
                    libraryRepository.upsert(item)
                    count++
                    emit(ScanProgress.ItemFound(count))
                }.onFailure { e ->
                    logger.w(TAG, "Skipping ${scannedFile.displayName}: ${e.message}")
                }
            }

            // ── 3. Remove stale entries ──────────────────────────────────────
            val staleCount = removeStaleEntries(scannedPaths)
            if (staleCount > 0) logger.i(TAG, "Removed $staleCount stale entries")

            emit(ScanProgress.Completed(count))
            logger.i(TAG, "Scan complete — $count items")

        }.onFailure { e ->
            logger.e(TAG, "Scan failed", e)
            emit(ScanProgress.Error(e.message ?: "Unknown scan error"))
        }
    }

    private suspend fun removeStaleEntries(scannedPaths: Set<String>): Int {
        val allItems = mutableListOf<LibraryItem>()
        libraryRepository.observeAll().collect { items ->
            allItems.addAll(items)
            return@collect
        }

        var removed = 0
        allItems
            .filter { it.filePath !in scannedPaths }
            .forEach { stale ->
                libraryRepository.deleteItem(stale.id)
                stale.coverArtPath?.let { coverArtCache.evict(it) }
                removed++
            }
        return removed
    }
}

// ── Format mapping ────────────────────────────────────────────────────────────

private fun StorageFormat.toDomain() = when (this) {
    StorageFormat.EPUB  -> MediaFormat.EPUB
    StorageFormat.PDF   -> MediaFormat.PDF
    StorageFormat.MP3   -> MediaFormat.MP3
    StorageFormat.M4B   -> MediaFormat.M4B
    StorageFormat.OGG   -> MediaFormat.OGG
    StorageFormat.FLAC  -> MediaFormat.FLAC
    StorageFormat.OPUS  -> MediaFormat.OPUS
    StorageFormat.AAC   -> MediaFormat.AAC
}

// ── Hilt binding ──────────────────────────────────────────────────────────────

@Module
@InstallIn(SingletonComponent::class)
abstract class ScannerModule {
    @Binds @Singleton
    abstract fun bindLibraryScanner(impl: LibraryScannerImpl): LibraryScanner
}
