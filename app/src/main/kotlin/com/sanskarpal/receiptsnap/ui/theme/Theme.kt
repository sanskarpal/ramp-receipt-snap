package com.sanskarpal.receiptsnap.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Palette measured from ramp.com's rendered page: lime CTA, near-black ink, warm off-white.
val Lime = Color(0xFFE4F222)
val Ink = Color(0xFF0C0A08)
val Paper = Color(0xFFF4F2F0)
val Amber = Color(0xFFB45309)
val Positive = Color(0xFF15803D)
val Negative = Color(0xFFB91C1C)

private val Light = lightColorScheme(
    primary = Ink, onPrimary = Color.White,
    primaryContainer = Lime, onPrimaryContainer = Ink,
    secondary = Color(0xFF55524E), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE9E6E2), onSecondaryContainer = Ink,
    background = Paper, onBackground = Ink,
    surface = Color.White, onSurface = Ink,
    surfaceVariant = Color(0xFFECE9E5), onSurfaceVariant = Color(0xFF5E5A55),
    outline = Color(0xFFD9D5D0), outlineVariant = Color(0xFFE6E2DD),
    error = Negative,
)

private val Dark = darkColorScheme(
    primary = Lime, onPrimary = Ink,
    primaryContainer = Lime, onPrimaryContainer = Ink,
    secondary = Color(0xFFBDB8B2), onSecondary = Ink,
    secondaryContainer = Color(0xFF2A2826), onSecondaryContainer = Color(0xFFEDEAE6),
    background = Color(0xFF121212), onBackground = Color(0xFFEDEAE6),
    surface = Color(0xFF1C1B1A), onSurface = Color(0xFFEDEAE6),
    surfaceVariant = Color(0xFF2A2826), onSurfaceVariant = Color(0xFFB0ABA5),
    outline = Color(0xFF3A3734), outlineVariant = Color(0xFF2E2C2A),
    error = Color(0xFFF87171),
)

private val AppTypography = Typography(
    headlineMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 26.sp, letterSpacing = (-0.5).sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp),
)

@Composable
fun ReceiptSnapTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, typography = AppTypography, content = content)
}
