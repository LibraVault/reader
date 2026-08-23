package xyz.libravault.core.tts

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import javax.inject.Provider

class TtsEngineFactoryTest {

    private class FakeTtsEngine : TtsEngine {
        override val state: StateFlow<TtsState> = MutableStateFlow(TtsState())
        override val completionEvent: SharedFlow<Unit> = MutableSharedFlow()
        override val stopEvent: SharedFlow<Unit> = MutableSharedFlow()
        override fun initialize() {}
        override fun speak(text: String) {}
        override fun pause() {}
        override fun resume() {}
        override fun stop() {}
        override fun setVoice(voiceId: String) {}
        override fun setSpeechRate(rate: Float) {}
        override fun shutdown() {}
    }

    @Test
    fun `TtsEngineType has three values including CLOUD`() {
        val types = TtsEngineType.values()
        assertEquals(3, types.size)
        assertTrue(types.contains(TtsEngineType.ANDROID))
        assertTrue(types.contains(TtsEngineType.POCKET_TTS))
        assertTrue(types.contains(TtsEngineType.CLOUD))
    }

    @Test
    fun `create resolves the engine bound to the requested type`() {
        val androidEngine = FakeTtsEngine()
        val cloudEngine = FakeTtsEngine()
        val factory = TtsEngineFactory(
            mapOf(
                TtsEngineType.ANDROID to Provider { androidEngine },
                TtsEngineType.CLOUD to Provider { cloudEngine },
            ),
        )

        assertSame(androidEngine, factory.create(TtsEngineType.ANDROID))
        assertSame(cloudEngine, factory.create(TtsEngineType.CLOUD))
    }

    @Test
    fun `create fails loudly rather than returning null for an unbound type`() {
        val factory = TtsEngineFactory(mapOf(TtsEngineType.ANDROID to Provider { FakeTtsEngine() }))

        try {
            factory.create(TtsEngineType.CLOUD)
            throw AssertionError("Expected an exception for an unbound engine type")
        } catch (e: IllegalStateException) {
            // Expected — e.g. core:cloudtts's Hilt module failed to install,
            // or a fdroid build somehow reached CLOUD with no binding.
        }
    }
}
