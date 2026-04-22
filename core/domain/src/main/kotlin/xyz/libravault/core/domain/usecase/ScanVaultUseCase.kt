package xyz.libravault.core.domain.usecase

import kotlinx.coroutines.flow.Flow
import xyz.libravault.core.domain.scanner.LibraryScanner
import xyz.libravault.core.domain.scanner.ScanProgress
import javax.inject.Inject

/**
 * Triggers a full vault scan.
 * Delegates entirely to [LibraryScanner] — no storage imports in core:domain.
 */
class ScanVaultUseCase @Inject constructor(
    private val scanner: LibraryScanner,
) {
    operator fun invoke(): Flow<ScanProgress> = scanner.scan()
}
