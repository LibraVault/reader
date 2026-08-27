package xyz.libravault.feature.player.service

import android.content.Context
import android.content.SharedPreferences
import xyz.libravault.core.storage.LibravaultPreferences

/**
 * Reads the user's "Pause vault audio when the app backgrounds" setting
 * (Phase 3, #508) from the shared `libravault_prefs` file.
 *
 * Same no-cross-module-dependency shape as [SkipDurationPreference] — the key
 * lives in [LibravaultPreferences], read directly here rather than depending
 * on `feature:settings`.
 *
 * Default is **true** (always pause), matching the unconditional behavior
 * [PlaybackService.vaultAutoStopObserver][xyz.libravault.feature.player.service.PlaybackService]
 * shipped in Phase 2 — see that observer's doc comment for why pausing
 * proactively is a correctness fix, not just a preference, for most users.
 * This toggle exists for the minority who understand and accept the race it
 * guards against (a mid-stream read can throw once the vault's VMK is
 * zeroed) and would rather audio keep playing through a brief backgrounding.
 */
object VaultStopOnLockPreference {

    fun isEnabled(context: Context): Boolean =
        isEnabled(context.getSharedPreferences(LibravaultPreferences.FILE_NAME, Context.MODE_PRIVATE))

    /** Pure variant taking an explicit [SharedPreferences] so this is
     * unit-testable without Robolectric. */
    fun isEnabled(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(LibravaultPreferences.KEY_VAULT_STOP_ON_LOCK, true)
}
