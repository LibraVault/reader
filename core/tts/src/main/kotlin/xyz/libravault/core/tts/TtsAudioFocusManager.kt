package xyz.libravault.core.tts

import android.content.Context
import android.media.AudioManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin wrapper around [AudioManager]'s audio-focus API so a TTS engine defers to
 * whatever else starts producing audio while it's speaking - most importantly
 * LibraVault's own ExoPlayer-backed audiobook playback, which already has
 * `handleAudioFocus = true` and therefore requests focus on every `play()`,
 * including from the lockscreen / Bluetooth / Android Auto controls that call
 * `player.play()` directly with no Read-Aloud awareness of their own (#137's
 * "only one thing produces audio at a time" acceptance criterion - see the
 * PR's QA history for why a purely in-app mutual-exclusion check isn't enough).
 *
 * Uses the pre-API-26 [AudioManager.requestAudioFocus]/[AudioManager.abandonAudioFocus]
 * overloads (deprecated but fully functional) rather than [android.media.AudioFocusRequest]
 * on purpose: they take a listener directly instead of a builder chain, which keeps this
 * class - and its engine callers - testable on a plain JVM unit test. `core:tts` does not
 * set `testOptions.unitTests.isReturnDefaultValues`, so any real (unmocked)
 * `AudioFocusRequest.Builder` call would throw "not mocked" the way `AndroidTtsEngine`'s
 * `TextToSpeech` construction already does.
 */
@Singleton
class TtsAudioFocusManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private var listener: AudioManager.OnAudioFocusChangeListener? = null

    /**
     * Requests transient audio focus for an about-to-start/resume TTS utterance.
     * [onFocusLost] fires when another audio source takes focus away - e.g. an
     * audiobook resumed from the lockscreen while Read Aloud is speaking - and is
     * expected to stop the caller so playback doesn't overlap.
     */
    @Suppress("DEPRECATION")
    fun requestFocus(onFocusLost: () -> Unit) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val newListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
            if (focusChange == AudioManager.AUDIOFOCUS_LOSS ||
                focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
            ) {
                onFocusLost()
            }
        }
        listener = newListener
        audioManager.requestAudioFocus(
            newListener,
            AudioManager.STREAM_MUSIC,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
        )
    }

    /** Releases focus once TTS is no longer producing audio (paused, stopped, or shut down). */
    @Suppress("DEPRECATION")
    fun abandonFocus() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        listener?.let { audioManager.abandonAudioFocus(it) }
        listener = null
    }
}
