package xyz.libravault.core.storage

import xyz.libravault.core.domain.model.LibraryItem

object StorageManager {
    suspend fun loadFileContent(item: LibraryItem): String? {
        // Phase C: iOS file reading from app sandbox via FileManager
        return null
    }

    suspend fun getFileMetadata(item: LibraryItem): Map<String, String> {
        // Phase C: Extract metadata using native iOS APIs
        return emptyMap()
    }

    suspend fun saveHighlight(itemId: Long, position: String, text: String) {
        // Phase C: Persist highlights to local database
    }

    suspend fun saveBookmark(itemId: Long, position: String, label: String?) {
        // Phase C: Persist bookmarks to local database
    }
}
