package xyz.libravault.core.tts.pocket

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import xyz.libravault.core.tts.TtsVoiceInfo

class PocketTtsEngineTest {

    @Test
    fun `PocketTtsEngine class exists and can be instantiated`() {
        val engine = PocketTtsEngine()
        assertNotNull(engine)
    }

    @Test
    fun `PocketTtsEngine implements TtsEngine interface`() {
        val engine = PocketTtsEngine()
        assertNotNull(engine.state)
        assertNotNull(engine.completionEvent)
    }

    @Test
    fun `PocketTtsEngine has empty initial voices`() {
        val engine = PocketTtsEngine()
        assertEquals(0, engine.state.value.availableVoices.size)
    }

    @Test
    fun `TtsVoiceInfo is constructible for pocket voices`() {
        val voice = TtsVoiceInfo(
            id = "pocket-voice-1",
            displayName = "Pocket Voice 1",
            locale = "en-US",
            requiresNetwork = false,
        )
        assertEquals("pocket-voice-1", voice.id)
        assertEquals(false, voice.requiresNetwork)
    }
}
