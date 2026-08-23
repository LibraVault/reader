package xyz.libravault.feature.settings.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.libravault.core.cloudtts.CloudCredentialFields
import xyz.libravault.core.cloudtts.CloudProviderId

/** Robolectric-hosted, same shape as [TtsSettingsSectionTest] — pure
 * function of its parameters, exercised with plain callbacks. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CloudVoicesSectionTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `consent off hides the provider picker`() {
        composeTestRule.setContent {
            CloudVoicesSection(
                consentEnabled = false,
                selectedProvider = null,
                configuredProviders = emptySet(),
                onConsentAccepted = {},
                onConsentDisabled = {},
                onProviderSelected = {},
                onValidateAndSaveKey = { _, _ -> Result.success(Unit) },
                onClearKey = {},
            )
        }

        composeTestRule.onNodeWithText("Enable Cloud Voices").assertIsDisplayed()
        composeTestRule.onNodeWithText("Provider").assertDoesNotExist()
    }

    @Test
    fun `toggling the switch on shows the disclosure, not an immediate consent flip`() {
        var accepted = false
        composeTestRule.setContent {
            CloudVoicesSection(
                consentEnabled = false,
                selectedProvider = null,
                configuredProviders = emptySet(),
                onConsentAccepted = { accepted = true },
                onConsentDisabled = {},
                onProviderSelected = {},
                onValidateAndSaveKey = { _, _ -> Result.success(Unit) },
                onClearKey = {},
            )
        }

        composeTestRule.onNodeWithText("Enable Cloud Voices").performClick()

        // The switch itself doesn't grant consent — the disclosure must be
        // accepted first (PRD §6: "explicit accept required; never a
        // pre-checked box").
        assertTrue(!accepted)
        composeTestRule.onNodeWithText("Enable Cloud Voices?").assertIsDisplayed()

        composeTestRule.onNodeWithText("Accept & Enable").performClick()
        assertTrue(accepted)
    }

    @Test
    fun `disabling consent is immediate, no disclosure`() {
        var disabled = false
        composeTestRule.setContent {
            CloudVoicesSection(
                consentEnabled = true,
                selectedProvider = null,
                configuredProviders = emptySet(),
                onConsentAccepted = {},
                onConsentDisabled = { disabled = true },
                onProviderSelected = {},
                onValidateAndSaveKey = { _, _ -> Result.success(Unit) },
                onClearKey = {},
            )
        }

        composeTestRule.onNodeWithText("Enable Cloud Voices").performClick()
        assertTrue(disabled)
        composeTestRule.onNodeWithText("Enable Cloud Voices?").assertDoesNotExist()
    }

    @Test
    fun `consent on shows all five fixed vendor presets`() {
        composeTestRule.setContent {
            CloudVoicesSection(
                consentEnabled = true,
                selectedProvider = null,
                configuredProviders = emptySet(),
                onConsentAccepted = {},
                onConsentDisabled = {},
                onProviderSelected = {},
                onValidateAndSaveKey = { _, _ -> Result.success(Unit) },
                onClearKey = {},
            )
        }

        // assertExists, not assertIsDisplayed: CloudVoicesSection doesn't own
        // scrolling (its caller, SettingsScreen, wraps the whole screen in
        // verticalScroll) — 5 provider rows plus the explanatory text
        // genuinely exceeds Robolectric's default unscrolled test-window
        // height, so the later rows are correctly off-screen here without a
        // scrollable wrapper. What this test cares about is that all five
        // are actually composed, not this isolated test's viewport size.
        composeTestRule.onNodeWithText("ElevenLabs").assertExists()
        composeTestRule.onNodeWithText("OpenAI").assertExists()
        composeTestRule.onNodeWithText("Google Cloud TTS").assertExists()
        composeTestRule.onNodeWithText("Azure AI Speech").assertExists()
        composeTestRule.onNodeWithText("Amazon Polly").assertExists()
    }

    @Test
    fun `selecting a provider reports it and reveals the configure button`() {
        var selected: CloudProviderId? = null
        composeTestRule.setContent {
            CloudVoicesSection(
                consentEnabled = true,
                selectedProvider = null,
                configuredProviders = emptySet(),
                onConsentAccepted = {},
                onConsentDisabled = {},
                onProviderSelected = { selected = it },
                onValidateAndSaveKey = { _, _ -> Result.success(Unit) },
                onClearKey = {},
            )
        }

        composeTestRule.onNodeWithText("OpenAI").performClick()

        assertEquals(CloudProviderId.OPENAI, selected)
    }

    @Test
    fun `configured provider shows the checkmark and an Update button, not Configure`() {
        composeTestRule.setContent {
            CloudVoicesSection(
                consentEnabled = true,
                selectedProvider = CloudProviderId.OPENAI,
                configuredProviders = setOf(CloudProviderId.OPENAI),
                onConsentAccepted = {},
                onConsentDisabled = {},
                onProviderSelected = {},
                onValidateAndSaveKey = { _, _ -> Result.success(Unit) },
                onClearKey = {},
            )
        }

        composeTestRule.onNodeWithText("✓ Configured").assertIsDisplayed()
        composeTestRule.onNodeWithText("Update API Key").assertIsDisplayed()
        composeTestRule.onNodeWithText("Remove").assertIsDisplayed()
    }

    @Test
    fun `entering a key and saving calls onValidateAndSaveKey with the entered value`() {
        var savedCredentials: Map<String, String>? = null
        composeTestRule.setContent {
            CloudVoicesSection(
                consentEnabled = true,
                selectedProvider = CloudProviderId.OPENAI,
                configuredProviders = emptySet(),
                onConsentAccepted = {},
                onConsentDisabled = {},
                onProviderSelected = {},
                onValidateAndSaveKey = { _, credentials ->
                    savedCredentials = credentials
                    Result.success(Unit)
                },
                onClearKey = {},
            )
        }

        composeTestRule.onNodeWithText("Configure API Key").performClick()
        composeTestRule.onNodeWithText("API Key").performTextInput("sk-test-12345")
        composeTestRule.onNodeWithText("Validate & Save").performClick()
        composeTestRule.waitForIdle()

        assertEquals(mapOf(CloudCredentialFields.API_KEY to "sk-test-12345"), savedCredentials)
    }

    @Test
    fun `a failed validation shows the error and does not close the dialog`() {
        composeTestRule.setContent {
            CloudVoicesSection(
                consentEnabled = true,
                selectedProvider = CloudProviderId.OPENAI,
                configuredProviders = emptySet(),
                onConsentAccepted = {},
                onConsentDisabled = {},
                onProviderSelected = {},
                onValidateAndSaveKey = { _, _ -> Result.failure(RuntimeException("Invalid key")) },
                onClearKey = {},
            )
        }

        composeTestRule.onNodeWithText("Configure API Key").performClick()
        composeTestRule.onNodeWithText("API Key").performTextInput("sk-bad")
        composeTestRule.onNodeWithText("Validate & Save").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Invalid key").assertIsDisplayed()
        // Dialog stays open — the field is still there.
        composeTestRule.onNodeWithText("API Key").assertIsDisplayed()
    }
}
