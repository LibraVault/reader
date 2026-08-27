package xyz.libravault.core.storage

/**
 * Shared SharedPreferences identifiers for the app-wide `libravault_prefs` file.
 *
 * The player feature module reads the user's skip-duration setting directly
 * from this file rather than depending on `feature:settings`, so both modules
 * MUST agree on the file name and key. Centralizing them here gives compile-time
 * enforcement (any module that needs them transitively depends on `:core:storage`,
 * which both `feature:player` and `feature:settings` already do).
 *
 * @see xyz.libravault.feature.settings.UserPreferencesRepository
 * @see xyz.libravault.feature.player.service.SkipDurationPreference
 * @see VaultScreenSecurityPreference
 * @see ReadingThemePreference
 */
object LibravaultPreferences {
    /** SharedPreferences file name. */
    const val FILE_NAME = "libravault_prefs"

    /** Skip-duration preference key. Integer seconds, 5–120. */
    const val KEY_SKIP_DURATION_SEC = "skip_duration_sec"

    /** Default reading-theme preference key (#428). String, one of
     * [xyz.libravault.core.domain.model.AppReadingTheme]'s names, default "DARK". */
    const val KEY_READING_THEME = "reading_theme"

    /** Screen Security preference key (PRD §7.3) — whether `FLAG_SECURE` is
     * applied while rendering decrypted Encrypted Vault content. Boolean,
     * default true (on). */
    const val KEY_SCREEN_SECURITY_ENABLED = "screen_security_enabled"

    /** Whether the one-time Folder-vs-Vault explainer has been shown. Only
     * `feature:vault` reads/writes this one, but it's centralized here
     * anyway (same reasoning as the other keys — one source of truth for
     * this shared file, not a magic string in `feature:vault`). */
    const val KEY_VAULT_EXPLAINER_SHOWN = "vault_explainer_shown"

    /** Phase 3 (#508) — whether unlocked Encrypted Vault items appear in the
     * main Library list, not just via "Manage Encrypted Vaults". Boolean,
     * default false (gateway-only, matches the behavior shipped before this
     * setting existed). @see VaultLibraryVisibilityPreference */
    const val KEY_VAULT_LIBRARY_VISIBLE = "vault_library_visible"

    /** Phase 3 (#508) — whether the lock-screen/notification for vault audio
     * shows the real title/author instead of a generic "Vault" placeholder.
     * Boolean, default false (placeholder). Read directly by `feature:player`.
     * @see xyz.libravault.feature.player.service.VaultNotificationMetadataPreference */
    const val KEY_VAULT_NOTIFICATION_REAL_METADATA = "vault_notification_real_metadata"

    /** Phase 3 (#508) — whether vault audio pauses automatically when the app
     * backgrounds (and the vault auto-locks). Boolean, default true (matches
     * the always-pause behavior shipped in Phase 2). Read directly by
     * `feature:player`. @see xyz.libravault.feature.player.service.VaultStopOnLockPreference */
    const val KEY_VAULT_STOP_ON_LOCK = "vault_stop_on_lock"
}
