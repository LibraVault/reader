package xyz.libravault.core.storage

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves relative image references inside a Markdown file (`![](./img.png)`,
 * `![](images/pic.jpg)`, `![](../shared/x.png)`) against the file's own location on
 * a SAF vault tree, so they can be loaded via their real `content://` [Uri].
 *
 * SAF document IDs are opaque per the DocumentsProvider contract — encoding a
 * filesystem-style relative path into them isn't guaranteed to work across every
 * provider, even though it happens to for Android's built-in external-storage
 * provider (what every real LibraVault vault uses in practice, since vaults are
 * local folders, not cloud-synced ones). Rather than lean on that undocumented
 * behavior, this walks the actual [DocumentFile] tree from the vault root to find
 * the Markdown file's parent directory, then descends the relative path's segments
 * from there — correct for any provider, at the cost of one directory listing per
 * path segment (library folders are typically shallow, so this is cheap; the parent
 * lookup itself is O(vault size) and should be done once per file open, not once
 * per image reference — see [findParentDirectory] vs [resolveMarkdownAssetPath]).
 */
@Singleton
class MarkdownAssetResolver @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /**
     * Finds the [DocumentFile] directory directly containing [markdownFileUri], by
     * walking the tree from [vaultTreeUri]. Call once per file open — the result
     * should be reused for every relative image reference in that file via
     * [resolveMarkdownAssetPath], not recomputed per image.
     */
    fun findParentDirectory(vaultTreeUri: Uri, markdownFileUri: Uri): DocumentFile? {
        val root = DocumentFile.fromTreeUri(context, vaultTreeUri) ?: return null
        return findParentDirectory(root, markdownFileUri)
    }

    private fun findParentDirectory(directory: DocumentFile, targetFileUri: Uri): DocumentFile? {
        for (child in directory.listFiles()) {
            if (child.uri == targetFileUri) return directory
            if (child.isDirectory) {
                findParentDirectory(child, targetFileUri)?.let { return it }
            }
        }
        return null
    }
}

/**
 * Resolves [relativePath] against [parentDirectory] (from
 * [MarkdownAssetResolver.findParentDirectory]). Returns null for absolute http(s)
 * URLs — LibraVault is offline-first and never makes a network request on the
 * user's behalf — and for any path segment that doesn't resolve to a real
 * file/folder. A plain top-level function (no [Context]/DI needed, unlike
 * [MarkdownAssetResolver.findParentDirectory]) so it can be called directly from a
 * Composable image-loading callback without a Hilt entry point.
 */
fun resolveMarkdownAssetPath(parentDirectory: DocumentFile, relativePath: String): Uri? {
    if (relativePath.startsWith("http://", ignoreCase = true) ||
        relativePath.startsWith("https://", ignoreCase = true)
    ) {
        return null
    }

    var current = parentDirectory
    for (segment in relativePath.split('/')) {
        current = when (segment) {
            "", "." -> current
            ".." -> current.parentFile ?: return null
            else -> current.findFile(segment) ?: return null
        }
    }
    return current.uri
}
