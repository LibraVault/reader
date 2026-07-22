package xyz.libravault.core.logger

/**
 * Opt-in, local-only crash and error logger.
 *
 * Logs are written to the app's private storage — never transmitted
 * anywhere. The user explicitly enables logging via Settings.
 *
 * Platform-specific implementations handle actual log persistence:
 * - Android: Uses SharedPreferences for opt-in state, file rotation
 * - iOS: Uses UserDefaults for opt-in state, local file storage
 *
 * Usage:
 *   logger.d("FileScanner", "Scanning started")
 *   logger.e("FileScanner", "Failed to scan", throwable)
 */
expect class LibravaultLogger {
    var isEnabled: Boolean

    fun d(tag: String, message: String)
    fun i(tag: String, message: String)
    fun w(tag: String, message: String, throwable: Throwable? = null)
    fun e(tag: String, message: String, throwable: Throwable? = null)

    suspend fun readLogs(): String
    suspend fun clearLogs()
}
