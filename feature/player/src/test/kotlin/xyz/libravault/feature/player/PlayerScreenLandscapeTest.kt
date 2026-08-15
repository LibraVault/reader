package xyz.libravault.feature.player

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
 * [PortraitPlayerContent] and [LandscapePlayerContent] are pure functions of
 * (item, state, actions) — see [PlayerActions] — specifically so the landscape
 * layout added for #164 (the audio player no longer locks rotation to portrait,
 * see PlayerScreen's removed LockScreenOrientation() call) is exercisable here
 * the same way TtsSettingsSection is, without a real PlayerViewModel/Hilt/
 * ExoPlayer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlayerScreenLandscapeTest {

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

    private val state = PlayerUiState(
        item       = item,
        isLoading  = false,
        isPlaying  = false,
        positionMs = 1_000,
        durationMs = 10_000,
        chapters   = listOf(Chapter(index = 0, title = "Chapter 1", startMs = 0, endMs = 10_000)),
    )

    @Test
    fun `landscape layout shows the same core info as portrait`() {
        composeTestRule.setContent {
            LandscapePlayerContent(item = item, state = state, actions = noopActions())
        }

        composeTestRule.onNodeWithText("Love and Friendship").assertIsDisplayed()
        composeTestRule.onNodeWithText("Jane Austen").assertIsDisplayed()
        composeTestRule.onNodeWithText("Chapter 1").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Play").assertIsDisplayed()
        composeTestRule.onNodeWithText("1×").assertIsDisplayed()
    }

    @Test
    fun `landscape play button still reports play-pause`() {
        var toggled = false
        composeTestRule.setContent {
            LandscapePlayerContent(
                item    = item,
                state   = state,
                actions = noopActions(onPlayPause = { toggled = true }),
            )
        }

        composeTestRule.onNodeWithContentDescription("Play").performClick()

        assertTrue(toggled)
    }

    @Test
    fun `landscape speed button still reports clicks`() {
        var clicked = false
        composeTestRule.setContent {
            LandscapePlayerContent(
                item    = item,
                state   = state,
                actions = noopActions(onSpeedClick = { clicked = true }),
            )
        }

        composeTestRule.onNodeWithText("1×").performClick()

        assertTrue(clicked)
    }

    @Test
    fun `portrait layout still renders after extracting shared pieces`() {
        composeTestRule.setContent {
            PortraitPlayerContent(item = item, state = state, actions = noopActions())
        }

        composeTestRule.onNodeWithText("Love and Friendship").assertIsDisplayed()
        composeTestRule.onNodeWithText("Chapter 1").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Play").assertIsDisplayed()
    }

    private fun noopActions(
        onSeek: (Long) -> Unit = {},
        onPlayPause: () -> Unit = {},
        onSkipBack: () -> Unit = {},
        onSkipForward: () -> Unit = {},
        onPreviousChapter: () -> Unit = {},
        onNextChapter: () -> Unit = {},
        onSpeedClick: () -> Unit = {},
    ) = PlayerActions(
        onSeek            = onSeek,
        onPlayPause       = onPlayPause,
        onSkipBack        = onSkipBack,
        onSkipForward     = onSkipForward,
        onPreviousChapter = onPreviousChapter,
        onNextChapter     = onNextChapter,
        onSpeedClick      = onSpeedClick,
    )
}
