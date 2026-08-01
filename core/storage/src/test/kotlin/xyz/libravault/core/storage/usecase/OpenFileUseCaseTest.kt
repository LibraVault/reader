package xyz.libravault.core.storage.usecase

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import xyz.libravault.core.domain.model.LibraryItem
import xyz.libravault.core.domain.model.MediaFormat
import xyz.libravault.core.domain.repository.LibraryRepository

/**
 * OpenFileUseCase resolves an ACTION_VIEW intent's Uri to a LibraryItem — either
 * an existing library entry, a transient item for a recognized new file, or null
 * for an unsupported format. detectFormat() (private) has an 8-branch mime/
 * extension mapping with no prior coverage; exercised here through invoke().
 */
class OpenFileUseCaseTest {

    private val contentResolver = mockk<ContentResolver>()
    private val context = mockk<Context>()
    private val libraryRepository = mockk<LibraryRepository>()
    private val useCase = OpenFileUseCase(context, libraryRepository)

    @BeforeEach
    fun setUp() {
        every { context.contentResolver } returns contentResolver
    }

    private fun mockUri(fileName: String): Uri {
        val uri = mockk<Uri>()
        every { uri.toString() } returns "content://test/$fileName"
        every { uri.lastPathSegment } returns fileName
        return uri
    }

    @Test
    fun `returns the existing item directly when the path is already in the library`() = runTest {
        val uri = mockUri("book.epub")
        // Evaluated *before* coEvery{}, not inline inside it — calling a method on a
        // different mock (uri.toString()) inside every/coEvery's argument list gets
        // swept into MockK's call-recording for the outer stub instead of being
        // evaluated as a plain value, producing a matcher that never actually matches.
        val uriString = uri.toString()
        val existing = LibraryItem(
            id = 5, vaultFolderId = 1, filePath = uriString,
            title = "T", author = "A", format = MediaFormat.EPUB,
        )
        coEvery { libraryRepository.findByPath(uriString) } returns existing

        val result = useCase(uri)

        assertEquals(existing, result)
    }

    @ParameterizedTest(name = "mime=\"{0}\" name={1} -> {2}")
    @CsvSource(
        "application/epub+zip, book.epub, EPUB",
        "'', book.epub, EPUB",
        "application/pdf, doc.pdf, PDF",
        "'', doc.PDF, PDF",
        "audio/mpeg, track.mp3, MP3",
        "audio/x-m4b, audiobook.m4b, M4B",
        "audio/ogg, track.ogg, OGG",
        "audio/vorbis, track.ogg, OGG",
        "audio/flac, track.flac, FLAC",
        "audio/x-flac, track.flac, FLAC",
        "audio/opus, track.opus, OPUS",
        "audio/aac, track.aac, AAC",
        "audio/x-aac, track.aac, AAC",
    )
    fun `detects format from mime type or file extension, case-insensitively`(
        mime: String,
        fileName: String,
        expected: MediaFormat,
    ) = runTest {
        val uri = mockUri(fileName)
        coEvery { libraryRepository.findByPath(any()) } returns null
        every { contentResolver.getType(uri) } returns mime.ifEmpty { null }

        val result = useCase(uri)

        assertEquals(expected, result?.format)
    }

    @Test
    fun `returns null for an unrecognized mime type and extension`() = runTest {
        val uri = mockUri("archive.zip")
        coEvery { libraryRepository.findByPath(any()) } returns null
        every { contentResolver.getType(uri) } returns null

        val result = useCase(uri)

        assertNull(result)
    }

    @Test
    fun `builds a transient item with title derived from the filename, not persisted to a vault`() = runTest {
        val uri = mockUri("My Great Book.epub")
        coEvery { libraryRepository.findByPath(any()) } returns null
        every { contentResolver.getType(uri) } returns null

        val result = useCase(uri)

        assertEquals(-1L, result?.id, "sentinel: not a persisted item")
        assertEquals(0L, result?.vaultFolderId, "sentinel: external intent, not a vault scan")
        assertEquals("My Great Book", result?.title)
        assertEquals("Unknown", result?.author)
        assertEquals(uri.toString(), result?.filePath)
    }

    @Test
    fun `falls back to the raw filename as title when there is no extension to strip`() = runTest {
        val uri = mockUri("noextension")
        coEvery { libraryRepository.findByPath(any()) } returns null
        every { contentResolver.getType(uri) } returns "application/epub+zip"

        val result = useCase(uri)

        assertEquals("noextension", result?.title)
    }
}
