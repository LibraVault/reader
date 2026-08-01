package xyz.libravault.core.storage

import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Covers [resolveMarkdownAssetPath] — the pure path-segment logic MarkdownAssetResolver
 * uses once it already has the Markdown file's parent [DocumentFile] (the SAF tree walk
 * itself, [MarkdownAssetResolver.findParentDirectory], needs a real Android runtime and
 * isn't covered here — see the class doc for why).
 */
class MarkdownAssetResolverTest {

    private fun documentFile(uri: Uri = mockk(relaxed = true)): DocumentFile =
        mockk(relaxed = true) { every { this@mockk.uri } returns uri }

    @Test
    fun `resolves a bare filename in the same directory`() {
        val target = documentFile()
        val parent = documentFile()
        every { parent.findFile("img.png") } returns target

        assertEquals(target.uri, resolveMarkdownAssetPath(parent, "img.png"))
    }

    @Test
    fun `resolves a leading dot-slash the same as a bare filename`() {
        val target = documentFile()
        val parent = documentFile()
        every { parent.findFile("img.png") } returns target

        assertEquals(target.uri, resolveMarkdownAssetPath(parent, "./img.png"))
    }

    @Test
    fun `resolves a nested subfolder reference`() {
        val target = documentFile()
        val imagesDir = documentFile()
        every { imagesDir.findFile("img.png") } returns target
        val parent = documentFile()
        every { parent.findFile("images") } returns imagesDir

        assertEquals(target.uri, resolveMarkdownAssetPath(parent, "images/img.png"))
    }

    @Test
    fun `resolves a parent-directory reference via dot-dot`() {
        val target = documentFile()
        val grandparent = documentFile()
        every { grandparent.findFile("shared.png") } returns target
        val parent = documentFile()
        every { parent.parentFile } returns grandparent

        assertEquals(target.uri, resolveMarkdownAssetPath(parent, "../shared.png"))
    }

    @Test
    fun `returns null when dot-dot has no parent directory`() {
        val parent = documentFile()
        every { parent.parentFile } returns null

        assertNull(resolveMarkdownAssetPath(parent, "../shared.png"))
    }

    @Test
    fun `returns null when the referenced file does not exist`() {
        val parent = documentFile()
        every { parent.findFile("missing.png") } returns null

        assertNull(resolveMarkdownAssetPath(parent, "missing.png"))
    }

    @Test
    fun `never resolves an http url`() {
        val parent = documentFile()
        assertNull(resolveMarkdownAssetPath(parent, "http://example.com/img.png"))
    }

    @Test
    fun `never resolves an https url`() {
        val parent = documentFile()
        assertNull(resolveMarkdownAssetPath(parent, "https://example.com/img.png"))
    }
}
