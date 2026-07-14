package xyz.libravault.feature.player.service

import android.content.Context
import android.content.SharedPreferences
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
 * Note: previously this object was annotated `@OptIn(UnstableApi::class)`. That was
 * a copy-paste mistake — the class has nothing to do with Media3, only with
 * `SharedPreferences` and arithmetic — and it propagated a misleading opt-in
 * requirement to every consumer. Removed in the second review pass.
 *
 * @see xyz.libravault.feature.settings.UserPreferencesRepository
 * @see xyz.libravault.core.storage.LibravaultPreferences
 */
object SkipDurationPreference {

    /** Default skip duration in seconds, matching `UserPreferences.defaultSkipDurationSec`. */
    const val DEFAULT_SKIP_DURATION_SEC = 30
    private const val MIN_SKIP_DURATION_SEC = 5
    private const val MAX_SKIP_DURATION_SEC = 120

    /**
     * Returns the user's skip duration in milliseconds, clamped to [5, 120] seconds.
     *
     * Reads from the standard `libravault_prefs` [SharedPreferences] file. The
     * first read after process start loads the prefs file from disk; subsequent
     * reads are in-memory ([SharedPreferences] is cached internally by the
     * platform), so calling this on every ±seek tap is cheap.
     */
    fun getSkipDurationMs(context: Context): Long =
        getSkipDurationMs(
            context.getSharedPreferences(LibravaultPreferences.FILE_NAME, Context.MODE_PRIVATE),
        )

    /**
     * Pure variant that takes an explicit [SharedPreferences] so the clamp logic
     * can be unit-tested without Robolectric. Production callers should use the
     * [Context] overload above.
     */
    fun getSkipDurationMs(prefs: SharedPreferences): Long {
        val sec = prefs
            .getInt(LibravaultPreferences.KEY_SKIP_DURATION_SEC, DEFAULT_SKIP_DURATION_SEC)
            .coerceIn(MIN_SKIP_DURATION_SEC, MAX_SKIP_DURATION_SEC)
        return sec * 1_000L
    }
}
