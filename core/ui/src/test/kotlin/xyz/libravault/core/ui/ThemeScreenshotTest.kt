package xyz.libravault.core.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.libravault.core.ui.components.GeneratedCover
import xyz.libravault.core.ui.theme.LibravaultTheme
import xyz.libravault.core.ui.theme.ReadingTheme

/**
 * Screenshot baselines for the three reading themes — Phase 5 of
 * docs/TEST_COVERAGE_PRD.md.
 *
 * Runs on Robolectric with native graphics, so this is a plain JVM test in the
 * existing `jvm-tests.yml` gate: no emulator, no separate CI job.
 *
 * ## What this is for, and what it is not for
 *
 * Everything else in Phase 5 asserts things the semantics tree can answer —
 * contrast ratios (ColorSchemeContrastTest), accessible names
 * (PlayerAccessibilityTest, ReaderTopBarAccessibilityTest). Those are precise
 * and they are blind to anything about *pixels*: text clipped by a too-small
 * container, controls overlapping, a divider that vanishes into its background,
 * a theme that silently stops applying. Those are the regressions this catches.
 *
 * Deliberately a **small, stable set**: one sampler composable rendered in
 * Dark, Light and Sepia. Baselines are a liability as well as an asset — every
 * intentional UI change rewrites them, and a large set trains everyone to
 * re-record without looking. Three images that are actually inspected beat
 * thirty that are rubber-stamped.
 *
 * ## Re-recording is a deliberate act
 *
 * ```
 * ./gradlew :core:ui:recordRoborazziDebug     # rewrite baselines
 * ./gradlew :core:ui:verifyRoborazziDebug     # compare (what CI runs)
 * ```
 *
 * The `src/test/screenshots` directory is in `.github/agent-policy.yml`
 * sensitive_paths. Re-recording a baseline makes a failing test pass *while
 * recording the break* — the same silent failure mode as
 * `testdata/vault-format`, and the reason both are gated behind a human.
 * **Look at the diff image under `build/outputs/roborazzi` before
 * re-recording.**
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class ThemeScreenshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `dark theme sampler`() = captureSampler(ReadingTheme.DARK, darkTheme = true, name = "theme_dark")

    @Test
    fun `light theme sampler`() = captureSampler(ReadingTheme.LIGHT, darkTheme = false, name = "theme_light")

    @Test
    fun `sepia theme sampler`() = captureSampler(ReadingTheme.SEPIA, darkTheme = false, name = "theme_sepia")

    private fun captureSampler(readingTheme: ReadingTheme, darkTheme: Boolean, name: String) {
        composeTestRule.setContent {
            LibravaultTheme(darkTheme = darkTheme, readingTheme = readingTheme) {
                Sampler()
            }
        }
        composeTestRule.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    /**
     * A deliberately boring cross-section of the design system: body and
     * secondary text, a filled and an outlined button, a divider, and the
     * generated cover placeholder.
     *
     * Secondary text on `surfaceVariant` is here on purpose — that is the exact
     * pairing that was below WCAG AA in sepia until it was fixed (see
     * ColorSchemeContrastTest). The contrast test proves the numbers; this
     * proves it still *looks* like readable text on a real surface.
     */
    @Composable
    private fun Sampler() {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("Love and Friendship", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Jane Austen",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(12.dp))
                HorizontalDivider()
                Spacer(Modifier.size(12.dp))
                Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text("On a surface variant", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Secondary text — the pairing that failed WCAG AA in sepia",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.size(12.dp))
                Row {
                    Button(onClick = {}) { Text("Read") }
                    Spacer(Modifier.size(8.dp))
                    OutlinedButton(onClick = {}) { Text("Details") }
                }
                Spacer(Modifier.size(12.dp))
                GeneratedCover(title = "Dune", modifier = Modifier.size(72.dp))
            }
        }
    }
}
