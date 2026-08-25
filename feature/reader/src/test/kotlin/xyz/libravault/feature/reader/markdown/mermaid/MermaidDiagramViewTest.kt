package xyz.libravault.feature.reader.markdown.mermaid

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MermaidDiagramViewTest {

    @Test
    fun `height under the cap passes through unchanged`() {
        assertEquals(0f, clampMermaidHeightPx(0f))
        assertEquals(120f, clampMermaidHeightPx(120f))
        assertEquals(MAX_MERMAID_HEIGHT_PX, clampMermaidHeightPx(MAX_MERMAID_HEIGHT_PX))
    }

    @Test
    fun `a hostile diagram reporting an absurd height is clamped to the cap`() {
        assertEquals(MAX_MERMAID_HEIGHT_PX, clampMermaidHeightPx(1_000_000f))
        assertEquals(MAX_MERMAID_HEIGHT_PX, clampMermaidHeightPx(Float.MAX_VALUE))
    }

    @Test
    fun `a negative reported height is clamped to zero, not left negative`() {
        assertEquals(0f, clampMermaidHeightPx(-50f))
    }

    @Test
    fun `paths under the mermaid asset subfolder are allowed`() {
        assertTrue(isMermaidAssetPath("/assets/mermaid/mermaid_host.html"))
        assertTrue(isMermaidAssetPath("/assets/mermaid/mermaid.min.js"))
    }

    @Test
    fun `paths outside the mermaid asset subfolder are rejected`() {
        assertFalse(isMermaidAssetPath("/assets/some_other_module_asset.txt"))
        assertFalse(isMermaidAssetPath("/assets/"))
        assertFalse(isMermaidAssetPath("/assets"))
        assertFalse(isMermaidAssetPath("/other/mermaid/mermaid_host.html"))
        assertFalse(isMermaidAssetPath(null))
    }
}
