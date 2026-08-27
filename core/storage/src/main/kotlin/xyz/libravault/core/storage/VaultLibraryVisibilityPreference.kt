package xyz.libravault.core.storage

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Reads the user's "Show Encrypted Vault items in Library" setting (Phase 3,
 * #508) from the shared `libravault_prefs` file — same
 * key-lives-in-`LibravaultPreferences`, no-cross-module-dependency shape as
 * [VaultScreenSecurityPreference].
 *
 * Default is off: today Encrypted Vault items are 100% gateway-only (reachable
 * only via Settings → "Manage Encrypted Vaults"), and this setting is a pure
 * opt-in addition — a device with no explicit preference recorded yet keeps
 * that shipped behavior, not the newly-added one.
 *
 * Unlike [VaultScreenSecurityPreference], this one is read from `feature:library`
 * (a UI module, not `core:vaultstore`) via `LibraryViewModel`'s existing
 * `@ApplicationContext` — no new cross-module edge beyond the `core:storage`
 * dependency every feature module already has.
 */
object VaultLibraryVisibilityPreference {

    fun isEnabled(context: Context): Boolean =
        isEnabled(context.getSharedPreferences(LibravaultPreferences.FILE_NAME, Context.MODE_PRIVATE))

    /** Pure variant taking an explicit [SharedPreferences] so this is
     * unit-testable without Robolectric. */
    fun isEnabled(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(LibravaultPreferences.KEY_VAULT_LIBRARY_VISIBLE, false)

    /**
     * Reactive variant of [isEnabled] — emits the current value immediately,
     * then again every time the setting changes (e.g. via feature:settings'
     * toggle), for as long as the flow is collected. Same
     * [SharedPreferences.OnSharedPreferenceChangeListener] pattern as
     * [VaultScreenSecurityPreference.observe].
     */
    fun observe(prefs: SharedPreferences): Flow<Boolean> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == LibravaultPreferences.KEY_VAULT_LIBRARY_VISIBLE) trySend(isEnabled(prefs))
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(isEnabled(prefs))
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    fun observe(context: Context): Flow<Boolean> =
        observe(context.getSharedPreferences(LibravaultPreferences.FILE_NAME, Context.MODE_PRIVATE))
}
