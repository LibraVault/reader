package xyz.libravault.core.storage.usecase

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import xyz.libravault.core.domain.model.LibraryItem
import xyz.libravault.core.domain.model.MediaFormat
import xyz.libravault.core.domain.repository.LibraryRepository
import javax.inject.Inject

/**
 * Resolves an external file URI (from ACTION_VIEW intent) to a [LibraryItem].
 *
 * Flow:
 *  1. Check if the URI already exists in the library (matched by filePath).
 *  2. If found, return it directly — progress will be restored normally.
 *  3. If not found, create a transient [LibraryItem] with the URI as filePath
 *     so the reader/player can open it immediately. The item uses vaultFolderId=0
 *     to indicate it came from an external intent rather than a vault scan.
 *     It is NOT persisted to Room — we don't add files to the library without
 *     the user explicitly granting vault folder access.
 *
 * Format detection is done from the URI's MIME type or file extension.
 *
 * Lives in core:storage (not core:domain) because it needs [Context] for
 * ContentResolver MIME-type lookup — core:domain is intentionally Android-free
 * to remain Kotlin Multiplatform compatible.
 */
class OpenFileUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val libraryRepository: LibraryRepository,
) {
    suspend operator fun invoke(uri: Uri): LibraryItem? {
        // Check if already in library
        val existing = libraryRepository.search(uri.toString())
            .firstOrNull { it.filePath == uri.toString() }
        if (existing != null) return existing

        // Detect format
        val mimeType = context.contentResolver.getType(uri) ?: ""
        val fileName = uri.lastPathSegment ?: ""
        val format   = detectFormat(mimeType, fileName) ?: return null

        // Build a transient item — not saved to Room
        return LibraryItem(
            id            = -1L,        // sentinel: not a persisted item
            vaultFolderId = 0L,         // sentinel: external intent
            filePath      = uri.toString(),
            title         = fileName.substringBeforeLast('.').ifBlank { fileName },
            author        = "Unknown",
            format        = format,
        )
    }

    private fun detectFormat(mime: String, name: String): MediaFormat? {
        val ext = name.substringAfterLast('.').lowercase()
        return when {
            mime == "application/epub+zip"              || ext == "epub" -> MediaFormat.EPUB
            mime == "application/pdf"                   || ext == "pdf"  -> MediaFormat.PDF
            mime == "audio/mpeg"                        || ext == "mp3"  -> MediaFormat.MP3
            mime == "audio/x-m4b"                       || ext == "m4b"  -> MediaFormat.M4B
            mime == "audio/ogg"  || mime == "audio/vorbis" || ext == "ogg"  -> MediaFormat.OGG
            mime == "audio/flac" || mime == "audio/x-flac" || ext == "flac" -> MediaFormat.FLAC
            mime == "audio/opus"                        || ext == "opus" -> MediaFormat.OPUS
            mime == "audio/aac"  || mime == "audio/x-aac"  || ext == "aac"  -> MediaFormat.AAC
            else -> null
        }
    }
}
