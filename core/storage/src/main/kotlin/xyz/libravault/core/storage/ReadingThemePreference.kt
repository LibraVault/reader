package xyz.libravault.core.storage

import android.content.Context
import android.content.SharedPreferences
import xyz.libravault.core.domain.model.AppReadingTheme

/**
 * Reads/writes the user's default reading theme (`UserPreferences.defaultReadingTheme`)
 * directly from the shared `libravault_prefs` file.
 *
 * The repository that owns this preference lives in
 * [xyz.libravault.feature.settings.UserPreferencesRepository] — a different Gradle
 * module that neither `feature:reader` nor `feature:vault` depend on. Same
 * no-cross-module-dependency pattern as
 * [xyz.libravault.feature.player.service.SkipDurationPreference] and
 * [VaultScreenSecurityPreference]: the key constant
 * lives in [LibravaultPreferences] (already a dependency of `feature:settings`,
 * `feature:reader` and `feature:vault`), so there is no manual cross-module
 * constant to keep in sync.
 *
 * Unlike those two, this one also writes — the reader screens seed their initial
 * theme from here on open and write user in-reader theme changes straight back
 * (#428), so the choice survives closing the reader and matches whatever's shown
 * in Settings.
 */
object ReadingThemePreference {

    fun read(context: Context): AppReadingTheme =
        read(context.getSharedPreferences(LibravaultPreferences.FILE_NAME, Context.MODE_PRIVATE))

    /** Pure variant taking an explicit [SharedPreferences] so this is unit-testable
     * without Robolectric. */
    fun read(prefs: SharedPreferences): AppReadingTheme = AppReadingTheme.valueOf(
        prefs.getString(LibravaultPreferences.KEY_READING_THEME, AppReadingTheme.DARK.name)
            ?: AppReadingTheme.DARK.name
    )

    fun write(context: Context, theme: AppReadingTheme) {
        write(context.getSharedPreferences(LibravaultPreferences.FILE_NAME, Context.MODE_PRIVATE), theme)
    }

    /** Pure variant taking an explicit [SharedPreferences] so this is unit-testable
     * without Robolectric. */
    fun write(prefs: SharedPreferences, theme: AppReadingTheme) {
        prefs.edit().putString(LibravaultPreferences.KEY_READING_THEME, theme.name).apply()
    }
}
