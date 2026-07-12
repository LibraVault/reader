@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package xyz.libravault.core.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import xyz.libravault.core.ui.R

/**
 * Lora — serif. Variable font (wght axis 100–900).
 * Used for editorial display/headline/title styles and book-card titles.
 */
private val Lora = FontFamily(
    Font(
        resId = R.font.lora,
        weight = FontWeight.Normal,
        variationSettings = FontVariation.Settings(FontVariation.weight(400)),
    ),
    Font(
        resId = R.font.lora,
        weight = FontWeight.Medium,
        variationSettings = FontVariation.Settings(FontVariation.weight(500)),
    ),
    Font(
        resId = R.font.lora,
        weight = FontWeight.SemiBold,
        variationSettings = FontVariation.Settings(FontVariation.weight(600)),
    ),
    Font(
        resId = R.font.lora,
        weight = FontWeight.Bold,
        variationSettings = FontVariation.Settings(FontVariation.weight(700)),
    ),
)

/** Default sans-serif — used for body text, labels, and UI chrome. */
private val Sans = FontFamily.SansSerif

val LibravaultTypography = Typography(
    displayLarge   = TextStyle(fontFamily = Lora, fontWeight = FontWeight.Bold,     fontSize = 40.sp, lineHeight = 48.sp, letterSpacing = (-0.5).sp),
    displayMedium  = TextStyle(fontFamily = Lora, fontWeight = FontWeight.Bold,     fontSize = 32.sp, lineHeight = 40.sp, letterSpacing = (-0.25).sp),
    displaySmall   = TextStyle(fontFamily = Lora, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 36.sp),
    headlineLarge  = TextStyle(fontFamily = Lora, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 36.sp),
    headlineMedium = TextStyle(fontFamily = Lora, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 30.sp),
    headlineSmall  = TextStyle(fontFamily = Lora, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 26.sp),
    titleLarge     = TextStyle(fontFamily = Lora, fontWeight = FontWeight.Medium,   fontSize = 18.sp, lineHeight = 26.sp),
    titleMedium    = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium,   fontSize = 16.sp, lineHeight = 24.sp),
    titleSmall     = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium,   fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge      = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal,   fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium     = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal,   fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall      = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal,   fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge     = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium,   fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium    = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium,   fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall     = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium,   fontSize = 11.sp, lineHeight = 16.sp),
)