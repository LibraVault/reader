package xyz.libravault.core.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import xyz.libravault.core.ui.findActivity

/**
 * [SYSTEM] (#349/#370) follows the OS-level light/dark appearance setting instead of a
 * fixed choice. It is never itself a renderable color scheme — every consumer that needs
 * an actual scheme (this file's own [LibravaultTheme], Mermaid diagram theming, Readium's
 * `EpubPreferences.theme`, …) must resolve it first via [resolved] into a
 * [ConcreteReadingTheme]. This mirrors iOS's `ReadingTheme`/`ConcreteReadingTheme` split
 * (#374): the compiler enforces resolution at every call site instead of risking a
 * silently-wrong fallback in a non-exhaustive `when`.
 *
 * [AMOLED] (#420) is a distinct 5th option, not a replacement for [DARK]: a true-black
 * (#000000) page background for OLED/AMOLED screens, where [DARK]'s surface is a dark
 * grey/brown (`DarkSurface0`) rather than actual black. Like [SEPIA], it is never a
 * [SYSTEM] resolution target — the OS only ever expresses two appearances (light/dark),
 * and [DARK] is the app's existing answer for "dark".
 */
enum class ReadingTheme { DARK, LIGHT, SEPIA, AMOLED, SYSTEM }

/** The concrete, renderable themes [ReadingTheme] can resolve to — i.e. [ReadingTheme]
 * minus [ReadingTheme.SYSTEM]. See [ReadingTheme]'s doc for why this split exists. */
enum class ConcreteReadingTheme { DARK, LIGHT, SEPIA, AMOLED }

/**
 * Resolves [ReadingTheme.SYSTEM] to [ConcreteReadingTheme.DARK] or
 * [ConcreteReadingTheme.LIGHT] per [systemInDarkTheme] (pass `isSystemInDarkTheme()` from
 * a `@Composable` call site — see [LibravaultTheme]). Dark/Light/Sepia/Amoled pass through
 * unchanged regardless of the system setting. Sepia and Amoled are never resolution
 * targets: neither is one of the OS's two appearance choices, same call as iOS's
 * `resolved(for colorScheme:)`.
 */
fun ReadingTheme.resolved(systemInDarkTheme: Boolean): ConcreteReadingTheme = when (this) {
    ReadingTheme.DARK   -> ConcreteReadingTheme.DARK
    ReadingTheme.LIGHT  -> ConcreteReadingTheme.LIGHT
    ReadingTheme.SEPIA  -> ConcreteReadingTheme.SEPIA
    ReadingTheme.AMOLED -> ConcreteReadingTheme.AMOLED
    ReadingTheme.SYSTEM -> if (systemInDarkTheme) ConcreteReadingTheme.DARK else ConcreteReadingTheme.LIGHT
}

val LocalReadingTheme = staticCompositionLocalOf { ReadingTheme.DARK }

@PublishedApi
internal val DarkColorScheme = darkColorScheme(
    primary             = LeatherLight,
    onPrimary           = WarmNeutral900,
    primaryContainer    = LeatherDark,
    onPrimaryContainer  = LeatherLight,
    secondary           = AgedBrass,
    onSecondary         = WarmNeutral900,
    background          = DarkSurface0,
    onBackground        = WarmNeutral100,
    surface             = DarkSurface1,
    onSurface           = WarmNeutral100,
    surfaceVariant      = DarkSurface2,
    onSurfaceVariant    = WarmGrey400,
    outline             = WarmNeutral500,
)

@PublishedApi
internal val LightColorScheme = lightColorScheme(
    primary             = LeatherBrown,
    onPrimary           = WarmNeutral50,
    primaryContainer    = LeatherLight,
    onPrimaryContainer  = LeatherDark,
    secondary           = AgedBrass,
    onSecondary         = WarmNeutral900,
    background          = WarmNeutral50,
    onBackground        = WarmNeutral900,
    surface             = WarmNeutral100,
    onSurface           = WarmNeutral900,
    surfaceVariant      = WarmNeutral300,
    onSurfaceVariant    = WarmNeutral700,
    outline             = WarmNeutral700,
)

