package xyz.libravault.feature.player.service

import android.content.Context
import androidx.media3.common.util.UnstableApi
import xyz.libravault.core.storage.LibravaultPreferences

/**
 * Reads the user's skip-duration setting (5–120 seconds) from the shared
 * `libravault_prefs` SharedPreferences file.
 *
 * The repository that owns this file lives in
 * [xyz.libravault.feature.settings.UserPreferencesRepository] — a different Gradle
 * module. To avoid forcing a cross-module dependency just to read one integer,
 * [feature:player] reads the well-known key directly. The key constants live
 * in [LibravaultPreferences] (in `:core:storage`, already a dependency of both
 * `feature:player` and `feature:settings`), so there is no manual
 * cross-module constant sync to maintain.
 *
 * Default is 30 seconds, matching the default in
 * [xyz.libravault.core.domain.model.UserPreferences.defaultSkipDurationSec].
 *
 * @see xyz.libravault.feature.settings.UserPreferencesRepository
 * @see xyz.libravault.core.storage.LibravaultPreferences
 */
@UnstableApi
object SkipDurationPreference {

    private const val DEFAULT_SKIP_DURATION_SEC = 30
    private const val MIN_SKIP_DURATION_SEC     = 5
    private const val MAX_SKIP_DURATION_SEC     = 120

    /** Returns the user's skip duration in milliseconds, clamped to [5, 120] seconds. */
    fun getSkipDurationMs(context: Context): Long {
        val sec = context
            .getSharedPreferences(LibravaultPreferences.FILE_NAME, Context.MODE_PRIVATE)
            .getInt(LibravaultPreferences.KEY_SKIP_DURATION_SEC, DEFAULT_SKIP_DURATION_SEC)
            .coerceIn(MIN_SKIP_DURATION_SEC, MAX_SKIP_DURATION_SEC)
        return sec * 1_000L
    }
}
