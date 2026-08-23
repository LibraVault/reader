package xyz.libravault.feature.reader.components

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.libravault.core.ui.theme.LibravaultTheme

/**
 * Accessibility assertions for [ReaderTopBar] — Phase 5 of
 * docs/TEST_COVERAGE_PRD.md, following the same approach as
 * `feature:player`'s PlayerAccessibilityTest.
 *
 * The reader top bar is the densest icon-only surface in the app: up to eight
 * controls, seven of which are a bare glyph. With TalkBack, an unlabelled one is
 * announced as nothing but "button".
 *
 * ## Two assertions, deliberately different in kind
 *
 * [everyControlIsLabelled] sweeps *all* clickable nodes, so a control added
 * later cannot arrive unlabelled unnoticed. It is a floor, and a low one — it
 * accepts any non-empty name.
 *
 * [fontControlsAnnounceWhatTheyDo] pins the *specific* wording of the two font
 * buttons, because the sweep cannot catch a label that exists but is useless.
 * These buttons render `Text("A-")` / `Text("A+")`, which is a perfectly good
 * visual affordance and a poor spoken one: Compose merges that text into the
 * button's semantics, so TalkBack announced **"A minus, button"** and **"A
 * plus, button"** — technically labelled, practically a guessing game, and
 * invisible to the sweep because a name was present.
 *
 * That is the general lesson worth keeping: "has a label" and "has a *usable*
 * label" are different properties, and only the first can be checked
 * generically.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReaderTopBarAccessibilityTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun setTopBar(
        showFontControls: Boolean = true,
        withToc: Boolean = true,
        showReadAloud: Boolean = false,
        readAloudActive: Boolean = false,
    ) {
        composeTestRule.setContent {
            LibravaultTheme {
                ReaderTopBar(
                    title = "Love and Friendship",
                    onBack = {},
                    onFontDecrease = {},
                    onFontIncrease = {},
                    onAddBookmark = {},
                    onShowBookmarks = {},
                    onSettings = {},
                    showFontControls = showFontControls,
                    onShowToc = if (withToc) ({}) else null,
                    showReadAloud = showReadAloud,
                    readAloudActive = readAloudActive,
                )
            }
        }
    }

    @Test
    fun everyControlIsLabelled() {
        setTopBar()

        val nodes = composeTestRule.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        // Without this the filter below is vacuously satisfied by an empty tree.
        assertTrue(
            "No clickable nodes found — the harness rendered nothing, so this would pass vacuously.",
            nodes.isNotEmpty(),
        )

        val unlabelled = nodes.zip(composeTestRule.clickableLabels())
            .filter { (_, label) -> label.isEmpty() }
            .map { (node, _) ->
                val b = node.boundsInRoot
                "control at (${b.left.toInt()}, ${b.top.toInt()})"
            }

        assertTrue(
            "Reader top-bar controls with no accessible name — TalkBack announces these as a bare " +
                "\"button\": $unlabelled (of ${nodes.size} clickable nodes).",
            unlabelled.isEmpty(),
        )
    }

    /**
     * The font buttons must say what they *do*, not what they look like.
     *
     * Asserts the raw glyphs are gone as well as that the real names are
     * present: leaving "A-" announced alongside a description would still be
     * wrong, and a presence-only check would pass.
     */
    @Test
    fun fontControlsAnnounceWhatTheyDo() {
        setTopBar()

        val labels = composeTestRule.clickableLabels()

        assertTrue(
            "Expected a spoken label for the decrease-font control. Labels: $labels",
            labels.any { it.contains("decrease", ignoreCase = true) && it.contains("font", ignoreCase = true) },
        )
        assertTrue(
            "Expected a spoken label for the increase-font control. Labels: $labels",
            labels.any { it.contains("increase", ignoreCase = true) && it.contains("font", ignoreCase = true) },
        )
        assertTrue(
            "The bare glyphs \"A-\"/\"A+\" must not be what TalkBack reads — they are a visual " +
                "affordance, not a spoken one. Labels: $labels",
            labels.none { it.trim() == "A-" || it.trim() == "A+" },
        )
    }

    /**
     * Regression guard for #424: the settings trigger's spoken name must come
     * from its now-visible "Themes & Settings" label, not a leftover
     * "Reader settings" `contentDescription` on the icon (which would make the
     * icon itself a second, redundant accessible node under TalkBack).
     */
    @Test
    fun settingsControlAnnouncesItsVisibleLabel() {
        setTopBar()

        val labels = composeTestRule.clickableLabels()

        assertTrue(
            "Expected the settings control's accessible name to be its visible label. Labels: $labels",
            labels.any { it == "Themes & Settings" },
        )
    }

    /**
     * The Read Aloud action is icon-only (unlike settings, it leans on visual
     * prominence rather than a text label — see ReaderTopBar.showReadAloud's
     * doc), so its spoken name has to come entirely from contentDescription,
     * and that name must change with state rather than always announcing
     * "Read Aloud" while a session is already playing.
     */
    @Test
    fun readAloudControlAnnouncesItsCurrentState() {
        setTopBar(showReadAloud = true, readAloudActive = false)
        assertTrue(
            "Expected \"Read Aloud\" while inactive. Labels: ${composeTestRule.clickableLabels()}",
            composeTestRule.clickableLabels().any { it == "Read Aloud" },
        )
    }

    @Test
    fun readAloudControlAnnouncesStopWhileActive() {
        setTopBar(showReadAloud = true, readAloudActive = true)
        val labels = composeTestRule.clickableLabels()
        assertTrue(
            "Expected \"Stop Read Aloud\" while active. Labels: $labels",
            labels.any { it == "Stop Read Aloud" },
        )
        assertTrue(
            "The inactive label must not linger once a session is playing. Labels: $labels",
            labels.none { it == "Read Aloud" },
        )
    }

    /**
     * The font controls are optional (`showFontControls = false` for formats
     * with no adjustable type). Hiding them must not strand an unlabelled
     * control, and the sweep must still find something to check.
     */
    @Test
    fun everyControlIsLabelledWithoutOptionalControls() {
        setTopBar(showFontControls = false, withToc = false)

        val nodes = composeTestRule.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        assertTrue("No clickable nodes found — would pass vacuously.", nodes.isNotEmpty())

        val unlabelled = composeTestRule.clickableLabels().count { it.isEmpty() }
        assertTrue(
            "Reader top bar has $unlabelled unlabelled controls when font controls and TOC are hidden.",
            unlabelled == 0,
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
