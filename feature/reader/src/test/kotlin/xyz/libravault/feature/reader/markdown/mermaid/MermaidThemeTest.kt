package xyz.libravault.feature.reader.markdown.mermaid

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import xyz.libravault.core.ui.theme.ReadingTheme

class MermaidThemeTest {

    @Test
    fun `every reading theme maps to a real Mermaid built-in theme name`() {
        assertEquals("default", mermaidThemeName(ReadingTheme.LIGHT))
        assertEquals("dark", mermaidThemeName(ReadingTheme.DARK))
        assertEquals("neutral", mermaidThemeName(ReadingTheme.SEPIA))
    }

    @Test
    fun `every ReadingTheme value has a mapping`() {
        // Fails to compile (exhaustive when) rather than silently defaulting if a new
        // ReadingTheme is ever added — this loop is just a runtime belt-and-braces
        // check that every value actually calls the function without throwing.
        for (theme in ReadingTheme.entries) {
            mermaidThemeName(theme)
        }
    }
}
