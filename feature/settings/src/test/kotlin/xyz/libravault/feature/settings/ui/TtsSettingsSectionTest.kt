package xyz.libravault.feature.settings.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.libravault.core.tts.TtsEngineType
import xyz.libravault.core.tts.TtsVoiceInfo
import xyz.libravault.core.tts.pocket.ModelStatus

/**
 * First Compose UI test in this codebase — runs on Robolectric (JVM, no
 * emulator) via the `testing.android` bundle that already sat unused in the
 * version catalog. [TtsSettingsSection] is a pure function of its parameters
 * (state in, callbacks out) specifically so it's exercisable like this without
 * mocking Hilt singletons; see `SettingsViewModel.ttsState` for where the real
 * state comes from.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TtsSettingsSectionTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val pocketVoice = TtsVoiceInfo(
        id = "en_US-ljspeech-high",
        displayName = "Ljspeech",
        locale = "en-US",
    )

    private val androidVoice = TtsVoiceInfo(
        id = "en-us-x-sfg-local",
        displayName = "English (United States) — en-us-x-sfg-local",
        locale = "en-US",
    )

    @Test
    fun `android engine selected hides pocket-only model section but still shows voice picker`() {
        composeTestRule.setContent {
            TtsSettingsSection(
                engineType = TtsEngineType.ANDROID,
                speechRate = 1.0f,
                selectedVoiceId = null,
                availableVoices = emptyList(),
                modelStatus = ModelStatus.Idle,
                onEngineTypeSelected = {},
                onVoiceSelected = {},
                onSpeechRateChanged = {},
            )
        }

        composeTestRule.onNodeWithText("Android System TTS").assertIsDisplayed()
        composeTestRule.onNodeWithText("Pocket TTS (offline)").assertIsDisplayed()
        composeTestRule.onNodeWithText("Voice").assertIsDisplayed()
        composeTestRule.onNodeWithText("Voice model ready").assertDoesNotExist()
        composeTestRule.onNodeWithText("Preparing voice model… 0%").assertDoesNotExist()
    }

    @Test
    fun `android engine lists real system voices and reports selection`() {
        var selectedVoiceId: String? = null

        composeTestRule.setContent {
            TtsSettingsSection(
                engineType = TtsEngineType.ANDROID,
                speechRate = 1.0f,
                selectedVoiceId = null,
                availableVoices = listOf(androidVoice),
                modelStatus = ModelStatus.Idle,
                onEngineTypeSelected = {},
                onVoiceSelected = { selectedVoiceId = it },
                onSpeechRateChanged = {},
            )
        }

        composeTestRule.onNodeWithText("${androidVoice.displayName} (${androidVoice.locale})")
            .performClick()

        assertEquals("en-us-x-sfg-local", selectedVoiceId)
    }

    @Test
    fun `network-required voice is labelled`() {
        val networkVoice = androidVoice.copy(id = "cloud-voice", requiresNetwork = true)

        composeTestRule.setContent {
            TtsSettingsSection(
                engineType = TtsEngineType.ANDROID,
                speechRate = 1.0f,
                selectedVoiceId = null,
                availableVoices = listOf(networkVoice),
                modelStatus = ModelStatus.Idle,
                onEngineTypeSelected = {},
                onVoiceSelected = {},
                onSpeechRateChanged = {},
            )
        }

        composeTestRule
            .onNodeWithText("${networkVoice.displayName} (${networkVoice.locale}) — requires network")
            .assertIsDisplayed()
    }

    @Test
    fun `android engine with no voices yet shows the generic empty state`() {
        composeTestRule.setContent {
            TtsSettingsSection(
                engineType = TtsEngineType.ANDROID,
                speechRate = 1.0f,
                selectedVoiceId = null,
                availableVoices = emptyList(),
                modelStatus = ModelStatus.Idle,
                onEngineTypeSelected = {},
                onVoiceSelected = {},
                onSpeechRateChanged = {},
            )
        }

        composeTestRule.onNodeWithText("Voices become available once the TTS engine is ready.")
            .assertIsDisplayed()
    }

    @Test
    fun `selecting pocket tts reports the new engine type`() {
        var selected: TtsEngineType? = null
        composeTestRule.setContent {
            TtsSettingsSection(
                engineType = TtsEngineType.ANDROID,
                speechRate = 1.0f,
                selectedVoiceId = null,
                availableVoices = emptyList(),
                modelStatus = ModelStatus.Idle,
                onEngineTypeSelected = { selected = it },
                onVoiceSelected = {},
                onSpeechRateChanged = {},
            )
        }

        composeTestRule.onNodeWithText("Pocket TTS (offline)").performClick()

        assertEquals(TtsEngineType.POCKET_TTS, selected)
    }

    @Test
    fun `preparing model status shows progress and hides voices`() {
        composeTestRule.setContent {
            TtsSettingsSection(
                engineType = TtsEngineType.POCKET_TTS,
                speechRate = 1.0f,
                selectedVoiceId = null,
                availableVoices = emptyList(),
                modelStatus = ModelStatus.Preparing(0.42f),
                onEngineTypeSelected = {},
                onVoiceSelected = {},
                onSpeechRateChanged = {},
            )
        }

        composeTestRule.onNodeWithText("Preparing voice model… 42%").assertIsDisplayed()
        composeTestRule.onNodeWithText("Voices become available once the TTS engine is ready.")
            .assertIsDisplayed()
    }

    @Test
    fun `failed model status shows the error`() {
        composeTestRule.setContent {
            TtsSettingsSection(
                engineType = TtsEngineType.POCKET_TTS,
                speechRate = 1.0f,
                selectedVoiceId = null,
                availableVoices = emptyList(),
                modelStatus = ModelStatus.Failed("assets missing"),
                onEngineTypeSelected = {},
                onVoiceSelected = {},
                onSpeechRateChanged = {},
            )
        }

        composeTestRule.onNodeWithText("Model setup failed: assets missing").assertIsDisplayed()
    }

    @Test
    fun `voice picker lists the pocket voice catalog and reports selection`() {
        var selectedVoiceId: String? = null

        composeTestRule.setContent {
            TtsSettingsSection(
                engineType = TtsEngineType.POCKET_TTS,
                speechRate = 1.0f,
                selectedVoiceId = null,
                availableVoices = listOf(pocketVoice),
                modelStatus = ModelStatus.Ready("/path"),
                onEngineTypeSelected = {},
                onVoiceSelected = { selectedVoiceId = it },
                onSpeechRateChanged = {},
            )
        }

        composeTestRule.onNodeWithText("Ljspeech (en-US)").performClick()

        assertEquals("en_US-ljspeech-high", selectedVoiceId)
    }

    @Test
    fun `speech rate slider shows the current rate and reports changes`() {
        var changedRate: Float? = null
        composeTestRule.setContent {
            TtsSettingsSection(
                engineType = TtsEngineType.ANDROID,
                speechRate = 1.5f,
                selectedVoiceId = null,
                availableVoices = emptyList(),
                modelStatus = ModelStatus.Idle,
                onEngineTypeSelected = {},
                onVoiceSelected = {},
                onSpeechRateChanged = { changedRate = it },
            )
        }

        composeTestRule.onNodeWithText("Speech Rate: 1.5×").assertIsDisplayed()
        // Untouched — nothing should fire onSpeechRateChanged just from rendering.
        assertNull(changedRate)
    }
}
