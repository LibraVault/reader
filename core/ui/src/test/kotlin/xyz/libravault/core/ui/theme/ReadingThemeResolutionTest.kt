package xyz.libravault.core.ui.theme

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Plain-JVM coverage for [ReadingTheme.resolved] — the single place #349/#370's
 * SYSTEM→Dark/Light policy lives. [LibravaultThemeTest] separately covers that
 * [LibravaultTheme] actually renders the resolved theme (status-bar-icon proxy).
 */
class ReadingThemeResolutionTest {

    @Test
    fun `DARK LIGHT SEPIA and AMOLED pass through unchanged regardless of the system setting`() {
        for (systemInDarkTheme in listOf(true, false)) {
            assertEquals(ConcreteReadingTheme.DARK, ReadingTheme.DARK.resolved(systemInDarkTheme))
            assertEquals(ConcreteReadingTheme.LIGHT, ReadingTheme.LIGHT.resolved(systemInDarkTheme))
            assertEquals(ConcreteReadingTheme.SEPIA, ReadingTheme.SEPIA.resolved(systemInDarkTheme))
            assertEquals(ConcreteReadingTheme.AMOLED, ReadingTheme.AMOLED.resolved(systemInDarkTheme))
        }
    }

    @Test
    fun `SYSTEM resolves to DARK when the OS is in dark mode`() {
        assertEquals(ConcreteReadingTheme.DARK, ReadingTheme.SYSTEM.resolved(systemInDarkTheme = true))
    }

    @Test
    fun `SYSTEM resolves to LIGHT when the OS is in light mode`() {
        assertEquals(ConcreteReadingTheme.LIGHT, ReadingTheme.SYSTEM.resolved(systemInDarkTheme = false))
    }

    @Test
    fun `SYSTEM never resolves to SEPIA`() {
        // Sepia isn't one of the OS's two appearance choices — System can only ever
        // land on Dark or Light, for either value of the ambient setting.
        assertEquals(ConcreteReadingTheme.DARK, ReadingTheme.SYSTEM.resolved(true))
        assertEquals(ConcreteReadingTheme.LIGHT, ReadingTheme.SYSTEM.resolved(false))
    }

    @Test
    fun `SYSTEM never resolves to AMOLED`() {
        // Same reasoning as the Sepia case above (#420): true-black isn't one of the OS's
        // two appearance choices either — System can only ever land on Dark or Light.
        assertEquals(ConcreteReadingTheme.DARK, ReadingTheme.SYSTEM.resolved(true))
        assertEquals(ConcreteReadingTheme.LIGHT, ReadingTheme.SYSTEM.resolved(false))
    }

    @Test
    fun `every ReadingTheme value resolves without throwing`() {
        // Fails to compile (exhaustive when in resolved()) rather than silently
        // defaulting if a new ReadingTheme case is ever added.
        for (theme in ReadingTheme.entries) {
            theme.resolved(true)
            theme.resolved(false)
        }
    }
}
