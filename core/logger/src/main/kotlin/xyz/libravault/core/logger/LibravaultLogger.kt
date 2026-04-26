package xyz.libravault.core.logger

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Opt-in, local-only crash and error logger.
 *
 * Logs are written to the app's private files directory — never transmitted
 * anywhere. The user explicitly enables logging via Settings. Logs rotate
 * at [MAX_LOG_SIZE_BYTES] to prevent unbounded disk growth.
 *
 * Usage:
 *   logger.e("FileScanner", "Failed to scan $uri", throwable)
 */
@Singleton
class LibravaultLogger @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        private const val LOG_FILE_NAME    = "libravault.log"
        private const val MAX_LOG_SIZE_BYTES = 512 * 1024L  // 512 KB
        private const val TAG              = "LibraVault"

        private val TIMESTAMP_FMT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
            .withZone(ZoneId.systemDefault())
    }

    private val logFile: File
        get() = File(context.filesDir, LOG_FILE_NAME)

    /** Whether the user has opted in to local logging. Backed by SharedPreferences. */
    var isEnabled: Boolean
        get() = context
            .getSharedPreferences("libravault_prefs", Context.MODE_PRIVATE)
            .getBoolean("logging_enabled", false)
        set(value) = context
            .getSharedPreferences("libravault_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("logging_enabled", value).apply()

    fun d(tag: String, message: String) = write("D", tag, message, null)
    fun i(tag: String, message: String) = write("I", tag, message, null)
    fun w(tag: String, message: String, throwable: Throwable? = null) = write("W", tag, message, throwable)
    fun e(tag: String, message: String, throwable: Throwable? = null) = write("E", tag, message, throwable)

    private fun write(level: String, tag: String, message: String, throwable: Throwable?) {
        // Always log to Logcat in debug builds
        when (level) {
            "D" -> Log.d(TAG, "[$tag] $message")
            "I" -> Log.i(TAG, "[$tag] $message")
            "W" -> Log.w(TAG, "[$tag] $message", throwable)
            "E" -> Log.e(TAG, "[$tag] $message", throwable)
        }

        if (!isEnabled) return

        val timestamp = TIMESTAMP_FMT.format(Instant.now())
        val stackTrace = throwable?.stackTraceToString()?.let { "\n$it" } ?: ""
        val line = "$timestamp [$level/$tag] $message$stackTrace\n"

        runCatching {
            rotateIfNeeded()
            logFile.appendText(line)
        }
    }

    private fun rotateIfNeeded() {
        if (logFile.exists() && logFile.length() > MAX_LOG_SIZE_BYTES) {
            val archive = File(context.filesDir, "libravault.log.bak")
            logFile.copyTo(archive, overwrite = true)
            logFile.delete()
        }
    }

    /** Returns log contents for user-initiated sharing / inspection. */
    suspend fun readLogs(): String = withContext(Dispatchers.IO) {
        if (!logFile.exists()) "No logs recorded." else logFile.readText()
    }

    /** Clears all local logs. */
    suspend fun clearLogs() = withContext(Dispatchers.IO) {
        logFile.delete()
        File(context.filesDir, "libravault.log.bak").delete()
    }
}
