package xyz.libravault.core.tts

import android.content.Context
import android.media.AudioManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Covers [TtsAudioFocusManager] in isolation - the piece #137's QA rounds found
 * missing: neither TTS engine used to request real [AudioManager] audio focus,
 * so an audiobook resumed from the lockscreen/notification/Bluetooth controls
 * (which call `player.play()` directly, with zero Read-Aloud awareness) could
 * play simultaneously with an active Read Aloud session.
 *
 * Deliberately mocks [AudioManager] rather than exercising the real
 * [AudioManager.requestAudioFocus] implementation - `core:tts` doesn't set
 * `testOptions.unitTests.isReturnDefaultValues`, so any real (unmocked) call into
 * android.jar's stub would throw. See [TtsAudioFocusManager]'s KDoc for why it
 * uses the listener-based overloads instead of `AudioFocusRequest.Builder` for
 * exactly this reason.
 */
class TtsAudioFocusManagerTest {

    private val audioManager = mockk<AudioManager>(relaxed = true)
    private val context = mockk<Context> {
        every { getSystemService(Context.AUDIO_SERVICE) } returns audioManager
    }

    private fun manager() = TtsAudioFocusManager(context)

    @Test
    fun `requestFocus asks AudioManager for transient focus on the music stream`() {
        manager().requestFocus {}

        verify {
            audioManager.requestAudioFocus(
                any(),
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
            )
        }
    }

    @Test
    fun `losing focus invokes the onFocusLost callback`() {
        val listenerSlot = slot<AudioManager.OnAudioFocusChangeListener>()
        every {
            audioManager.requestAudioFocus(capture(listenerSlot), any(), any())
        } returns AudioManager.AUDIOFOCUS_REQUEST_GRANTED

        var lost = false
        manager().requestFocus { lost = true }
        listenerSlot.captured.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)

        assertTrue(lost)
    }

    @Test
    fun `losing focus transiently also invokes the onFocusLost callback`() {
        val listenerSlot = slot<AudioManager.OnAudioFocusChangeListener>()
        every {
            audioManager.requestAudioFocus(capture(listenerSlot), any(), any())
        } returns AudioManager.AUDIOFOCUS_REQUEST_GRANTED

        var lost = false
        manager().requestFocus { lost = true }
        listenerSlot.captured.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)

        assertTrue(lost)
    }

    @Test
    fun `gaining focus does not invoke the onFocusLost callback`() {
        val listenerSlot = slot<AudioManager.OnAudioFocusChangeListener>()
        every {
            audioManager.requestAudioFocus(capture(listenerSlot), any(), any())
        } returns AudioManager.AUDIOFOCUS_REQUEST_GRANTED

        var lost = false
        manager().requestFocus { lost = true }
        listenerSlot.captured.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)

        assertFalse(lost)
    }

    @Test
    fun `abandonFocus releases the exact listener that was registered`() {
        val listenerSlot = slot<AudioManager.OnAudioFocusChangeListener>()
        every {
            audioManager.requestAudioFocus(capture(listenerSlot), any(), any())
        } returns AudioManager.AUDIOFOCUS_REQUEST_GRANTED

        val manager = manager()
        manager.requestFocus {}
        manager.abandonFocus()

        verify { audioManager.abandonAudioFocus(match { it === listenerSlot.captured }) }
    }

    @Test
    fun `abandonFocus without a prior request is a no-op`() {
        manager().abandonFocus()

        verify(exactly = 0) { audioManager.abandonAudioFocus(any()) }
    }

    @Test
    fun `missing AudioManager system service is handled gracefully`() {
        val contextWithoutAudioManager = mockk<Context> {
            every { getSystemService(Context.AUDIO_SERVICE) } returns null
        }

        // Neither call should throw.
        val manager = TtsAudioFocusManager(contextWithoutAudioManager)
        manager.requestFocus {}
        manager.abandonFocus()
    }
}
