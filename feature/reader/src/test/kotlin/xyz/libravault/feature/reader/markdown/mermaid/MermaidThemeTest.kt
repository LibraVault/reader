package xyz.libravault.feature.reader.markdown.mermaid

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import xyz.libravault.core.ui.theme.ConcreteReadingTheme

class MermaidThemeTest {

    @Test
    fun `every concrete reading theme maps to a real Mermaid built-in theme name`() {
        assertEquals("default", mermaidThemeName(ConcreteReadingTheme.LIGHT))
        assertEquals("dark", mermaidThemeName(ConcreteReadingTheme.DARK))
        assertEquals("neutral", mermaidThemeName(ConcreteReadingTheme.SEPIA))
    }

    @Test
    fun `every ConcreteReadingTheme value has a mapping`() {
        // Fails to compile (exhaustive when) rather than silently defaulting if a new
        // ConcreteReadingTheme is ever added — this loop is just a runtime belt-and-braces
        // check that every value actually calls the function without throwing.
        for (theme in ConcreteReadingTheme.entries) {
            mermaidThemeName(theme)
        }
    }
}
