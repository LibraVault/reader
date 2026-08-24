package xyz.libravault.feature.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.libravault.core.domain.model.UserPreferences
import xyz.libravault.core.domain.model.VaultFolder
import xyz.libravault.core.tts.TtsEngineType
import xyz.libravault.core.tts.pocket.ModelStatus

/**
 * [SettingsContent] extraction — docs/TEST_COVERAGE_PRD.md Phase 7. Split out
 * of [SettingsScreen] per the PlayerScreen template (thin ViewModel wrapper +
 * pure state/actions content), so it renders here with no Hilt graph, the
 * same pattern [TtsSettingsSectionTest] and [ui.CloudVoicesSectionTest]
 * already use for their sub-sections.
 *
 * "Existing tests pass unchanged" (SettingsViewModelTest, SupportLinkTest,
 * CloudVoicesSectionTest, TtsSettingsSectionTest — 67 tests) was checked
 * before this file was added, per the Phase 7 recipe's evidence-of-preservation
 * step.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsContentTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun baseState(
        isBillingSupported: Boolean = false,
        productsAvailable: Boolean = false,
        subscriptionActive: Boolean = false,
        cloudVoicesConsent: Boolean = false,
        engineType: TtsEngineType = TtsEngineType.ANDROID,
        vaults: List<VaultFolder> = emptyList(),
    ) = SettingsContentState(
        prefs = UserPreferences(),
        vaultState = VaultManagementState(vaults = vaults),
        isSupporter = false,
        productsAvailable = productsAvailable,
        subscriptionActive = subscriptionActive,
        isBillingSupported = isBillingSupported,
        appVersionName = "1.2.3",
        ttsState = TtsSettingsUiState(engineType = engineType, modelStatus = ModelStatus.Idle),
        cloudVoicesConsent = cloudVoicesConsent,
        selectedCloudProvider = null,
        configuredCloudProviders = emptySet(),
    )

    private fun noopActions() = SettingsActions(
        onBack = {},
        onEncryptedVaultsClick = {},
        onAddVaultClick = {},
        onRemoveVault = {},
        onReadingThemeChanged = {},
        onPlaybackSpeedChanged = {},
        onSkipDurationChanged = {},
        onTtsEngineTypeSelected = {},
        onTtsVoiceSelected = {},
        onTtsSpeechRateChanged = {},
        onCloudVoicesConsentAccepted = {},
        onCloudVoicesConsentDisabled = {},
        onCloudProviderSelected = {},
        onCloudVoiceIdChanged = {},
        onValidateAndSaveCloudKey = { _, _ -> Result.success(Unit) },
        onClearCloudKey = {},
        onUseCloudEngineToggled = {},
        onDynamicColorToggled = {},
        onLoggingToggled = {},
        onViewLogs = {},
        onClearLogs = {},
        onClearCoverCache = {},
        onScreenSecurityToggled = {},
        onSupportProjectClick = {},
        onSubscribeClick = {},
        onTipClick = {},
    )

    // ── cloudVoicesActuallySending — the AND this file exists to protect ────────
    // (found in review: the consent flag alone can outlive a lapsed subscription
    // or a switch away from the cloud engine; the "Permissions" copy must reflect
    // reality, not just one of the three conditions)

    @Test
    fun `cloudVoicesActuallySending is true only when subscribed, consented, and cloud engine active`() {
        assertTrue(
            baseState(subscriptionActive = true, cloudVoicesConsent = true, engineType = TtsEngineType.CLOUD)
                .cloudVoicesActuallySending
        )
    }

    @Test
    fun `cloudVoicesActuallySending is false if subscription lapsed`() {
        assertFalse(
            baseState(subscriptionActive = false, cloudVoicesConsent = true, engineType = TtsEngineType.CLOUD)
                .cloudVoicesActuallySending
        )
    }

    @Test
    fun `cloudVoicesActuallySending is false if consent was never given`() {
        assertFalse(
            baseState(subscriptionActive = true, cloudVoicesConsent = false, engineType = TtsEngineType.CLOUD)
                .cloudVoicesActuallySending
        )
    }

    @Test
    fun `cloudVoicesActuallySending is false if engine switched away from cloud`() {
        assertFalse(
            baseState(subscriptionActive = true, cloudVoicesConsent = true, engineType = TtsEngineType.ANDROID)
                .cloudVoicesActuallySending
        )
    }

    // ── Vaults section ───────────────────────────────────────────────────────

    @Test
    fun `empty vault list shows the empty-state message`() {
        composeTestRule.setContent {
            xyz.libravault.core.ui.theme.LibravaultTheme {
                SettingsContent(state = baseState(vaults = emptyList()), actions = noopActions())
            }
        }
        composeTestRule.onNodeWithText("No vaults configured. Add a folder to get started.")
            .assertIsDisplayed()
    }

    @Test
    fun `non-empty vault list renders each vault's display name`() {
        val vault = VaultFolder(id = 1, uri = "content://tree/primary:Books", displayName = "My Audiobooks")
        composeTestRule.setContent {
            xyz.libravault.core.ui.theme.LibravaultTheme {
                SettingsContent(state = baseState(vaults = listOf(vault)), actions = noopActions())
            }
        }
        composeTestRule.onNodeWithText("My Audiobooks").assertIsDisplayed()
        composeTestRule.onNodeWithText("No vaults configured. Add a folder to get started.")
            .assertDoesNotExist()
    }

    // ── Support Development section — the three-way branch this screen has ────
    // always had, now exercised directly instead of only by manual QA.

    @Test
    fun `fdroid flavor shows the external support link and coming-soon recurring copy`() {
        composeTestRule.setContent {
            xyz.libravault.core.ui.theme.LibravaultTheme {
                SettingsContent(state = baseState(isBillingSupported = false), actions = noopActions())
            }
        }
        // "Support Development" is the screen's last section — below the fold
        // in the test's default viewport, so scroll to it first. assertExists
        // alone would pass even if a layout regression pushed it fully off-screen.
        composeTestRule.onNodeWithText("Support the Project").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Recurring support is coming soon").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Subscribe — $1/mo").assertDoesNotExist()
    }

    @Test
    fun `play flavor with no products available shows the coming-soon message, not a broken buy button`() {
        composeTestRule.setContent {
            xyz.libravault.core.ui.theme.LibravaultTheme {
                SettingsContent(
                    state = baseState(isBillingSupported = true, productsAvailable = false),
                    actions = noopActions(),
                )
            }
        }
        composeTestRule.onNodeWithText("Support options are coming soon").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Subscribe — $1/mo").assertDoesNotExist()
        composeTestRule.onNodeWithText("Support the Project").assertDoesNotExist()
    }

    @Test
    fun `play flavor with products available invokes onSubscribeClick, not the fdroid external link`() {
        var subscribeClicked = false
        var tipClicked = false
        composeTestRule.setContent {
            xyz.libravault.core.ui.theme.LibravaultTheme {
                SettingsContent(
                    state = baseState(isBillingSupported = true, productsAvailable = true),
                    actions = noopActions().copy(
                        onSubscribeClick = { subscribeClicked = true },
                        onTipClick = { tipClicked = true },
                    ),
                )
            }
        }
        composeTestRule.onNodeWithText("Subscribe — $1/mo").performScrollTo().performClick()
        assertTrue(subscribeClicked)
        assertFalse(tipClicked)

        composeTestRule.onNodeWithText("Send a one-time tip").performScrollTo().performClick()
        assertTrue(tipClicked)
    }

    // ── Remove-vault dialog ─────────────────────────────────────────────────
    // This is the one piece of local UI state SettingsContent still owns
    // (vaultToRemove) — worth its own test since it's the only place a
    // regression here would be silent (the dialog renders fine either way;
    // only the callback wiring could quietly stop calling onRemoveVault).

    @Test
    fun `confirming remove invokes onRemoveVault with the tapped vault, not just dismisses`() {
        val vault = VaultFolder(id = 7, uri = "content://tree/primary:Old", displayName = "Old Vault")
        var removed: VaultFolder? = null
        composeTestRule.setContent {
            xyz.libravault.core.ui.theme.LibravaultTheme {
                SettingsContent(
                    state = baseState(vaults = listOf(vault)),
                    actions = noopActions().copy(onRemoveVault = { removed = it }),
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Remove vault").performClick()
        composeTestRule.onNodeWithText("Remove").performClick()

        assertEquals(vault, removed)
    }
}
