package xyz.libravault.core.storage

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import xyz.libravault.core.logger.LibravaultLogger
import xyz.libravault.core.storage.model.MediaFormat
import xyz.libravault.core.storage.model.ScannedFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * findOpfPath()/parseOpf() (internal, org.xmlpull.v1-based) previously had no
 * coverage — AGP's mockable android.jar stubs XmlPullParserFactory, so plain
 * JVM tests threw "not mocked" at runtime, and two prior sessions dead-ended
 * chasing a Robolectric+JUnit5 bridge (see project memory). The fix: bundling
 * kxml2 as a testImplementation gives XmlPullParserFactory.newInstance() a
 * real implementation to fall back to on plain JVM — the same underlying
 * kxml2-family parser Android itself ships, so behavior matches production.
 *
 * Writing these tests surfaced a real bug: the namespace-aware parser
 * resolves a <dc:title> element's .name to just "title" (prefix stripped
 * into .prefix/.namespace), so the old literal "dc:title"/"dc:creator"
 * checks never matched a single real, spec-compliant EPUB — title/author
 * silently came back "Unknown" for every book. Fixed to match on the
 * resolved Dublin Core namespace URI instead.
 *
 * Also covers [MetadataExtractor.extractMarkdownTitle] — the title-precedence
 * rule from the Markdown viewer PRD: first `# H1`, else YAML front matter's
 * `title:` field, else null (caller falls back to filename). Pure string
 * logic, no Android/SAF dependency, so it's tested directly against the same
 * mocked-collaborator instance as the OPF tests above.
 */
class MetadataExtractorTest {

    private val coverArtCache = mockk<CoverArtCache>()
    private val extractor = MetadataExtractor(
        context = mockk<Context>(relaxed = true),
        coverArtCache = coverArtCache,
        logger = mockk<LibravaultLogger>(relaxed = true),
    )

    // ── findOpfPath ─────────────────────────────────────────────────────────

    @Test
    fun `findOpfPath returns the rootfile full-path`() {
        val container = """<?xml version="1.0"?>
            <container>
              <rootfiles>
                <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
              </rootfiles>
            </container>""".trimIndent()

        val path = extractor.findOpfPath(container.byteInputStream())

        assertEquals("OEBPS/content.opf", path)
    }

    @Test
    fun `findOpfPath returns null when there is no rootfile element`() {
        val container = """<?xml version="1.0"?><container><rootfiles/></container>"""

        val path = extractor.findOpfPath(container.byteInputStream())

        assertNull(path)
    }

    // ── parseOpf ────────────────────────────────────────────────────────────

    @Test
    fun `parseOpf extracts title and author from a properly namespaced OPF`() = runTest {
        val opf = realisticOpf(title = "Some Book", author = "Some Author")

        val result = extractor.parseOpf(opf.byteInputStream(), emptyMap(), "key")

        assertEquals("Some Book", result.title)
        assertEquals("Some Author", result.author)
    }

    @Test
    fun `parseOpf falls back to Unknown when title or creator is absent`() = runTest {
        val opf = """<?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf">
              <metadata/>
            </package>""".trimIndent()

        val result = extractor.parseOpf(opf.byteInputStream(), emptyMap(), "key")

        assertEquals("Unknown", result.title)
        assertEquals("Unknown", result.author)
    }

    @Test
    fun `parseOpf extracts calibre series and series index`() = runTest {
        val opf = """<?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/">
              <metadata>
                <dc:title>Book Three</dc:title>
                <meta name="calibre:series" content="The Great Series"/>
                <meta name="calibre:series_index" content="3.5"/>
              </metadata>
            </package>""".trimIndent()

        val result = extractor.parseOpf(opf.byteInputStream(), emptyMap(), "key")

        assertEquals("The Great Series", result.series)
        assertEquals(3.5f, result.seriesIndex)
    }

