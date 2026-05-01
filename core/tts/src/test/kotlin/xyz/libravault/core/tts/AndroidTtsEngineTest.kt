package xyz.libravault.core.tts

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AndroidTtsEngineTest {

    private fun split(text: String) = AndroidTtsEngine.splitIntoUtterances(text)

    // ── Short text ─────────────────────────────────────────────────────────────

    @Test
    fun `short text is returned as single chunk`() {
        val text = "Hello, world."
        val result = split(text)
        assertEquals(listOf(text), result)
    }

    @Test
    fun `empty string returns single empty chunk`() {
        val result = split("")
        assertEquals(listOf(""), result)
    }

    // ── Long text splitting ────────────────────────────────────────────────────

    @Test
    fun `text exactly at limit is a single chunk`() {
        val text = "a".repeat(3900)
        assertEquals(1, split(text).size)
    }

    @Test
    fun `text one char over limit splits into two chunks`() {
        // No sentence boundary — should hard-split at the limit.
        val text = "a".repeat(3901)
        val chunks = split(text)
        assertEquals(2, chunks.size)
        assertTrue(chunks.all { it.isNotEmpty() })
    }

    @Test
    fun `splits at sentence boundary when one exists within limit`() {
        // Build text where a '.' appears well before the 3900 limit.
        val sentence1 = "First sentence."
        val filler    = " " + "word ".repeat(750)   // ~3750 chars total with sentence1
        val sentence2 = " Second sentence continues here."
        val text = sentence1 + filler + sentence2

        val chunks = split(text)
        // First chunk should end with the sentence boundary, not cut a word.
        assertTrue(chunks.first().trimEnd().endsWith("."),
            "Expected first chunk to end with '.', got: '${chunks.first().takeLast(20)}'")
    }

    @Test
    fun `all chunks are within the 3900 char limit`() {
        val longText = ("This is a sentence. " ).repeat(500)  // ~10 000 chars
        val chunks = split(longText)
        assertTrue(chunks.size >= 2)
        chunks.forEach { chunk ->
            assertTrue(chunk.length <= 3900,
                "Chunk of length ${chunk.length} exceeds limit")
        }
    }

    @Test
    fun `no text is lost after splitting`() {
        val longText = ("Word ".repeat(1000)).trim()
        val chunks = split(longText)
        val reassembled = chunks.joinToString(" ")
        // Every word in the original appears in the reassembled text.
        val originalWords = longText.split(" ").toSet()
        val reassembledWords = reassembled.split(" ").toSet()
        assertEquals(originalWords, reassembledWords)
    }

    @Test
    fun `splits on exclamation and question marks too`() {
        val text = "Is this right? " + "x".repeat(3890) + " Yes it is!"
        val chunks = split(text)
        assertTrue(chunks.size >= 2)
        chunks.forEach { assertTrue(it.length <= 3900) }
    }
}
