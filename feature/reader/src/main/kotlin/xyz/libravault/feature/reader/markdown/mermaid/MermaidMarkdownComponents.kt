package xyz.libravault.feature.reader.markdown.mermaid

import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.components.MarkdownComponents
import com.mikepenz.markdown.compose.components.markdownComponents
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import xyz.libravault.core.ui.theme.ReadingTheme

/**
 * Overrides [MarkdownComponents.codeFence] to render a ```mermaid``` fence as a real
 * diagram (#121) via [MermaidDiagramView], falling back to `defaults.codeFence` — the
 * library's own real default rendering, obtained by constructing a
 * [markdownComponents] with no overrides at all — for every other fenced code block,
 * so nothing about normal code-block rendering changes.
 *
 * `codeFence` is the only customization point this needs: [mermaidSourceOrNull] reads
 * straight from [MarkdownComponentModel.node]/`.content`, the same pair the library's
 * own default implementation uses internally, so no other slot needs touching.
 */
@Composable
fun rememberMermaidMarkdownComponents(readingTheme: ReadingTheme): MarkdownComponents {
    val defaults = remember { markdownComponents() }
    return remember(defaults, readingTheme) {
        markdownComponents(
            codeFence = { model: MarkdownComponentModel ->
                val source = mermaidSourceOrNull(model.node, model.content)
                if (source != null) {
                    MermaidDiagramView(source = source, readingTheme = readingTheme)
                } else {
                    defaults.codeFence(this, model)
                }
            },
        )
    }
}
