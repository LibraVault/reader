package xyz.libravault.core.tts

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TtsEngineFactoryTest {

    @Test
    fun `TtsEngineType enum values exist`() {
        val types = TtsEngineType.values()
        assertEquals(2, types.size)
        assertTrue(types.contains(TtsEngineType.ANDROID))
        assertTrue(types.contains(TtsEngineType.POCKET_TTS))
    }

    @Test
    fun `TtsEngineType can be stringified`() {
        assertEquals("ANDROID", TtsEngineType.ANDROID.toString())
        assertEquals("POCKET_TTS", TtsEngineType.POCKET_TTS.toString())
    }
}
