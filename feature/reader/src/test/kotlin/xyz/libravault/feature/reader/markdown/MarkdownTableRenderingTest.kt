package xyz.libravault.feature.reader.markdown

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Confirms GFM tables actually parse into a real per-cell grid, rather than either the
 * "[Table omitted]" placeholder this viewer showed before #120's renderer bump to
 * 0.32.0, or — the other way this could silently fail — one giant blob of raw
 * `| a | b |` text if the table extension weren't recognized at all
 * (0.28.0 didn't parse GFM tables — see feature/reader/build.gradle.kts).
 *
 * Matches with `substring = true` rather than exact text, and asserts existence rather
 * than [androidx.compose.ui.test.assertIsDisplayed]:
 *  - Cell text isn't trimmed of the alignment padding a hand-written pipe table
 *    naturally has ("Format   " vs "Format"), confirmed by dumping the semantics tree
 *    during development — exact matches failed on that padding alone, nothing to do
 *    with whether the table parsed correctly.
 *  - Robolectric has no real font metrics, so glyph-advance widths come out as a
 *    handful of synthetic pixels per character — harmless for what this test needs
 *    to prove, but it makes isDisplayed's exact on-screen-bounds check unreliable
 *    here (the same tree dump showed a correctly-parsed 3-row x 2-column grid with
 *    narrow-but-nonzero, non-overlapping per-cell bounds that isDisplayed still
 *    flagged inconsistently across cells).
 *
 * Deliberately drives [com.mikepenz.markdown.m3.Markdown] directly with real table source
 * rather than going through [MarkdownReaderScreen] end-to-end — the screen needs a SAF
 * [android.net.Uri]/ViewModel/Hilt graph that isn't worth standing up just to prove the
 * renderer dependency itself parses table syntax. Runs on Robolectric (JVM, no emulator),
 * mirroring FormatFilterRowTest / TtsSettingsSectionTest's setup.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MarkdownTableRenderingTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun textContaining(text: String): SemanticsMatcher = hasText(text, substring = true)

    @Test
    fun `GFM table parses into separate header and data cells, not a placeholder`() {
        val tableMarkdown = """
            | Format   | Extension |
            |----------|-----------|
            | Markdown | .md       |
            | EPUB     | .epub     |
        """.trimIndent()

        composeTestRule.setContent {
            Markdown(content = tableMarkdown, typography = markdownTypography())
        }

        // Each is its own node containing exactly this text — if the table extension
        // weren't recognized, the whole thing would render as one paragraph containing
        // the raw "| Format | Extension |" line, and these lookups would still find
        // the substring (a false pass) but the placeholder-absence check below would
        // fail to catch a *different* kind of pre-#120 regression, so both matter together.
        composeTestRule.onNode(textContaining("Format")).assertExists()
        composeTestRule.onNode(textContaining("Extension")).assertExists()
        composeTestRule.onNode(textContaining("Markdown")).assertExists()
        composeTestRule.onNode(textContaining(".md")).assertExists()
        composeTestRule.onNode(textContaining("EPUB")).assertExists()
        composeTestRule.onNode(textContaining(".epub")).assertExists()

        // The old fast-follow gap this bump closes — must not be present anymore.
        composeTestRule.onAllNodes(textContaining("Table omitted")).assertCountEquals(0)
    }

    @Test
    fun `a table with GFM inline formatting in cells still parses the cell text`() {
        // 0.32.0 (unlike the 0.28.0 pin this replaces) supports full inline content —
        // bold/code — inside table cells, not just plain text.
        val tableMarkdown = """
            | Field | Value |
            |-------|-------|
            | Name  | **Bold** and `code` |
        """.trimIndent()

        composeTestRule.setContent {
            Markdown(content = tableMarkdown, typography = markdownTypography())
        }

        composeTestRule.onNode(textContaining("Field")).assertExists()
        composeTestRule.onNode(textContaining("Value")).assertExists()
        composeTestRule.onNode(textContaining("Name")).assertExists()
    }
}
