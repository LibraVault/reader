package xyz.libravault.feature.vault

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.libravault.core.ui.theme.LibravaultTheme

/**
 * Accessibility and behaviour of [PinField] — Phase 5 of
 * docs/TEST_COVERAGE_PRD.md.
 *
 * This is the entry point to an encrypted vault, and it used to exist twice:
 * [CreateVaultScreen] and [UnlockVaultScreen] carried byte-for-byte copies.
 * Both are now this one component, which is the reason it is worth testing once
 * properly rather than twice badly.
 *
 * The show/hide toggle announced a bare **"Show"** / **"Hide"** — a verb with
 * no object, on a screen that also has a Back button and a submit button.
 * TalkBack said "Hide, button" and left the user to infer what. It now names
 * the thing it toggles.
 *
 * That is the same class of defect as the reader's "A-"/"A+" font buttons
 * (#269): a label that *exists*, so a generic "everything is labelled" sweep
 * passes, but that does not tell the user what the control does. Only a test
 * that pins the wording catches it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PinFieldAccessibilityTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    /** Renders a [PinField] whose visibility toggle is actually wired up. */
    private fun setPinField(initiallyVisible: Boolean = false) {
        composeTestRule.setContent {
            LibravaultTheme {
                // `remember` is load-bearing: without it every recomposition
                // rebuilds the state and the toggle silently snaps back, so the
                // relabel tests below fail against correct production code.
                var visible by remember { mutableStateOf(initiallyVisible) }
                PinField(
                    value = "1234",
                    onValueChange = {},
                    visible = visible,
                    onVisibleChange = { visible = it },
                    isError = false,
                    supportingText = null,
                )
            }
        }
    }

    @Test
    fun `toggle names the thing it toggles, not just the verb`() {
        setPinField(initiallyVisible = false)

        val labels = composeTestRule.clickableLabels()
        assertTrue(
            "The show/hide control must say what it acts on. \"Show\" alone is a verb with no " +
                "object — TalkBack reads \"Show, button\" on a screen with several buttons. Labels: $labels",
            labels.any { it.equals("Show PIN", ignoreCase = true) },
        )
        assertTrue(
            "The bare verb must not be the announcement. Labels: $labels",
            labels.none { it.trim().equals("Show", ignoreCase = true) },
        )
    }

    /**
     * The label must track the state, and the *old* label must be gone.
     *
     * A control announcing both, or announcing "Show PIN" while the PIN is
     * already visible, is exactly as wrong as one with no label — and a
     * presence-only assertion would pass in both cases.
     */
    @Test
    fun `toggle relabels itself when the PIN becomes visible`() {
        setPinField(initiallyVisible = false)

        composeTestRule.onNodeWithContentDescription("Show PIN").performClick()

        val labels = composeTestRule.clickableLabels()
        assertTrue(
            "After revealing the PIN the control must offer to hide it. Labels: $labels",
            labels.any { it.equals("Hide PIN", ignoreCase = true) },
        )
        assertTrue(
            "After revealing the PIN nothing should still offer to show it. Labels: $labels",
            labels.none { it.equals("Show PIN", ignoreCase = true) },
        )
    }

    @Test
    fun `toggle relabels itself when the PIN is hidden again`() {
        setPinField(initiallyVisible = true)

        composeTestRule.onNodeWithContentDescription("Hide PIN").performClick()

        val labels = composeTestRule.clickableLabels()
        assertEquals(
            "Toggling back must restore the show affordance exactly once. Labels: $labels",
            1,
            labels.count { it.equals("Show PIN", ignoreCase = true) },
        )
    }

    @Test
    fun `every clickable control in the field is labelled`() {
        setPinField()

        val nodes = composeTestRule.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        assertTrue(
            "No clickable nodes found — the harness rendered nothing, so this would pass vacuously.",
            nodes.isNotEmpty(),
        )
        assertEquals(
            "Unlabelled controls in the PIN field: TalkBack announces these as a bare \"button\".",
            0,
            composeTestRule.clickableLabels().count { it.isEmpty() },
        )
    }

    /** The accessible name Compose exposes: `contentDescription`, else merged `Text`. */
    private fun ComposeContentTestRule.clickableLabels(): List<String> =
        onAllNodes(hasClickAction()).fetchSemanticsNodes().map { node ->
            val description = node.config.getOrNull(SemanticsProperties.ContentDescription)
                ?.joinToString(" ")
                ?.trim()
                .orEmpty()
            val text = node.config.getOrNull(SemanticsProperties.Text)
                ?.joinToString(" ") { it.text }
                ?.trim()
                .orEmpty()
            description.ifEmpty { text }
        }
}
