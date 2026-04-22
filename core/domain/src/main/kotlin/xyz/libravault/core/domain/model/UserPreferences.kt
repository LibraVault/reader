package xyz.libravault.core.domain.model

import xyz.libravault.core.ui.theme.ReadingTheme

/**
 * App-wide user preferences persisted to SharedPreferences.
 * These are distinct from per-item [ReaderSettings] — they represent
 * global defaults the user sets in the Settings screen.
 */
data class UserPreferences(
    val defaultReadingTheme: ReadingTheme    = ReadingTheme.DARK,
    val defaultPlaybackSpeed: Float          = 1.0f,
    val defaultSkipDurationSec: Int          = 30,
    val loggingEnabled: Boolean              = false,
    val dynamicColorEnabled: Boolean         = true,  // Material You
)
