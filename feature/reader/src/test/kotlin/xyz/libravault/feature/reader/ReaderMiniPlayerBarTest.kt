package xyz.libravault.feature.reader

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.libravault.feature.player.service.PlaybackStateHolder

/**
 * [ReaderMiniPlayerBar] and [ReaderReadAloudMiniBar] — docs/TEST_COVERAGE_PRD.md
 * Phase 7. These two are the genuinely pure pieces of ReaderScreen.kt (widened
 * from `private` to `internal`); the top-level `ReaderScreen` composable itself
 * is deliberately NOT extracted the PlayerScreen/SettingsScreen way — see the
 * doc comment on ReaderMiniPlayerBar for why (shared ViewModelStoreOwner
 * coordination with EpubReaderScreen/MarkdownReaderScreen).
 *
 * Display-state assertions only, not click-wiring: an extended investigation
 * found `performClick()` silently fails to invoke any callback on either bar
 * specifically when rendered with its real, distinct Material icon set
 * (SkipPrevious/FastRewind/Pause/PlayArrow/SkipNext/Stop/Headphones together) —
 * every individual icon and a same-icon-repeated version of the same layout
 * click fine in isolation, so this is not believed to be a production bug, but
 * a genuine, unexplained Robolectric/Compose test-harness interaction. Filed as
 * a known gap rather than shipped as a flaky or silently-weakened assertion —
 * see the PR description for the isolation steps taken.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReaderMiniPlayerBarTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun nowPlaying(isPlaying: Boolean) = PlaybackStateHolder.State(
        itemId = 1L,
        title = "Love and Friendship",
        author = "Jane Austen",
        isPlaying = isPlaying,
        isActive = true,
    )

    @Test
    fun `shows title and author, and Play when paused`() {
        composeTestRule.setContent {
            MaterialTheme {
                ReaderMiniPlayerBar(
                    nowPlaying = nowPlaying(isPlaying = false),
                    onNowPlayingClick = {}, onPrevious = {}, onSeekBack = {},
                    onPlayPause = {}, onSeekForward = {}, onNext = {},
                )
            }
        }
        composeTestRule.onNodeWithText("Love and Friendship").assertIsDisplayed()
        composeTestRule.onNodeWithText("Jane Austen").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Play").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Pause").assertDoesNotExist()
    }

    @Test
    fun `shows Pause when playing`() {
        composeTestRule.setContent {
            MaterialTheme {
                ReaderMiniPlayerBar(
                    nowPlaying = nowPlaying(isPlaying = true),
                    onNowPlayingClick = {}, onPrevious = {}, onSeekBack = {},
                    onPlayPause = {}, onSeekForward = {}, onNext = {},
                )
            }
        }
        composeTestRule.onNodeWithContentDescription("Pause").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Play").assertDoesNotExist()
    }

    @Test
    fun `read-aloud bar shows title and Pause reading when playing`() {
        composeTestRule.setContent {
            MaterialTheme {
                ReaderReadAloudMiniBar(isPlaying = true, onExpand = {}, onPlayPause = {}, onStop = {})
            }
        }
        composeTestRule.onNodeWithText("Reading aloud").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Pause reading").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Resume reading").assertDoesNotExist()
    }

    @Test
    fun `read-aloud bar shows Resume reading when paused`() {
        composeTestRule.setContent {
            MaterialTheme {
                ReaderReadAloudMiniBar(isPlaying = false, onExpand = {}, onPlayPause = {}, onStop = {})
            }
        }
        composeTestRule.onNodeWithContentDescription("Resume reading").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Pause reading").assertDoesNotExist()
    }
}
