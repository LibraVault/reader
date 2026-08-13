package xyz.libravault.feature.reader.markdown.mermaid

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Exercises [mermaidSourceOrNull] against a real GFM AST — the same
 * `org.jetbrains:markdown-jvm` parser [com.mikepenz.markdown.m3.Markdown] uses
 * internally to render the document (see feature/reader/build.gradle.kts for why this
 * is a transitive dependency, not one added for testing) — rather than a hand-built
 * fake node tree that could silently drift from what the real parser actually
 * produces for a `CODE_FENCE` node's children.
 */
class MermaidBlockDetectorTest {

    private val parser = MarkdownParser(GFMFlavourDescriptor())

    /** Finds the first `CODE_FENCE` node anywhere in [source]'s real parsed tree. */
    private fun firstCodeFenceNode(source: String): ASTNode? {
        val root = parser.buildMarkdownTreeFromString(source)
        fun search(node: ASTNode): ASTNode? {
            if (node.type == MarkdownElementTypes.CODE_FENCE) return node
            for (child in node.children) {
                search(child)?.let { return it }
            }
            return null
        }
        return search(root)
    }

    @Test
    fun `a mermaid fence returns its body`() {
        val source = "```mermaid\ngraph TD\n  A --> B\n```"
        val node = firstCodeFenceNode(source)!!

        assertEquals("graph TD\n  A --> B", mermaidSourceOrNull(node, source))
    }

    @Test
    fun `a fence with no language is not a mermaid diagram`() {
        val source = "```\nplain code\n```"
        val node = firstCodeFenceNode(source)!!

        assertNull(mermaidSourceOrNull(node, source))
    }

    @Test
    fun `a fence with a different language is not a mermaid diagram`() {
        val source = "```kotlin\nval x = 1\n```"
        val node = firstCodeFenceNode(source)!!

        assertNull(mermaidSourceOrNull(node, source))
    }

    @Test
    fun `the language check is case-sensitive, matching GFM's own convention`() {
        val source = "```Mermaid\ngraph TD\n```"
        val node = firstCodeFenceNode(source)!!

        assertNull(mermaidSourceOrNull(node, source))
    }

    @Test
    fun `a fence info string with trailing attributes after the language still matches`() {
        // CommonMark's own convention: only the first whitespace-delimited token in a
        // fence's info string is the language.
        val source = "```mermaid title=\"Flow\"\ngraph TD\n```"
        val node = firstCodeFenceNode(source)!!

        assertEquals("graph TD", mermaidSourceOrNull(node, source))
    }

    @Test
    fun `a multi-line diagram body is reassembled with newlines in source order`() {
        val source = "```mermaid\nsequenceDiagram\n  Alice->>Bob: Hello\n  Bob-->>Alice: Hi\n```"
        val node = firstCodeFenceNode(source)!!

        assertEquals(
            "sequenceDiagram\n  Alice->>Bob: Hello\n  Bob-->>Alice: Hi",
            mermaidSourceOrNull(node, source),
        )
    }

    @Test
    fun `an empty mermaid fence returns an empty body, not null`() {
        val source = "```mermaid\n```"
        val node = firstCodeFenceNode(source)!!

        assertEquals("", mermaidSourceOrNull(node, source))
    }

    @Test
    fun `a document with no code fence at all has nothing to find`() {
        assertNull(firstCodeFenceNode("Just a paragraph, no code fence."))
    }
}
