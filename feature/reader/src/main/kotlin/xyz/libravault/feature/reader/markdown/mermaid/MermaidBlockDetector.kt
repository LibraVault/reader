package xyz.libravault.feature.reader.markdown.mermaid

import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.ast.getTextInNode

/**
 * Detects a GFM fenced code block whose info string is exactly `mermaid` (```mermaid`)
 * and extracts its body — the block-detection half of #121, Phase 1.
 *
 * A plain function over [ASTNode]/`content` (the same pair
 * [com.mikepenz.markdown.compose.components.MarkdownComponentModel] bundles) rather
 * than something coupled to that Compose-only type, so it's testable against a real
 * parsed AST — `org.jetbrains:markdown-jvm` is already a transitive dependency of the
 * renderer this module pins (see feature/reader/build.gradle.kts), not a new one added
 * for this — with no Compose host needed. See MermaidBlockDetectorTest.
 *
 * [node] is expected to be a `MarkdownElementTypes.CODE_FENCE` node — the same node
 * type [com.mikepenz.markdown.compose.components.MarkdownComponents.codeFence] receives,
 * which is the actual integration point (see MarkdownComponents.kt in this package).
 * [content] is the full text `node`'s offsets are relative to.
 *
 * Returns null for any fence whose language isn't exactly `mermaid` (case-sensitive,
 * matching GFM's own case-sensitive info-string convention — ` ```Mermaid` is a
 * regular, unrecognized-language code block, not a diagram) — including a fence with
 * no language at all, and non-fence nodes.
 */
internal fun mermaidSourceOrNull(node: ASTNode, content: String): String? {
    val language = node.findChildOfType(MarkdownTokenTypes.FENCE_LANG)
        ?.getTextInNode(content)
        ?.toString()
        ?.trim()
        // A fence info string can carry more than the language (e.g. a filename or
        // attributes after a space, same convention CommonMark itself documents) —
        // only the first token is the language.
        ?.substringBefore(' ')
        ?: return null

    if (language != "mermaid") return null

    // CODE_FENCE_CONTENT nodes are one per source line, with no trailing newline of
    // their own (see JetBrains/markdown's own HtmlGenerator, which reconstructs lines
    // the same way) — joining with "\n" reassembles the original multi-line body.
    return node.children
        .filter { it.type == MarkdownTokenTypes.CODE_FENCE_CONTENT }
        .joinToString("\n") { it.getTextInNode(content) }
}
