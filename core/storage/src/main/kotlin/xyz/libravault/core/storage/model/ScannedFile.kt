package xyz.libravault.core.storage.model

import android.net.Uri

data class ScannedFile(
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
    val format: MediaFormat,
    val sizeBytes: Long,
)
