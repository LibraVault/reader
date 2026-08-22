package xyz.libravault.feature.reader.markdown

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.MarkdownTypography
import androidx.compose.ui.text.font.FontFamily as ComposeFontFamily
import xyz.libravault.core.ui.theme.OpenDyslexicFontFamily
import xyz.libravault.feature.reader.FontFamily as ReaderFontFamily
import xyz.libravault.feature.reader.ReaderSettings

/**
 * Extra letter-spacing (in sp) bundled with [ReaderFontFamily.OPEN_DYSLEXIC] (#423)
 * — same rationale as `epub.DYSLEXIA_FRIENDLY_LETTER_SPACING`: dyslexia-friendly
 * typography guidance recommends generous letter-spacing alongside the typeface
 * itself, not the font alone. Added to whatever the base style already specifies
 * (`TextUnit` has no `+` operator, so this stays a raw Float added via `.value`
 * — see `scaled()` below).
 */
private const val DYSLEXIA_FRIENDLY_LETTER_SPACING_SP = 0.4f

/**
 * Adapts mikepenz's Material3-derived default typography to [ReaderSettings]'s font
 * family / size / line-spacing preferences.
 *
 * Reading-theme colors need no adapter here: mikepenz's `markdownColor()` defaults
 * already read `MaterialTheme.colorScheme`, and `LibravaultTheme` — which wraps the
 * whole reader screen in ReaderScreen.kt — already swaps that color scheme for
 * light/dark/sepia. Theme color propagates for free; only typography needs plumbing.
 *
 * Mirrors [com.mikepenz.markdown.m3.markdownTypography]'s own default TextStyle
 * sourcing (same MaterialTheme.typography.* role for role) so this stays a thin
 * override rather than a parallel, hardcoded style sheet.
 */
@Composable
fun rememberMarkdownTypography(settings: ReaderSettings): MarkdownTypography {
    val composeFontFamily = when (settings.fontFamily) {
        ReaderFontFamily.SYSTEM        -> ComposeFontFamily.Default
        ReaderFontFamily.SERIF         -> ComposeFontFamily.Serif
        ReaderFontFamily.SANS_SERIF    -> ComposeFontFamily.SansSerif
        ReaderFontFamily.MONOSPACE     -> ComposeFontFamily.Monospace
        ReaderFontFamily.OPEN_DYSLEXIC -> OpenDyslexicFontFamily
    }

    fun TextStyle.scaled(overrideFontFamily: ComposeFontFamily = composeFontFamily): TextStyle = copy(
        fontFamily = overrideFontFamily,
        fontSize = fontSize * settings.fontSize,
        lineHeight = if (lineHeight.isSpecified) {
            lineHeight * settings.fontSize * settings.lineSpacing
        } else {
            lineHeight
        },
        // Only bumped for styles actually using the resolved font-family preference
        // (overrideFontFamily == composeFontFamily) — code/inlineCode below force
        // Monospace regardless of settings.fontFamily and should stay unaffected.
        letterSpacing = if (overrideFontFamily == composeFontFamily &&
            settings.fontFamily == ReaderFontFamily.OPEN_DYSLEXIC
        ) {
            val base = if (letterSpacing.isSpecified) letterSpacing.value else 0f
            (base + DYSLEXIA_FRIENDLY_LETTER_SPACING_SP).sp
        } else {
            letterSpacing
        },
    )

    val typography = MaterialTheme.typography
    val bodyLarge = typography.bodyLarge.scaled()

    return markdownTypography(
        h1 = typography.displayLarge.scaled(),
        h2 = typography.displayMedium.scaled(),
        h3 = typography.displaySmall.scaled(),
        h4 = typography.headlineMedium.scaled(),
        h5 = typography.headlineSmall.scaled(),
        h6 = typography.titleLarge.scaled(),
        text = bodyLarge,
        code = typography.bodyMedium.scaled(overrideFontFamily = ComposeFontFamily.Monospace),
        // bodyLarge may already carry the OpenDyslexic letter-spacing bump (#423) —
        // reset it explicitly here rather than inheriting it onto Monospace, which
        // was never meant to receive it (same reasoning as `code` above, which gets
        // a fresh, unbumped .scaled() call instead of copying from bodyLarge).
        inlineCode = bodyLarge.copy(
            fontFamily = ComposeFontFamily.Monospace,
            letterSpacing = TextUnit.Unspecified,
        ),
        quote = typography.bodyMedium.scaled().plus(SpanStyle(fontStyle = FontStyle.Italic)),
        paragraph = bodyLarge,
        ordered = bodyLarge,
        bullet = bodyLarge,
        list = bodyLarge,
        link = bodyLarge.copy(fontWeight = FontWeight.Bold, textDecoration = TextDecoration.Underline),
        // GFM table cell text — new in the 0.32.0 renderer pin (see
        // feature/reader/build.gradle.kts). Reuses bodyLarge rather than a smaller
        // style: table content is regular reading content, not chrome, so it should
        // respect the same user font-size preference as everything else.
        table = bodyLarge,
    )
}
