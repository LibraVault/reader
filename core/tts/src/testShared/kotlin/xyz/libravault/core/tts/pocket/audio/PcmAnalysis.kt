package xyz.libravault.core.tts.pocket.audio

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Measurements over a mono PCM buffer, in sherpa-onnx's output format: one
 * `Float` per sample, nominally normalized to `[-1, 1]`.
 *
 * These exist so the on-device Pocket TTS test (`PocketTtsAudioOutputTest`)
 * can assert something *about the audio itself* rather than only about engine
 * lifecycle - the gap issue #107 was filed for. Exact waveform comparison is
 * deliberately not offered: ONNX runtime output is not bit-identical across
 * devices and architectures, so the useful regression signals are aggregate
 * ones (is there energy, how long is it, how much of it is silence).
 *
 * This file is compiled into *both* the JVM unit test and the instrumentation
 * test source sets (see `core/tts/build.gradle.kts`). That keeps the math
 * itself covered by the always-on JVM CI job against synthetic waveforms,
 * while the on-device test - which only runs on arm64 hardware - applies the
 * same functions to real synthesis output.
 */
object PcmAnalysis {

    /** Default analysis window. 20 ms is short enough to resolve inter-word gaps. */
    const val DEFAULT_WINDOW_MS: Int = 20

    /**
     * Default RMS below which a window counts as silence. Piper output has a
     * low noise floor, but "digital zero" is still too strict a bar for
     * model-generated silence, which dithers slightly around zero.
     */
    const val DEFAULT_SILENCE_RMS: Double = 1e-3

    /** Playback duration of [sampleCount] mono samples at [sampleRate] Hz. */
    fun durationSeconds(sampleCount: Int, sampleRate: Int): Double {
        require(sampleRate > 0) { "sampleRate must be positive, was $sampleRate" }
        return sampleCount.toDouble() / sampleRate
    }

    /**
     * Root-mean-square amplitude over `[from, to)` - the standard proxy for
     * perceived loudness, and the cheapest "is this actually audio, or is it
     * silence" signal. Returns 0.0 for an empty range.
     */
    fun rms(samples: FloatArray, from: Int = 0, to: Int = samples.size): Double {
        require(from in 0..samples.size) { "from=$from out of bounds for ${samples.size} samples" }
        require(to in from..samples.size) { "to=$to out of bounds for from=$from, ${samples.size} samples" }
        if (from == to) return 0.0

        // Accumulate in Double: a few hundred thousand squared floats overflow
        // Float's precision long before they overflow Double's.
        var sumOfSquares = 0.0
        for (i in from until to) {
            val sample = samples[i].toDouble()
            sumOfSquares += sample * sample
        }
        return sqrt(sumOfSquares / (to - from))
    }

    /** Largest absolute sample value, i.e. how close the buffer runs to clipping. */
    fun peak(samples: FloatArray): Double {
        var peak = 0.0
        for (sample in samples) {
            val magnitude = abs(sample.toDouble())
            if (magnitude > peak) peak = magnitude
        }
        return peak
    }

    /**
     * True if any sample is `NaN` or infinite. A model or phonemizer that has
     * gone wrong tends to emit these rather than plausible-but-wrong audio,
     * and they survive straight through to `AudioTrack` as artefacts.
     */
    fun hasNonFiniteSamples(samples: FloatArray): Boolean =
        samples.any { !it.isFinite() }

    /**
     * How many samples fall outside `[-limit, limit]`. A handful is normal
     * headroom overshoot; a large share means the buffer would clip audibly.
     */
    fun outOfRangeCount(samples: FloatArray, limit: Float = 1.0f): Int =
        samples.count { it.isFinite() && abs(it) > limit }

    /**
     * Fraction of the buffer (by sample count, so a trailing partial window is
     * weighted correctly) sitting in windows quieter than [silenceRms].
     *
     * An empty buffer is reported as fully silent - vacuously, there is no
     * audible content in it.
     */
    fun silentFraction(
        samples: FloatArray,
        sampleRate: Int,
        windowMs: Int = DEFAULT_WINDOW_MS,
        silenceRms: Double = DEFAULT_SILENCE_RMS,
    ): Double {
        if (samples.isEmpty()) return 1.0
        val windowSize = windowSize(sampleRate, windowMs)

        var silentSamples = 0
        forEachWindow(samples, windowSize, silenceRms) { from, to, isSilent ->
            if (isSilent) silentSamples += to - from
        }
        return silentSamples.toDouble() / samples.size
    }

    /**
     * Longest unbroken run of silence, in seconds. Catches output that has the
     * right total duration and plenty of energy overall but has dropped a
     * stretch in the middle - a whole sentence lost, say.
     */
    fun longestSilenceSeconds(
        samples: FloatArray,
        sampleRate: Int,
        windowMs: Int = DEFAULT_WINDOW_MS,
        silenceRms: Double = DEFAULT_SILENCE_RMS,
    ): Double {
        if (samples.isEmpty()) return 0.0
        val windowSize = windowSize(sampleRate, windowMs)

        var longestRun = 0
        var currentRun = 0
        forEachWindow(samples, windowSize, silenceRms) { from, to, isSilent ->
            currentRun = if (isSilent) currentRun + (to - from) else 0
            longestRun = max(longestRun, currentRun)
        }
        return durationSeconds(longestRun, sampleRate)
    }

    private fun windowSize(sampleRate: Int, windowMs: Int): Int {
        require(sampleRate > 0) { "sampleRate must be positive, was $sampleRate" }
        require(windowMs > 0) { "windowMs must be positive, was $windowMs" }
        // At very low sample rates a window can round to zero samples, which
        // would loop forever - one sample is the floor.
        return max(1, sampleRate * windowMs / 1000)
    }

    private inline fun forEachWindow(
        samples: FloatArray,
        windowSize: Int,
        silenceRms: Double,
        action: (from: Int, to: Int, isSilent: Boolean) -> Unit,
    ) {
        var from = 0
        while (from < samples.size) {
            val to = min(from + windowSize, samples.size)
            action(from, to, rms(samples, from, to) < silenceRms)
            from = to
        }
    }
}
