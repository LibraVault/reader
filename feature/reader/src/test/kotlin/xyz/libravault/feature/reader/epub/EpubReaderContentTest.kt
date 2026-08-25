package xyz.libravault.feature.reader.epub

import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.fragment.app.FragmentActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.libravault.core.ui.theme.LibravaultTheme
import xyz.libravault.feature.reader.ReaderSettings

/**
 * [EpubReaderContent] — docs/TEST_COVERAGE_PRD.md Phase 7 (issue #605). Covers the
 * Idle/Loading/Error/DrmProtected branches of [EpubReaderScreen]'s state machine, now
 * that they're split out into a pure, `internal` composable (see its doc comment for
 * why this extraction doesn't carry the sibling-`hiltViewModel()`-sharing risk that
 * re-scoped `ReaderScreenKt`).
 *
 * The [EpubPublicationState.Ready] branch is deliberately not exercised here — it hands
 * off to the private `EpubNavigatorView`, whose behaviour lives in a `DisposableEffect`
 * that commits a real `EpubNavigatorFragment` against a real Readium `Publication`.
 * Robolectric has no meaningful fake for that (same category as the existing
 * `androidTest`-only `ReadiumIntegrationTest`), so driving it here would be exactly the
 * "level that cannot observe the property" trap AGENTS.md warns about.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EpubReaderContentTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // Unused by the Idle/Loading/Error/DrmProtected branches under test — only the
    // Ready branch (not exercised here) ever reads it — but the parameter is non-null,
    // so a real Robolectric-hosted FragmentManager is the simplest honest value to pass.
    private val fragmentManager
        get() = Robolectric.buildActivity(FragmentActivity::class.java).setup().get().supportFragmentManager

    private fun setContent(state: EpubPublicationState) {
        composeTestRule.setContent {
            LibravaultTheme {
                EpubReaderContent(
                    publicationState         = state,
                    initialCfi               = null,
                    settings                 = ReaderSettings(),
                    highlights               = emptyList(),
                    pendingLocator           = null,
                    onPendingLocatorConsumed = {},
                    fragmentManager          = fragmentManager,
                    onPositionChanged        = {},
                    onLocatorChanged         = {},
                    onCentreTap              = {},
                    onAddHighlight           = { _, _, _ -> },
                )
            }
        }
    }

    @Test
    fun `Idle shows a progress indicator`() {
        setContent(EpubPublicationState.Idle)
        composeTestRule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertExists()
    }

    @Test
    fun `Loading shows a progress indicator`() {
        setContent(EpubPublicationState.Loading)
        composeTestRule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertExists()
    }

    @Test
    fun `Error shows the failure message with the underlying cause`() {
        setContent(EpubPublicationState.Error("corrupt zip central directory"))
        composeTestRule.onNodeWithText("Could not open EPUB: corrupt zip central directory").assertExists()
    }

    @Test
    fun `DrmProtected with a scheme name names the scheme`() {
        setContent(EpubPublicationState.DrmProtected(schemeName = "Adobe ADEPT"))
        composeTestRule.onNodeWithText(
            "This book is protected and can't be opened (protected by Adobe ADEPT)"
        ).assertExists()
    }

    @Test
    fun `DrmProtected with no scheme name omits the parenthetical`() {
        setContent(EpubPublicationState.DrmProtected(schemeName = null))
        composeTestRule.onNodeWithText("This book is protected and can't be opened").assertExists()
    }
}
