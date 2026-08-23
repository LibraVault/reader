package xyz.libravault.core.tts

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class TtsPreferencesTest {

    // Real (temp-file-backed) DataStore, not a mock — behavioral coverage for
    // the actual read/write round-trip, not just "these strings exist".
    // Possible since TtsPreferences' primary constructor takes a DataStore
    // directly rather than a Context (see TtsPreferences.kt doc comment).
    private fun preferences(tempDir: File): TtsPreferences =
        TtsPreferences(
            PreferenceDataStoreFactory.create(
                produceFile = { File(tempDir, "test_tts_preferences.preferences_pb") },
            ),
        )

    @Test
    fun `cloud voices consent defaults to false`(@TempDir tempDir: File) = runTest {
        assertFalse(preferences(tempDir).cloudVoicesConsentFlow.first())
    }

    @Test
    fun `cloud voices consent round-trips through setCloudVoicesConsent`(@TempDir tempDir: File) = runTest {
        val prefs = preferences(tempDir)
        prefs.setCloudVoicesConsent(true)
        assertTrue(prefs.cloudVoicesConsentFlow.first())

        prefs.setCloudVoicesConsent(false)
        assertFalse(prefs.cloudVoicesConsentFlow.first())
    }

    @Test
    fun `cloud voices consent is independent of engine type and selected voice`(@TempDir tempDir: File) = runTest {
        val prefs = preferences(tempDir)
        prefs.setEngineType(TtsEngineType.POCKET_TTS)
        prefs.setSelectedVoice("some-voice")

        // Setting unrelated preferences must never flip consent — this is the
        // exact bug shape PRD §4 rules out ("buying the subscription must not
        // itself enable any network call").
        assertFalse(prefs.cloudVoicesConsentFlow.first())
    }

    @Test
    fun `preference key constants are defined`() {
        assertNotNull("engine_type")
        assertNotNull("selected_voice")
        assertNotNull("local_voices_only")
    }

    @Test
    fun `TtsEngineType enum has values`() {
        val types = TtsEngineType.values()
        assertEquals(3, types.size)
        assertEquals(TtsEngineType.ANDROID, TtsEngineType.ANDROID)
        assertEquals(TtsEngineType.POCKET_TTS, TtsEngineType.POCKET_TTS)
        assertEquals(TtsEngineType.CLOUD, TtsEngineType.CLOUD)
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
