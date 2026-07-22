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
)

enum class AppReadingTheme { DARK, LIGHT, SEPIA }

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
