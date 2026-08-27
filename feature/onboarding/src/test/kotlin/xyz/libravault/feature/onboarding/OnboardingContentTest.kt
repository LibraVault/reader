package xyz.libravault.feature.onboarding

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Coverage for #690 — the onboarding screen had no way to reach Settings
 * before completing onboarding. Exercises [OnboardingContent] (the stateless
 * inner composable) directly rather than [OnboardingScreen], which requires a
 * Hilt-injected [OnboardingViewModel].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OnboardingContentTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `Settings button is displayed and invokes onSettingsClick`() {
        var settingsClicked = false

        composeTestRule.setContent {
            OnboardingContent(
                state = OnboardingUiState(),
                onAddFolderClick = {},
                onFinished = {},
                onSettingsClick = { settingsClicked = true },
            )
        }

        composeTestRule.onNodeWithContentDescription("Settings").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Settings").performClick()

        assert(settingsClicked) { "onSettingsClick was not invoked when the Settings button was clicked" }
    }
}
