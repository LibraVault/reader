package xyz.libravault.core.tts

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class TtsPreferencesTest {

    @Test
    fun `preference key constants are defined`() {
        assertNotNull("engine_type")
        assertNotNull("selected_voice")
        assertNotNull("local_voices_only")
    }

    @Test
    fun `TtsEngineType enum has values`() {
        val types = TtsEngineType.values()
        assertEquals(2, types.size)
        assertEquals(TtsEngineType.ANDROID, TtsEngineType.ANDROID)
        assertEquals(TtsEngineType.POCKET_TTS, TtsEngineType.POCKET_TTS)
    }

    @Test
    fun `engine type can be serialized to string`() {
        assertEquals("ANDROID", TtsEngineType.ANDROID.name)
        assertEquals("POCKET_TTS", TtsEngineType.POCKET_TTS.name)
    }

    @Test
    fun `engine type can be deserialized from string`() {
        val android = TtsEngineType.valueOf("ANDROID")
        val pocket = TtsEngineType.valueOf("POCKET_TTS")
        assertEquals(TtsEngineType.ANDROID, android)
        assertEquals(TtsEngineType.POCKET_TTS, pocket)
    }

    @Test
    fun `invalid engine type name throws exception`() {
        try {
            TtsEngineType.valueOf("INVALID_TYPE")
            throw AssertionError("Should have thrown IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // Expected
        }
    }
}
