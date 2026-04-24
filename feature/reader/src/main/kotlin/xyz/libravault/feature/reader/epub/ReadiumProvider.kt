package xyz.libravault.feature.reader.epub

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.epub.EpubParser
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Application-scoped wrapper around Readium's publication opening pipeline.
 *
 * In Readium 3.0.0-beta.2 the monolithic [Readium] facade was removed.
 * Publication opening is now explicit:
 *   - [AssetRetriever] → fetch and sniff the asset format from a URL
 *   - [EpubParser]     → parse the EPUB OPF into a [Publication.Builder]
 *   - [PublicationOpener] → orchestrate parsing and content protection
 *
 * This class is intentionally thin. It does not manage publication state;
 * callers (via [EpubReaderViewModel]) are responsible for closing the
 * returned [Publication] when they are done with it.
 */
@Singleton
class ReadiumProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * [AssetRetriever] needs a [ContentResolver] (for content:// / file:// URIs)
     * and an [org.readium.r2.shared.util.http.HttpClient] (for http:// URIs).
     * We pass a basic [DefaultHttpClient] for completeness even though v1 is
     * offline-only; it keeps the retriever future-proof.
     */
    private val assetRetriever = AssetRetriever(
        contentResolver = context.contentResolver,
        httpClient = DefaultHttpClient()
    )

    /**
     * [PublicationOpener] constructor takes the parser that will be used for
     * every open() call. We use [EpubParser] since Libravault v1 is EPUB-only.
     */
    private val publicationOpener = PublicationOpener(
        publicationParser = EpubParser()
    )

    /**
     * Opens an EPUB from a SAF content URI or file URI.
     *
     * Returns [Result.success] with the opened [Publication], or
     * [Result.failure] with a descriptive exception on any error.
     *
     * Callers **must** call [Publication.close] when the publication is
     * no longer needed to free native resources held by the parser.
     */
    suspend fun open(uri: Uri): Result<Publication> {
        // AbsoluteUrl is Readium's typed URL wrapper. Content URIs (content://)
        // and file URIs (file://) are both valid absolute URLs.
        val url = AbsoluteUrl(uri.toString())
            ?: return Result.failure(
                IllegalArgumentException("Cannot form an absolute URL from: $uri")
            )

        // Step 1 — retrieve asset (detects format, wraps into Readium's Asset type)
        val asset = assetRetriever.retrieve(url)
            .getOrNull()
            ?: return Result.failure(
                Exception("Failed to retrieve asset at $uri")
            )

        // Step 2 — open publication (parses OPF, builds Publication object)
        // allowUserInteraction = false because we never show DRM dialogs in v1
        return publicationOpener.open(
            asset = asset,
            allowUserInteraction = false
        ).fold(
            onSuccess = { Result.success(it) },
            onFailure = {
                Result.failure(
                    Exception("Failed to open publication: ${it.message}")
                )
            }
        )
    }
}
