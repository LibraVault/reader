package xyz.libravault.app

import android.content.Context
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Test
import org.junit.runner.RunWith

private const val TAG = "SettingsNavJankTest"

/**
 * #653 — one-shot CI regression check: does the Library <-> Settings back-navigation
 * transition drop measurably more frames on current dev HEAD than on the commit
 * immediately before the vault-unification merges (#543, #604)?
 *
 * This is deliberately NOT trying to reproduce the actual reported symptom (a
 * ~800-900ms stall root-caused via real-device logcat to a Samsung One UI
 * `RefreshRateModeManager` display-refresh-rate renegotiation racing the nav
 * transition — see issue #653's comments). That mechanism is OEM display-HAL code;
 * it does not exist on a stock emulator image (this runs on CircleCI's
 * `android/android-machine` executor, `android-34;google_apis;x86_64`) and cannot be
 * reproduced here regardless of what our code does. A clean result from this test
 * does NOT clear the app of the reported symptom — it only checks a narrower,
 * device-agnostic question: is Compose doing more/heavier work per frame during this
 * transition than before. That's still worth knowing on its own.
 *
 * Drives the real, already-installed app black-box via UiAutomator (same technique
 * as the manual `ab-test-settings-stall.sh` repro script) rather than through
 * ComposeTestRule + Hilt test scaffolding, which the app module has never needed
 * before — a black-box driver sidesteps standing up that infrastructure entirely
 * while still exercising the exact real navigation path a user hits.
 *
 * Jank is read from `dumpsys gfxinfo <pkg>` (Android's own built-in frame-timing
 * stats: total frames, janky-frame %, 50th/90th/95th/99th percentile render time) —
 * no new runtime dependency, no JankStats wiring, just the same tool `adb shell
 * dumpsys gfxinfo` gives any Android engineer today. Reset immediately before the
 * two Settings round-trips, read immediately after, so the window is as tight as
 * the manual repro's own logcat-timestamp bracketing.
 *
 * Intentionally has no hard pass/fail threshold — there's no established "acceptable
 * jank" baseline for this transition yet. The point of this run is the printed
 * report (visible in the CircleCI test-output / logcat), read alongside the same
 * test's report from a baseline-commit run, not a red/green gate.
 */
@RunWith(AndroidJUnit4::class)
class SettingsNavJankTest {

    @Test
    fun settingsBackNavigation_reportsFrameJank() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val pkg = context.packageName

        // Bypass the folder-picker-gated onboarding flow — same idea as the manual
        // script's `run-as` seed, just done directly since instrumentation already
        // runs in-process against the target app's own Context.
        context.getSharedPreferences("libravault_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("onboarded", true)
            .apply()

        val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg)
            ?: error("No launcher intent for $pkg — is the app actually installed?")
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launchIntent)

        check(device.wait(Until.hasObject(By.desc("More")), 15_000)) {
            "Library screen's overflow menu never appeared — cold start failed or " +
                "onboarding bypass didn't take"
        }

        // Bracket exactly the two round-trips under test, same as the manual repro.
        instrumentation.uiAutomation.executeShellCommand("dumpsys gfxinfo $pkg reset").close()

        repeat(2) { cycle ->
            Log.i(TAG, "=== cycle ${cycle + 1}: begin ===")

            device.findObject(By.desc("More")).click()
            check(device.wait(Until.hasObject(By.text("Settings")), 5_000)) {
                "Settings menu item never appeared after tapping the overflow menu"
            }
            device.findObject(By.text("Settings")).click()

            check(device.wait(Until.hasObject(By.desc("Back")), 5_000)) {
                "Settings screen's back button never appeared"
            }
            // Give the Settings screen a moment to fully settle before navigating
            // back, matching the manual repro's own pacing.
            Thread.sleep(2_000)

            device.findObject(By.desc("Back")).click()
            check(device.wait(Until.hasObject(By.desc("More")), 5_000)) {
                "Library screen never came back after tapping Settings' back button"
            }
            Thread.sleep(2_000)

            Log.i(TAG, "=== cycle ${cycle + 1}: done ===")
        }

        val report = instrumentation.uiAutomation
            .executeShellCommand("dumpsys gfxinfo $pkg")
            .readFully()

        Log.i(TAG, "=== GFXINFO REPORT (settingsBackNavigation_reportsFrameJank) ===\n$report")
        // Also to stdout — CircleCI's connectedDebugAndroidTest output and the
        // stored JUnit XML both surface System.out, no separate artifact wiring
        // needed to read this back.
        println("=== GFXINFO REPORT (settingsBackNavigation_reportsFrameJank) ===")
        println(report)

        check("Total frames rendered" in report) {
            "gfxinfo report looks malformed — got:\n$report"
        }
    }
}

private fun ParcelFileDescriptor.readFully(): String =
    ParcelFileDescriptor.AutoCloseInputStream(this).bufferedReader().use { it.readText() }
