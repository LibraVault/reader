package xyz.libravault.core.storage

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

class MetadataExtractorOpfTest {

    private val mockCoverArtCache = mockk<CoverArtCache>(relaxed = true)

    private lateinit var extractor: MetadataExtractor

    @BeforeEach
    fun setUp() {
        val mockContext = mockk<android.content.Context>(relaxed = true)
        extractor = MetadataExtractor(mockContext, mockCoverArtCache, mockk(relaxed = true))
    }

    // ── findOpfPath ──────────────────────────────────────────────────────────

    @Test
    fun `findOpfPath extracts full-path from container xml`() {
        val containerXml = """<?xml version="1.0"?>
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles>
                <rootfile full-path="content.opf" media-type="application/oebps-package+xml"/>
              </rootfiles>
            </container>
        """.trimIndent()

        val result = extractor.findOpfPath(ByteArrayInputStream(containerXml.toByteArray()))

        assertEquals("content.opf", result)
    }

    @Test
    fun `findOpfPath returns null when no rootfile tag`() {
        val containerXml = """<?xml version="1.0"?>
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles>
              </rootfiles>
            </container>
        """.trimIndent()

        val result = extractor.findOpfPath(ByteArrayInputStream(containerXml.toByteArray()))

        assertNull(result)
    }

    @Test
    fun `findOpfPath handles nested paths`() {
        val containerXml = """<?xml version="1.0"?>
            <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles>
                <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
              </rootfiles>
            </container>
        """.trimIndent()

        val result = extractor.findOpfPath(ByteArrayInputStream(containerXml.toByteArray()))

        assertEquals("OEBPS/content.opf", result)
    }

    // ── parseOpf ─────────────────────────────────────────────────────────────

    @Test
    fun `parseOpf extracts title and author`() = runTest {
        val opfXml = """<?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:title>Test Book</dc:title>
                <dc:creator>Test Author</dc:creator>
              </metadata>
            </package>
        """.trimIndent()

        val result = extractor.parseOpf(ByteArrayInputStream(opfXml.toByteArray()), emptyMap(), "cache-key")

        assertEquals("Test Book", result.title)
        assertEquals("Test Author", result.author)
    }

    @Test
    fun `parseOpf extracts calibre series metadata`() = runTest {
        val opfXml = """<?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:calibre="http://calibre.kovidgoyal.net/2009/metadata">
                <dc:title>Book 3</dc:title>
                <dc:creator>Author</dc:creator>
                <meta name="calibre:series" content="My Series"/>
                <meta name="calibre:series_index" content="3.5"/>
              </metadata>
            </package>
        """.trimIndent()

        val result = extractor.parseOpf(ByteArrayInputStream(opfXml.toByteArray()), emptyMap(), "cache-key")

        assertEquals("My Series", result.series)
        assertEquals(3.5f, result.seriesIndex)
    }

    @Test
    fun `parseOpf falls back to Unknown when title or author missing`() = runTest {
        val opfXml = """<?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
              </metadata>
            </package>
        """.trimIndent()

        val result = extractor.parseOpf(ByteArrayInputStream(opfXml.toByteArray()), emptyMap(), "cache-key")

        assertEquals("Unknown", result.title)
        assertEquals("Unknown", result.author)
    }

    @Test
    fun `parseOpf handles EPUB3 cover meta tag`() = runTest {
        val opfXml = """<?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:title>Book</dc:title>
                <dc:creator>Author</dc:creator>
                <meta name="cover" content="cover-image"/>
              </metadata>
              <manifest>
                <item id="cover-image" href="images/cover.jpg" media-type="image/jpeg"/>
              </manifest>
            </package>
        """.trimIndent()

        val coverBytes = byteArrayOf(0xFF, 0xD8) // JPEG header
        val zipEntries = mapOf("OEBPS/images/cover.jpg" to coverBytes)
        coEvery { mockCoverArtCache.save("cache-key", coverBytes) } returns "/path/to/cached/cover.jpg"

        val result = extractor.parseOpf(ByteArrayInputStream(opfXml.toByteArray()), zipEntries, "cache-key")

        assertEquals("/path/to/cached/cover.jpg", result.coverArtPath)
    }

    @Test
    fun `parseOpf XXE prevention - DOCTYPE is safely ignored`() = runTest {
        // This XXE-probe payload should be safely parsed without attempting entity resolution
        val maliciousXml = """<?xml version="1.0"?>
            <!DOCTYPE foo [
              <!ENTITY xxe SYSTEM "file:///etc/passwd">
            ]>
            <package xmlns="http://www.idpf.org/2007/opf">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:title>Safe Book</dc:title>
                <dc:creator>Author</dc:creator>
              </metadata>
            </package>
        """.trimIndent()

        // Should complete without attempting to read /etc/passwd and without hanging
        val result = extractor.parseOpf(ByteArrayInputStream(maliciousXml.toByteArray()), emptyMap(), "cache-key")

        assertEquals("Safe Book", result.title)
        assertEquals("Author", result.author)
    }
}
