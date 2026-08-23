package xyz.libravault.core.cloudtts

import android.media.AudioAttributes
import android.media.MediaDataSource
import android.media.MediaPlayer
import android.util.Log
import javax.inject.Inject

private const val TAG = "CloudPlayback"

/**
 * Plays a vendor-encoded audio buffer (mp3, typically) received from
 * [CloudTtsProvider.synthesize]. A real player needs Android runtime
 * (Robolectric or a device), so [CloudTtsEngine] depends on this interface
 * rather than [MediaPlayerCloudPlayback] directly — tests substitute a fake
 * without ever touching `android.media.*`, the same interface-plus-fake
 * shape this codebase already uses for `HardwareKeyWrap`/`TtsEngine` itself.
 */
interface CloudPlayback {
    fun play(audioBytes: ByteArray, onCompletion: () -> Unit, onError: (String) -> Unit)
    fun pause()
    fun resume()
    fun stop()
}

/**
 * Real implementation, wrapping [MediaPlayer]. Unlike `core:tts`'s
 * `PocketPlayback` (raw PCM float samples over `AudioTrack`), cloud vendors
 * return already-encoded audio, so this wraps [MediaPlayer] — its own
 * decoder handles whatever format each vendor sends back.
 *
 * Feeds the byte array via [MediaDataSource] rather than writing a temp
 * file: no cache-file cleanup to get wrong, and the audio (synthesized
 * speech from the user's own reading material) never touches disk.
 *
 * All methods `synchronized(lock)`: [MediaPlayer] "objects are not
 * thread-safe" per its own docs, and this class is genuinely called from
 * two different threads — `play()` from [CloudTtsEngine]'s background
 * `scope`, `pause()`/`resume()`/`stop()` directly from whatever thread the
 * [xyz.libravault.core.tts.TtsEngine] caller is on (typically Main, same as
 * `AndroidTtsEngine`/`PocketTtsEngine`) — a race here could pause/resume a
 * stale reference or call `release()` on a player still mid-`start()` on
 * the other thread (found in review).
 */
class MediaPlayerCloudPlayback @Inject constructor() : CloudPlayback {
    private val lock = Any()
    private var mediaPlayer: MediaPlayer? = null

    override fun play(audioBytes: ByteArray, onCompletion: () -> Unit, onError: (String) -> Unit) = synchronized(lock) {
        stopLocked()
        val player = MediaPlayer()
        try {
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            player.setDataSource(ByteArrayMediaDataSource(audioBytes))
            player.setOnCompletionListener { onCompletion() }
            player.setOnErrorListener { _, what, extra ->
                Log.e(TAG, "MediaPlayer error: what=$what extra=$extra")
                onError("MediaPlayer error $what/$extra")
                true
            }
            // Synchronous prepare(), not prepareAsync(): this always runs
            // from CloudTtsEngine's background scope, never the caller's
            // thread, and the byte array is already fully in memory (no
            // network/disk I/O left for prepare() to block on).
            player.prepare()
            player.start()
            mediaPlayer = player
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start playback: ${e.message}", e)
            player.release()
            onError(e.message ?: "Failed to start playback")
        }
    }

    override fun pause() {
        synchronized(lock) { mediaPlayer?.pause() }
    }

    override fun resume() {
        synchronized(lock) { mediaPlayer?.start() }
    }

    override fun stop() {
        synchronized(lock) { stopLocked() }
    }

    private fun stopLocked() {
        mediaPlayer?.apply {
            runCatching { stop() }
            release()
        }
        mediaPlayer = null
    }
}

private class ByteArrayMediaDataSource(private val data: ByteArray) : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= data.size) return -1
        val length = minOf(size.toLong(), data.size - position).toInt()
        System.arraycopy(data, position.toInt(), buffer, offset, length)
        return length
    }

    override fun getSize(): Long = data.size.toLong()

    override fun close() = Unit
}
