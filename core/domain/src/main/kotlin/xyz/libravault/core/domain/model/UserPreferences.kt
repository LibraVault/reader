package xyz.libravault.core.domain.model

/**
 * App-wide user preferences persisted to SharedPreferences.
 * These are distinct from per-item reader settings — they represent
 * global defaults the user sets in the Settings screen.
 *
 * Note: ReadingTheme is intentionally duplicated here as [AppReadingTheme]
 * rather than imported from core:ui — core:domain must remain free of
 * Android/UI dependencies to stay Kotlin Multiplatform compatible.
 */
data class UserPreferences(
    val defaultReadingTheme: AppReadingTheme  = AppReadingTheme.DARK,
    val defaultPlaybackSpeed: Float           = 1.0f,
    val defaultSkipDurationSec: Int           = 30,
    val loggingEnabled: Boolean               = false,
    val dynamicColorEnabled: Boolean          = true,
    /** PRD §7.3 — `FLAG_SECURE` while rendering decrypted Encrypted Vault
     * content (blocks screenshots/screen recording/non-secure mirroring).
     * Default on; a global toggle, not per-vault (implementation plan's
     * reasoning: if you have multiple vaults you almost certainly want the
     * same posture for all of them). Read directly by `feature:vault` via
     * `core.storage.LibravaultPreferences`'s shared key, the same
     * no-cross-module-dependency pattern `defaultSkipDurationSec` uses for
     * `feature:player`. */
    val screenSecurityEnabled: Boolean        = true,
    /** Phase 3 (#508) — whether unlocked Encrypted Vault items appear in the
     * main Library list, not just via "Manage Encrypted Vaults". Default
     * false: a pure opt-in addition, zero behavior change for anyone who
     * doesn't touch this setting. Read directly by `feature:library` via
     * `core.storage.LibravaultPreferences`'s shared key, same pattern as
     * [screenSecurityEnabled]. */
    val vaultLibraryVisible: Boolean          = false,
    /** Phase 3 (#508) — whether the lock-screen/notification for vault audio
     * shows the real title/author instead of a generic "Vault" placeholder.
     * Default false (placeholder) — unlike [screenSecurityEnabled], the safer
     * default here is the newly-added, more private option, not today's
     * shipped behavior (which had no toggle at all). Read directly by
     * `feature:player`. */
    val vaultNotificationRealMetadata: Boolean = false,
    /** Phase 3 (#508) — whether vault audio pauses automatically when the app
     * backgrounds. Default true, matching the always-pause behavior shipped
     * in Phase 2 (a correctness fix, not just a preference — see
     * `PlaybackService.vaultAutoStopObserver`'s doc). Read directly by
     * `feature:player`. */
    val vaultStopOnLock: Boolean               = true,
)

/**
 * [SYSTEM] (#349/#370) follows the OS-level light/dark appearance setting rather than a
 * fixed choice; resolved to a concrete [xyz.libravault.core.ui.theme.ConcreteReadingTheme]
 * at render time in core:ui (`ReadingTheme.resolved`), mirroring the UI-layer enum this one
 * is intentionally duplicated from. Default for new installs stays [DARK], matching the
 * iOS decision (#374) — System is an available choice, not the default.
 *
 * [AMOLED] (#420) is a distinct 5th option — a true-black (#000000) page background for
 * OLED/AMOLED screens, not a replacement for [DARK]. See
 * [xyz.libravault.core.ui.theme.ReadingTheme]'s doc for the full rationale; duplicated
 * here only because core:domain must stay free of Android/UI dependencies.
 */
enum class AppReadingTheme { DARK, LIGHT, SEPIA, AMOLED, SYSTEM }

// ── Playback speed helpers ───────────────────────────────────────────────────

/** Valid increments: 0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0, 2.5, 3.0 */
fun snapPlaybackSpeed(speed: Float): Float {
    val quarterSteps = kotlin.math.round(speed * 4f)
    return (quarterSteps / 4f).coerceIn(0.5f, 3.0f)
}

/** Formats snapped speed as e.g. "0.75×", "1×", "1.25×", "2.5×" */
fun formatPlaybackSpeed(speed: Float): String {
    val snapped = snapPlaybackSpeed(speed)
    // Epsilon-based comparison to avoid floating-point precision issues
    // where 1.0f % 1f produces 0.99999994f instead of 0f
    val diff = snapped - snapped.toInt()
    return if (kotlin.math.abs(diff) < 0.001f) "${snapped.toInt()}×"
    else String.format(java.util.Locale.ROOT, "%.2g×", snapped).replace(",", ".")
}
