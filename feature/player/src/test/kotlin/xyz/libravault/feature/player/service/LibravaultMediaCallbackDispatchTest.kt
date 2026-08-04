package xyz.libravault.feature.player.service

import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import xyz.libravault.core.domain.model.LibraryItem
import xyz.libravault.core.domain.model.ListeningProgress
import xyz.libravault.core.domain.model.MediaFormat
import xyz.libravault.core.domain.usecase.GetAdjacentLibraryItemUseCase
import xyz.libravault.core.domain.usecase.GetListeningProgressUseCase
import xyz.libravault.core.domain.usecase.SaveListeningProgressUseCase

/**
 * Unit tests for [LibravaultMediaCallback.onCustomCommand] — the lockscreen /
 * Quick-Settings tile's PLAY_PAUSE, SEEK_BY, and PREVIOUS/NEXT dispatch.
 * `feature:player`'s `testOptions.unitTests.isReturnDefaultValues = true` makes
 * [android.os.Bundle] return defaults instead of throwing, and [ExoPlayer] is mockable
 * directly (its playback surface is the [androidx.media3.common.Player] interface), so
 * this exercises the real dispatch path without needing instrumentation — only the exact
 * custom offset carried through a real Bundle round-trip is out of scope here (see
 * [LibravaultMediaCallbackStripTest] for that boundary).
 *
 * Dispatch is coroutine-based (needed for the PREVIOUS/NEXT file-switch path's suspending
 * DB lookups), so every callback here is built with [Dispatchers.Unconfined] instead of the
 * production default ([Dispatchers.Main]) — that lets `.get()` observe the completed result
 * synchronously without a real Android main looper, since mockk's suspend-function mocks
 * return immediately rather than truly suspending.
 */
class LibravaultMediaCallbackDispatchTest {

    private val session = mockk<MediaSession>(relaxed = true)
    private val controller = mockk<MediaSession.ControllerInfo>(relaxed = true)
    private val playbackStateHolder = PlaybackStateHolder()
    private val getAdjacentItem = mockk<GetAdjacentLibraryItemUseCase>(relaxed = true)
    private val getListeningProgress = mockk<GetListeningProgressUseCase>(relaxed = true)
    private val saveListeningProgress = mockk<SaveListeningProgressUseCase>(relaxed = true)

    private fun callback(player: ExoPlayer) =
        LibravaultMediaCallback(
            context = mockk(relaxed = true),
            player = player,
            seekStepMs = 30_000L,
            playbackStateHolder = playbackStateHolder,
            getAdjacentItem = getAdjacentItem,
            getListeningProgress = getListeningProgress,
            saveListeningProgress = saveListeningProgress,
            dispatcher = Dispatchers.Unconfined,
        )

    private fun loadCurrentItem(
        itemId: Long = 1L,
        vaultFolderId: Long = 10L,
        filePath: String = "content://vault/chapter-02.mp3",
    ) {
        playbackStateHolder.update(
            itemId = itemId,
            vaultFolderId = vaultFolderId,
            filePath = filePath,
            title = "Current",
            author = "Author",
            coverArtPath = null,
            isPlaying = true,
        )
    }

    private fun libraryItem(id: Long, filePath: String) = LibraryItem(
        id = id,
        vaultFolderId = 10L,
        filePath = filePath,
        title = "Next chapter",
        author = "Author",
        format = MediaFormat.MP3,
    )

    @Test
    fun `PLAY_PAUSE resumes playback when paused`() {
        val player = mockk<ExoPlayer>(relaxed = true)
        every { player.playWhenReady } returns false

        val result = callback(player)
            .onCustomCommand(session, controller, SessionCommand(CustomCommandActions.PLAY_PAUSE, android.os.Bundle()), android.os.Bundle())
            .get()

        assertEquals(SessionResult.RESULT_SUCCESS, result.resultCode)
        verify(exactly = 1) { player.play() }
        verify(exactly = 0) { player.pause() }
    }

    @Test
    fun `PLAY_PAUSE pauses playback when playing`() {
        val player = mockk<ExoPlayer>(relaxed = true)
        every { player.playWhenReady } returns true

        val result = callback(player)
            .onCustomCommand(session, controller, SessionCommand(CustomCommandActions.PLAY_PAUSE, android.os.Bundle()), android.os.Bundle())
            .get()

        assertEquals(SessionResult.RESULT_SUCCESS, result.resultCode)
        verify(exactly = 1) { player.pause() }
        verify(exactly = 0) { player.play() }
    }

