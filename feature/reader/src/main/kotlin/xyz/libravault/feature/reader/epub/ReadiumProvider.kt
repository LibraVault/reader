package xyz.libravault.feature.reader.epub

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.streamer.Readium
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Application-scoped wrapper around the Readium entry-point class.
 *
 * [Readium] is expensive to initialise — it registers content handlers,
 * sets up asset retrievers, etc. — so a single instance is shared for
 * the lifetime of the app.
 *
 * This class is intentionally thin. It does not manage publication state;
 * callers (via [EpubReaderViewModel]) are responsible for closing the
 * returned [Publication] when they are done with it.
 *
 * Readium API used: 3.0.0-beta.2
 *   - [Readium.assetRetriever] → retrieve asset from URL
 *   - [Readium.streamer]       → open asset to a Publication
 */
@Singleton
class ReadiumProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val readium = Readium(context)

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
        val asset = readium.assetRetriever.retrieve(url)
            .getOrElse { error ->
                return Result.failure(
                    Exception("Failed to retrieve asset at $uri: ${error.message}")
                )
            }

        // Step 2 — open publication (parses OPF, builds Publication object)
        // allowUserInteraction = false because we never show DRM dialogs in v1
        return readium.streamer.open(asset, allowUserInteraction = false)
            .fold(
                onSuccess = { Result.success(it) },
                onFailure = { error ->
                    Result.failure(Exception("Failed to open publication: ${error.message}"))
                },
            )
    }
}
