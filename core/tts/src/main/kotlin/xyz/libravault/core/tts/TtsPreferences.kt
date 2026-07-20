package xyz.libravault.core.tts

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "TtsPreferences"

@Singleton
class TtsPreferences @Inject constructor() {
    // TODO: Replace with androidx.datastore:datastore-preferences once dependency is added
    // For now, using in-memory state flows as placeholder

    private val _engineType = MutableStateFlow(TtsEngineType.ANDROID)
    val engineTypeFlow: StateFlow<TtsEngineType> = _engineType.asStateFlow()

    private val _selectedVoice = MutableStateFlow<String?>(null)
    val selectedVoiceFlow: StateFlow<String?> = _selectedVoice.asStateFlow()

    private val _localOnly = MutableStateFlow(true)
    val localOnlyFlow: StateFlow<Boolean> = _localOnly.asStateFlow()

    suspend fun setEngineType(type: TtsEngineType) {
        Log.d(TAG, "Setting engine type to $type")
        _engineType.value = type
        // TODO: Persist to DataStore
    }

    suspend fun setSelectedVoice(voiceId: String?) {
        Log.d(TAG, "Setting selected voice to $voiceId")
        _selectedVoice.value = voiceId
        // TODO: Persist to DataStore
    }

    suspend fun setLocalOnly(enabled: Boolean) {
        Log.d(TAG, "Setting local-only to $enabled")
        _localOnly.value = enabled
        // TODO: Persist to DataStore
    }
}
