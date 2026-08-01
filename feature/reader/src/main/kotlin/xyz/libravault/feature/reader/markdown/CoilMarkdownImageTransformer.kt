package xyz.libravault.feature.reader.markdown

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.documentfile.provider.DocumentFile
import coil.compose.rememberAsyncImagePainter
import com.mikepenz.markdown.model.ImageData
import com.mikepenz.markdown.model.ImageTransformer
import xyz.libravault.core.storage.resolveMarkdownAssetPath

/**
 * Resolves a Markdown image reference (`![](./img.png)`) against the file's own SAF
 * directory and loads it via Coil — the same image-loading library already used
 * elsewhere in the app (e.g. library covers), so no new dependency is needed.
 *
 * [assetParentDirectory] is null when there's no vault association to resolve
 * relative paths from (e.g. an external-intent-opened file) or resolution failed at
 * load time — every image reference then falls back to [ImageTransformer]'s own
 * default (no image shown) rather than crashing.
 *
 * Deliberately never resolves http(s) URLs (see [resolveMarkdownAssetPath]) —
 * LibraVault is offline-first and doesn't request the `INTERNET` permission.
 */
class CoilMarkdownImageTransformer(
    private val assetParentDirectory: DocumentFile?,
) : ImageTransformer {

    @Composable
    override fun transform(link: String): ImageData? {
        val parent = assetParentDirectory ?: return null
        val resolvedUri = remember(parent, link) { resolveMarkdownAssetPath(parent, link) } ?: return null
        return ImageData(painter = rememberAsyncImagePainter(model = resolvedUri))
    }
}
