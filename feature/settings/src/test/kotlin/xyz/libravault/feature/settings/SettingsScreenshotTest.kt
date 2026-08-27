package xyz.libravault.feature.settings

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.libravault.core.cloudtts.CloudProviderId
import xyz.libravault.core.domain.model.UserPreferences
import xyz.libravault.core.domain.model.VaultFolder
import xyz.libravault.core.tts.TtsEngineType
import xyz.libravault.core.tts.pocket.ModelStatus
import xyz.libravault.core.ui.theme.LibravaultTheme
import xyz.libravault.core.ui.theme.ReadingTheme

/**
 * Screenshot baselines for [SettingsContent] — docs/TEST_COVERAGE_PRD.md
 * Phase 7. Same recipe as [xyz.libravault.core.ui.ThemeScreenshotTest]: a
 * representative state, captured once per reading theme, on Robolectric with
 * native graphics (plain JVM test, no emulator, runs in the existing
 * `jvm-tests.yml` gate).
 *
 * The state deliberately turns on every optional section (a configured vault,
 * a Supporter badge, the Cloud Voices section) so the baseline actually
 * exercises the screen's longest path, not just its empty defaults.
 *
 * `src/test/screenshots` is a `sensitive_paths` entry in `.github/agent-policy.yml`
 * — re-recording a baseline makes a failing test pass while recording the
 * break. Inspect the compare images under `build/outputs/roborazzi` before
 * ever re-recording.
 *
 * ```
 * ./gradlew :feature:settings:recordRoborazziFdroidDebug   # rewrite baselines
 * ./gradlew :feature:settings:verifyRoborazziFdroidDebug   # compare (what CI runs)
 * ```
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class SettingsScreenshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val representativeState = SettingsContentState(
        prefs = UserPreferences(),
        vaultState = VaultManagementState(
            vaults = listOf(
                VaultFolder(id = 1, uri = "content://tree/primary:Books", displayName = "Audiobooks"),
            ),
        ),
        isSupporter = true,
        productsAvailable = true,
        subscriptionActive = true,
        isBillingSupported = true,
        appVersionName = "1.2.3",
        ttsState = TtsSettingsUiState(engineType = TtsEngineType.CLOUD, modelStatus = ModelStatus.Idle),
        cloudVoicesConsent = true,
        selectedCloudProvider = CloudProviderId.ELEVENLABS,
        configuredCloudProviders = setOf(CloudProviderId.ELEVENLABS),
    )

    private val noopActions = SettingsActions(
        onBack = {}, onEncryptedVaultsClick = {}, onAddVaultClick = {}, onRemoveVault = {},
        onReadingThemeChanged = {}, onPlaybackSpeedChanged = {}, onSkipDurationChanged = {},
        onTtsEngineTypeSelected = {}, onTtsVoiceSelected = {}, onTtsSpeechRateChanged = {},
        onCloudVoicesConsentAccepted = {}, onCloudVoicesConsentDisabled = {}, onCloudProviderSelected = {},
        onCloudVoiceIdChanged = {}, onValidateAndSaveCloudKey = { _, _ -> Result.success(Unit) },
        onClearCloudKey = {}, onUseCloudEngineToggled = {}, onDynamicColorToggled = {},
        onLoggingToggled = {}, onViewLogs = {}, onClearLogs = {}, onClearCoverCache = {},
        onScreenSecurityToggled = {}, onVaultLibraryVisibleToggled = {},
        onVaultNotificationRealMetadataToggled = {}, onVaultStopOnLockToggled = {},
        onSupportProjectClick = {}, onSubscribeClick = {}, onTipClick = {},
    )

    @Test
    fun `dark theme`() = capture(ReadingTheme.DARK, darkTheme = true, name = "settings_dark")

    @Test
    fun `light theme`() = capture(ReadingTheme.LIGHT, darkTheme = false, name = "settings_light")

    @Test
    fun `sepia theme`() = capture(ReadingTheme.SEPIA, darkTheme = false, name = "settings_sepia")

    private fun capture(readingTheme: ReadingTheme, darkTheme: Boolean, name: String) {
        composeTestRule.setContent {
            LibravaultTheme(darkTheme = darkTheme, readingTheme = readingTheme) {
                SettingsContent(state = representativeState, actions = noopActions)
            }
        }
        composeTestRule.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }
}
