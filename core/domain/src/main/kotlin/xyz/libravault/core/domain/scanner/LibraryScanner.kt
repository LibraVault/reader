package xyz.libravault.core.domain.scanner

import kotlinx.coroutines.flow.Flow

sealed class ScanProgress {
    data object Started                           : ScanProgress()
    data class  ItemFound(val count: Int)         : ScanProgress()
    data class  Completed(val total: Int)         : ScanProgress()
    data class  Error(val message: String)        : ScanProgress()
}

/**
 * Abstraction over the vault scanning pipeline.
 *
 * Defined in core:domain so use cases can depend on it without importing
 * core:storage — keeping core:domain free of Android and storage dependencies.
 *
 * Implemented by LibraryScannerImpl in core:storage, bound via Hilt.
 */
interface LibraryScanner {
    fun scan(): Flow<ScanProgress>
}
