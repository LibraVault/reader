package xyz.libravault.core.storage

import android.net.Uri
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import xyz.libravault.core.domain.model.LibraryItem
import xyz.libravault.core.domain.model.MediaFormat
import xyz.libravault.core.domain.repository.LibraryRepository
import xyz.libravault.core.domain.repository.VaultRepository
import xyz.libravault.core.domain.scanner.FormatCounts
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

    // Prevents concurrent scans across ViewModels (e.g. OnboardingViewModel
    // and LibraryViewModel both calling scan() within the same session).
    private val scanInProgress = AtomicBoolean(false)

    // Fire-and-forget scope for Phase 2 metadata enrichment — survives flow completion
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Track current enrichment job so we can cancel it on re-scan
    private var enrichmentJob: Job? = null

    override fun scan(): Flow<ScanProgress> = flow {
        if (!scanInProgress.compareAndSet(false, true)) {
            logger.i(TAG, "Scan already in progress — skipping duplicate trigger")
            emit(ScanProgress.Completed(0))
            return@flow
        }
        emit(ScanProgress.Started)
        logger.i(TAG, "Scan started")

        try { runCatching {
            // ── 1. Collect vault URIs ────────────────────────────────────────
            val vaults = mutableListOf<Pair<Long, Uri>>().apply {
                addAll(
                    vaultRepository.observeVaults().first()
                        .map { it.id to Uri.parse(it.uri) }
                )
            }

            if (vaults.isEmpty()) {
                emit(ScanProgress.Completed(0, 0))
                return@runCatching
            }

            // ── 2. Phase 1: insert stubs immediately ─────────────────────────
            // For each discovered file, immediately insert a minimal LibraryItem
            // (title = filename, no cover, no duration) so the UI can show
            // something within milliseconds. Phase 2 enriches these stubs.
            val scannedPaths = mutableSetOf<String>()
            var count = 0
            val formatCounts = mutableMapOf<String, Int>().withDefault { 0 }

            // Load existing items once so we can skip re-inserting known files
            val existingPaths = libraryRepository.observeAll()
                .first()
                .map { it.filePath }
                .toSet()

            fileScanner.scanAll(vaults.map { it.second }).collect { scannedFile ->
                val vaultId = vaults
                    .firstOrNull { (_, uri) ->
                        scannedFile.uri.toString().startsWith(uri.toString())
                    }?.first ?: return@collect

                val path = scannedFile.uri.toString()
                scannedPaths.add(path)

                // Insert stub immediately if not already in DB —
                // the UI gets a populated list right away
                if (path !in existingPaths) {
                    runCatching {
                        val domainFormat = scannedFile.format.toDomain()
                        val stub = LibraryItem(
                            vaultFolderId = vaultId,
                            filePath      = path,
                            title         = scannedFile.displayName
                                .substringBeforeLast('.'),
                            author        = "Unknown",
                            format        = domainFormat,
                        )
                        libraryRepository.upsert(stub)
                        count++
                        formatCounts[domainFormat.name] = formatCounts.getValue(domainFormat.name) + 1
                        emit(ScanProgress.ItemFound(count))
                        logger.d(TAG, "Stub inserted: ${stub.title}")
                    }.onFailure { e ->
                        logger.w(TAG, "Failed stub insert for ${scannedFile.displayName}: ${e.message}")
                    }
                }
            }

            // ── 3. Remove stale entries ──────────────────────────────────────
            // Guard: if the scan returned 0 files despite active vaults, something
            // went wrong (SAF permission temporarily unavailable, I/O error, etc.).
            // Skipping stale removal prevents mass data-loss when the scanner finds
            // nothing due to a transient permission issue rather than actual deletion.
            if (scannedPaths.isEmpty()) {
                logger.w(TAG, "Scan found 0 files across ${vaults.size} vault(s) — skipping stale removal to prevent data loss")
            } else {
                val staleCount = removeStaleEntries(scannedPaths)
                if (staleCount > 0) logger.i(TAG, "Removed $staleCount stale entries")
            }

            // Signal Phase 1 complete — UI is now populated
            val epubCount = formatCounts["EPUB"] ?: 0
            val pdfCount = formatCounts["PDF"] ?: 0
            val audiobookCount = formatCounts.values.sum() - epubCount - pdfCount // all remaining are audio
            emit(
                ScanProgress.Completed(
                    processed = count,
                    total = count,
                    formatCounts = FormatCounts(
                        epub = epubCount,
                        pdf = pdfCount,
                        audiobook = audiobookCount,
                    ),
                )
            )
            logger.i(TAG, "Phase 1 complete — $count new stubs, metadata enrichment starts in background")

            // ── 4. Phase 2: enrich metadata OFF the hot flow ──────────────────
            // Run in a fire-and-forget coroutine so that the Flow collector
            // (LibraryViewModel) sees the stream complete and clears the
            // scanning flag.  Slow/broken files no longer block the UI.
            enrichmentJob?.cancel()
            enrichmentJob = backgroundScope.launch {
                enrichMetadata()
            }
        }.onFailure { e ->
            logger.e(TAG, "Scan failed", e)
            emit(ScanProgress.Error(e.message ?: "Unknown scan error"))
        }
        } finally {
            // Always release the lock — covers success, failure, AND flow cancellation
            scanInProgress.set(false)
            logger.d(TAG, "Scan lock released")
        }
    }

    /**
     * Phase 2 enrichment — runs independently of the scan flow.
     * Items already enriched (coverArtPath or durationMs set) are skipped
     * to avoid redundant I/O on every restart.
     *
     * Pure function so it can be unit-tested without spinning up the
     * scanner's background coroutine scope.
     */
    internal fun needsEnrichment(item: LibraryItem): Boolean {
        // Defensive: if the row says it has a cover but the file on disk
        // has gone (user wiped the cover cache, OS evicted `cacheDir`,
        // etc.), force a re-extraction regardless of the format-specific
        // gate below. Otherwise the stale absolute path would survive
        // forever and the UI would show a permanently blank cover.
        val savedCoverPath = item.coverArtPath
        val coverFileMissing = savedCoverPath != null && !File(savedCoverPath).exists()

        return coverFileMissing || when (item.format) {
            MediaFormat.MP3, MediaFormat.M4B,
            MediaFormat.OGG, MediaFormat.FLAC,
            MediaFormat.OPUS, MediaFormat.AAC -> item.durationMs == null
            MediaFormat.EPUB, MediaFormat.PDF  -> item.coverArtPath == null &&
                    item.author == "Unknown"
        }
    }

    private suspend fun enrichMetadata() {
        try {
            var enriched = 0
            val itemsToEnrich = libraryRepository.observeAll().first()

            for (item in itemsToEnrich) {
                if (!needsEnrichment(item)) continue

                runCatching {
                    val scannedFile = xyz.libravault.core.storage.model.ScannedFile(
                        uri         = Uri.parse(item.filePath),
                        displayName = item.title,
                        mimeType    = "",   // not needed for metadata extraction
                        format      = item.format.toStorage(),
                        sizeBytes   = 0L,  // not needed for metadata extraction
                    )
                    val metadata = metadataExtractor.extract(scannedFile)
                    val enrichedItem = item.copy(
                        title        = metadata.title,
                        author       = metadata.author,
                        narrator     = metadata.narrator,
                        series       = metadata.series,
                        seriesIndex  = metadata.seriesIndex,
                        coverArtPath = metadata.coverArtPath,
                        durationMs   = metadata.durationMs,
                        pageCount    = metadata.pageCount,
                    )
                    libraryRepository.upsert(enrichedItem)
                    enriched++
                    logger.d(TAG, "Enriched: ${item.title}")
                }.onFailure { e ->
                    logger.w(TAG, "Enrichment failed for ${item.title}: ${e.message}")
                }
            }

            logger.i(TAG, "Phase 2 complete — enriched $enriched items")
        } catch (e: Throwable) {
            logger.e(TAG, "Unhandled enrichment exception", e)
        }
    }

    private suspend fun removeStaleEntries(scannedPaths: Set<String>): Int {
        val allItems = libraryRepository.observeAll().first()

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

private fun MediaFormat.toStorage() = when (this) {
    MediaFormat.EPUB  -> StorageFormat.EPUB
    MediaFormat.PDF   -> StorageFormat.PDF
    MediaFormat.MP3   -> StorageFormat.MP3
    MediaFormat.M4B   -> StorageFormat.M4B
    MediaFormat.OGG   -> StorageFormat.OGG
    MediaFormat.FLAC  -> StorageFormat.FLAC
    MediaFormat.OPUS  -> StorageFormat.OPUS
    MediaFormat.AAC   -> StorageFormat.AAC
}

// ── Hilt binding ──────────────────────────────────────────────────────────────

@Module
@InstallIn(SingletonComponent::class)
abstract class ScannerModule {
    @Binds @Singleton
    abstract fun bindLibraryScanner(impl: LibraryScannerImpl): LibraryScanner
}
