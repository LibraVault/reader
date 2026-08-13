package xyz.libravault.feature.reader.markdown.mermaid

import xyz.libravault.core.ui.theme.ReadingTheme

/**
 * Maps LibraVault's reading theme to one of Mermaid's own built-in theme names, passed
 * to `mermaid.initialize({ theme: ... })` in mermaid_host.html (see MermaidDiagramView.kt).
 *
 * v1 scope: built-in theme names only, not custom `themeVariables` — SEPIA maps to
 * `neutral` as the closest stock theme (muted, low-contrast), not a hand-tuned colour
 * match for LibraVault's actual sepia palette (LibraVaultColorScheme.forReadingTheme).
 * A closer match is possible via themeVariables (Mermaid's per-colour override API) but
 * is deliberately deferred — not needed to prove the rendering pipeline itself works.
 */
internal fun mermaidThemeName(readingTheme: ReadingTheme): String = when (readingTheme) {
    ReadingTheme.LIGHT -> "default"
    ReadingTheme.DARK -> "dark"
    ReadingTheme.SEPIA -> "neutral"
}
