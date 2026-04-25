package xyz.libravault.core.storage

import android.content.Context
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
                    MediaFormat.EPUB -> extractEpub(file)
                    MediaFormat.PDF  -> extractPdf(file)
                }
            }.getOrElse { e ->
                logger.e(TAG, "Failed to extract metadata from ${file.displayName}", e)
                fallback(file)
            }
        }

    // ── Audio (MediaMetadataRetriever) ───────────────────────────────────────

    private suspend fun extractAudio(file: ScannedFile): ExtractedMetadata {
        val retriever = MediaMetadataRetriever()
        return try {
            // Guard against native hangs on corrupted/unusual files — 10s hard cap
            withTimeout(10_000L) {
                retriever.setDataSource(context, file.uri)
            }

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
                title       = title,
                author      = author,
                durationMs  = duration,
                coverArtPath = coverPath,
            )
        } catch (_: TimeoutCancellationException) {
            logger.w(TAG, "Metadata extraction timed out for ${file.displayName}")
            fallback(file)
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

                // Read all relevant entries into memory (OPF files are small)
                var entry = zip.nextEntry
                while (entry != null) {
                    val name = entry.name
                    if (name.endsWith(".opf") ||
                        name == "META-INF/container.xml" ||
                        name.contains("cover", ignoreCase = true) &&
                        (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png"))
                    ) {
                        entries[name] = zip.readBytes()
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

    private fun findOpfPath(stream: InputStream): String? {
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

    private suspend fun parseOpf(
        stream: InputStream,
        zipEntries: Map<String, ByteArray>,
        cacheKey: String,
    ): ExtractedMetadata {
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
                    "dc:title"   -> if (inMetadata) title  = parser.nextText()
                    "dc:creator" -> if (inMetadata) author = parser.nextText()

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

        // Try to extract cover
        val coverPath = run {
            val coverHref = coverImageId?.let { manifestItems[it] }
                ?: manifestItems.entries
                    .firstOrNull { it.key.contains("cover", ignoreCase = true) }
                    ?.value

            val coverBytes = coverHref?.let { href ->
                zipEntries.entries.firstOrNull { it.key.endsWith(href) }?.value
            }

            coverBytes?.let { bytes ->
                coverArtCache.getCachedPath(cacheKey)
                    ?: coverArtCache.save(cacheKey, bytes)
            }
        }

        return ExtractedMetadata(
            title        = title ?: UNKNOWN,
            author       = author ?: UNKNOWN,
            series       = series,
            seriesIndex  = seriesIndex,
            coverArtPath = coverPath,
        )
    }

    // ── PDF (PdfRenderer) ────────────────────────────────────────────────────

    private fun extractPdf(file: ScannedFile): ExtractedMetadata {
        val pfd = context.contentResolver.openFileDescriptor(file.uri, "r")
            ?: return fallback(file)

        return pfd.use { descriptor ->
            val renderer = PdfRenderer(descriptor)
            val pageCount = renderer.pageCount
            renderer.close()

            // PdfRenderer doesn't expose title metadata — use filename
            val title = file.displayName.substringBeforeLast('.')

            ExtractedMetadata(
                title     = title,
                author    = UNKNOWN,
                pageCount = pageCount,
            )
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun fallback(file: ScannedFile) = ExtractedMetadata(
        title  = file.displayName.substringBeforeLast('.'),
        author = UNKNOWN,
    )

    private fun MediaMetadataRetriever.extract(key: Int): String? =
        extractMetadata(key)?.takeIf { it.isNotBlank() }

    private fun newParser(stream: InputStream): XmlPullParser =
        XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }
            .newPullParser()
            .also { it.setInput(stream, null) }
            .also { it.next() }
}
