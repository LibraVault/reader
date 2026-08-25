package xyz.libravault.feature.vault

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.libravault.core.ui.theme.LibravaultTheme
import xyz.libravault.core.ui.theme.ReadingTheme

/**
 * Screenshot baselines for [CreateVaultContent] — docs/TEST_COVERAGE_PRD.md
 * Phase 7. Same recipe as [xyz.libravault.feature.settings.SettingsScreenshotTest]:
 * a representative state, captured once per reading theme, on Robolectric
 * with native graphics.
 *
 * The recovery-key step is the representative state: it's the wizard's most
 * visually distinct screen (QR code, monospace key block, checkbox) and the
 * one `SecureScreenEffect` guards, so it's the state most worth a pixel-level
 * regression guard.
 *
 * `src/test/screenshots` is a `sensitive_paths` entry in `.github/agent-policy.yml`
 * — re-recording a baseline makes a failing test pass while recording the
 * break. Inspect the compare images under `build/outputs/roborazzi` before
 * ever re-recording.
 *
 * ```
 * ./gradlew :feature:vault:recordRoborazziDebug   # rewrite baselines
 * ./gradlew :feature:vault:verifyRoborazziDebug   # compare (what CI runs)
 * ```
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class CreateVaultScreenshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val representativeState = CreateVaultUiState(
        step = CreateVaultStep.RECOVERY_KEY,
        displayName = "My Audiobooks",
        recoveryKeyDisplay = "ABCD-EFGH-JKLM-NPQR-STUV-WXYZ",
        hasConfirmedSaved = false,
        createdVaultId = "v1",
    )

    private val noopActions = CreateVaultActions(
        onDisplayNameChanged = {}, onNameConfirmed = {}, onPinChanged = {}, onPinSubmitted = {},
        onConfirmPinChanged = {}, onConfirmPinSubmitted = {}, onSavedConfirmedChanged = {},
        onBack = {}, onCreated = {},
    )

    @Test
    fun `dark theme`() = capture(ReadingTheme.DARK, darkTheme = true, name = "create_vault_recovery_key_dark")

    @Test
    fun `light theme`() = capture(ReadingTheme.LIGHT, darkTheme = false, name = "create_vault_recovery_key_light")

    @Test
    fun `sepia theme`() = capture(ReadingTheme.SEPIA, darkTheme = false, name = "create_vault_recovery_key_sepia")

    private fun capture(readingTheme: ReadingTheme, darkTheme: Boolean, name: String) {
        composeTestRule.setContent {
            LibravaultTheme(darkTheme = darkTheme, readingTheme = readingTheme) {
                CreateVaultContent(state = representativeState, actions = noopActions)
            }
        }
        composeTestRule.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }
}
