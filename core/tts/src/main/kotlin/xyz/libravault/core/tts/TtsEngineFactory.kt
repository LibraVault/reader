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
    private val pocketEngine: PocketTtsEngine,
) {
    // Pocket TTS used to fall back to Android system TTS on F-Droid builds
    // here, because the voice model was downloaded on first use and F-Droid
    // ships with no INTERNET permission. The model is now bundled into the
    // APK at build time instead (see PocketModelManager and
    // third-party/sherpa-onnx/setup-android-model.sh), so both flavors can
    // use it - no flavor check needed.
    fun create(type: TtsEngineType): TtsEngine {
        return when (type) {
            TtsEngineType.ANDROID -> {
                Log.d(TAG, "Using Android system TTS")
                androidEngine
            }
            TtsEngineType.POCKET_TTS -> {
                Log.d(TAG, "Using Pocket TTS")
                pocketEngine
            }
        }
    }
}
