package xyz.libravault.core.storage.model

enum class MediaFormat {
    EPUB, PDF, MP3, M4B, OGG, FLAC, OPUS, AAC;
    companion object {
        fun fromMimeOrName(mime: String, name: String): MediaFormat? {
            val ext = name.substringAfterLast('.').lowercase()
            return when {
                mime == "application/epub+zip"              || ext == "epub" -> EPUB
                mime == "application/pdf"                   || ext == "pdf"  -> PDF
                mime == "audio/mpeg"                        || ext == "mp3"  -> MP3
                mime == "audio/x-m4b"                       || ext == "m4b"  -> M4B
                mime == "audio/ogg"  || mime == "audio/vorbis" || ext == "ogg"  -> OGG
                mime == "audio/flac" || mime == "audio/x-flac" || ext == "flac" -> FLAC
                mime == "audio/opus"                        || ext == "opus" -> OPUS
                mime == "audio/aac"  || mime == "audio/x-aac"  || ext == "aac"  -> AAC
                else -> null
            }
        }
    }
}
