package xyz.libravault.feature.settings

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xyz.libravault.core.domain.model.AppReadingTheme
import xyz.libravault.core.domain.model.UserPreferences

/**
 * Unit tests for [UserPreferencesRepository.read] / [UserPreferencesRepository.update].
 *
 * These were previously only exercised indirectly via a fully-mocked
 * [UserPreferencesRepository] in [SettingsViewModelTest] — the actual default
 * values, enum parsing, and playback-speed snapping on read() were never run.
 */
class UserPreferencesRepositoryTest {

    private lateinit var mockPrefs: SharedPreferences
    private lateinit var mockEditor: SharedPreferences.Editor
    private lateinit var mockContext: Context
    private lateinit var repository: UserPreferencesRepository

    @BeforeEach
    fun setUp() {
        mockPrefs = mockk()
        mockEditor = mockk(relaxed = true)
        every { mockPrefs.edit() } returns mockEditor
        every { mockEditor.putString(any(), any()) } returns mockEditor
        every { mockEditor.putFloat(any(), any()) } returns mockEditor
        every { mockEditor.putInt(any(), any()) } returns mockEditor
        every { mockEditor.putBoolean(any(), any()) } returns mockEditor

        mockContext = mockk()
        every { mockContext.getSharedPreferences("libravault_prefs", Context.MODE_PRIVATE) } returns mockPrefs

        repository = UserPreferencesRepository(mockContext)
    }

    // ── read() defaults ──────────────────────────────────────────────────────

    @Test
    fun `read returns documented defaults when nothing is stored`() {
        every { mockPrefs.getString("reading_theme", "DARK") } returns null
        every { mockPrefs.getFloat("playback_speed", 1.0f) } returns 1.0f
        every { mockPrefs.getInt("skip_duration_sec", 30) } returns 30
        every { mockPrefs.getBoolean("logging_enabled", false) } returns false
        every { mockPrefs.getBoolean("dynamic_color", true) } returns true
        every { mockPrefs.getBoolean("screen_security_enabled", true) } returns true

        val prefs = repository.read()

        assertEquals(UserPreferences(), prefs)
    }

    @Test
    fun `read parses each AppReadingTheme value`() {
        every { mockPrefs.getFloat("playback_speed", 1.0f) } returns 1.0f
        every { mockPrefs.getInt("skip_duration_sec", 30) } returns 30
        every { mockPrefs.getBoolean("logging_enabled", false) } returns false
        every { mockPrefs.getBoolean("dynamic_color", true) } returns true
        every { mockPrefs.getBoolean("screen_security_enabled", true) } returns true

        for (theme in AppReadingTheme.values()) {
            every { mockPrefs.getString("reading_theme", "DARK") } returns theme.name
            assertEquals(theme, repository.read().defaultReadingTheme)
        }
    }

    @Test
    fun `read snaps a stored playback speed to the nearest quarter step`() {
        every { mockPrefs.getString("reading_theme", "DARK") } returns "DARK"
        every { mockPrefs.getFloat("playback_speed", 1.0f) } returns 1.1f
        every { mockPrefs.getInt("skip_duration_sec", 30) } returns 30
        every { mockPrefs.getBoolean("logging_enabled", false) } returns false
        every { mockPrefs.getBoolean("dynamic_color", true) } returns true
        every { mockPrefs.getBoolean("screen_security_enabled", true) } returns true

        // 1.1 snaps to the nearest quarter-step, 1.0 or 1.25 — never the raw 1.1.
        val result = repository.read().defaultPlaybackSpeed
        assertEquals(1.0f, result, 0.001f)
    }

    @Test
    fun `read reflects stored values`() {
        every { mockPrefs.getString("reading_theme", "DARK") } returns "SEPIA"
        every { mockPrefs.getFloat("playback_speed", 1.0f) } returns 1.5f
        every { mockPrefs.getInt("skip_duration_sec", 30) } returns 60
        every { mockPrefs.getBoolean("logging_enabled", false) } returns true
        every { mockPrefs.getBoolean("dynamic_color", true) } returns false
        every { mockPrefs.getBoolean("screen_security_enabled", true) } returns false

        val prefs = repository.read()

        assertEquals(
            UserPreferences(
                defaultReadingTheme = AppReadingTheme.SEPIA,
                defaultPlaybackSpeed = 1.5f,
                defaultSkipDurationSec = 60,
                loggingEnabled = true,
                dynamicColorEnabled = false,
                screenSecurityEnabled = false,
            ),
            prefs,
        )
    }

    // ── update() ─────────────────────────────────────────────────────────────

    @Test
    fun `update writes every field under its documented key`() {
        val stringKey = slot<String>()
        val stringValue = slot<String>()
        val floatKey = slot<String>()
        val floatValue = slot<Float>()
        val intKey = slot<String>()
        val intValue = slot<Int>()
        val boolKeys = mutableListOf<String>()
        val boolValues = mutableListOf<Boolean>()

        every { mockEditor.putString(capture(stringKey), capture(stringValue)) } returns mockEditor
        every { mockEditor.putFloat(capture(floatKey), capture(floatValue)) } returns mockEditor
        every { mockEditor.putInt(capture(intKey), capture(intValue)) } returns mockEditor
        every { mockEditor.putBoolean(capture(boolKeys), capture(boolValues)) } returns mockEditor

        repository.update(
            UserPreferences(
                defaultReadingTheme = AppReadingTheme.LIGHT,
                defaultPlaybackSpeed = 1.25f,
                defaultSkipDurationSec = 45,
                loggingEnabled = true,
                dynamicColorEnabled = false,
                screenSecurityEnabled = false,
            ),
        )

        assertEquals("reading_theme", stringKey.captured)
        assertEquals("LIGHT", stringValue.captured)
        assertEquals("playback_speed", floatKey.captured)
        assertEquals(1.25f, floatValue.captured, 0.001f)
        assertEquals("skip_duration_sec", intKey.captured)
        assertEquals(45, intValue.captured)
        assertEquals(listOf("logging_enabled", "dynamic_color", "screen_security_enabled"), boolKeys)
        assertEquals(listOf(true, false, false), boolValues)
        verify { mockEditor.apply() }
    }
}
