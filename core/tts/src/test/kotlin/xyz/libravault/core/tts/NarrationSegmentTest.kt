package xyz.libravault.core.tts

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

// joinToNarrationText() — the fallback flattening path TtsEngine.speak(segments)'s
// default implementation uses for every engine with no segment-aware rendering
// (Pocket, Cloud). Pure JVM logic, no Android dependency (#636 QA follow-up).

class NarrationSegmentTest {

    @Test
    fun `empty list produces empty string`() {
        assertEquals("", emptyList<NarrationSegment>().joinToNarrationText())
    }

    @Test
    fun `a single NONE segment is returned as-is`() {
        val result = listOf(NarrationSegment("Hello.")).joinToNarrationText()
        assertEquals("Hello.", result)
    }

    @Test
    fun `a leading SENTENCE pause is not prefixed on the first segment`() {
        val result = listOf(
            NarrationSegment("First.", pauseBefore = NarrationSegment.PauseHint.SENTENCE),
        ).joinToNarrationText()
        assertEquals("First.", result)
    }

    @Test
    fun `a leading PARAGRAPH pause is not prefixed on the first segment`() {
        val result = listOf(
            NarrationSegment("First.", pauseBefore = NarrationSegment.PauseHint.PARAGRAPH),
        ).joinToNarrationText()
        assertEquals("First.", result)
    }

    @Test
    fun `a leading SCENE_BREAK pause is not prefixed on the first segment`() {
        val result = listOf(
            NarrationSegment("First.", pauseBefore = NarrationSegment.PauseHint.SCENE_BREAK),
        ).joinToNarrationText()
        assertEquals("First.", result)
    }

    @Test
    fun `NONE appends directly with no separator`() {
        val result = listOf(
            NarrationSegment("First"),
            NarrationSegment(" second."),
        ).joinToNarrationText()
        assertEquals("First second.", result)
    }

    @Test
    fun `SENTENCE inserts a period-space separator between segments`() {
        val result = listOf(
            NarrationSegment("First."),
            NarrationSegment("Second.", pauseBefore = NarrationSegment.PauseHint.SENTENCE),
        ).joinToNarrationText()
        assertEquals("First.. Second.", result)
    }

    @Test
    fun `PARAGRAPH inserts a blank line between segments`() {
        val result = listOf(
            NarrationSegment("First paragraph."),
            NarrationSegment("Second paragraph.", pauseBefore = NarrationSegment.PauseHint.PARAGRAPH),
        ).joinToNarrationText()
        assertEquals("First paragraph.\n\nSecond paragraph.", result)
    }

    @Test
    fun `SCENE_BREAK inserts a blank line between segments, same as PARAGRAPH`() {
        val result = listOf(
            NarrationSegment("Before the break."),
            NarrationSegment("After the break.", pauseBefore = NarrationSegment.PauseHint.SCENE_BREAK),
        ).joinToNarrationText()
        assertEquals("Before the break.\n\nAfter the break.", result)
    }

    @Test
    fun `mixed pause hints across several segments join in order`() {
        val result = listOf(
            NarrationSegment("Heading", kind = NarrationSegment.Kind.HEADING),
            NarrationSegment("Body text.", pauseBefore = NarrationSegment.PauseHint.PARAGRAPH),
            NarrationSegment("List item one.", pauseBefore = NarrationSegment.PauseHint.SENTENCE),
            NarrationSegment("List item two.", pauseBefore = NarrationSegment.PauseHint.SENTENCE),
            NarrationSegment("New scene.", pauseBefore = NarrationSegment.PauseHint.SCENE_BREAK),
        ).joinToNarrationText()

        assertEquals(
            "Heading\n\nBody text.. List item one.. List item two.\n\nNew scene.",
            result,
        )
    }
}
