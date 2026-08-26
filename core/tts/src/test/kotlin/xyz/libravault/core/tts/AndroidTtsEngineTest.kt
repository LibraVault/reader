package xyz.libravault.core.tts

import android.os.Looper
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AndroidTtsEngineTest {

    private fun split(text: String) = AndroidTtsEngine.splitIntoUtterances(text)
    private fun items(segments: List<NarrationSegment>) = AndroidTtsEngine.buildPlaybackItems(segments)

    // ── Audio focus (#137 mutual exclusion via lockscreen/notification) ────
    //
    // pause()/stop()/shutdown() are the only lifecycle methods reachable without a
    // live android.speech.tts.TextToSpeech instance - speak()/resume() both bail out
    // via `tts ?: return` before doing anything, and constructing a real TextToSpeech
    // needs a real Android device/emulator (core:tts does not set
    // testOptions.unitTests.isReturnDefaultValues, unlike feature:player). See
    // PocketTtsEngineTest for the full request/abandon cycle, which doesn't have this
    // native-construction boundary.
    //
    // AndroidTtsEngine's own init{} eagerly builds `Handler(Looper.getMainLooper())`,
    // so even reaching pause()/stop()/shutdown() needs Looper.getMainLooper() mocked -
    // it's real Android framework code, unmocked by default on the JVM.

    @BeforeEach
    fun mockMainLooper() {
        mockkStatic(Looper::class)
        every { Looper.getMainLooper() } returns mockk(relaxed = true)
    }

    @AfterEach
    fun unmockMainLooper() {
        unmockkStatic(Looper::class)
    }

    @Test
    fun `pause abandons audio focus`() {
        val audioFocusManager = mockk<TtsAudioFocusManager>(relaxed = true)
        val engine = AndroidTtsEngine(mockk(relaxed = true), audioFocusManager)

        engine.pause()

        verify { audioFocusManager.abandonFocus() }
    }

    @Test
    fun `stop abandons audio focus`() {
        val audioFocusManager = mockk<TtsAudioFocusManager>(relaxed = true)
        val engine = AndroidTtsEngine(mockk(relaxed = true), audioFocusManager)

        engine.stop()

        verify { audioFocusManager.abandonFocus() }
    }

    @Test
    fun `stop emits stopEvent`() = kotlinx.coroutines.test.runTest {
        val audioFocusManager = mockk<TtsAudioFocusManager>(relaxed = true)
        val engine = AndroidTtsEngine(mockk(relaxed = true), audioFocusManager)

        // CoroutineStart.UNDISPATCHED runs this coroutine inline up to its first
        // suspension point (registering the collection on stopEvent) before this
        // line returns, so stop()'s tryEmit below is guaranteed to have a live
        // subscriber - a SharedFlow with no replay drops emissions nobody is
        // collecting for yet.
        val stopEventDeferred = async(start = CoroutineStart.UNDISPATCHED) { engine.stopEvent.first() }

        engine.stop()

        withTimeout(1_000) { stopEventDeferred.await() }
    }

    @Test
    fun `shutdown abandons audio focus`() {
        val audioFocusManager = mockk<TtsAudioFocusManager>(relaxed = true)
        val engine = AndroidTtsEngine(mockk(relaxed = true), audioFocusManager)

        engine.shutdown()

        verify { audioFocusManager.abandonFocus() }
    }

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

    // ── Voice validation (hardening) ───────────────────────────────────────────

    @Test
    fun `TtsVoiceInfo includes requiresNetwork flag`() {
        val voiceLocal = TtsVoiceInfo(
            id = "en-us",
            displayName = "English US",
            locale = "en-US",
            requiresNetwork = false,
        )
        assertFalse(voiceLocal.requiresNetwork)

        val voiceRemote = TtsVoiceInfo(
            id = "en-gb-cloud",
            displayName = "English GB (Cloud)",
            locale = "en-GB",
            requiresNetwork = true,
        )
        assertTrue(voiceRemote.requiresNetwork)
    }

    @Test
    fun `TtsVoiceInfo defaults requiresNetwork to false`() {
        val voice = TtsVoiceInfo(
            id = "test",
            displayName = "Test Voice",
            locale = "en-US",
        )
        assertFalse(voice.requiresNetwork)
    }

    // ── Segment-aware playback queue (#499 v2a Phase C, #636) ───────────────────
    //
    // buildPlaybackItems is the one piece of the segment-rendering path this file can
    // reach — AndroidTtsEngineTest can't construct a real android.speech.tts.TextToSpeech
    // (see the top-of-file note), so speak(segments)'s own queueing can't be exercised
    // here at all; this only verifies the pure translation from segments to the queue
    // this class hands TextToSpeech.

    @Test
    fun `a segment with no pause becomes a single Speech item`() {
        val result = items(listOf(NarrationSegment("Hello.")))
        assertEquals(listOf(PlaybackItem.Speech("Hello.")), result)
    }

    @Test
    fun `a pause hint becomes a Silence item ahead of the segment's Speech item`() {
        val result = items(
            listOf(NarrationSegment("Below.", pauseBefore = NarrationSegment.PauseHint.SCENE_BREAK)),
        )
        assertEquals(2, result.size)
        assertTrue(result[0] is PlaybackItem.Silence)
        assertEquals(PlaybackItem.Speech("Below."), result[1])
    }

    @Test
    fun `Sentence, Paragraph, and SceneBreak pauses produce increasingly long silences`() {
        val sentence = items(listOf(NarrationSegment("x", pauseBefore = NarrationSegment.PauseHint.SENTENCE)))
        val paragraph = items(listOf(NarrationSegment("x", pauseBefore = NarrationSegment.PauseHint.PARAGRAPH)))
        val sceneBreak = items(listOf(NarrationSegment("x", pauseBefore = NarrationSegment.PauseHint.SCENE_BREAK)))

        val sentenceMs = (sentence[0] as PlaybackItem.Silence).durationMs
        val paragraphMs = (paragraph[0] as PlaybackItem.Silence).durationMs
        val sceneBreakMs = (sceneBreak[0] as PlaybackItem.Silence).durationMs

        assertTrue(sentenceMs < paragraphMs, "expected sentence ($sentenceMs) < paragraph ($paragraphMs)")
        assertTrue(paragraphMs < sceneBreakMs, "expected paragraph ($paragraphMs) < sceneBreak ($sceneBreakMs)")
    }

    @Test
    fun `a blank segment contributes no Speech item but keeps its pause`() {
        val result = items(listOf(NarrationSegment("  ", pauseBefore = NarrationSegment.PauseHint.PARAGRAPH)))
        assertEquals(listOf(PlaybackItem.Silence(500L)), result)
    }

    @Test
    fun `a long segment still splits into multiple Speech items with only one leading pause`() {
        val longText = ("This is a sentence. ").repeat(500) // ~10 000 chars, well over MAX_UTTERANCE_CHARS
        val result = items(listOf(NarrationSegment(longText, pauseBefore = NarrationSegment.PauseHint.PARAGRAPH)))

        assertTrue(result.first() is PlaybackItem.Silence)
        val speechItems = result.drop(1)
        assertTrue(speechItems.size > 1, "expected the long segment to split into multiple Speech items")
        assertTrue(speechItems.all { it is PlaybackItem.Speech })
    }

    @Test
    fun `multiple segments concatenate their items in order`() {
        val result = items(
            listOf(
                NarrationSegment("First."),
                NarrationSegment("Second.", pauseBefore = NarrationSegment.PauseHint.SENTENCE),
            ),
        )
        assertEquals(
            listOf(
                PlaybackItem.Speech("First."),
                PlaybackItem.Silence(150L),
                PlaybackItem.Speech("Second."),
            ),
            result,
        )
    }
}
