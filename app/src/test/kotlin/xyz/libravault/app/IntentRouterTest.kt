package xyz.libravault.app

import android.net.Uri
import androidx.navigation.NavController
import androidx.navigation.NavOptionsBuilder
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xyz.libravault.core.domain.model.LibraryItem
import xyz.libravault.core.domain.model.MediaFormat
import xyz.libravault.core.storage.usecase.OpenFileUseCase

/**
 * [IntentRouter.route] has real branching logic (unsupported format, audio vs.
 * text, persisted vs. transient item) that determines which screen an incoming
 * ACTION_VIEW intent lands on — worth covering directly rather than only via the
 * two use cases it delegates to.
 */
class IntentRouterTest {

    private val openFile = mockk<OpenFileUseCase>()
    private val router = IntentRouter(openFile)

    @BeforeEach
    fun setUp() {
        // route() calls the real android.net.Uri.encode() for external files —
        // unmocked in tearDown to avoid leaking the class redefinition across
        // tests (see LibraryViewModelTest's setUp/tearDown for the same pattern).
        mockkStatic(Uri::class)
        every { Uri.encode(any()) } answers { firstArg() }
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(Uri::class)
    }

    private fun mockUri(value: String): Uri {
        val uri = mockk<Uri>()
        every { uri.toString() } returns value
        return uri
    }

    /** Captures the route string passed to NavController.navigate(route) { ... }. */
    private fun mockNavController(): Pair<NavController, () -> String?> {
        val navController = mockk<NavController>(relaxed = true)
        val routeSlot = slot<String>()
        every {
            navController.navigate(capture(routeSlot), any<NavOptionsBuilder.() -> Unit>())
        } just Runs
        return navController to { if (routeSlot.isCaptured) routeSlot.captured else null }
    }

    private fun libraryItem(id: Long, format: MediaFormat) = LibraryItem(
        id = id,
        vaultFolderId = if (id > 0) 1 else 0,
        filePath = "irrelevant",
        title = "T",
        author = "A",
        format = format,
    )

    @Test
    fun `unsupported format does not navigate`() = runTest {
        val uri = mockUri("content://unsupported")
        coEvery { openFile(uri) } returns null
        val (navController, _) = mockNavController()

        router.route(uri, navController)

        verify(exactly = 0) {
            navController.navigate(any<String>(), any<NavOptionsBuilder.() -> Unit>())
        }
    }

    @Test
    fun `persisted audio item routes to Player by id`() = runTest {
        val uri = mockUri("content://audio")
        coEvery { openFile(uri) } returns libraryItem(id = 42, format = MediaFormat.MP3)
        val (navController, capturedRoute) = mockNavController()

        router.route(uri, navController)

        assertEquals("player/42", capturedRoute())
    }

    @Test
    fun `transient audio item routes to ExternalPlayer with the encoded uri`() = runTest {
        val uri = mockUri("content://external-audio.mp3")
        coEvery { openFile(uri) } returns libraryItem(id = -1, format = MediaFormat.M4B)
        val (navController, capturedRoute) = mockNavController()

        router.route(uri, navController)

        assertEquals("player/external/content://external-audio.mp3", capturedRoute())
    }

    @Test
    fun `persisted text item routes to Reader by id`() = runTest {
        val uri = mockUri("content://book")
        coEvery { openFile(uri) } returns libraryItem(id = 7, format = MediaFormat.EPUB)
        val (navController, capturedRoute) = mockNavController()

        router.route(uri, navController)

        assertEquals("reader/7", capturedRoute())
    }

    @Test
    fun `transient text item routes to ExternalReader with the encoded uri`() = runTest {
        val uri = mockUri("content://external-book.epub")
        coEvery { openFile(uri) } returns libraryItem(id = -1, format = MediaFormat.PDF)
        val (navController, capturedRoute) = mockNavController()

        router.route(uri, navController)

        assertEquals("reader/external/content://external-book.epub", capturedRoute())
    }

    @Test
    fun `a freshly-scanned item with id 0 is treated as transient, not persisted`() = runTest {
        // OpenFileUseCase's transient items use id = -1L, but IntentRouter's own
        // condition is `item.id > 0` — id == 0 (Room's default/unset id) must also
        // route as transient, not crash or route to a nonexistent "item 0".
        val uri = mockUri("content://zero-id")
        coEvery { openFile(uri) } returns libraryItem(id = 0, format = MediaFormat.EPUB)
        val (navController, capturedRoute) = mockNavController()

        router.route(uri, navController)

        assertEquals("reader/external/content://zero-id", capturedRoute())
    }
}
