package xyz.libravault.core.domain.scanner

import kotlinx.coroutines.flow.Flow

sealed class ScanProgress {
    data object Started : ScanProgress()
    data class ItemFound(val count: Int) : ScanProgress()
    data class Completed(
        val processed: Int,
        val total: Int = 0,
        val formatCounts: FormatCounts? = null,
    ) : ScanProgress()
    data class Error(val message: String, val throwable: Throwable? = null) : ScanProgress()
}

/**
 * Format breakdown for scan completion messages.
 * Used by the SCAN_FORMAT_BREAKDOWN feature flag (LIB-193 / v1.0.1).
 */
data class FormatCounts(
    val epub: Int,
    val pdf: Int,
    val audiobook: Int,
)

/**
 * Abstraction over the vault scanning pipeline.
 *
 * Defined in core:domain so use cases can depend on it without importing
 * core:storage -- keeping core:domain free of Android and storage dependencies.
 *
 * Implemented by LibraryScannerImpl in core:storage, bound via Hilt.
 */
interface LibraryScanner {
    fun scan(): Flow<ScanProgress>
}
