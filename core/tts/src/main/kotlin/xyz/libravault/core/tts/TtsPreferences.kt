package xyz.libravault.core.tts

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "TtsPreferences"
private const val PREFERENCES_NAME = "tts_preferences"

private val ENGINE_TYPE_KEY = stringPreferencesKey("engine_type")
private val SELECTED_VOICE_KEY = stringPreferencesKey("selected_voice")
private val LOCAL_ONLY_KEY = booleanPreferencesKey("local_voices_only")

val Context.ttsPreferencesDataStore: DataStore<Preferences> by preferencesDataStore(name = PREFERENCES_NAME)

@Singleton
class TtsPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val dataStore = context.ttsPreferencesDataStore

    val engineTypeFlow: Flow<TtsEngineType> = dataStore.data.map { preferences ->
        val typeName = preferences[ENGINE_TYPE_KEY] ?: TtsEngineType.ANDROID.name
        try {
            TtsEngineType.valueOf(typeName)
        } catch (e: IllegalArgumentException) {
            TtsEngineType.ANDROID
        }
    }

    val selectedVoiceFlow: Flow<String?> = dataStore.data.map { preferences ->
        preferences[SELECTED_VOICE_KEY]
    }

    val localOnlyFlow: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[LOCAL_ONLY_KEY] ?: true
    }

    suspend fun setEngineType(type: TtsEngineType) {
        dataStore.edit { preferences ->
            preferences[ENGINE_TYPE_KEY] = type.name
        }
    }

    suspend fun setSelectedVoice(voiceId: String?) {
        dataStore.edit { preferences ->
            if (voiceId != null) {
                preferences[SELECTED_VOICE_KEY] = voiceId
            } else {
                preferences.remove(SELECTED_VOICE_KEY)
            }
        }
    }

    suspend fun setLocalOnly(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[LOCAL_ONLY_KEY] = enabled
        }
    }
}
