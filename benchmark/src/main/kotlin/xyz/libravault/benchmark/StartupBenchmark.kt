package xyz.libravault.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test

/**
 * App-launch baseline for the fdroid flavour (see benchmark/build.gradle.kts for why).
 *
 * `PACKAGE_NAME` must track :app's `applicationId` — the debug build's
 * `.debug` suffix does not apply to the release-shaped `benchmark` build
 * type this module targets.
 */
private const val PACKAGE_NAME = "xyz.libravault.app"

class StartupBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun coldStartupNoCompilation() = startup(
        compilationMode = CompilationMode.None(),
        startupMode = StartupMode.COLD,
    )

    @Test
    fun coldStartupPartialCompilation() = startup(
        compilationMode = CompilationMode.Partial(),
        startupMode = StartupMode.COLD,
    )

    @Test
    fun warmStartup() = startup(
        compilationMode = CompilationMode.Partial(),
        startupMode = StartupMode.WARM,
    )

    @Test
    fun hotStartup() = startup(
        compilationMode = CompilationMode.Partial(),
        startupMode = StartupMode.HOT,
    )

    private fun startup(compilationMode: CompilationMode, startupMode: StartupMode) =
        benchmarkRule.measureRepeated(
            packageName = PACKAGE_NAME,
            metrics = listOf(StartupTimingMetric()),
            compilationMode = compilationMode,
            startupMode = startupMode,
            iterations = 10,
            setupBlock = {
                pressHome()
            },
        ) {
            startActivityAndWait()
            // Wait for the app's first frame to actually draw content so cold
            // startup isn't measured as "done" the instant a blank window
            // appears. No Compose test tags exist on LibraryScreen yet to
            // wait on something more specific (see feature/library) — this
            // generic "any view drawn" wait is the safest thing available.
            device.wait(Until.hasObject(By.pkg(PACKAGE_NAME).depth(0)), 5_000)
        }
}
