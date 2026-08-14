package xyz.libravault.core.storage

import android.net.Uri
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xyz.libravault.core.domain.model.VaultFolder
import xyz.libravault.core.domain.repository.LibraryRepository
import xyz.libravault.core.domain.repository.VaultRepository
import xyz.libravault.core.domain.scanner.ScanProgress
import xyz.libravault.core.logger.LibravaultLogger
import xyz.libravault.core.storage.model.ScannedFile

/**
 * Regression coverage for the "PDF added via Add Vault never appears on
 * the home screen" bug (issue #154).
 *
 * `scan()` used to guard against concurrent calls with an `AtomicBoolean`
 * that made a call arriving mid-scan a silent no-op: it emitted
 * `ScanProgress.Completed(0)` immediately WITHOUT re-reading the vault
 * list, so a vault added while another scan was still enumerating files
 * was dropped forever — the in-flight scan had already snapshotted the
 * old vault list, and the new call never looked at all.
 *
 * These tests pin the fix: `scan()` now serializes concurrent calls with
 * a [kotlinx.coroutines.sync.Mutex], so a call arriving mid-scan *waits*
 * for the in-flight scan to finish and then runs its own scan against a
 * fresh vault snapshot, instead of being discarded.
 */
class LibraryScannerImplScanTest {

    private val vaultRepository = mockk<VaultRepository>()
    private val libraryRepository = mockk<LibraryRepository>()
    private val fileScanner = mockk<FileScanner>()
    private val metadataExtractor = mockk<MetadataExtractor>(relaxed = true)
    private val coverArtCache = mockk<CoverArtCache>(relaxed = true)
    private val logger = mockk<LibravaultLogger>(relaxed = true)

    private lateinit var scanner: LibraryScannerImpl

    @BeforeEach
    fun setUp() {
        // scan() calls the real android.net.Uri.parse() on each vault's
        // stored URI string; unmocked in @AfterEach so the class
        // redefinition doesn't leak across tests.
        mockkStatic(Uri::class)
        every { Uri.parse(any()) } answers { mockk(relaxed = true) }

        every { libraryRepository.observeAll() } returns flowOf(emptyList())

        scanner = LibraryScannerImpl(
            vaultRepository = vaultRepository,
            libraryRepository = libraryRepository,
            fileScanner = fileScanner,
            metadataExtractor = metadataExtractor,
            coverArtCache = coverArtCache,
            logger = logger,
        )
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(Uri::class)
    }

    @Test
    fun `vault added while a scan is in flight is picked up by the next scan, not dropped`() = runTest {
        val vaultA = VaultFolder(id = 1, uri = "content://vaultA", displayName = "Vault A")
        val vaultB = VaultFolder(id = 2, uri = "content://vaultB", displayName = "Vault B")
        val vaults = MutableStateFlow(listOf(vaultA))
        every { vaultRepository.observeVaults() } returns vaults

        // The first scan's file enumeration blocks on this gate — standing
        // in for a slow SAF directory walk — so we can deterministically
        // interleave a second scan() call while the first is still holding
        // the lock.
        val gate = CompletableDeferred<Unit>()
        val requestedVaultCounts = mutableListOf<Int>()
        var scanAllCallCount = 0
        every { fileScanner.scanAll(any()) } answers {
            val requestedVaults = firstArg<List<Uri>>()
            requestedVaultCounts.add(requestedVaults.size)
            scanAllCallCount++
            if (scanAllCallCount == 1) {
                flow<ScannedFile> { gate.await() }
            } else {
                emptyFlow()
            }
        }

        // First scan starts and blocks partway through file enumeration.
        val firstScan = async { scanner.scan().toList() }
        advanceUntilIdle()
        assertEquals(1, scanAllCallCount, "first scan should have started enumerating files")

        // A vault is added to the DB while the first scan is still in
        // flight — the "add vault from Settings mid-scan" race from the
        // bug report.
        vaults.value = listOf(vaultA, vaultB)

        // A second scan() call arrives now (e.g. SettingsViewModel.scanVaults()
        // right after inserting the new vault row). Under the old
        // AtomicBoolean guard this would emit Completed(0) and return
        // immediately, without ever running. Under the fix it must wait
        // for the mutex instead.
        val secondScan = async { scanner.scan().toList() }
        advanceUntilIdle()
        assertTrue(
            secondScan.isActive,
            "second scan() must wait for the in-progress scan instead of completing immediately",
        )

        // Let the first scan finish, releasing the lock.
        gate.complete(Unit)
        advanceUntilIdle()

        val first = firstScan.await()
        val second = secondScan.await()

        assertEquals(2, scanAllCallCount, "second scan should have actually run, not been dropped")
        assertEquals(1, requestedVaultCounts[0], "first scan snapshotted vaults before vault B existed")
        assertEquals(
            2,
            requestedVaultCounts[1],
            "second scan re-read the vault list after acquiring the lock and must see the vault " +
                "added mid-scan",
        )

        assertTrue(first.any { it is ScanProgress.Started })
        assertTrue(second.any { it is ScanProgress.Started }, "a dropped scan never emits Started")
    }
}
