package xyz.libravault.core.tts

import android.util.Log
import xyz.libravault.core.tts.pocket.PocketTtsEngine
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "TtsEngineFactory"

enum class TtsEngineType {
    ANDROID,
    POCKET_TTS,
}

@Singleton
class TtsEngineFactory @Inject constructor(
    private val androidEngine: AndroidTtsEngine,
) {
    fun create(type: TtsEngineType): TtsEngine {
        return when (type) {
            TtsEngineType.ANDROID -> {
                Log.d(TAG, "Using Android system TTS")
                androidEngine
            }
            TtsEngineType.POCKET_TTS -> {
                // Check build flavor at runtime
                if (isFdroidBuild()) {
                    Log.w(TAG, "Pocket TTS requested but F-Droid build; falling back to Android TTS")
                    androidEngine
                } else {
                    Log.d(TAG, "Using Pocket TTS")
                    PocketTtsEngine()
                }
            }
        }
    }

    private fun isFdroidBuild(): Boolean {
        return try {
            val clazz = Class.forName("xyz.libravault.BuildConfig")
            val field = clazz.getDeclaredField("FLAVOR")
            field.isAccessible = true
            val flavor = field.get(null) as String
            flavor == "fdroid"
        } catch (e: Exception) {
            Log.e(TAG, "Could not determine build flavor: ${e.message}")
            false
        }
    }
}
