package xyz.libravault.core.tts.pocket

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PocketVoiceCatalogTest {

    @Test
    fun `availableVoices is empty before the model has finished downloading`() {
        val modelManager = mockk<PocketModelManager> {
            every { isModelValid() } returns false
        }
        val catalog = PocketVoiceCatalog(modelManager)

        assertTrue(catalog.availableVoices().isEmpty())
    }

    @Test
    fun `availableVoices exposes the single bundled voice once the model is ready`() {
        val modelManager = mockk<PocketModelManager> {
            every { isModelValid() } returns true
        }
        val catalog = PocketVoiceCatalog(modelManager)

        val voices = catalog.availableVoices()

        assertEquals(1, voices.size)
        assertEquals(PocketVoiceCatalog.DEFAULT_VOICE_ID, voices[0].id)
        assertFalse(voices[0].requiresNetwork)
    }

    @Test
    fun `default voice id is en_US-ljspeech-medium`() {
        assertEquals("en_US-ljspeech-medium", PocketVoiceCatalog.DEFAULT_VOICE_ID)
    }

    @Test
    fun `default speaker id is 0 (single-speaker model)`() {
        assertEquals(0, PocketVoiceCatalog.DEFAULT_SPEAKER_ID)
    }
}