    @Test
    fun `SEEK_BY seeks through SeekClamp using current position and duration`() {
        val player = mockk<ExoPlayer>(relaxed = true)
        every { player.currentPosition } returns 10_000L
        every { player.duration } returns 60_000L

        val result = callback(player)
            .onCustomCommand(session, controller, SessionCommand(CustomCommandActions.SEEK_BY, android.os.Bundle()), android.os.Bundle())
            .get()

        assertEquals(SessionResult.RESULT_SUCCESS, result.resultCode)
        // Bundle.getLong returns its default (0L) under isReturnDefaultValues=true,
        // so the clamped target here is simply the current position with a 0 delta —
        // this still proves dispatch routes SEEK_BY through SeekClamp.clamp rather
        // than skipping straight to an unclamped seekTo.
        verify(exactly = 1) { player.seekTo(SeekClamp.clamp(10_000L, 0L, 60_000L)) }
    }

    @Test
    fun `PREVIOUS with no item loaded is a no-op`() {
        val player = mockk<ExoPlayer>(relaxed = true)

        val result = callback(player)
            .onCustomCommand(session, controller, SessionCommand(CustomCommandActions.PREVIOUS, android.os.Bundle()), android.os.Bundle())
            .get()

        assertEquals(SessionResult.RESULT_SUCCESS, result.resultCode)
        verify(exactly = 0) { player.setMediaItem(any(), any<Long>()) }
        coVerify(exactly = 0) { getAdjacentItem.previous(any(), any()) }
    }

    @Test
    fun `PREVIOUS with no sibling file in that direction is a no-op`() {
        val player = mockk<ExoPlayer>(relaxed = true)
        loadCurrentItem(filePath = "content://vault/chapter-01.mp3")
        coEvery { getAdjacentItem.previous(10L, "content://vault/chapter-01.mp3") } returns null

        val result = callback(player)
            .onCustomCommand(session, controller, SessionCommand(CustomCommandActions.PREVIOUS, android.os.Bundle()), android.os.Bundle())
            .get()

        assertEquals(SessionResult.RESULT_SUCCESS, result.resultCode)
        verify(exactly = 0) { player.setMediaItem(any(), any<Long>()) }
        assertEquals(1L, playbackStateHolder.state.value.itemId) // unchanged
    }

    @Test
    fun `NEXT with a sibling file switches the player, saves progress, and resumes playback`() {
        val player = mockk<ExoPlayer>(relaxed = true)
        every { player.isPlaying } returns true
        every { player.currentPosition } returns 45_000L
        every { player.playbackParameters } returns PlaybackParameters(1.0f)
        loadCurrentItem(itemId = 1L, vaultFolderId = 10L, filePath = "content://vault/chapter-01.mp3")
        val nextItem = libraryItem(id = 2L, filePath = "content://vault/chapter-02.mp3")
        coEvery { getAdjacentItem.next(10L, "content://vault/chapter-01.mp3") } returns nextItem
        coEvery { getListeningProgress(2L) } returns ListeningProgress(itemId = 2L, positionMs = 5_000L)

        val result = callback(player)
            .onCustomCommand(session, controller, SessionCommand(CustomCommandActions.NEXT, android.os.Bundle()), android.os.Bundle())
            .get()

        assertEquals(SessionResult.RESULT_SUCCESS, result.resultCode)
        coVerify(exactly = 1) {
            saveListeningProgress(match { it.itemId == 1L && it.positionMs == 45_000L })
        }
        verify(exactly = 1) { player.setMediaItem(any(), 5_000L) }
        verify(exactly = 1) { player.prepare() }
        verify(exactly = 1) { player.play() }
        assertEquals(2L, playbackStateHolder.state.value.itemId)
        assertEquals("content://vault/chapter-02.mp3", playbackStateHolder.state.value.filePath)
    }

    @Test
    fun `NEXT does not resume playback if the player was paused before the switch`() {
        val player = mockk<ExoPlayer>(relaxed = true)
        every { player.isPlaying } returns false
        every { player.playbackParameters } returns PlaybackParameters(1.0f)
        loadCurrentItem(itemId = 1L, vaultFolderId = 10L, filePath = "content://vault/chapter-01.mp3")
        val nextItem = libraryItem(id = 2L, filePath = "content://vault/chapter-02.mp3")
        coEvery { getAdjacentItem.next(10L, "content://vault/chapter-01.mp3") } returns nextItem
        coEvery { getListeningProgress(2L) } returns null

        callback(player)
            .onCustomCommand(session, controller, SessionCommand(CustomCommandActions.NEXT, android.os.Bundle()), android.os.Bundle())
            .get()

        verify(exactly = 1) { player.setMediaItem(any(), 0L) }
        verify(exactly = 0) { player.play() }
        assertEquals(false, playbackStateHolder.state.value.isPlaying)
    }

    @Test
    fun `unknown custom action is rejected without touching the player`() {
        val player = mockk<ExoPlayer>(relaxed = true)

        val result = callback(player)
            .onCustomCommand(session, controller, SessionCommand("unknown.action", android.os.Bundle()), android.os.Bundle())
            .get()

        assertEquals(SessionResult.RESULT_ERROR_NOT_SUPPORTED, result.resultCode)
        verify(exactly = 0) { player.play() }
        verify(exactly = 0) { player.pause() }
        verify(exactly = 0) { player.seekTo(any<Long>()) }
    }
}
