package xyz.libravault.feature.library

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import xyz.libravault.core.domain.model.LibraryItem
import xyz.libravault.core.domain.model.MediaFormat

/**
 * Unit tests for [interleaveByRecency], the merge behind the Library
 * screen's "Continue" shelf.
 *
 * Regression coverage for a bug where the merge's `when` branch always
 * evaluated to `true` while both lists had remaining items, so the function
 * silently degenerated into "all of reading, then all of listening" instead
 * of alternating between the two sources.
 */
class InterleaveByRecencyTest {

    private fun item(id: Long, format: MediaFormat = MediaFormat.EPUB) = LibraryItem(
        id = id,
        vaultFolderId = 1L,
        filePath = "/book$id",
        title = "Book $id",
        author = "Author",
        format = format,
    )

    @Test
    fun `alternates reading and listening starting with reading`() {
        val reading = listOf(item(1), item(2), item(3))
        val listening = listOf(item(10), item(20), item(30))

        val result = interleaveByRecency(reading, listening)

        assertEquals(listOf(1L, 10L, 2L, 20L, 3L, 30L), result.map { it.id })
    }

    @Test
    fun `does not degenerate into draining reading before listening`() {
        // Regression guard: the buggy version returned [1, 2, 3, 10, 20, 30]
        // (all of reading first) whenever both lists had items remaining.
        val reading = listOf(item(1), item(2), item(3))
        val listening = listOf(item(10), item(20), item(30))

        val result = interleaveByRecency(reading, listening).map { it.id }

        assertEquals(false, result == listOf(1L, 2L, 3L, 10L, 20L, 30L))
    }

    @Test
    fun `drains the remaining list once the other is exhausted`() {
        val reading = listOf(item(1))
        val listening = listOf(item(10), item(20), item(30))

        val result = interleaveByRecency(reading, listening)

        assertEquals(listOf(1L, 10L, 20L, 30L), result.map { it.id })
    }

    @Test
    fun `empty reading list falls back to listening order`() {
        val listening = listOf(item(10), item(20))

        val result = interleaveByRecency(emptyList(), listening)

        assertEquals(listOf(10L, 20L), result.map { it.id })
    }

    @Test
    fun `empty listening list falls back to reading order`() {
        val reading = listOf(item(1), item(2))

        val result = interleaveByRecency(reading, emptyList())

        assertEquals(listOf(1L, 2L), result.map { it.id })
    }

    @Test
    fun `both lists empty returns empty`() {
        assertEquals(emptyList<LibraryItem>(), interleaveByRecency(emptyList(), emptyList()))
    }

    @Test
    fun `duplicate item present in both lists is de-duplicated to its reading-list slot`() {
        val shared = item(99)
        val reading = listOf(shared, item(2))
        val listening = listOf(shared, item(20))

        val result = interleaveByRecency(reading, listening)

        assertEquals(listOf(99L, 2L, 20L), result.map { it.id })
    }
}
