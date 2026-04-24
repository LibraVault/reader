package xyz.libravault.feature.player.service

import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

sealed class SleepTimerState {
    data object Inactive                         : SleepTimerState()
    data class  Active(val remainingMs: Long)    : SleepTimerState()
    data object FadingOut                        : SleepTimerState()
}

/**
 * Coroutine-based sleep timer with a 10-second linear volume fade-out.
 *
 * When the timer fires:
 *  1. Volume drops linearly from 1.0 → 0.0 over [FADE_DURATION_MS] (10 s).
 *  2. ExoPlayer is paused and volume is restored to 1.0.
 *
 * Supports:
 *  - Custom duration in minutes
 *  - End-of-chapter (caller passes remaining chapter duration)
 *  - Cancellation at any time
 */
@Singleton
class SleepTimer @Inject constructor() {

    companion object {
        const val FADE_DURATION_MS = 10_000L        // 10 seconds
        private const val FADE_STEPS = 100          // 100 ms per step → smooth fade
        private const val FADE_STEP_MS = FADE_DURATION_MS / FADE_STEPS
    }

    private val _state = MutableStateFlow<SleepTimerState>(SleepTimerState.Inactive)
    val state: StateFlow<SleepTimerState> = _state.asStateFlow()

    private var timerJob: Job? = null
    private var activePlayer: ExoPlayer? = null

    /**
     * Start the timer.
     * @param durationMs How long to wait before beginning the fade-out.
     * @param player     ExoPlayer instance to fade and pause.
     * @param scope      CoroutineScope tied to the ViewModel lifecycle.
     */
    fun start(durationMs: Long, player: ExoPlayer, scope: CoroutineScope) {
        cancel()
        activePlayer = player
        timerJob = scope.launch {
            var remaining = durationMs

            // ── Countdown ─────────────────────────────────────────────────
            while (remaining > 0 && isActive) {
                _state.value = SleepTimerState.Active(remaining)
                val tick = minOf(remaining, 1_000L)
                delay(tick)
                remaining -= tick
            }

            if (!isActive) return@launch

            // ── Fade out ──────────────────────────────────────────────────
            _state.value = SleepTimerState.FadingOut
            repeat(FADE_STEPS) { step ->
                if (!isActive) return@repeat
                val volume = 1.0f - (step + 1).toFloat() / FADE_STEPS
                player.volume = volume.coerceAtLeast(0f)
                delay(FADE_STEP_MS)
            }

            // ── Stop and restore ──────────────────────────────────────────
            player.pause()
            player.volume = 1.0f
            activePlayer = null
            _state.value = SleepTimerState.Inactive
        }
    }

    /**
     * Cancel the timer immediately.
     * Restores the last known player volume to 1.0 so the user isn't left
     * with a low volume after cancelling.
     */
    fun cancel() {
        timerJob?.cancel()
        timerJob = null
        activePlayer?.volume = 1.0f
        activePlayer = null
        _state.value = SleepTimerState.Inactive
    }

    val isActive: Boolean get() = timerJob?.isActive == true
}
