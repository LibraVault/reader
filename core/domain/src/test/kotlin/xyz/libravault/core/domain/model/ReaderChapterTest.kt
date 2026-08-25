package xyz.libravault.core.domain.model

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class ReaderChapterTest {

    @Test
    fun `title and index are stored as given`() {
        val chapter = ReaderChapter(title = "Chapter One", index = 0) { "text" }

        assertEquals("Chapter One", chapter.title)
        assertEquals(0, chapter.index)
    }

    @Test
    fun `textProvider is invoked lazily and returns its result`() = runTest {
        var invoked = false
        val chapter = ReaderChapter(title = "Chapter One", index = 0) {
            invoked = true
            "resolved chapter text"
        }

        assertEquals(false, invoked, "textProvider must not run before it's called")
        val text = chapter.textProvider()

        assertEquals(true, invoked)
        assertEquals("resolved chapter text", text)
    }

    @Test
    fun `chapters with different titles are not equal`() {
        val provider: suspend () -> String = { "same text" }
        val first = ReaderChapter(title = "Chapter One", index = 0, textProvider = provider)
        val second = ReaderChapter(title = "Chapter Two", index = 0, textProvider = provider)

        assertNotEquals(first, second)
    }

    @Test
    fun `chapters built from the same textProvider reference and fields are equal`() {
        val provider: suspend () -> String = { "same text" }
        val first = ReaderChapter(title = "Chapter One", index = 0, textProvider = provider)
        val second = ReaderChapter(title = "Chapter One", index = 0, textProvider = provider)

        assertEquals(first, second)
    }
}
