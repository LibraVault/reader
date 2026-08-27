package xyz.libravault.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cold/warm/hot startup baseline (issue #695, Phase 0). No macrobenchmark
 * module existed before this — every prior UI-jank investigation (e.g. #653)
 * started from zero, with nothing to diff a regression against.
 *
 * Runs against `:app`'s `benchmark` build type (release-shaped: minified,
 * non-debuggable — the `debug` build type's numbers are not representative of
 * what users actually run). See `app/build.gradle.kts`'s `benchmark` build
 * type and this module's `targetProjectPath`.
 *
 * `PACKAGE_NAME` has no `.debug` suffix because `benchmark` inherits from
 * `release`, which — unlike `debug` — declares no `applicationIdSuffix`.
 *
 * Per-screen frame-timing / scroll-jank benchmarks (Library, Reader, Player —
 * the surfaces `docs/TEST_COVERAGE_PRD.md` §S2 flags as highest-traffic and
 * least tested) are Phase 0b, tracked in #695: they need Compose test tags
 * wired into those screens first, which this module doesn't assume.
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun startupCold() = startup(StartupMode.COLD)

    @Test
    fun startupWarm() = startup(StartupMode.WARM)

    @Test
    fun startupHot() = startup(StartupMode.HOT)

    private fun startup(startupMode: StartupMode) = benchmarkRule.measureRepeated(
        packageName = PACKAGE_NAME,
        metrics = listOf(StartupTimingMetric()),
        iterations = 5,
        startupMode = startupMode,
        // No Baseline Profile shipped yet (tracked separately in #695's Phase
        // 0 checklist) — `None` measures the JIT-warm-up-from-scratch case,
        // which is the honest baseline until one exists. Switching to
        // `Partial`/`Full` later will show up as an intentional, expected
        // jump in these numbers, not a regression.
        compilationMode = CompilationMode.None(),
    ) {
        pressHome()
        startActivityAndWait()
        // Give the first frame after launch (library list / onboarding) a
        // moment to settle so COLD/WARM/HOT runs measure comparable end
        // states rather than racing whatever composed first.
        device.wait(Until.hasObject(By.pkg(PACKAGE_NAME).depth(0)), 5_000)
    }

    private companion object {
        const val PACKAGE_NAME = "xyz.libravault.app"
    }
}
