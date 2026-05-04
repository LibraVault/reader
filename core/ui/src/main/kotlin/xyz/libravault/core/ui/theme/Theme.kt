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
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

enum class ReadingTheme { DARK, LIGHT, SEPIA }

val LocalReadingTheme = staticCompositionLocalOf { ReadingTheme.DARK }

@PublishedApi
internal val DarkColorScheme = darkColorScheme(
    primary             = LeatherBrown,
    onPrimary           = WarmNeutral50,
    primaryContainer    = LeatherDark,
    onPrimaryContainer  = LeatherLight,
    secondary           = VaultGold,
    onSecondary         = WarmNeutral900,
    background          = DarkBackground,
    onBackground        = WarmNeutral100,
    surface             = DarkSurface,
    onSurface           = WarmNeutral100,
    surfaceVariant      = DarkSurfaceVar,
    onSurfaceVariant    = WarmNeutral500,
    outline             = WarmNeutral700,
)

@PublishedApi
internal val LightColorScheme = lightColorScheme(
    primary             = LeatherBrown,
    onPrimary           = WarmNeutral50,
    primaryContainer    = LeatherLight,
    onPrimaryContainer  = LeatherDark,
    secondary           = VaultGold,
    onSecondary         = WarmNeutral900,
    background          = WarmNeutral50,
    onBackground        = WarmNeutral900,
    surface             = WarmNeutral100,
    onSurface           = WarmNeutral900,
    surfaceVariant      = WarmNeutral200,
    onSurfaceVariant    = WarmNeutral700,
    outline             = WarmNeutral200,
)

@PublishedApi
internal val SepiaColorScheme = lightColorScheme(
    primary             = LeatherBrown,
    onPrimary           = WarmNeutral50,
    primaryContainer    = LeatherLight,
    onPrimaryContainer  = LeatherDark,
    secondary           = VaultGold,
    onSecondary         = WarmNeutral900,
    background          = SepiaBackground,
    onBackground        = SepiaText,
    surface             = SepiaBackground,
    onSurface           = SepiaText,
    surfaceVariant      = Color(0xFFE3D5B6),
    onSurfaceVariant    = SepiaText.copy(alpha = 0.7f),
    outline             = Color(0xFFC4B490),
)

@Composable
fun LibravaultTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    readingTheme: ReadingTheme = ReadingTheme.DARK,
    useDynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        // Dynamic color requires Android 12+ (API 31); fall through to leather palette on older devices
        useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        readingTheme == ReadingTheme.SEPIA -> SepiaColorScheme
        darkTheme -> DarkColorScheme
        else      -> LightColorScheme
    }

    CompositionLocalProvider(LocalReadingTheme provides readingTheme) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography  = LibravaultTypography,
            content     = content,
        )
    }
}
