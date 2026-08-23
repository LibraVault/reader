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

// Premium Cloud TTS Voices (BYOK), PRD docs/cloud-tts-premium-prd.md §4 — the
// second, independent gate switch alongside SupportBillingClient's real
// subscription signal (see core:cloudtts's CloudTtsGate, which combines this
// with `subscriptionActive`). Off by default. Deliberately NOT the existing
// LOCAL_ONLY_KEY/localOnlyFlow below: that key is dead code today (zero
// production call sites) with inverted polarity (localOnly=true ⇔
// cloudVoicesConsent=false) — repurposing it would silently flip a stored
// boolean's meaning for anyone who greps LOCAL_ONLY_KEY later, which is the
// wrong kind of subtlety for a security/privacy-relevant consent flag.
private val CLOUD_VOICES_CONSENT_KEY = booleanPreferencesKey("cloud_voices_consent")

val Context.ttsPreferencesDataStore: DataStore<Preferences> by preferencesDataStore(name = PREFERENCES_NAME)

/**
 * Primary constructor takes the [DataStore] directly (rather than a
 * [Context]) so JVM tests can construct this against an in-memory/temp-file
 * store with no Android Context or Robolectric — the [Inject]-annotated
 * secondary constructor is what Hilt actually uses in production.
 */
@Singleton
class TtsPreferences constructor(
    private val dataStore: DataStore<Preferences>,
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(context.ttsPreferencesDataStore)

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

    /** Off by default — PRD §4. Independent of subscription state; buying the
     * subscription must never flip this. See [CLOUD_VOICES_CONSENT_KEY]. */
    val cloudVoicesConsentFlow: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[CLOUD_VOICES_CONSENT_KEY] ?: false
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

    suspend fun setCloudVoicesConsent(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[CLOUD_VOICES_CONSENT_KEY] = enabled
        }
    }
}