    @Test
    fun `parseOpf resolves cover art via the EPUB3 cover meta and manifest`() = runTest {
        val opf = """<?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/">
              <metadata>
                <dc:title>Cover Book</dc:title>
                <meta name="cover" content="cover-image"/>
              </metadata>
              <manifest>
                <item id="cover-image" href="images/cover.jpg" media-type="image/jpeg"/>
              </manifest>
            </package>""".trimIndent()
        val coverBytes = byteArrayOf(1, 2, 3)
        val zipEntries = mapOf("OEBPS/images/cover.jpg" to coverBytes)
        every { coverArtCache.getCachedPath("key") } returns null
        coEvery { coverArtCache.save("key", coverBytes) } returns "/cache/cover.jpg"

        val result = extractor.parseOpf(opf.byteInputStream(), zipEntries, "key")

        assertEquals("/cache/cover.jpg", result.coverArtPath)
    }

    @Test
    fun `parseOpf reuses an already-cached cover instead of re-saving`() = runTest {
        val opf = """<?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/">
              <metadata>
                <dc:title>Cover Book</dc:title>
                <meta name="cover" content="cover-image"/>
              </metadata>
              <manifest>
                <item id="cover-image" href="images/cover.jpg" media-type="image/jpeg"/>
              </manifest>
            </package>""".trimIndent()
        val zipEntries = mapOf("OEBPS/images/cover.jpg" to byteArrayOf(1, 2, 3))
        every { coverArtCache.getCachedPath("key") } returns "/cache/existing.jpg"

        val result = extractor.parseOpf(opf.byteInputStream(), zipEntries, "key")

        assertEquals("/cache/existing.jpg", result.coverArtPath)
        coVerify(exactly = 0) { coverArtCache.save(any(), any()) }
    }

    // ── extract() end-to-end over a synthetic in-memory EPUB zip ───────────────

    @Test
    fun `extract parses a real EPUB zip end-to-end`() = runTest {
        val opf = realisticOpf(title = "Zipped Book", author = "Zipped Author")
        val zipBytes = buildEpubZip(
            "META-INF/container.xml" to """<?xml version="1.0"?>
                <container><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>""".trimIndent(),
            "OEBPS/content.opf" to opf,
        )
        val uri = mockk<Uri>(relaxed = true)
        val contentResolver = mockk<ContentResolver>()
        val context = mockk<Context> { every { this@mockk.contentResolver } returns contentResolver }
        every { contentResolver.openInputStream(uri) } returns zipBytes.inputStream()
        val extractorWithRealZip = MetadataExtractor(context, mockk(relaxed = true), mockk(relaxed = true))
        val file = ScannedFile(uri, "book.epub", "application/epub+zip", MediaFormat.EPUB, 1234L)

        val result = extractorWithRealZip.extract(file)

        assertEquals("Zipped Book", result.title)
        assertEquals("Zipped Author", result.author)
    }

    // ── extractWithoutCaching() — Encrypted Vault import path ────────────────

    @Test
    fun `extractWithoutCaching returns raw cover bytes and never touches CoverArtCache`() = runTest {
        val coverBytes = byteArrayOf(9, 9, 9)
        val opf = """<?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/">
              <metadata>
                <dc:title>Vault Book</dc:title>
                <dc:creator>Vault Author</dc:creator>
                <meta name="cover" content="cover-image"/>
              </metadata>
              <manifest>
                <item id="cover-image" href="images/cover.jpg" media-type="image/jpeg"/>
              </manifest>
            </package>""".trimIndent()
        val fullZip = buildEpubZipBinary(
            "META-INF/container.xml" to """<?xml version="1.0"?>
                <container><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>""".trimIndent().toByteArray(),
            "OEBPS/content.opf" to opf.toByteArray(),
            "OEBPS/images/cover.jpg" to coverBytes,
        )

        val uri = mockk<Uri>(relaxed = true)
        val contentResolver = mockk<ContentResolver>()
        val context = mockk<Context> { every { this@mockk.contentResolver } returns contentResolver }
        every { contentResolver.openInputStream(uri) } returns fullZip.inputStream()
        val strictCoverArtCache = mockk<CoverArtCache>() // no stubs — any call fails the test
        val extractorWithRealZip = MetadataExtractor(context, strictCoverArtCache, mockk(relaxed = true))
        val file = ScannedFile(uri, "book.epub", "application/epub+zip", MediaFormat.EPUB, 1234L)

        val (metadata, rawCover) = extractorWithRealZip.extractWithoutCaching(file)

        assertEquals("Vault Book", metadata.title)
        assertEquals("Vault Author", metadata.author)
        assertNull(metadata.coverArtPath)
        assertArrayEquals(coverBytes, rawCover)
        // mockk without any `every`/`coEvery` stub throws on first invocation —
        // the test failing with that exception (rather than a clean assertion
        // failure) IS the proof this path never called CoverArtCache at all.
    }

