package xyz.libravault.core.ui.theme

import androidx.compose.ui.graphics.Color

// ── Brand — warm leather & aged gold ─────────────────────────────────────────
val LeatherBrown      = Color(0xFF8B5E3C)   // primary — mid leather (AA on parchment)
val LeatherDark       = Color(0xFF3D2208)   // primaryContainer — dark hide
val LeatherLight      = Color(0xFFE8C898)   // dark-mode primary — warm tan, AA on dark surface

// ── Accent — aged brass ───────────────────────────────────────────────────────
val AgedBrass         = Color(0xFFC9A24B)   // muted ochre for single-CTA accents

// ── Neutrals — warm, not cool grey ───────────────────────────────────────────
val WarmNeutral900    = Color(0xFF1A0E04)   // near-black leather
val WarmNeutral700    = Color(0xFF3D2208)   // dark brown (also used as light-mode outline)
val WarmNeutral500    = Color(0xFF8B6E47)   // mid warm brown (dark-mode outline, 3.6:1)
val WarmNeutral400    = Color(0xFFA89072)   // mid warm grey
val WarmNeutral300    = Color(0xFFD9C5A6)   // warm tan
val WarmNeutral200    = Color(0xFFE8D5BC)   // warm off-white
val WarmNeutral100    = Color(0xFFF5EDE0)   // parchment
val WarmNeutral50     = Color(0xFFFBF7F2)   // near-white warm

// Subdued grey for secondary text on dark — readable but not muddy
val WarmGrey400       = Color(0xFFB8A98E)

// ── Sepia (reading mode) ──────────────────────────────────────────────────────
val SepiaBackground   = Color(0xFFF5EDD6)
val SepiaText         = Color(0xFF3B2F1E)
val SepiaOutline      = Color(0xFF7A5C3E)   // 5.2:1 on SepiaBackground

// Muted secondary text for sepia (onSurfaceVariant). Deliberately a SOLID
// colour rather than SepiaText.copy(alpha = …): an alpha'd colour's contrast
// depends on whatever happens to be behind it, so the same token passed WCAG
// AA on `surface` while failing on the slightly darker `surfaceVariant` — and
// nothing caught it, because the contrast test ignored alpha. See
// ColorSchemeContrastTest. 5.1:1 on surfaceVariant, 6.4:1 on surface.
val SepiaTextMuted    = Color(0xFF60543F)

// ── Surface ramp (dark mode) — 3-step leather hierarchy ──────────────────────
val DarkSurface0      = Color(0xFF1A1410)   // background — deepest leather
val DarkSurface1      = Color(0xFF241B14)   // surface — cover brown
val DarkSurface2      = Color(0xFF2E231A)   // surfaceVariant — lifted card

// ── AMOLED (#420) — true-black reading theme ─────────────────────────────────
// background/surface are Color.Black itself (0xFF000000), not a named token here —
// see AmoledColorScheme's doc in Theme.kt for why. This is only the one step off pure
// black needed for card/divider hierarchy on top of it.
val AmoledSurfaceVariant = Color(0xFF141008)