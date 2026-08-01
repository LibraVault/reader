package xyz.libravault.feature.reader.markdown

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.isSpecified
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.MarkdownTypography
import androidx.compose.ui.text.font.FontFamily as ComposeFontFamily
import xyz.libravault.feature.reader.FontFamily as ReaderFontFamily
import xyz.libravault.feature.reader.ReaderSettings

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
        ReaderFontFamily.SYSTEM     -> ComposeFontFamily.Default
        ReaderFontFamily.SERIF      -> ComposeFontFamily.Serif
        ReaderFontFamily.SANS_SERIF -> ComposeFontFamily.SansSerif
        ReaderFontFamily.MONOSPACE  -> ComposeFontFamily.Monospace
    }

    fun TextStyle.scaled(overrideFontFamily: ComposeFontFamily = composeFontFamily): TextStyle = copy(
        fontFamily = overrideFontFamily,
        fontSize = fontSize * settings.fontSize,
        lineHeight = if (lineHeight.isSpecified) {
            lineHeight * settings.fontSize * settings.lineSpacing
        } else {
            lineHeight
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
        inlineCode = bodyLarge.copy(fontFamily = ComposeFontFamily.Monospace),
        quote = typography.bodyMedium.scaled().plus(SpanStyle(fontStyle = FontStyle.Italic)),
        paragraph = bodyLarge,
        ordered = bodyLarge,
        bullet = bodyLarge,
        list = bodyLarge,
        link = bodyLarge.copy(fontWeight = FontWeight.Bold, textDecoration = TextDecoration.Underline),
    )
}
