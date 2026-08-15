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
 * @see xyz.libravault.feature.vault.VaultScreenSecurityPreference
 */
object LibravaultPreferences {
    /** SharedPreferences file name. */
    const val FILE_NAME = "libravault_prefs"

    /** Skip-duration preference key. Integer seconds, 5–120. */
    const val KEY_SKIP_DURATION_SEC = "skip_duration_sec"

    /** Screen Security preference key (PRD §7.3) — whether `FLAG_SECURE` is
     * applied while rendering decrypted Encrypted Vault content. Boolean,
     * default true (on). */
    const val KEY_SCREEN_SECURITY_ENABLED = "screen_security_enabled"

    /** Whether the one-time Folder-vs-Vault explainer has been shown. Only
     * `feature:vault` reads/writes this one, but it's centralized here
     * anyway (same reasoning as the other keys — one source of truth for
     * this shared file, not a magic string in `feature:vault`). */
    const val KEY_VAULT_EXPLAINER_SHOWN = "vault_explainer_shown"
}