    @Test
    fun `extractWithoutCaching falls back cleanly when the EPUB has no OPF, still touches no cache`() = runTest {
        val zipBytes = buildEpubZip("README.txt" to "not an epub")
        val uri = mockk<Uri>(relaxed = true)
        val contentResolver = mockk<ContentResolver>()
        val context = mockk<Context> { every { this@mockk.contentResolver } returns contentResolver }
        every { contentResolver.openInputStream(uri) } returns zipBytes.inputStream()
        val strictCoverArtCache = mockk<CoverArtCache>()
        val extractor = MetadataExtractor(context, strictCoverArtCache, mockk(relaxed = true))
        val file = ScannedFile(uri, "broken.epub", "application/epub+zip", MediaFormat.EPUB, 10L)

        val (metadata, rawCover) = extractor.extractWithoutCaching(file)

        assertEquals("broken", metadata.title)
        assertNull(rawCover)
    }

    // ── extractMarkdownTitle ────────────────────────────────────────────────

    @Test
    fun `extracts title from leading H1`() {
        val text = "# My Document\n\nSome body text."
        assertEquals("My Document", extractor.extractMarkdownTitle(text))
    }

    @Test
    fun `H1 takes precedence over front matter title`() {
        val text = """
            ---
            title: Front Matter Title
            ---
            # Real Title

            Body.
        """.trimIndent()
        assertEquals("Real Title", extractor.extractMarkdownTitle(text))
    }

    @Test
    fun `falls back to front matter title when there is no H1`() {
        val text = """
            ---
            title: Front Matter Title
            ---
            Just a paragraph, no heading.
        """.trimIndent()
        assertEquals("Front Matter Title", extractor.extractMarkdownTitle(text))
    }

    @Test
    fun `front matter title tolerates quotes`() {
        val text = """
            ---
            title: "Quoted Title"
            ---
            Body.
        """.trimIndent()
        assertEquals("Quoted Title", extractor.extractMarkdownTitle(text))
    }

    @Test
    fun `returns null when there is neither H1 nor front matter title`() {
        val text = "Just a paragraph with no heading or front matter."
        assertNull(extractor.extractMarkdownTitle(text))
    }

    @Test
    fun `unterminated front matter block is not parsed as front matter`() {
        // No closing `---` found: the whole text (including the leading `---`
        // line) is treated as plain body. That leading line isn't a valid H1,
        // so this falls back to null (caller falls back to filename) rather
        // than reaching into the malformed block for a heading.
        val text = "---\ntitle: Never Closed\n\n# Heading Found In Body"
        assertNull(extractor.extractMarkdownTitle(text))
    }

    @Test
    fun `blank document returns null`() {
        assertNull(extractor.extractMarkdownTitle(""))
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private fun realisticOpf(title: String, author: String) = """<?xml version="1.0"?>
        <package xmlns="http://www.idpf.org/2007/opf" xmlns:dc="http://purl.org/dc/elements/1.1/">
          <metadata>
            <dc:title>$title</dc:title>
            <dc:creator>$author</dc:creator>
          </metadata>
        </package>""".trimIndent()

    private fun buildEpubZip(vararg entries: Pair<String, String>): ByteArray =
        buildEpubZipBinary(*entries.map { (name, content) -> name to content.toByteArray() }.toTypedArray())

    private fun buildEpubZipBinary(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