/**
 * True-black (#420) — same leather brand accents as [DarkColorScheme], but `background`
 * and `surface` are pure black (`Color(0xFF000000)`) instead of `DarkSurface0`/1, the
 * whole point of an OLED/AMOLED reading theme. `surfaceVariant` stays a hair off pure
 * black ([AmoledSurfaceVariant]) purely for element hierarchy (cards/dividers still need
 * to be visually distinguishable from the page) — it is not itself the "true black" claim;
 * `background`, the actual page color, is.
 */
@PublishedApi
internal val AmoledColorScheme = darkColorScheme(
    primary             = LeatherLight,
    onPrimary           = WarmNeutral900,
    primaryContainer    = LeatherDark,
    onPrimaryContainer  = LeatherLight,
    secondary           = AgedBrass,
    onSecondary         = WarmNeutral900,
    background          = Color.Black,
    onBackground        = WarmNeutral100,
    surface             = Color.Black,
    onSurface           = WarmNeutral100,
    surfaceVariant      = AmoledSurfaceVariant,
    onSurfaceVariant    = WarmGrey400,
    outline             = WarmNeutral500,
)

@PublishedApi
internal val SepiaColorScheme = lightColorScheme(
    primary             = LeatherBrown,
    onPrimary           = WarmNeutral50,
    primaryContainer    = LeatherLight,
    onPrimaryContainer  = LeatherDark,
    secondary           = AgedBrass,
    onSecondary         = WarmNeutral900,
    background          = SepiaBackground,
    onBackground        = SepiaText,
    surface             = SepiaBackground,
    onSurface           = SepiaText,
    surfaceVariant      = Color(0xFFE3D5B6),
    // Was SepiaText.copy(alpha = 0.7f), which rendered at 4.18:1 on
    // surfaceVariant — below WCAG AA. See SepiaTextMuted.
    onSurfaceVariant    = SepiaTextMuted,
    outline             = SepiaOutline,
)

@Composable
fun LibravaultTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    readingTheme: ReadingTheme = ReadingTheme.DARK,
    useDynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    // readingTheme is resolved against darkTheme (default isSystemInDarkTheme(), i.e. the
    // OS-level appearance) before anything below looks at it. This is also the fix for a
    // latent bug: the old version of this `when` only special-cased SEPIA and otherwise
    // fell straight through to the ambient `darkTheme` value, so an explicit DARK or LIGHT
    // pick was silently overridden by whatever the system's own light/dark setting
    // happened to be — the reading-theme selector had no actual effect for those two
    // cases. Resolving first makes DARK/LIGHT/SEPIA/AMOLED always win regardless of the
    // system setting, and makes SYSTEM the only case that still follows it.
    val resolvedTheme = readingTheme.resolved(darkTheme)

    // Sepia and Light are both light backgrounds — only Dark, Amoled (and dynamic-dark)
    // call for light-colored status bar icons. Tracked alongside colorScheme, rather than
    // derived from it after the fact, since dynamic color schemes don't cleanly say "I'm
    // dark".
    var isLightBackground = false
    val colorScheme = when {
        // Dynamic color requires Android 12+ (API 31); fall through to leather palette on older devices
        useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            isLightBackground = !darkTheme
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        resolvedTheme == ConcreteReadingTheme.SEPIA  -> { isLightBackground = true; SepiaColorScheme }
        resolvedTheme == ConcreteReadingTheme.DARK   -> DarkColorScheme
        resolvedTheme == ConcreteReadingTheme.AMOLED -> AmoledColorScheme
        else                                         -> { isLightBackground = true; LightColorScheme }
    }

    // Reactively matches the status bar's icon color to the resolved theme. Previously
    // nothing in the app ever called this — status bar icons followed whatever the static
    // android:windowLightStatusBar value in themes.xml happened to be (tuned for the dark
    // theme), so picking a light reading theme (Sepia, or plain Light) left white icons on
    // a light/cream background with effectively no contrast. Fixed at this single call site
    // since LibravaultTheme wraps both the app root (MainActivity) and each reader screen's
    // independent per-book theme override — every caller gets this for free.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            view.context.findActivity()?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars =
                    isLightBackground
            }
        }
    }

    CompositionLocalProvider(LocalReadingTheme provides readingTheme) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography  = LibravaultTypography,
            shapes      = LibravaultShapes,
            content     = content,
        )
    }
}