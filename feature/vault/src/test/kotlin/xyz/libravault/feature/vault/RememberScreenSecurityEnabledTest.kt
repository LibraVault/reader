package xyz.libravault.feature.vault

import android.app.Activity
import android.content.Context
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.libravault.core.storage.LibravaultPreferences
import xyz.libravault.core.ui.SecureScreenEffect

/**
 * Regression guard for issue #530 L5 / #569: the vault content screens used
 * to read [xyz.libravault.core.storage.VaultScreenSecurityPreference.isEnabled]
 * once via a keyless `remember { }`, so toggling "Screen Security" in Settings
 * only took effect the next time the screen was recomposed from scratch
 * (leave + re-enter), contradicting [SecureScreenEffect]'s own doc comment
 * ("toggling restores normal behavior immediately"). [rememberScreenSecurityEnabled]
 * fixes this by observing the preference reactively.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RememberScreenSecurityEnabledTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val Activity.isSecure: Boolean
        get() = (window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0

    @Test
    fun `flipping the setting elsewhere updates the window without leaving the screen`() {
        val prefs = composeTestRule.activity.getSharedPreferences(LibravaultPreferences.FILE_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(LibravaultPreferences.KEY_SCREEN_SECURITY_ENABLED, true).apply()

        composeTestRule.setContent {
            SecureScreenEffect(enabled = rememberScreenSecurityEnabled(composeTestRule.activity))
        }
        composeTestRule.waitForIdle()
        assertTrue(composeTestRule.activity.isSecure)

        // Simulate the Settings screen writing to the same SharedPreferences file, without
        // this composable ever leaving composition.
        prefs.edit().putBoolean(LibravaultPreferences.KEY_SCREEN_SECURITY_ENABLED, false).apply()
        composeTestRule.waitForIdle()

        assertFalse(
            "toggling the setting elsewhere must update the window immediately, not on next recomposition",
            composeTestRule.activity.isSecure,
        )
    }
}
