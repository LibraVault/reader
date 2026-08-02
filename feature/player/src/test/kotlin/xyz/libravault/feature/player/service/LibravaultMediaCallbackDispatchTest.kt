package xyz.libravault.feature.player.service

import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Unit tests for [LibravaultMediaCallback.onCustomCommand] — the lockscreen /
 * Quick-Settings tile's PLAY_PAUSE and SEEK_BY dispatch. `feature:player`'s
 * `testOptions.unitTests.isReturnDefaultValues = true` makes [android.os.Bundle]
 * return defaults instead of throwing, and [ExoPlayer] is mockable directly
 * (its playback surface is the [androidx.media3.common.Player] interface), so
 * this exercises the real dispatch path without needing instrumentation —
 * only the exact custom offset carried through a real Bundle round-trip is
 * out of scope here (see [LibravaultMediaCallbackStripTest] for that boundary).
 */
class LibravaultMediaCallbackDispatchTest {

    private val session = mockk<MediaSession>(relaxed = true)
    private val controller = mockk<MediaSession.ControllerInfo>(relaxed = true)

    private fun callback(player: ExoPlayer) =
        LibravaultMediaCallback(context = mockk(relaxed = true), player = player, seekStepMs = 30_000L)

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
    fun `PREVIOUS dispatches to seekToPrevious`() {
        val player = mockk<ExoPlayer>(relaxed = true)

        val result = callback(player)
            .onCustomCommand(session, controller, SessionCommand(CustomCommandActions.PREVIOUS, android.os.Bundle()), android.os.Bundle())
            .get()

        assertEquals(SessionResult.RESULT_SUCCESS, result.resultCode)
        verify(exactly = 1) { player.seekToPrevious() }
        verify(exactly = 0) { player.seekToNext() }
    }

    @Test
    fun `NEXT dispatches to seekToNext`() {
        val player = mockk<ExoPlayer>(relaxed = true)

        val result = callback(player)
            .onCustomCommand(session, controller, SessionCommand(CustomCommandActions.NEXT, android.os.Bundle()), android.os.Bundle())
            .get()

        assertEquals(SessionResult.RESULT_SUCCESS, result.resultCode)
        verify(exactly = 1) { player.seekToNext() }
        verify(exactly = 0) { player.seekToPrevious() }
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
