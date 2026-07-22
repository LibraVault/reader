package xyz.libravault.core.storage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import xyz.libravault.core.storage.model.MediaFormat

class MediaFormatTest {

    @Test
    fun `detects EPUB by mime type`() =
        assertEquals(MediaFormat.EPUB, MediaFormat.fromMimeOrName("application/epub+zip", "book.epub"))

    @Test
    fun `detects EPUB by extension`() =
        assertEquals(MediaFormat.EPUB, MediaFormat.fromMimeOrName("application/octet-stream", "book.epub"))

    @Test
    fun `detects PDF by mime type`() =
        assertEquals(MediaFormat.PDF, MediaFormat.fromMimeOrName("application/pdf", "doc.pdf"))

    @Test
    fun `detects MP3`() =
        assertEquals(MediaFormat.MP3, MediaFormat.fromMimeOrName("audio/mpeg", "track.mp3"))

    @Test
    fun `detects M4B`() =
        assertEquals(MediaFormat.M4B, MediaFormat.fromMimeOrName("audio/x-m4b", "audiobook.m4b"))

    @Test
    fun `detects OGG`() =
        assertEquals(MediaFormat.OGG, MediaFormat.fromMimeOrName("audio/ogg", "track.ogg"))

    @Test
    fun `detects FLAC`() =
        assertEquals(MediaFormat.FLAC, MediaFormat.fromMimeOrName("audio/flac", "track.flac"))

    @Test
    fun `detects OPUS`() =
        assertEquals(MediaFormat.OPUS, MediaFormat.fromMimeOrName("audio/opus", "track.opus"))

    @Test
    fun `detects AAC`() =
        assertEquals(MediaFormat.AAC, MediaFormat.fromMimeOrName("audio/aac", "track.aac"))

    @Test
    fun `returns null for unsupported format`() =
        assertNull(MediaFormat.fromMimeOrName("video/mp4", "video.mp4"))

    @Test
    fun `extension takes precedence when mime is generic`() =
        assertEquals(MediaFormat.FLAC, MediaFormat.fromMimeOrName("application/octet-stream", "lossless.flac"))

    @Test
    fun `case insensitive extension matching`() =
        assertEquals(MediaFormat.EPUB, MediaFormat.fromMimeOrName("application/octet-stream", "BOOK.EPUB"))
}
