package xyz.libravault.core.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import xyz.libravault.core.logger.LibravaultLogger
import xyz.libravault.core.storage.model.MediaFormat
import xyz.libravault.core.storage.model.ScannedFile
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

data class ExtractedMetadata(
    val title: String,
    val author: String,
    val narrator: String?      = null,
    val series: String?        = null,
    val seriesIndex: Float?    = null,
    val durationMs: Long?      = null,
    val pageCount: Int?        = null,
    val coverArtPath: String?  = null,
)

@Singleton
class MetadataExtractor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val coverArtCache: CoverArtCache,
    private val logger: LibravaultLogger,
) {
    companion object {
        private const val TAG = "MetadataExtractor"
        private const val UNKNOWN = "Unknown"
        private const val DC_NAMESPACE = "http://purl.org/dc/elements/1.1/"

        // A hostile EPUB can declare a zip entry (cover image, OPF, or
        // container.xml) whose true uncompressed size is far larger than
        // ZipEntry.size claims — that field can be -1 (unknown, streamed
        // entries) or simply lie, so it can't be trusted as a pre-check.
        // This caps the bytes actually *read* from the entry, independent of
        // whatever it claims. 25 MB is generous for any legitimate embedded
        // cover (even an uncompressed multi-thousand-pixel JPEG/PNG) or a
        // hand-authored OPF/container.xml, while still bounding the memory a
        // single hostile entry can force the scanner to buffer.
        internal const val MAX_ZIP_ENTRY_BYTES = 25 * 1024 * 1024
    }

    suspend fun extract(file: ScannedFile): ExtractedMetadata =
        withContext(Dispatchers.IO) {
            runCatching {
                when (file.format) {
                    MediaFormat.MP3,
                    MediaFormat.M4B,
                    MediaFormat.OGG,
                    MediaFormat.FLAC,
                    MediaFormat.OPUS,
                    MediaFormat.AAC  -> extractAudio(file)
                    MediaFormat.EPUB     -> extractEpub(file)
                    MediaFormat.PDF      -> extractPdf(file)
                    MediaFormat.MARKDOWN -> extractMarkdown(file)
                }
            }.getOrElse { e ->
                logger.e(TAG, "Failed to extract metadata from ${file.displayName}", e)
                fallback(file)
            }
        }

    /**
     * Like [extract], but for the Encrypted Vault import path (`feature:vault`),
     * which must never let a plaintext copy of a cover image touch
     * [CoverArtCache]'s disk cache — that cache is deliberately unencrypted
     * app-private storage, a reasonable tradeoff for the normal library
     * (source files already sit in the clear in their Folder) but a real leak
     * for content a user is specifically encrypting.
     *
     * Returns the same fields [extract] would, except
     * [ExtractedMetadata.coverArtPath] is always null; the cover's raw,
     * still-undecoded/unresized bytes (if any) come back as the second
     * element instead. Callers must run them through
     * [CoverArtCache.downsampleToJpeg] — never [CoverArtCache.save] — before
     * handing the result to `VaultStore.importFile`/`setCoverArt`.
     *
     * A deliberately separate set of `xxxRaw` methods below, not a
     * `persistCover` flag threaded through the existing `extractAudio`/
     * `extractEpub`/`extractPdf` — those stay exactly as they were (verified
     * by [MetadataExtractorTest]'s existing coverage), and this path reuses
     * only the pieces that don't touch the cache ([parseOpfMetadataOnly],
     * [findEpubCoverBytes], [renderPdfFirstPageJpeg]).
     */
    suspend fun extractWithoutCaching(file: ScannedFile): Pair<ExtractedMetadata, ByteArray?> =
        withContext(Dispatchers.IO) {
            runCatching {
                when (file.format) {
                    MediaFormat.MP3,
                    MediaFormat.M4B,
                    MediaFormat.OGG,
                    MediaFormat.FLAC,
                    MediaFormat.OPUS,
                    MediaFormat.AAC  -> extractAudioRaw(file)
                    MediaFormat.EPUB     -> extractEpubRaw(file)
                    MediaFormat.PDF      -> extractPdfRaw(file)
                    MediaFormat.MARKDOWN -> extractMarkdown(file) to null
                }
            }.getOrElse { e ->
                logger.e(TAG, "Failed to extract metadata from ${file.displayName}", e)
                fallback(file) to null
            }
        }

    // ── Audio (MediaMetadataRetriever) ───────────────────────────────────────

    private suspend fun extractAudio(file: ScannedFile): ExtractedMetadata {
        val retriever = MediaMetadataRetriever()
        return try {
            // Whole-extract timeout — the previous guard only wrapped
            // setDataSource, but extract() / embeddedPicture can also hang
            // on malformed frames. 10 s is generous for a 3-hour audiobook;
            // a healthy 5 MB file extracts in <100 ms.
            withTimeout(10_000L) {
                retriever.setDataSource(context, file.uri)

                val title    = retriever.extract(MediaMetadataRetriever.METADATA_KEY_TITLE)
                    ?: file.displayName.substringBeforeLast('.')
                val author   = retriever.extract(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    ?: retriever.extract(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                    ?: UNKNOWN
                val duration = retriever.extract(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()

                // Embedded album art
                val coverPath = retriever.embeddedPicture?.let { bytes ->
                    val cacheKey = file.uri.toString()
                    coverArtCache.getCachedPath(cacheKey)
                        ?: coverArtCache.save(cacheKey, bytes)
                }

                ExtractedMetadata(
                    title        = title,
                    author       = author,
                    durationMs   = duration,
                    coverArtPath = coverPath,
                )
            }
        } catch (_: TimeoutCancellationException) {
            logger.w(TAG, "Metadata extraction timed out for ${file.displayName}")
            fallback(file)
        } finally {
            retriever.release()
        }
    }

    /** [extractAudio], minus the [CoverArtCache.save] call — see [extractWithoutCaching]. */
    private suspend fun extractAudioRaw(file: ScannedFile): Pair<ExtractedMetadata, ByteArray?> {
        val retriever = MediaMetadataRetriever()
        return try {
            withTimeout(10_000L) {
                retriever.setDataSource(context, file.uri)

                val title    = retriever.extract(MediaMetadataRetriever.METADATA_KEY_TITLE)
                    ?: file.displayName.substringBeforeLast('.')
                val author   = retriever.extract(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    ?: retriever.extract(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                    ?: UNKNOWN
                val duration = retriever.extract(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()

                ExtractedMetadata(title = title, author = author, durationMs = duration) to retriever.embeddedPicture
            }
        } catch (_: TimeoutCancellationException) {
            logger.w(TAG, "Metadata extraction timed out for ${file.displayName}")
            fallback(file) to null
        } finally {
            retriever.release()
        }
    }

    // ── EPUB (ZipInputStream + OPF parsing) ──────────────────────────────────

    private suspend fun extractEpub(file: ScannedFile): ExtractedMetadata {
        val inputStream = context.contentResolver.openInputStream(file.uri)
            ?: return fallback(file)

        return inputStream.use { stream ->
            ZipInputStream(stream).use { zip ->
                val entries = mutableMapOf<String, ByteArray>()

                // Read all relevant entries into memory (OPF files are small,
                // but a "cover" match can be a hostile, oversized image —
                // readBounded rejects anything past MAX_ZIP_ENTRY_BYTES
                // rather than trusting entry.size, which can be -1 or lie).
                var entry = zip.nextEntry
                while (entry != null) {
                    val name = entry.name
                    if (name.endsWith(".opf") ||
                        name == "META-INF/container.xml" ||
                        name.contains("cover", ignoreCase = true) &&
                        (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png"))
                    ) {
                        val bytes = zip.readBounded(MAX_ZIP_ENTRY_BYTES)
                        if (bytes != null) {
                            entries[name] = bytes
                        } else {
                            logger.w(TAG, "extractEpub: skipping oversized zip entry '$name' in ${file.displayName} (> $MAX_ZIP_ENTRY_BYTES bytes)")
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }

                // Find OPF path from container.xml
                val containerXml = entries["META-INF/container.xml"]
                val opfPath = containerXml?.let { findOpfPath(it.inputStream()) }

                // Parse OPF
                val opfBytes = opfPath?.let { entries[it] }
                    ?: entries.entries.firstOrNull { it.key.endsWith(".opf") }?.value
                    ?: return@use fallback(file)

                parseOpf(opfBytes.inputStream(), entries, file.uri.toString())
            }
        }
    }

    /** [extractEpub], minus the [CoverArtCache.save] call — see [extractWithoutCaching].
     * The zip-reading loop is duplicated from [extractEpub] rather than shared
     * (it's small and mechanical); the actual OPF-parsing logic is not
     * duplicated — both paths go through [parseOpfMetadataOnly]. */
    private suspend fun extractEpubRaw(file: ScannedFile): Pair<ExtractedMetadata, ByteArray?> {
        val inputStream = context.contentResolver.openInputStream(file.uri)
            ?: return fallback(file) to null

        return inputStream.use { stream ->
            ZipInputStream(stream).use { zip ->
                val entries = mutableMapOf<String, ByteArray>()

                var entry = zip.nextEntry
                while (entry != null) {
                    val name = entry.name
                    if (name.endsWith(".opf") ||
                        name == "META-INF/container.xml" ||
                        name.contains("cover", ignoreCase = true) &&
                        (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png"))
                    ) {
                        val bytes = zip.readBounded(MAX_ZIP_ENTRY_BYTES)
                        if (bytes != null) {
                            entries[name] = bytes
                        } else {
                            logger.w(TAG, "extractEpubRaw: skipping oversized zip entry '$name' in ${file.displayName} (> $MAX_ZIP_ENTRY_BYTES bytes)")
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }

                val containerXml = entries["META-INF/container.xml"]
                val opfPath = containerXml?.let { findOpfPath(it.inputStream()) }
                val opfBytes = opfPath?.let { entries[it] }
                    ?: entries.entries.firstOrNull { it.key.endsWith(".opf") }?.value
                    ?: return@use (fallback(file) to null)

                val meta = parseOpfMetadataOnly(opfBytes.inputStream())
                val coverBytes = findEpubCoverBytes(meta.coverImageId, meta.manifestItems, entries)
                ExtractedMetadata(
                    title       = meta.title ?: UNKNOWN,
                    author      = meta.author ?: UNKNOWN,
                    series      = meta.series,
                    seriesIndex = meta.seriesIndex,
                ) to coverBytes
            }
        }
    }

    /**
     * Reads the remainder of the current zip entry into a byte array,
     * aborting and returning `null` the moment more than [limit] bytes have
     * actually been read — never trusts [java.util.zip.ZipEntry.size], which
     * can be `-1` (unknown, common for streamed/data-descriptor entries) or
     * simply misreport the true uncompressed size. This is what stands
     * between a hostile entry and an unbounded [ZipInputStream.readBytes]
     * buffering the whole thing into memory.
     */
    private fun ZipInputStream.readBounded(limit: Int): ByteArray? {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        var total = 0
        while (true) {
            val n = read(chunk)
            if (n < 0) break
            total += n
            if (total > limit) return null
            buffer.write(chunk, 0, n)
        }
        return buffer.toByteArray()
    }

    internal fun findOpfPath(stream: InputStream): String? {
        val parser = newParser(stream)
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG &&
                parser.name == "rootfile"
            ) {
                return parser.getAttributeValue(null, "full-path")
            }
            parser.next()
        }
        return null
    }

    /** Everything [parseOpf] parses out of the OPF XML itself, before any
     * cover-art lookup/caching decision — shared by [parseOpf] (persists to
     * [CoverArtCache]) and [extractEpubRaw] (never does). */
    private data class OpfMetadata(
        val title: String?,
        val author: String?,
        val series: String?,
        val seriesIndex: Float?,
        val coverImageId: String?,
        val manifestItems: Map<String, String>, // id → href
    )

    private fun parseOpfMetadataOnly(stream: InputStream): OpfMetadata {
        val parser = newParser(stream)

        var title: String?       = null
        var author: String?      = null
        var series: String?      = null
        var seriesIndex: Float?  = null
        var coverImageId: String? = null
        val manifestItems        = mutableMapOf<String, String>() // id → href

        var inMetadata = false

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "metadata" -> inMetadata = true
                    // The parser is namespace-aware, so a <dc:title> element's
                    // name here is just "title" — the "dc" prefix is stripped
                    // off into parser.prefix/parser.namespace separately, not
                    // part of .name. Match on the resolved namespace URI
                    // rather than the prefix (a document is free to bind any
                    // prefix to Dublin Core, "dc" is only a convention).
                    "title"   -> if (inMetadata && parser.namespace == DC_NAMESPACE) title  = parser.nextText()
                    "creator" -> if (inMetadata && parser.namespace == DC_NAMESPACE) author = parser.nextText()

                    // Calibre series metadata
                    "meta" -> if (inMetadata) {
                        when (parser.getAttributeValue(null, "name")) {
                            "calibre:series"       -> series      = parser.getAttributeValue(null, "content")
                            "calibre:series_index" -> seriesIndex = parser.getAttributeValue(null, "content")?.toFloatOrNull()
                        }
                        // EPUB 3 cover
                        if (parser.getAttributeValue(null, "name") == "cover") {
                            coverImageId = parser.getAttributeValue(null, "content")
                        }
                    }

                    // Manifest items for cover lookup
                    "item" -> {
                        val id   = parser.getAttributeValue(null, "id")
                        val href = parser.getAttributeValue(null, "href")
                        if (id != null && href != null) manifestItems[id] = href
                    }
                }
                XmlPullParser.END_TAG -> if (parser.name == "metadata") inMetadata = false
            }
            parser.next()
        }

        return OpfMetadata(title, author, series, seriesIndex, coverImageId, manifestItems)
    }

    /** Resolves the OPF's declared cover (EPUB3 `<meta name="cover">`, falling
     * back to any manifest item whose id/href contains "cover") to its raw
     * bytes from the already-read [zipEntries] map — shared by [parseOpf] and
     * [extractEpubRaw]. */
    private fun findEpubCoverBytes(
        coverImageId: String?,
        manifestItems: Map<String, String>,
        zipEntries: Map<String, ByteArray>,
    ): ByteArray? {
        val coverHref = coverImageId?.let { manifestItems[it] }
            ?: manifestItems.entries
                .firstOrNull { it.key.contains("cover", ignoreCase = true) }
                ?.value

        return coverHref?.let { href ->
            zipEntries.entries.firstOrNull { it.key.endsWith(href) }?.value
        }
    }

    internal suspend fun parseOpf(
        stream: InputStream,
        zipEntries: Map<String, ByteArray>,
        cacheKey: String,
    ): ExtractedMetadata {
        val meta = parseOpfMetadataOnly(stream)
        val coverBytes = findEpubCoverBytes(meta.coverImageId, meta.manifestItems, zipEntries)
        val coverPath = coverBytes?.let { bytes ->
            coverArtCache.getCachedPath(cacheKey) ?: coverArtCache.save(cacheKey, bytes)
        }

        return ExtractedMetadata(
            title        = meta.title ?: UNKNOWN,
            author       = meta.author ?: UNKNOWN,
            series       = meta.series,
            seriesIndex  = meta.seriesIndex,
            coverArtPath = coverPath,
        )
    }

    // ── PDF (PdfRenderer) ────────────────────────────────────────────────────

    private suspend fun extractPdf(file: ScannedFile): ExtractedMetadata {
        val pfd = context.contentResolver.openFileDescriptor(file.uri, "r")
            ?: return fallback(file)

        return pfd.use { descriptor ->
            val renderer  = PdfRenderer(descriptor)
            val pageCount = renderer.pageCount
            val title     = file.displayName.substringBeforeLast('.')
            val cacheKey  = file.uri.toString()

            val coverPath = if (pageCount > 0) {
                coverArtCache.getCachedPath(cacheKey)
                    ?: renderPdfFirstPageJpeg(renderer)?.let { coverArtCache.save(cacheKey, it) }
            } else null

            renderer.close()

            ExtractedMetadata(
                title        = title,
                author       = UNKNOWN,
                pageCount    = pageCount,
                coverArtPath = coverPath,
            )
        }
    }

    /** [extractPdf], minus the [CoverArtCache.save] call — see [extractWithoutCaching]. */
    private suspend fun extractPdfRaw(file: ScannedFile): Pair<ExtractedMetadata, ByteArray?> {
        val pfd = context.contentResolver.openFileDescriptor(file.uri, "r")
            ?: return fallback(file) to null

        return pfd.use { descriptor ->
            val renderer  = PdfRenderer(descriptor)
            val pageCount = renderer.pageCount
            val title     = file.displayName.substringBeforeLast('.')
            val coverBytes = if (pageCount > 0) renderPdfFirstPageJpeg(renderer) else null
            renderer.close()

            ExtractedMetadata(title = title, author = UNKNOWN, pageCount = pageCount) to coverBytes
        }
    }

    /** Renders page 0 to a fixed-width JPEG thumbnail — the hardened,
     * tested behavior [extractPdf]/[extractPdfRaw] share. Assumes
     * `renderer.pageCount > 0`; callers check that first. */
    private fun renderPdfFirstPageJpeg(renderer: PdfRenderer): ByteArray {
        val page   = renderer.openPage(0)
        // Render at a fixed thumbnail width; maintain aspect ratio
        val width  = 256
        val height = (page.height.toFloat() / page.width * width).toInt()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        // Fill white background (PDF pages are transparent by default)
        Canvas(bitmap).drawColor(Color.WHITE)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        page.close()

        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            bitmap.recycle()
            out.toByteArray()
        }
    }

    // ── Markdown (H1 / YAML front-matter title) ──────────────────────────────

    private suspend fun extractMarkdown(file: ScannedFile): ExtractedMetadata {
        val text = context.contentResolver.openInputStream(file.uri)
            ?.use { it.bufferedReader().readText() }
            ?: return fallback(file)

        return ExtractedMetadata(
            title  = extractMarkdownTitle(text) ?: file.displayName.substringBeforeLast('.'),
            author = UNKNOWN,
        )
    }

    /**
     * Title precedence per the Markdown viewer PRD: first `# H1`, else YAML
     * front matter's `title:` field, else null (caller falls back to filename).
     */
    internal fun extractMarkdownTitle(text: String): String? {
        val (frontMatter, body) = splitFrontMatter(text)

        val h1 = body.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            ?.takeIf { it.startsWith("# ") }
            ?.removePrefix("#")
            ?.trim()
        if (!h1.isNullOrBlank()) return h1

        return frontMatter?.let {
            Regex("""^title:\s*["']?(.+?)["']?\s*$""", RegexOption.MULTILINE)
                .find(it)?.groupValues?.get(1)?.takeIf(String::isNotBlank)
        }
    }

    /** Splits a leading `---\n...\n---` YAML block from the rest of the document. */
    private fun splitFrontMatter(text: String): Pair<String?, String> {
        if (!text.startsWith("---")) return null to text
        val end = text.indexOf("\n---", startIndex = 3)
        if (end == -1) return null to text
        val frontMatter = text.substring(3, end)
        val bodyStart = text.indexOf('\n', end + 4).let { if (it == -1) text.length else it + 1 }
        return frontMatter to text.substring(minOf(bodyStart, text.length))
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun fallback(file: ScannedFile) = ExtractedMetadata(
        title  = file.displayName.substringBeforeLast('.'),
        author = UNKNOWN,
    )

    private fun MediaMetadataRetriever.extract(key: Int): String? =
        extractMetadata(key)?.takeIf { it.isNotBlank() }

    private fun newParser(stream: InputStream): XmlPullParser =
        XmlPullParserFactory.newInstance().apply {
            isNamespaceAware = true
            // Disable DOCTYPE / DTD processing entirely — an attacker can
            // ship an EPUB whose container.xml or OPF declares a DTD that
            // resolves to an external file (XXE) or a billion-laughs
            // expansion (CVE-style DoS). Set before any parse input is set.
            try {
                setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
            } catch (_: Exception) {
                // Some bundled factories (e.g. older AOSP variants) don't
                // support toggling this feature — feature absence on those
                // platforms already implies "no DOCTYPE expansion".
            }
        }
            .newPullParser()
            .also { it.setInput(stream, null) }
            .also { it.next() }
}
