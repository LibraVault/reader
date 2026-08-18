package xyz.libravault.feature.player

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.libravault.core.domain.model.LibraryItem
import xyz.libravault.core.domain.model.MediaFormat
import xyz.libravault.feature.player.service.Chapter

/**
 * Accessibility assertions for the audio player — Phase 5 of
 * docs/TEST_COVERAGE_PRD.md.
 *
 * The player is where a11y failures hurt most and show least. Its controls are
 * almost entirely **icon-only**: an unlabelled `IconButton` looks perfect and is
 * unusable with TalkBack, which announces it as nothing but "button". Neither
 * review nor a screenshot reveals that — only the semantics tree does.
 *
 * Assertions enumerate *every* clickable node rather than a hand-picked list. A
 * hand-picked a11y test protects the controls someone already thought about,
 * which are the ones least likely to be broken, and silently ignores the
 * control added next week. These fail on anything new that arrives unlabelled.
 *
 * A label is a `contentDescription` **or** visible `Text`: Compose merges a
 * button's text into its semantics, so `Button { Text("Add folder") }` is
 * correctly labelled without a description, and demanding one anyway would push
 * the codebase toward redundant descriptions that TalkBack reads twice.
 *
 * ## Why there is no touch-target assertion here
 *
 * A "every clickable is >= 48dp (WCAG 2.5.8)" test was written, and deleted
 * after mutation testing showed it **could not fail**. Recording that, because
 * the test is an obvious one to reach for and the next person will:
 *
 * `SemanticsNode.touchBoundsInRoot` is already expanded to the minimum touch
 * target by Compose itself. Shrinking the play/pause control from 72dp to 24dp
 * and re-running produced no failure — the node still reported `touch=48x48px`
 * against `size=24x24px`. The assertion was structurally incapable of failing,
 * not merely passing by luck.
 *
 * The obvious repair, asserting on `SemanticsNode.size` instead, is worse: a
 * correct `IconButton` drawing a 28dp icon measures 40x35px, so it would fail
 * code that is fine.
 *
 * The underlying reason is that Compose's pointer-input hit testing already
 * expands small targets to the minimum, so on Compose this criterion is
 * satisfied by construction and there is nothing left for a unit test to catch.
 * Genuine target-size problems here are about *visual* discoverability and
 * overlap, which is screenshot-baseline territory (Phase 5's other half), not
 * semantics.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlayerAccessibilityTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val item = LibraryItem(
        id            = 1,
        vaultFolderId = 1,
        filePath      = "/books/love-and-friendship.m4b",
        title         = "Love and Friendship",
        author        = "Jane Austen",
        format        = MediaFormat.M4B,
    )

    private fun state(isPlaying: Boolean = false) = PlayerUiState(
        item       = item,
        isLoading  = false,
        isPlaying  = isPlaying,
        positionMs = 1_000,
        durationMs = 10_000,
        chapters   = listOf(Chapter(index = 0, title = "Chapter 1", startMs = 0, endMs = 10_000)),
    )

    @Test
    fun `every clickable control in the portrait player is labelled`() {
        composeTestRule.setContent {
            PortraitPlayerContent(item = item, state = state(), actions = noopActions())
        }
        composeTestRule.assertAllClickablesLabelled("portrait player")
    }

    @Test
    fun `every clickable control in the landscape player is labelled`() {
        composeTestRule.setContent {
            LandscapePlayerContent(item = item, state = state(), actions = noopActions())
        }
        composeTestRule.assertAllClickablesLabelled("landscape player")
    }

    /**
     * The transport control relabels itself when playback state flips.
     *
     * A stale label is a real TalkBack bug that is invisible on screen: the icon
     * changes, the announcement does not, and a blind user is told "Play" on an
     * already-playing book. Asserting the old label is *absent* matters as much
     * as asserting the new one is present — a control that announced both would
     * be just as wrong, and a presence-only check would pass.
     */
    @Test
    fun `play pause control relabels itself when playback state changes`() {
        composeTestRule.setContent {
            PortraitPlayerContent(item = item, state = state(isPlaying = true), actions = noopActions())
        }

        val labels = composeTestRule.clickableLabels()
        assertTrue(
            "While playing, the transport control must announce 'Pause', not 'Play'. Labels: $labels",
            labels.any { it.equals("Pause", ignoreCase = true) },
        )
        assertTrue(
            "While playing, no control should still announce 'Play'. Labels: $labels",
            labels.none { it.equals("Play", ignoreCase = true) },
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** The accessible name Compose exposes: `contentDescription`, else merged `Text`. */
    private fun SemanticsNodeInteractionsProvider.clickableLabels(): List<String> =
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

    private fun ComposeContentTestRule.assertAllClickablesLabelled(screen: String) {
        val nodes = onAllNodes(hasClickAction()).fetchSemanticsNodes()
        // Without this the whole test passes when the harness renders nothing —
        // `none {}` over an empty list is vacuously true.
        assertTrue(
            "Found no clickable nodes in the $screen, so this test would pass vacuously. " +
                "The harness rendered nothing.",
            nodes.isNotEmpty(),
        )

        // Identify offenders by position, since by definition they have no name
        // to report. Bounds are what let someone actually find the control.
        val unlabelled = nodes.zip(clickableLabels())
            .filter { (_, label) -> label.isEmpty() }
            .map { (node, _) ->
                val b = node.boundsInRoot
                "unlabelled control at (${b.left.toInt()}, ${b.top.toInt()}) " +
                    "size ${b.width.toInt()}x${b.height.toInt()}px"
            }

        assertTrue(
            "Clickable controls in the $screen with no accessible name — TalkBack announces these " +
                "as a bare \"button\": $unlabelled (of ${nodes.size} clickable nodes). " +
                "Add a contentDescription to the Icon, or visible Text inside the control.",
            unlabelled.isEmpty(),
        )
    }

    private fun noopActions() = PlayerActions(
        onSeek            = {},
        onPlayPause       = {},
        onSkipBack        = {},
        onSkipForward     = {},
        onPreviousChapter = {},
        onNextChapter     = {},
        onSpeedClick      = {},
    )
}
