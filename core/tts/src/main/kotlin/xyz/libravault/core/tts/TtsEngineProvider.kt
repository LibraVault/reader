package xyz.libravault.core.tts

import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "TtsEngineProvider"

@Singleton
class TtsEngineProvider @Inject constructor(
    private val factory: TtsEngineFactory,
    private val preferences: TtsPreferences,
    @ApplicationContext private val context: android.content.Context,
) {
    private val scope = CoroutineScope(Dispatchers.Default)

    private val _engine = MutableStateFlow<TtsEngine>(factory.create(TtsEngineType.ANDROID))
    val engine: StateFlow<TtsEngine> = _engine.asStateFlow()

    private val _engineType = MutableStateFlow(TtsEngineType.ANDROID)
    val engineType: StateFlow<TtsEngineType> = _engineType.asStateFlow()

    init {
        // Observe preference changes and switch engines
        scope.launch {
            preferences.engineTypeFlow.collect { newType ->
                switchEngine(newType)
            }
        }
    }

    private suspend fun switchEngine(newType: TtsEngineType) {
        val oldEngine = _engine.value

        if (_engineType.value == newType) return

        Log.d(TAG, "Switching TTS engine from ${_engineType.value} to $newType")

        // Stop any active playback
        oldEngine.stop()

        // Create and initialize new engine
        val newEngine = factory.create(newType)
        newEngine.initialize()

        _engine.value = newEngine
        _engineType.value = newType

        Log.d(TAG, "Engine switched to $newType")
    }

    fun switchEngineSync(type: TtsEngineType) {
        scope.launch {
            switchEngine(type)
        }
    }

    fun shutdown() {
        _engine.value.shutdown()
    }
}
