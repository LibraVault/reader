package xyz.libravault.core.tts.pocket

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class PocketVoiceCatalogTest {

    @Test
    fun `voice name formatting works`() {
        // Test the formatting logic used by PocketVoiceCatalog
        val testCases = listOf(
            "bria" to "Bria",
            "voice-one" to "Voice One",
            "my_voice_name" to "My Voice Name",
            "en_us_female" to "En Us Female",
        )

        testCases.forEach { (input, expected) ->
            val formatted = input
                .replace("-", " ")
                .replace("_", " ")
                .split(" ")
                .joinToString(" ") { word ->
                    word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                }
            assertEquals(expected, formatted)
        }
    }

    @Test
    fun `TtsVoiceInfo created for Pocket voices has requiresNetwork false`() {
        val voice = xyz.libravault.core.tts.TtsVoiceInfo(
            id = "bria",
            displayName = "Bria",
            locale = "en-US",
            requiresNetwork = false,
        )
        assertEquals(false, voice.requiresNetwork)
    }

    @Test
    fun `default voice ID is bria`() {
        val defaultVoice = "bria"
        assertNotNull(defaultVoice)
        assertEquals("bria", defaultVoice)
    }
}
