package xyz.libravault.feature.vault

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.libravault.core.ui.theme.LibravaultTheme

/**
 * [CreateVaultContent] extraction — docs/TEST_COVERAGE_PRD.md Phase 7. Split
 * out of [CreateVaultScreen] per the PlayerScreen/SettingsScreen template, so
 * it renders here with no Hilt graph, the same pattern
 * [xyz.libravault.feature.settings.SettingsContentTest] already uses.
 *
 * "Existing tests pass unchanged" ([CreateVaultViewModelTest], which never
 * touches the composable) was checked before this file was added, per the
 * Phase 7 recipe's evidence-of-preservation step.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CreateVaultContentTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun baseState(
        step: CreateVaultStep = CreateVaultStep.NAME,
        displayName: String = "",
        pin: String = "",
        confirmPin: String = "",
        pinError: String? = null,
        isCreating: Boolean = false,
        creationError: String? = null,
        recoveryKeyDisplay: String? = null,
        hasConfirmedSaved: Boolean = false,
        createdVaultId: String? = null,
    ) = CreateVaultUiState(
        step = step,
        displayName = displayName,
        pin = pin,
        confirmPin = confirmPin,
        pinError = pinError,
        isCreating = isCreating,
        creationError = creationError,
        recoveryKeyDisplay = recoveryKeyDisplay,
        hasConfirmedSaved = hasConfirmedSaved,
        createdVaultId = createdVaultId,
    )

    private fun noopActions() = CreateVaultActions(
        onDisplayNameChanged = {},
        onNameConfirmed = {},
        onPinChanged = {},
        onPinSubmitted = {},
        onConfirmPinChanged = {},
        onConfirmPinSubmitted = {},
        onSavedConfirmedChanged = {},
        onBack = {},
        onCreated = {},
    )

    private fun setContent(state: CreateVaultUiState, actions: CreateVaultActions) {
        composeTestRule.setContent {
            LibravaultTheme {
                CreateVaultContent(state = state, actions = actions)
            }
        }
    }

    // ── Name step ────────────────────────────────────────────────────────────

    @Test
    fun `Next is disabled until a name is entered`() {
        setContent(baseState(displayName = ""), noopActions())
        composeTestRule.onNodeWithText("Next").assertIsNotEnabled()
    }

    @Test
    fun `Next is enabled once a name is entered`() {
        setContent(baseState(displayName = "My Vault"), noopActions())
        composeTestRule.onNodeWithText("Next").assertIsEnabled()
    }

    @Test
    fun `typing a vault name invokes onDisplayNameChanged`() {
        var changed: String? = null
        setContent(baseState(displayName = ""), noopActions().copy(onDisplayNameChanged = { changed = it }))
        composeTestRule.onNodeWithText("Vault name").performTextInput("Books")
        assertEquals("Books", changed)
    }

    @Test
    fun `tapping Next on the name step invokes onNameConfirmed`() {
        var confirmed = false
        setContent(baseState(displayName = "My Vault"), noopActions().copy(onNameConfirmed = { confirmed = true }))
        composeTestRule.onNodeWithText("Next").performClick()
        assertTrue(confirmed)
    }

    // ── PIN step ─────────────────────────────────────────────────────────────

    @Test
    fun `PIN step shows the pinError supporting text`() {
        setContent(baseState(step = CreateVaultStep.PIN, pinError = "At least 4 characters"), noopActions())
        composeTestRule.onNodeWithText("At least 4 characters").assertIsDisplayed()
    }

    @Test
    fun `PIN step shows the creationError message when hardware is unavailable`() {
        setContent(
            baseState(step = CreateVaultStep.PIN, creationError = "This device can't provide the security a PIN needs here. Try a longer passphrase, or check whether a system update is available."),
            noopActions(),
        )
        composeTestRule.onNodeWithText(
            "This device can't provide the security a PIN needs here. Try a longer passphrase, or check whether a system update is available.",
        ).assertIsDisplayed()
    }

    @Test
    fun `Show PIN toggle reveals the PIN visibility icon state`() {
        setContent(baseState(step = CreateVaultStep.PIN, pin = "1234"), noopActions())
        composeTestRule.onNodeWithContentDescription("Show PIN").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Show PIN").performClick()
        composeTestRule.onNodeWithContentDescription("Hide PIN").assertIsDisplayed()
    }

    @Test
    fun `tapping Next on the PIN step invokes onPinSubmitted`() {
        var submitted = false
        setContent(
            baseState(step = CreateVaultStep.PIN, pin = "1234"),
            noopActions().copy(onPinSubmitted = { submitted = true }),
        )
        composeTestRule.onNodeWithText("Next").performClick()
        assertTrue(submitted)
    }

    // ── Confirm PIN step ─────────────────────────────────────────────────────

    @Test
    fun `Create Vault is disabled until confirmPin is non-empty`() {
        setContent(baseState(step = CreateVaultStep.CONFIRM_PIN, confirmPin = ""), noopActions())
        composeTestRule.onNodeWithText("Create Vault").assertIsNotEnabled()
    }

    @Test
    fun `Create Vault shows a progress indicator instead of the button while isCreating`() {
        setContent(baseState(step = CreateVaultStep.CONFIRM_PIN, confirmPin = "1234", isCreating = true), noopActions())
        composeTestRule.onNodeWithText("Create Vault").assertDoesNotExist()
    }

    @Test
    fun `tapping Create Vault invokes onConfirmPinSubmitted`() {
        var submitted = false
        setContent(
            baseState(step = CreateVaultStep.CONFIRM_PIN, confirmPin = "1234"),
            noopActions().copy(onConfirmPinSubmitted = { submitted = true }),
        )
        composeTestRule.onNodeWithText("Create Vault").performClick()
        assertTrue(submitted)
    }

    // ── Recovery key step ────────────────────────────────────────────────────

    @Test
    fun `Done is disabled until the checkbox is confirmed, even with a created vault id`() {
        setContent(
            baseState(step = CreateVaultStep.RECOVERY_KEY, recoveryKeyDisplay = "ABCD-EFGH", createdVaultId = "v1", hasConfirmedSaved = false),
            noopActions(),
        )
        composeTestRule.onNodeWithText("Done").assertIsNotEnabled()
    }

    @Test
    fun `Done is disabled if confirmed but the vault id hasn't arrived yet`() {
        setContent(
            baseState(step = CreateVaultStep.RECOVERY_KEY, recoveryKeyDisplay = "ABCD-EFGH", createdVaultId = null, hasConfirmedSaved = true),
            noopActions(),
        )
        composeTestRule.onNodeWithText("Done").assertIsNotEnabled()
    }

    @Test
    fun `tapping Done invokes onCreated with the created vault id, only once both conditions hold`() {
        var created: String? = null
        setContent(
            baseState(step = CreateVaultStep.RECOVERY_KEY, recoveryKeyDisplay = "ABCD-EFGH", createdVaultId = "v1", hasConfirmedSaved = true),
            noopActions().copy(onCreated = { created = it }),
        )
        composeTestRule.onNodeWithText("Done").performScrollTo().performClick()
        assertEquals("v1", created)
    }

    @Test
    fun `checking the confirmation checkbox invokes onSavedConfirmedChanged with true`() {
        var confirmed: Boolean? = null
        setContent(
            baseState(step = CreateVaultStep.RECOVERY_KEY, recoveryKeyDisplay = "ABCD-EFGH", createdVaultId = "v1"),
            noopActions().copy(onSavedConfirmedChanged = { confirmed = it }),
        )
        composeTestRule.onNode(isToggleable()).performScrollTo().performClick()
        assertEquals(true, confirmed)
    }

    // ── Back navigation wiring ───────────────────────────────────────────────
    // The screen title and back button track state.step directly — worth its
    // own test since a regression here would silently desync the AppBar from
    // the wizard step it's supposedly labelling.

    @Test
    fun `AppBar title tracks the current step`() {
        setContent(baseState(step = CreateVaultStep.PIN), noopActions())
        composeTestRule.onNodeWithText("Set a PIN").assertIsDisplayed()
    }

    @Test
    fun `tapping the back arrow invokes onBack`() {
        var backInvoked = false
        setContent(baseState(step = CreateVaultStep.PIN), noopActions().copy(onBack = { backInvoked = true }))
        composeTestRule.onNodeWithContentDescription("Back").performClick()
        assertTrue(backInvoked)
    }
}
