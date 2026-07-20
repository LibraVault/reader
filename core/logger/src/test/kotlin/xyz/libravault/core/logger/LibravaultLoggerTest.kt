package xyz.libravault.core.logger

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class LibravaultLoggerTest {

    private lateinit var tempDir: File
    private lateinit var mockContext: Context
    private lateinit var mockPrefs: SharedPreferences
    private lateinit var mockPrefsEditor: SharedPreferences.Editor

    @BeforeEach
    fun setUp(@TempDir dir: File) {
        tempDir = dir

        mockPrefs = mockk<SharedPreferences>()
        mockPrefsEditor = mockk<SharedPreferences.Editor>(relaxed = true)

        every { mockPrefs.getBoolean("logging_enabled", false) } returns false
        every { mockPrefs.edit() } returns mockPrefsEditor
        every { mockPrefsEditor.putBoolean(any(), any()) } returns mockPrefsEditor
        every { mockPrefsEditor.apply() } answers { /* no-op */ }

        mockContext = mockk<Context>()
        every { mockContext.getSharedPreferences("libravault_prefs", Context.MODE_PRIVATE) } returns mockPrefs
        every { mockContext.filesDir } returns tempDir

        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
    }

    // ── isEnabled get/set ────────────────────────────────────────────────────

    @Test
    fun `isEnabled reads from SharedPreferences`() {
        every { mockPrefs.getBoolean("logging_enabled", false) } returns true
        val logger = LibravaultLogger(mockContext)

        assertTrue(logger.isEnabled)
    }

    @Test
    fun `isEnabled set writes to SharedPreferences`() {
        val logger = LibravaultLogger(mockContext)

        logger.isEnabled = true

        verify { mockPrefsEditor.putBoolean("logging_enabled", true) }
        verify { mockPrefsEditor.apply() }
    }

    // ── Logging when disabled ────────────────────────────────────────────────

    @Test
    fun `write does not touch log file when isEnabled is false`() = runTest {
        every { mockPrefs.getBoolean("logging_enabled", false) } returns false
        val logger = LibravaultLogger(mockContext)

        logger.d("tag", "message")
        logger.i("tag", "info")
        logger.w("tag", "warning")
        logger.e("tag", "error")

        // Log to Logcat should still happen
        verify { Log.d(any(), any()) }

        // But file should not be touched
        assertFalse(File(tempDir, "libravault.log").exists())
    }

    // ── Logging when enabled ─────────────────────────────────────────────────

    @Test
    fun `write appends log lines when isEnabled is true`() = runTest {
        every { mockPrefs.getBoolean("logging_enabled", false) } returns true
        val logger = LibravaultLogger(mockContext)

        logger.d("MyTag", "Debug message")
        logger.i("MyTag", "Info message")

        val logFile = File(tempDir, "libravault.log")
        assertTrue(logFile.exists())
        val content = logFile.readText()
        assertTrue(content.contains("[D/MyTag] Debug message"))
        assertTrue(content.contains("[I/MyTag] Info message"))
    }

    @Test
    fun `write includes throwable stack trace when provided`() = runTest {
        every { mockPrefs.getBoolean("logging_enabled", false) } returns true
        val logger = LibravaultLogger(mockContext)
        val exception = RuntimeException("Test error")

        logger.e("MyTag", "Error occurred", exception)

        val logFile = File(tempDir, "libravault.log")
        val content = logFile.readText()
        assertTrue(content.contains("[E/MyTag] Error occurred"))
        assertTrue(content.contains("java.lang.RuntimeException: Test error"))
    }

    // ── Log rotation ─────────────────────────────────────────────────────────

    @Test
    fun `rotateIfNeeded archives log to bak when exceeding 512 KB`() = runTest {
        every { mockPrefs.getBoolean("logging_enabled", false) } returns true
        val logger = LibravaultLogger(mockContext)

        val logFile = File(tempDir, "libravault.log")
        // Write > 512 KB
        val largeLine = "x".repeat(1000) + "\n"
        logFile.writeText(largeLine.repeat(600))

        logger.d("MyTag", "Trigger rotation")

        val bakFile = File(tempDir, "libravault.log.bak")
        assertTrue(bakFile.exists())
        assertTrue(logFile.length() < 2000) // Original file truncated
    }

    // ── readLogs ─────────────────────────────────────────────────────────────

    @Test
    fun `readLogs returns log file contents when file exists`() = runTest {
        val logFile = File(tempDir, "libravault.log")
        logFile.writeText("log line 1\nlog line 2\n")

        val logger = LibravaultLogger(mockContext)
        val content = logger.readLogs()

        assertEquals("log line 1\nlog line 2\n", content)
    }

    @Test
    fun `readLogs returns default message when file does not exist`() = runTest {
        val logger = LibravaultLogger(mockContext)
        val content = logger.readLogs()

        assertEquals("No logs recorded.", content)
    }

    // ── clearLogs ────────────────────────────────────────────────────────────

    @Test
    fun `clearLogs deletes both log and bak files`() = runTest {
        every { mockPrefs.getBoolean("logging_enabled", false) } returns true
        val logger = LibravaultLogger(mockContext)

        val logFile = File(tempDir, "libravault.log")
        val bakFile = File(tempDir, "libravault.log.bak")
        logFile.writeText("data")
        bakFile.writeText("backup")

        assertTrue(logFile.exists())
        assertTrue(bakFile.exists())

        logger.clearLogs()

        assertFalse(logFile.exists())
        assertFalse(bakFile.exists())
    }
}
