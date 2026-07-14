package xyz.libravault.feature.player.service

import android.content.Context
import androidx.media3.common.util.UnstableApi

/**
 * Reads the user's skip-duration setting (5–120 seconds) from the shared
 * `libravault_prefs` SharedPreferences file.
 *
 * The repository that owns this file lives in
 * [xyz.libravault.feature.settings.UserPreferencesRepository] — a different Gradle
 * module. To avoid forcing a cross-module dependency just to read one integer,
 * [feature:player] reads the well-known key directly. The two key constants,
 * [PREFS_NAME] and [KEY_SKIP_DURATION_SEC], MUST stay in sync with the
 * `PREFS_NAME` and `KEY_SKIP_DURATION` declared in `UserPreferencesRepository`.
 * When those keys change in `feature:settings`, update them here too.
 *
 * Default is 30 seconds, matching the default in
 * [xyz.libravault.core.domain.model.UserPreferences.defaultSkipDurationSec].
 *
 * @see xyz.libravault.feature.settings.UserPreferencesRepository
 */
@UnstableApi
object SkipDurationPreference {

    /** SharedPreferences file name. Must match `UserPreferencesRepository.PREFS_NAME`. */
    const val PREFS_NAME = "libravault_prefs"

    /** SharedPreferences key. Must match `UserPreferencesRepository.KEY_SKIP_DURATION`. */
    const val KEY_SKIP_DURATION_SEC = "skip_duration_sec"

    private const val DEFAULT_SKIP_DURATION_SEC = 30
    private const val MIN_SKIP_DURATION_SEC     = 5
    private const val MAX_SKIP_DURATION_SEC     = 120

    /** Returns the user's skip duration in milliseconds, clamped to [5, 120] seconds. */
    fun getSkipDurationMs(context: Context): Long {
        val sec = context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_SKIP_DURATION_SEC, DEFAULT_SKIP_DURATION_SEC)
            .coerceIn(MIN_SKIP_DURATION_SEC, MAX_SKIP_DURATION_SEC)
        return sec * 1_000L
    }
}
