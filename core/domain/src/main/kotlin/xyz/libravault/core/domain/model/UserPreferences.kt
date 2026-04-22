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
