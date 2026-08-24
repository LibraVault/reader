package xyz.libravault.feature.player.service

import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.libravault.core.domain.usecase.GetAdjacentLibraryItemUseCase
import xyz.libravault.core.domain.usecase.GetListeningProgressUseCase
import xyz.libravault.core.domain.usecase.SaveListeningProgressUseCase

/**
 * Regression test for issue #509: the lockscreen / Quick-Settings media tile showed a
 * duplicate Play button and dropped "Next" from its five-slot row.
 *
 * Root cause: [LibravaultMediaCallback.onConnect] left [Player.COMMAND_PLAY_PAUSE] in
 * `availablePlayerCommands` on the assumption that the system tile's central glyph is
 * driven only by `PlaybackStateCompat.state`. On some devices/Android versions the system
 * *also* derives a standalone play/pause action bit from that command being present,
 * duplicating the custom-layout's own Play/Pause button and pushing "Next" out of the row
 * — the same failure mode already documented for the seek/skip commands, just missed for
 * play/pause. See the class KDoc on [LibravaultMediaCallback] for the full rationale.
 *
 * Runs under Robolectric rather than plain JUnit 5: `onConnect` reads the real
 * `android.os.Bundle.EMPTY` static field, which is `null` under this module's
 * `testOptions.unitTests.isReturnDefaultValues = true` android.jar stub (no real
 * implementation backs it), so a plain-JVM test crashes with an NPE before ever
 * reaching the code this test cares about. Robolectric provides a real Bundle.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibravaultMediaCallbackConnectTest {

    private val session = mockk<MediaSession>(relaxed = true)
    private val controller = mockk<MediaSession.ControllerInfo>(relaxed = true)

    private fun callback() =
        LibravaultMediaCallback(
            context = ApplicationProvider.getApplicationContext(),
            player = mockk<ExoPlayer>(relaxed = true),
            seekStepMs = 30_000L,
            playbackStateHolder = PlaybackStateHolder(),
            getAdjacentItem = mockk<GetAdjacentLibraryItemUseCase>(relaxed = true),
            getListeningProgress = mockk<GetListeningProgressUseCase>(relaxed = true),
            saveListeningProgress = mockk<SaveListeningProgressUseCase>(relaxed = true),
            dispatcher = Dispatchers.Unconfined,
        )

    @Test
    fun `onConnect excludes COMMAND_PLAY_PAUSE from availablePlayerCommands - regression guard for duplicate Play button`() {
        val result = callback().onConnect(session, controller)

        assertFalse(
            "COMMAND_PLAY_PAUSE must not be advertised in availablePlayerCommands — the " +
                "system tile derives a standalone play/pause action bit from it on some " +
                "devices, duplicating the custom-layout's own Play/Pause button and pushing " +
                "Next out of the five-slot row (issue #509). The tile's central glyph still " +
                "tracks PlaybackStateCompat.state independently of this command.",
            result.availablePlayerCommands.contains(Player.COMMAND_PLAY_PAUSE),
        )
    }

    @Test
    fun `onConnect still excludes the seek and skip commands the custom layout already covers`() {
        val result = callback().onConnect(session, controller)
        val commands = result.availablePlayerCommands

        assertFalse(commands.contains(Player.COMMAND_SEEK_BACK))
        assertFalse(commands.contains(Player.COMMAND_SEEK_FORWARD))
        assertFalse(commands.contains(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM))
        assertFalse(commands.contains(Player.COMMAND_SEEK_TO_PREVIOUS))
        assertFalse(commands.contains(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM))
        assertFalse(commands.contains(Player.COMMAND_SEEK_TO_NEXT))
    }
}
