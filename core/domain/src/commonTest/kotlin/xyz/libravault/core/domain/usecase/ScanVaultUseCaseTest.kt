package xyz.libravault.core.domain.usecase

import app.cash.turbine.test
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import xyz.libravault.core.domain.scanner.LibraryScanner
import xyz.libravault.core.domain.scanner.ScanProgress

class ScanVaultUseCaseTest {

    private val scanner = mockk<LibraryScanner>()
    private val useCase = ScanVaultUseCase(scanner)

    @Test
    fun `emits all scanner events in order`() = runTest {
        every { scanner.scan() } returns flowOf(
            ScanProgress.Started,
            ScanProgress.ItemFound(1),
            ScanProgress.ItemFound(2),
            ScanProgress.Completed(2),
        )

        useCase().test {
            assertTrue(awaitItem() is ScanProgress.Started)
            assertEquals(1, (awaitItem() as ScanProgress.ItemFound).count)
            assertEquals(2, (awaitItem() as ScanProgress.ItemFound).count)
            assertEquals(0, (awaitItem() as ScanProgress.Completed).total)
            awaitComplete()
        }
    }

    @Test
    fun `propagates scan errors`() = runTest {
        every { scanner.scan() } returns flowOf(
            ScanProgress.Started,
            ScanProgress.Error("Permission denied"),
        )

        useCase().test {
            awaitItem() // Started
            val error = awaitItem() as ScanProgress.Error
            assertEquals("Permission denied", error.message)
            awaitComplete()
        }
    }

    @Test
    fun `empty vault emits started then completed with zero`() = runTest {
        every { scanner.scan() } returns flowOf(
            ScanProgress.Started,
            ScanProgress.Completed(0),
        )

        useCase().test {
            awaitItem() // Started
            val completed = awaitItem() as ScanProgress.Completed
            assertEquals(0, completed.total)
            awaitComplete()
        }
    }
}
