package xyz.libravault.feature.vault

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import xyz.libravault.core.storage.LibravaultPreferences

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
 * [LibravaultPreferences] (`:core:storage`, already a dependency of both
 * `feature:vault` and `feature:settings`), so there's no manual
 * cross-module constant to keep in sync.
 *
 * Default is on (matches `UserPreferences.screenSecurityEnabled`'s default) —
 * a device with no explicit preference recorded yet gets the safer default,
 * not the weaker one.
 */
object VaultScreenSecurityPreference {

    fun isEnabled(context: Context): Boolean =
        isEnabled(context.getSharedPreferences(LibravaultPreferences.FILE_NAME, Context.MODE_PRIVATE))

    /** Pure variant taking an explicit [SharedPreferences] so this is
     * unit-testable without Robolectric. */
    fun isEnabled(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(LibravaultPreferences.KEY_SCREEN_SECURITY_ENABLED, true)

    /**
     * Reactive variant of [isEnabled] — emits the current value immediately,
     * then again every time the setting changes (e.g. via feature:settings'
     * toggle) for as long as the flow is collected. Same
     * [SharedPreferences.OnSharedPreferenceChangeListener] pattern as
     * `core.storage.SupporterRepository.observe`. Callers that instead read
     * [isEnabled] once (e.g. under `remember { }` with no key) only pick up a
     * changed setting the next time their composition is recreated — not
     * "immediately," despite [SecureScreenEffect]'s doc comment promising
     * that (issue #530 L5).
     */
    fun observe(prefs: SharedPreferences): Flow<Boolean> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == LibravaultPreferences.KEY_SCREEN_SECURITY_ENABLED) trySend(isEnabled(prefs))
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(isEnabled(prefs))
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    fun observe(context: Context): Flow<Boolean> =
        observe(context.getSharedPreferences(LibravaultPreferences.FILE_NAME, Context.MODE_PRIVATE))
}
