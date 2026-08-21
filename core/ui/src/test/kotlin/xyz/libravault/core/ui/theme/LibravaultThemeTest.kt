package xyz.libravault.core.ui.theme

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.WindowCompat
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Runs on Robolectric (JVM, no emulator) — same setup as
 * `feature/settings`'s `TtsSettingsSectionTest`, the first Compose UI test in
 * this codebase.
 *
 * Covers the F-Droid review finding (F-Droid MR !43520, comment from
 * @MiggiV2) that sepia-theme status bar icons stayed white on the light/cream
 * background: [LibravaultTheme] never called [WindowCompat]'s status-bar-icon
 * API at all, so icon color just followed whatever the static
 * `android:windowLightStatusBar` value in themes.xml happened to be —
 * tuned for the app's dark default, and never updated for a light reading
 * theme chosen at runtime.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibravaultThemeTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private fun isLightStatusBarIcons(): Boolean {
        val activity = composeTestRule.activity
        return WindowCompat.getInsetsController(activity.window, activity.window.decorView)
            .isAppearanceLightStatusBars
    }

    @Test
    fun `dark reading theme uses light (white) status bar icons`() {
        composeTestRule.setContent {
            LibravaultTheme(darkTheme = true, readingTheme = ReadingTheme.DARK) {}
        }
        composeTestRule.waitForIdle()

        assertFalse("Dark theme should use light/white status bar icons", isLightStatusBarIcons())
    }

    @Test
    fun `sepia reading theme uses dark status bar icons for contrast against the cream background`() {
        // darkTheme = true (system in dark mode) deliberately, to prove the fix follows
        // readingTheme rather than accidentally following the ambient system setting —
        // this exact combination (dark system + Sepia reading theme) is what the
        // reviewer's device was in when they hit the bug.
        composeTestRule.setContent {
            LibravaultTheme(darkTheme = true, readingTheme = ReadingTheme.SEPIA) {}
        }
        composeTestRule.waitForIdle()

        assertTrue(
            "Sepia is a light/cream background — status bar icons must be dark for " +
                "contrast, regardless of the system's own dark/light setting",
            isLightStatusBarIcons(),
        )
    }

    @Test
    fun `light reading theme uses dark status bar icons`() {
        composeTestRule.setContent {
            LibravaultTheme(darkTheme = false, readingTheme = ReadingTheme.LIGHT) {}
        }
        composeTestRule.waitForIdle()

        assertTrue(isLightStatusBarIcons())
    }

    // ── #349/#370: SYSTEM + the bug it uncovered ────────────────────────────────

    @Test
    fun `an explicit DARK pick wins over an ambient light system setting`() {
        // Regression test for a latent bug this issue's resolution logic fixed: the old
        // `when` here only special-cased SEPIA and otherwise fell straight through to the
        // ambient darkTheme value, so an explicit DARK/LIGHT pick had no actual effect —
        // only the system setting mattered. darkTheme = false (system light) deliberately,
        // to prove DARK still wins.
        composeTestRule.setContent {
            LibravaultTheme(darkTheme = false, readingTheme = ReadingTheme.DARK) {}
        }
        composeTestRule.waitForIdle()

        assertFalse(
            "An explicit Dark pick must render dark regardless of the system's own " +
                "light/dark setting",
            isLightStatusBarIcons(),
        )
    }

    @Test
    fun `an explicit LIGHT pick wins over an ambient dark system setting`() {
        // Mirror of the test above: darkTheme = true (system dark) deliberately, to prove
        // LIGHT still wins.
        composeTestRule.setContent {
            LibravaultTheme(darkTheme = true, readingTheme = ReadingTheme.LIGHT) {}
        }
        composeTestRule.waitForIdle()

        assertTrue(
            "An explicit Light pick must render light regardless of the system's own " +
                "light/dark setting",
            isLightStatusBarIcons(),
        )
    }

    @Test
    fun `SYSTEM follows the ambient dark system setting`() {
        composeTestRule.setContent {
            LibravaultTheme(darkTheme = true, readingTheme = ReadingTheme.SYSTEM) {}
        }
        composeTestRule.waitForIdle()

        assertFalse(
            "SYSTEM with the OS in dark mode should render dark",
            isLightStatusBarIcons(),
        )
    }

    @Test
    fun `SYSTEM follows the ambient light system setting`() {
        composeTestRule.setContent {
            LibravaultTheme(darkTheme = false, readingTheme = ReadingTheme.SYSTEM) {}
        }
        composeTestRule.waitForIdle()

        assertTrue(
            "SYSTEM with the OS in light mode should render light",
            isLightStatusBarIcons(),
        )
    }
}
