package xyz.libravault.feature.reader.readaloud

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import xyz.libravault.feature.player.service.SleepTimerState

/**
 * Sleep timer for a Read Aloud (TTS) session (#138) — separate from
 * `feature:player`'s `SleepTimer`, which fades a real ExoPlayer's volume before
 * pausing it. There's no audio stream here to fade: this just pauses after the
 * countdown, matching iOS's documented TTS sleep-timer behaviour ("TTS/text
 * playback just pauses — AVSpeechSynthesizer has no mid-utterance volume control
 * to fade"). Reuses the same [SleepTimerState] the audiobook player exposes so
 * `SleepTimerSheet` renders both unmodified.
 *
 * Unlike `SleepTimer`, this is not a Hilt singleton — a Read Aloud session is
 * reader-scoped (owned by [xyz.libravault.feature.reader.ReaderViewModel]), so a
 * plain instance created alongside it and torn down with it is enough.
 */
class ReadAloudSleepTimer(private val onFire: () -> Unit) {

    private val _state = MutableStateFlow<SleepTimerState>(SleepTimerState.Inactive)
    val state: StateFlow<SleepTimerState> = _state.asStateFlow()

    private var timerJob: Job? = null

    fun start(durationMs: Long, scope: CoroutineScope) {
        cancel()
        timerJob = scope.launch {
            var remaining = durationMs
            while (remaining > 0 && isActive) {
                _state.value = SleepTimerState.Active(remaining)
                val tick = minOf(remaining, 1_000L)
                delay(tick)
                remaining -= tick
            }
            if (!isActive) return@launch
            _state.value = SleepTimerState.Inactive
            onFire()
        }
    }

    fun cancel() {
        timerJob?.cancel()
        timerJob = null
        _state.value = SleepTimerState.Inactive
    }
}
