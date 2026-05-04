package xyz.libravault.feature.settings

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import xyz.libravault.core.domain.model.AppReadingTheme
import xyz.libravault.core.domain.model.UserPreferences
import xyz.libravault.core.domain.model.snapPlaybackSpeed
import javax.inject.Inject
import javax.inject.Singleton

private const val PREFS_NAME              = "libravault_prefs"
private const val KEY_READING_THEME       = "reading_theme"
private const val KEY_PLAYBACK_SPEED      = "playback_speed"
private const val KEY_SKIP_DURATION       = "skip_duration_sec"
private const val KEY_LOGGING_ENABLED     = "logging_enabled"
private const val KEY_DYNAMIC_COLOR       = "dynamic_color"

@Singleton
class UserPreferencesRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs: SharedPreferences
        get() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun observe(): Flow<UserPreferences> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            trySend(read())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(read()) // Emit current value immediately
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    fun read(): UserPreferences = UserPreferences(
        defaultReadingTheme  = AppReadingTheme.valueOf(
            prefs.getString(KEY_READING_THEME, AppReadingTheme.DARK.name) ?: AppReadingTheme.DARK.name
        ),
        defaultPlaybackSpeed = snapPlaybackSpeed(prefs.getFloat(KEY_PLAYBACK_SPEED, 1.0f)),
        defaultSkipDurationSec = prefs.getInt(KEY_SKIP_DURATION, 30),
        loggingEnabled       = prefs.getBoolean(KEY_LOGGING_ENABLED, false),
        dynamicColorEnabled  = prefs.getBoolean(KEY_DYNAMIC_COLOR, true),
    )

    fun update(prefs: UserPreferences) {
        this.prefs.edit()
            .putString(KEY_READING_THEME, prefs.defaultReadingTheme.name)
            .putFloat(KEY_PLAYBACK_SPEED, prefs.defaultPlaybackSpeed)
            .putInt(KEY_SKIP_DURATION, prefs.defaultSkipDurationSec)
            .putBoolean(KEY_LOGGING_ENABLED, prefs.loggingEnabled)
            .putBoolean(KEY_DYNAMIC_COLOR, prefs.dynamicColorEnabled)
            .apply()
    }
}
