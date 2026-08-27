package xyz.libravault.feature.player.service

import android.content.Context
import android.content.SharedPreferences
import xyz.libravault.core.storage.LibravaultPreferences

/**
 * Reads the user's "Show real title/author in vault notifications" setting
 * (Phase 3, #508) from the shared `libravault_prefs` file.
 *
 * Same no-cross-module-dependency shape as [SkipDurationPreference]: the
 * repository that owns this preference lives in
 * [xyz.libravault.feature.settings.UserPreferencesRepository] — a different
 * Gradle module — so `feature:player` reads the well-known key directly via
 * [LibravaultPreferences] rather than taking on a dependency just to read one
 * boolean.
 *
 * Plain [Context]/[SharedPreferences] reader, not a [kotlinx.coroutines.flow.Flow]
 * like [xyz.libravault.core.storage.VaultScreenSecurityPreference] — this is
 * read once per [androidx.media3.common.MediaItem] build
 * ([PlayerViewModel.buildMediaItem][xyz.libravault.feature.player.PlayerViewModel]),
 * not continuously rendered by a composable, so there is nothing to observe.
 *
 * Default is **false** (generic "Vault" placeholder) — the opposite polarity
 * from [xyz.libravault.core.storage.VaultScreenSecurityPreference]'s
 * default-on, because here the safer default is to *not* leak the real
 * title/author onto a lock screen anyone standing near the device can read,
 * matching [PlayerViewModel.buildMediaItem]'s own doc comment (Phase 2
 * shipped the real-title behavior only as an explicit placeholder pending
 * this exact toggle).
 */
object VaultNotificationMetadataPreference {

    fun isEnabled(context: Context): Boolean =
        isEnabled(context.getSharedPreferences(LibravaultPreferences.FILE_NAME, Context.MODE_PRIVATE))

    /** Pure variant taking an explicit [SharedPreferences] so this is
     * unit-testable without Robolectric. */
    fun isEnabled(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(LibravaultPreferences.KEY_VAULT_NOTIFICATION_REAL_METADATA, false)
}
