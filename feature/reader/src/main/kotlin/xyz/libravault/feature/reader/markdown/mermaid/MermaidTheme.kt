package xyz.libravault.feature.reader.markdown.mermaid

import xyz.libravault.core.ui.theme.ConcreteReadingTheme

/**
 * Maps LibraVault's reading theme to one of Mermaid's own built-in theme names, passed
 * to `mermaid.initialize({ theme: ... })` in mermaid_host.html (see MermaidDiagramView.kt).
 *
 * Takes [ConcreteReadingTheme], not [xyz.libravault.core.ui.theme.ReadingTheme] — this is
 * one of the two call sites #370 found that switch over the reading theme and would
 * silently need a fallback for `SYSTEM` if it stayed. Resolving to [ConcreteReadingTheme]
 * before calling this means the compiler enforces resolution at the call site instead
 * (see [ConcreteReadingTheme]'s doc), the same shape as iOS's `mermaidThemeName(for:)`.
 *
 * v1 scope: built-in theme names only, not custom `themeVariables` — SEPIA maps to
 * `neutral` as the closest stock theme (muted, low-contrast), not a hand-tuned colour
 * match for LibraVault's actual sepia palette (LibraVaultColorScheme.forReadingTheme).
 * A closer match is possible via themeVariables (Mermaid's per-colour override API) but
 * is deliberately deferred — not needed to prove the rendering pipeline itself works.
 */
internal fun mermaidThemeName(readingTheme: ConcreteReadingTheme): String = when (readingTheme) {
    ConcreteReadingTheme.LIGHT -> "default"
    ConcreteReadingTheme.DARK -> "dark"
    ConcreteReadingTheme.SEPIA -> "neutral"
}
