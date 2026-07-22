package xyz.libravault.core.logger

/**
 * iOS stub implementation. Full implementation deferred to Phase B.
 *
 * For now: minimal no-op implementation to allow iOS builds to succeed.
 * Phase B will add proper os_log integration and UserDefaults backing.
 */
actual class LibravaultLogger {
    actual var isEnabled: Boolean = false

    actual fun d(tag: String, message: String) {}
    actual fun i(tag: String, message: String) {}
    actual fun w(tag: String, message: String, throwable: Throwable?) {}
    actual fun e(tag: String, message: String, throwable: Throwable?) {}

    actual suspend fun readLogs(): String = "iOS logging not yet implemented."
    actual suspend fun clearLogs() {}
}
