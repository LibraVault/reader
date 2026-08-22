package xyz.libravault.core.storage

import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import xyz.libravault.core.domain.model.AppReadingTheme

class ReadingThemePreferenceTest {

    @Test
    fun `read defaults to DARK when nothing is stored`() {
        val prefs = mockk<SharedPreferences>()
        every { prefs.getString(LibravaultPreferences.KEY_READING_THEME, AppReadingTheme.DARK.name) } returns null

        assertEquals(AppReadingTheme.DARK, ReadingThemePreference.read(prefs))
    }

    @Test
    fun `read parses each stored AppReadingTheme value`() {
        val prefs = mockk<SharedPreferences>()

        for (theme in AppReadingTheme.values()) {
            every { prefs.getString(LibravaultPreferences.KEY_READING_THEME, AppReadingTheme.DARK.name) } returns theme.name
            assertEquals(theme, ReadingThemePreference.read(prefs))
        }
    }

    @Test
    fun `write persists the theme name under the documented key and commits`() {
        val prefs = mockk<SharedPreferences>()
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        val key = slot<String>()
        val value = slot<String>()
        every { prefs.edit() } returns editor
        every { editor.putString(capture(key), capture(value)) } returns editor

        ReadingThemePreference.write(prefs, AppReadingTheme.SEPIA)

        assertEquals(LibravaultPreferences.KEY_READING_THEME, key.captured)
        assertEquals("SEPIA", value.captured)
        verify { editor.apply() }
    }
}
