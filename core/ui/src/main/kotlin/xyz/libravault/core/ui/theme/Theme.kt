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

enum class ReadingTheme { DARK, LIGHT, SEPIA }

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
    // Sepia and Light are both light backgrounds — only Dark (and dynamic-dark) call for
    // light-colored status bar icons. Tracked alongside colorScheme, rather than derived
    // from it after the fact, since dynamic color schemes don't cleanly say "I'm dark".
    var isLightBackground = false
    val colorScheme = when {
        // Dynamic color requires Android 12+ (API 31); fall through to leather palette on older devices
        useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            isLightBackground = !darkTheme
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        readingTheme == ReadingTheme.SEPIA -> { isLightBackground = true; SepiaColorScheme }
        darkTheme -> DarkColorScheme
        else      -> { isLightBackground = true; LightColorScheme }
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