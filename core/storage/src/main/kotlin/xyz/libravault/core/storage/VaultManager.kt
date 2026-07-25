package xyz.libravault.core.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.libravault.core.storage.model.MediaFormat
import xyz.libravault.core.storage.model.ScannedFile
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages persistent access to user-selected Vault folders via SAF.
 *
 * No broad storage permissions are ever requested — the user explicitly selects
 * each folder via ACTION_OPEN_DOCUMENT_TREE, and we persist the URI grant.
 */
@Singleton
class VaultManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * Call after the user picks a folder via ACTION_OPEN_DOCUMENT_TREE.
     * Persists read permission so it survives app restarts.
     */
    fun persistPermission(uri: Uri) {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
    }

    /**
     * Release persisted permission when the user removes a vault.
     */
    fun releasePermission(uri: Uri) {
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    /**
     * Returns all currently persisted (and still valid) vault URIs.
     */
    fun persistedVaultUris(): List<Uri> =
        context.contentResolver.persistedUriPermissions
            .filter { it.isReadPermission }
            .map { it.uri }

    /**
     * Recursively scans a document tree URI and returns all supported media files.
     * Runs on IO dispatcher — safe to call from a coroutine.
     * @param maxDepth Maximum directory depth to traverse (default 5).
     */
    suspend fun scanFolder(treeUri: Uri, maxDepth: Int = 5): List<ScannedFile> = withContext(Dispatchers.IO) {
        val results = mutableListOf<ScannedFile>()
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        traverseDirectory(treeUri, rootDocId, results, remainingDepth = maxDepth)
        results
    }

    private fun traverseDirectory(
        treeUri: Uri,
        parentDocId: String,
        results: MutableList<ScannedFile>,
        remainingDepth: Int,
    ) {
        if (remainingDepth <= 0) return

        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)

        context.contentResolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
            ),
            null, null, null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val docId   = cursor.getString(0)
                val name    = cursor.getString(1)
                val mime    = cursor.getString(2)
                val size    = cursor.getLong(3)

                when {
                    mime == DocumentsContract.Document.MIME_TYPE_DIR ->
                        traverseDirectory(treeUri, docId, results, remainingDepth = remainingDepth - 1)

                    else -> {
                        val format = MediaFormat.fromMimeOrName(mime, name) ?: return@use
                        val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                        results += ScannedFile(
                            uri = fileUri,
                            displayName = name,
                            mimeType = mime,
                            format = format,
                            sizeBytes = size,
                        )
                    }
                }
            }
        }
    }
}
