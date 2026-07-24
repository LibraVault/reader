package xyz.libravault.core.tts.pocket

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlin.math.min

private const val TAG = "PocketPlayback"
private const val SAMPLE_RATE_HZ = 24000
private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO
private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

class PocketPlayback {
    private var audioTrack: AudioTrack? = null
    private var isPaused = false
    private var generationCounter = 0

    /**
     * Starts playback by creating an AudioTrack and feeding audio chunks from the given Flow.
     * Converts FloatArray (range -1.0 to 1.0) to PCM 16-bit little-endian ShortArray.
     *
     * @param chunks Flow of FloatArray chunks (24 kHz mono)
     * @param onCompletion Callback when all chunks have been written
     */
    suspend fun play(chunks: Flow<FloatArray>, onCompletion: () -> Unit) {
        val currentGen = ++generationCounter
        val bufferSizeInBytes = AudioTrack.getMinBufferSize(
            SAMPLE_RATE_HZ,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
        )
        if (bufferSizeInBytes == AudioTrack.ERROR || bufferSizeInBytes == AudioTrack.ERROR_BAD_VALUE) {
            Log.e(TAG, "Failed to get minimum buffer size")
            return
        }

        audioTrack = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
            AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE_HZ)
                .setChannelMask(CHANNEL_CONFIG)
                .setEncoding(AUDIO_FORMAT)
                .build(),
            bufferSizeInBytes,
            AudioTrack.MODE_STREAM,
            android.media.AudioManager.AUDIO_SESSION_ID_GENERATE,
        ).apply {
            play()
        }

        try {
            feed(chunks, currentGen, bufferSizeInBytes)
            if (currentGen == generationCounter) {
                onCompletion()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Playback error: ${e.message}", e)
        } finally {
            if (currentGen == generationCounter) {
                audioTrack?.stop()
                audioTrack?.release()
                audioTrack = null
            }
        }
    }

    private suspend fun feed(
        chunks: Flow<FloatArray>,
        generation: Int,
        bufferSizeInBytes: Int,
    ) {
        val maxChunkSamples = bufferSizeInBytes / 2 // 16-bit = 2 bytes per sample
        chunks.collect { floatChunk ->
            if (generation != generationCounter) return@collect

            val shortChunk = FloatArray(floatChunk.size).apply {
                for (i in floatChunk.indices) {
                    this[i] = (floatChunk[i] * 32767f).coerceIn(-32768f, 32767f)
                }
            }.map { it.toInt().toShort() }.toShortArray()

            var offset = 0
            while (offset < shortChunk.size) {
                if (generation != generationCounter) return@collect

                while (isPaused && generation == generationCounter) {
                    Thread.sleep(50)
                }

                if (generation != generationCounter) return@collect

                val toWrite = min(maxChunkSamples, shortChunk.size - offset)
                audioTrack?.write(shortChunk, offset, toWrite, AudioTrack.WRITE_BLOCKING)
                offset += toWrite
            }
        }
    }

    fun pause() {
        isPaused = true
        audioTrack?.pause()
    }

    fun resume() {
        isPaused = false
        audioTrack?.play()
    }

    fun stop() {
        generationCounter++
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
        isPaused = false
    }
}
