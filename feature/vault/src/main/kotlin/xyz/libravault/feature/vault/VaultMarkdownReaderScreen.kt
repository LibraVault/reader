package xyz.libravault.feature.vault

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.MarkdownTypography
import xyz.libravault.core.ui.theme.OpenDyslexicFontFamily
import androidx.compose.ui.text.font.FontFamily as ComposeFontFamily

/**
 * Extra letter-spacing (in sp) bundled with [VaultReaderFontFamily.OPEN_DYSLEXIC]
 * (#423) — same value/rationale as `feature:reader`'s
 * `DYSLEXIA_FRIENDLY_LETTER_SPACING_SP`, duplicated for the same "parallel, not
 * shared" reason as the rest of this module (see [VaultMarkdownReaderScreen]'s
 * doc comment).
 */
private const val VAULT_DYSLEXIA_FRIENDLY_LETTER_SPACING_SP = 0.4f

/**
 * Renders a decrypted vault Markdown file's whole text via
 * [com.mikepenz.markdown.m3.Markdown] — the same Compose-native CommonMark
 * renderer `feature:reader`'s non-vault Markdown reader uses (issue #442).
 *
 * Deliberately v1-scoped, matching what's documented as out of scope on the
 * issue: one [Markdown] call for the whole document (no per-section
 * TOC/scroll-position tracking), no image transformer (the renderer's own
 * default already no-ops relative image references rather than crashing —
 * there's no vault equivalent of the SAF directory tree `feature:reader`'s
 * `CoilMarkdownImageTransformer` resolves them against), and no mermaid
 * diagram support. Reading position for vault Markdown isn't persisted for
 * the same reason — see [VaultReaderScreen]'s bookmark/highlight actions,
 * which stay disabled while this state is active.
 *
 * Typography is adapted from [VaultReaderSettings] via
 * [rememberVaultMarkdownTypography] rather than reusing `feature:reader`'s
 * `rememberMarkdownTypography` — this module doesn't depend on
 * `feature:reader` (see [VaultReaderTopBar]'s doc comment for the same,
 * already-established rationale). Reading-theme colors need no adapter here,
 * same as the non-vault reader, since mikepenz's default colors already read
 * `MaterialTheme.colorScheme`, which `VaultReaderScreen` already themes.
 */
@Composable
fun VaultMarkdownReaderScreen(
    text: String,
    settings: VaultReaderSettings,
    modifier: Modifier = Modifier,
) {
    val typography = rememberVaultMarkdownTypography(settings)
    Markdown(
        content = text,
        typography = typography,
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    )
}

/**
 * Adapts mikepenz's Material3-derived default typography to
 * [VaultReaderSettings]'s font family / size / line-spacing preferences —
 * duplicated from `feature:reader`'s `rememberMarkdownTypography` rather
 * than shared, same rationale as the rest of this module's settings/UI
 * duplication (see [VaultMarkdownReaderScreen]'s doc comment).
 */
@Composable
internal fun rememberVaultMarkdownTypography(settings: VaultReaderSettings): MarkdownTypography {
    val composeFontFamily = when (settings.fontFamily) {
        VaultReaderFontFamily.SYSTEM        -> ComposeFontFamily.Default
        VaultReaderFontFamily.SERIF         -> ComposeFontFamily.Serif
        VaultReaderFontFamily.SANS_SERIF    -> ComposeFontFamily.SansSerif
        VaultReaderFontFamily.MONOSPACE     -> ComposeFontFamily.Monospace
        VaultReaderFontFamily.OPEN_DYSLEXIC -> OpenDyslexicFontFamily
    }

    fun TextStyle.scaled(overrideFontFamily: ComposeFontFamily = composeFontFamily): TextStyle = copy(
        fontFamily = overrideFontFamily,
        fontSize = fontSize * settings.fontSize,
        lineHeight = if (lineHeight.isSpecified) {
            lineHeight * settings.fontSize * settings.lineSpacing
        } else {
            lineHeight
        },
        letterSpacing = if (overrideFontFamily == composeFontFamily &&
            settings.fontFamily == VaultReaderFontFamily.OPEN_DYSLEXIC
        ) {
            val base = if (letterSpacing.isSpecified) letterSpacing.value else 0f
            (base + VAULT_DYSLEXIA_FRIENDLY_LETTER_SPACING_SP).sp
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
        table = bodyLarge,
    )
}
