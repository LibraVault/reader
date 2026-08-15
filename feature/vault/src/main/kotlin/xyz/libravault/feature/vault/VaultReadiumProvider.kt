package xyz.libravault.feature.vault

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.shared.util.mediatype.MediaType
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.epub.EpubParser
import xyz.libravault.core.vaultcontent.VaultReadiumResource
import xyz.libravault.core.vaultcrypto.VaultFileReader
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Vault-native counterpart to `feature:reader`'s `ReadiumProvider` — opens an
 * EPUB [Publication] from a [VaultFileReader] instead of a plaintext `Uri`,
 * via `AssetRetriever.retrieve(Resource, MediaType)` (the entry point
 * `core:vaultcontent`'s [VaultReadiumResource] was built for — see its doc
 * comment — which skips URL/`ResourceFactory` resolution entirely, so no
 * synthetic URI scheme is needed).
 *
 * A deliberately separate, small class here, not an addition to
 * `feature:reader`'s `ReadiumProvider` — this PR's scope is a parallel
 * vault-native reading path with zero changes to existing reader/player
 * files (see the PR description for why).
 */
@Singleton
class VaultReadiumProvider @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val assetRetriever = AssetRetriever(
        contentResolver = context.contentResolver,
        httpClient = DefaultHttpClient(),
    )
    private val publicationOpener = PublicationOpener(publicationParser = EpubParser())

    /** [fileIdHex] is only used to build [VaultReadiumResource]'s synthetic
     * `vault://` source URL (Readium requires one); it never leaves the
     * device or touches anything persisted. */
    suspend fun open(reader: VaultFileReader, fileIdHex: String): Result<Publication> {
        val resource = VaultReadiumResource(reader, fileIdHex)
        val asset = assetRetriever.retrieve(resource, MediaType.EPUB).getOrNull()
            ?: return Result.failure(Exception("Failed to retrieve vault EPUB asset for $fileIdHex"))

        return publicationOpener.open(asset = asset, allowUserInteraction = false).fold(
            onSuccess = { Result.success(it) },
            onFailure = { Result.failure(Exception("Failed to open vault EPUB publication: ${it.message}")) },
        )
    }
}
