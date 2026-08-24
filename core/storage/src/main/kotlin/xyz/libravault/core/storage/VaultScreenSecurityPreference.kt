package xyz.libravault.core.storage

import android.content.Context
import android.content.SharedPreferences

/**
 * Reads the user's "Screen Security" setting (PRD §7.3 — `FLAG_SECURE` while
 * rendering decrypted Encrypted Vault content) from the shared
 * `libravault_prefs` file.
 *
 * The repository that owns this preference lives in
 * [xyz.libravault.feature.settings.UserPreferencesRepository] — a different
 * Gradle module. Same no-cross-module-dependency pattern
 * `feature:player`'s `SkipDurationPreference` already uses for
 * `defaultSkipDurationSec`: the key constant lives in
 * [LibravaultPreferences] (this module, already a dependency of
 * `feature:vault`, `feature:reader`, and `feature:settings`), so there's no
 * manual cross-module constant to keep in sync.
 *
 * Default is on (matches `UserPreferences.screenSecurityEnabled`'s default) —
 * a device with no explicit preference recorded yet gets the safer default,
 * not the weaker one.
 *
 * Moved here from `feature:vault` by #505 — `feature:reader` needs it too
 * (a `ContentSource.VaultEntry`-backed `ReaderScreen` gates `SecureScreenEffect`
 * on it the same way `feature:vault`'s screens already did), and this type
 * has no vault-specific dependency, only [LibravaultPreferences].
 */
object VaultScreenSecurityPreference {

    fun isEnabled(context: Context): Boolean =
        isEnabled(context.getSharedPreferences(LibravaultPreferences.FILE_NAME, Context.MODE_PRIVATE))

    /** Pure variant taking an explicit [SharedPreferences] so this is
     * unit-testable without Robolectric. */
    fun isEnabled(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(LibravaultPreferences.KEY_SCREEN_SECURITY_ENABLED, true)
}
