package xyz.libravault.core.ui

import android.app.Activity
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `FLAG_SECURE` had **five call sites across the vault screens and zero tests**
 * (docs/TEST_COVERAGE_PRD.md, S2).
 *
 * This is the kind of behaviour whose regression is invisible in QA: nothing
 * on screen changes, no test goes red, and the only symptom is that decrypted
 * vault content silently becomes screenshot-able and visible in the recents
 * thumbnail. Whether the window flag is actually set is the entire contract,
 * so it is what these assert — directly, on the real window, rather than
 * inferring it from whether some composable rendered.
 *
 * `SecureScreenEffect` is used in two shapes ([SecureScreenEffect]'s doc
 * comment has the full rationale), and both are covered here:
 *  - unconditional, on the recovery-key display/entry steps, which must stay
 *    secure regardless of the user's toggle;
 *  - toggle-driven, on vault content screens.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SecureScreenEffectTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val Activity.isSecure: Boolean
        get() = (window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0

    @Test
    fun `sets FLAG_SECURE while composed with the default unconditional form`() {
        composeTestRule.setContent { SecureScreenEffect() }
        composeTestRule.waitForIdle()

        assertTrue(
            "SecureScreenEffect() must set FLAG_SECURE — this is what keeps the recovery key " +
                "out of screenshots and the recents thumbnail",
            composeTestRule.activity.isSecure,
        )
    }

    @Test
    fun `does not set FLAG_SECURE when disabled`() {
        composeTestRule.setContent { SecureScreenEffect(enabled = false) }
        composeTestRule.waitForIdle()

        assertFalse(
            "enabled = false must leave the window unsecured",
            composeTestRule.activity.isSecure,
        )
    }

    /**
     * The toggle has to work in both directions while the screen stays
     * composed — a user can change "Screen Security" in settings and come
     * back, and `DisposableEffect(enabled)` is what re-runs.
     */
    @Test
    fun `flipping enabled on and off adds and clears the flag`() {
        var enabled by mutableStateOf(false)
        composeTestRule.setContent { SecureScreenEffect(enabled = enabled) }
        composeTestRule.waitForIdle()
        assertFalse("starts unsecured", composeTestRule.activity.isSecure)

        enabled = true
        composeTestRule.waitForIdle()
        assertTrue("turning the setting on must secure the window", composeTestRule.activity.isSecure)

        enabled = false
        composeTestRule.waitForIdle()
        assertFalse(
            "turning the setting off must clear FLAG_SECURE immediately, not on next launch",
            composeTestRule.activity.isSecure,
        )
    }

    /**
     * Leaving the screen must restore normal behaviour. If the flag leaked
     * past disposal, every later screen in the app would silently stay
     * unscreenshotable — a bug users would report as "screenshots are broken",
     * with nothing pointing back at the vault.
     */
    @Test
    fun `clears FLAG_SECURE when the effect leaves composition`() {
        var shown by mutableStateOf(true)
        composeTestRule.setContent { if (shown) SecureScreenEffect() }
        composeTestRule.waitForIdle()
        assertTrue(composeTestRule.activity.isSecure)

        shown = false
        composeTestRule.waitForIdle()
        assertFalse(
            "FLAG_SECURE must not outlive the composable that set it",
            composeTestRule.activity.isSecure,
        )
    }

    /**
     * Two secure screens can be composed at once (a sheet over a vault
     * screen). Disposing one must not unsecure the window while the other is
     * still showing.
     */
    @Test
    fun `window stays secure while a second secure effect is still composed`() {
        var showSecond by mutableStateOf(true)
        composeTestRule.setContent {
            SecureScreenEffect()
            if (showSecond) SecureScreenEffect()
        }
        composeTestRule.waitForIdle()
        assertTrue(composeTestRule.activity.isSecure)

        showSecond = false
        composeTestRule.waitForIdle()
        assertTrue(
            "disposing one of two secure effects must leave the window secure",
            composeTestRule.activity.isSecure,
        )
    }

    /**
     * The navigation case the reference counting exists for, in the order it
     * actually happens: Navigation-Compose keeps both destinations composed
     * during a transition, so the incoming screen's effect runs *before* the
     * outgoing screen's `onDispose`. Before ref-counting, that disposal
     * cleared the flag and left the incoming screen — showing decrypted vault
     * content — unsecured.
     */
    @Test
    fun `window stays secure across a transition where the outgoing screen disposes last`() {
        var outgoing by mutableStateOf(true)
        var incoming by mutableStateOf(false)
        composeTestRule.setContent {
            if (outgoing) SecureScreenEffect()
            if (incoming) SecureScreenEffect()
        }
        composeTestRule.waitForIdle()
        assertTrue(composeTestRule.activity.isSecure)

        // Transition begins: both destinations composed.
        incoming = true
        composeTestRule.waitForIdle()
        assertTrue(composeTestRule.activity.isSecure)

        // Transition ends: the outgoing destination leaves composition.
        outgoing = false
        composeTestRule.waitForIdle()
        assertTrue(
            "navigating between two secure vault screens must leave the window secure",
            composeTestRule.activity.isSecure,
        )
    }

    /** Once every secure screen is gone, the flag must actually be released. */
    @Test
    fun `flag is cleared only after the last secure effect disposes`() {
        var first by mutableStateOf(true)
        var second by mutableStateOf(true)
        composeTestRule.setContent {
            if (first) SecureScreenEffect()
            if (second) SecureScreenEffect()
        }
        composeTestRule.waitForIdle()

        first = false
        composeTestRule.waitForIdle()
        assertTrue("still one holder left", composeTestRule.activity.isSecure)

        second = false
        composeTestRule.waitForIdle()
        assertFalse(
            "with no secure screens composed the window must return to normal",
            composeTestRule.activity.isSecure,
        )
    }
}
