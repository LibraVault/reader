package xyz.libravault.core.tts.pocket

import android.content.Context
import android.util.Log
import xyz.libravault.core.tts.TtsVoiceInfo
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "PocketVoiceCatalog"
private const val VOICES_DIR_NAME = "pocket-tts/voices"
private const val DEFAULT_VOICE_ID = "bria"

@Singleton
class PocketVoiceCatalog @Inject constructor(
    private val context: Context,
) {
    private val voicesDir: File
        get() = File(context.filesDir, VOICES_DIR_NAME)

    /**
     * Get available voice prompts from the bundled assets and cached files.
     * Each voice is a WAV file that serves as a reference for voice cloning.
     */
    fun availableVoices(): List<TtsVoiceInfo> {
        ensureVoicesExist()

        val voices = mutableListOf<TtsVoiceInfo>()
        voicesDir.listFiles()?.forEach { file ->
            if (file.isFile && file.extension == "wav") {
                val voiceId = file.nameWithoutExtension
                voices.add(
                    TtsVoiceInfo(
                        id = voiceId,
                        displayName = formatVoiceName(voiceId),
                        locale = "en-US",
                        requiresNetwork = false,
                    )
                )
            }
        }

        return voices.sortedBy { it.displayName }
    }

    /**
     * Get a specific voice's WAV file path.
     * Returns null if voice not found.
     */
    fun getVoicePath(voiceId: String): String? {
        ensureVoicesExist()
        val voiceFile = File(voicesDir, "$voiceId.wav")
        return if (voiceFile.exists()) voiceFile.absolutePath else null
    }

    private fun ensureVoicesExist() {
        if (!voicesDir.exists()) {
            voicesDir.mkdirs()
            copyBundledVoices()
        }
    }

    private fun copyBundledVoices() {
        try {
            // TODO: Copy bundled voice assets to filesDir
            // Assets are typically at: assets/pocket-tts/voices/
            // Use context.assets.open("pocket-tts/voices/bria.wav") to read and copy
            Log.d(TAG, "TODO: Copy bundled voices from assets to ${voicesDir.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy bundled voices: ${e.message}", e)
        }
    }

    private fun formatVoiceName(voiceId: String): String {
        // Convert snake_case or kebab-case to Title Case
        return voiceId
            .replace("-", " ")
            .replace("_", " ")
            .split(" ")
            .joinToString(" ") { word ->
                word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            }
    }
}
