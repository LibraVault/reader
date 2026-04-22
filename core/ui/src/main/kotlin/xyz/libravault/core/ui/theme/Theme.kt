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
import androidx.compose.ui.platform.LocalContext

enum class ReadingTheme { DARK, LIGHT, SEPIA }

val LocalReadingTheme = staticCompositionLocalOf { ReadingTheme.DARK }

private val DarkColorScheme = darkColorScheme(
    primary             = VaultBlue,
    onPrimary           = Neutral50,
    primaryContainer    = VaultBlueDark,
    onPrimaryContainer  = VaultBlueLight,
    secondary           = VaultAmber,
    onSecondary         = Neutral900,
    background          = DarkBackground,
    onBackground        = Neutral100,
    surface             = DarkSurface,
    onSurface           = Neutral100,
    surfaceVariant      = DarkSurfaceVar,
    onSurfaceVariant    = Neutral500,
    outline             = Neutral700,
)

private val LightColorScheme = lightColorScheme(
    primary             = VaultBlue,
    onPrimary           = Neutral50,
    primaryContainer    = VaultBlueLight,
    onPrimaryContainer  = VaultBlueDark,
    secondary           = VaultAmber,
    onSecondary         = Neutral900,
    background          = Neutral50,
    onBackground        = Neutral900,
    surface             = Neutral100,
    onSurface           = Neutral900,
    surfaceVariant      = Neutral200,
    onSurfaceVariant    = Neutral700,
    outline             = Neutral200,
)

@Composable
fun LibravaultTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    readingTheme: ReadingTheme = ReadingTheme.DARK,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
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
