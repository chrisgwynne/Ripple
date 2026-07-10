package com.ripple.town.core.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Ripple's visual identity: warm storybook pixel-art. Material 3 is a technical
 * base only — the palette is soft naturals: warm greens, brick reds, creams,
 * pale blues and muted browns. Nothing glossy, nothing navy-and-black.
 */
object RippleColors {
    val Cream = Color(0xFFF4ECDB)
    val Parchment = Color(0xFFEFE3C8)
    val WarmGreen = Color(0xFF7C9B62)
    val DeepGreen = Color(0xFF55713F)
    val BrickRed = Color(0xFFB2593F)
    val DeepBrick = Color(0xFF8A4330)
    val PaleBlue = Color(0xFFAECBD6)
    val SkyBlue = Color(0xFF8FB6C9)
    val MutedBrown = Color(0xFF8A6F52)
    val DarkBrown = Color(0xFF5C4934)
    val Ink = Color(0xFF3B3228)
    val SoftInk = Color(0xFF6B5F4F)
    val Gold = Color(0xFFD9A648)
    val Blush = Color(0xFFD98E7A)

    // Map palette
    val Grass = Color(0xFF8FAE6B)
    val GrassDark = Color(0xFF7E9C5C)
    val Road = Color(0xFFC9B491)
    val RoadEdge = Color(0xFFB5A07E)
    val Path = Color(0xFFD8C6A2)
    val Water = Color(0xFF7FA9BC)
    val WaterDeep = Color(0xFF6C97AC)
    val TreeGreen = Color(0xFF5E7F45)
    val TreeTrunk = Color(0xFF6E563C)
    val Flowers = Color(0xFFC98BA4)
    val Plaza = Color(0xFFD3C3A4)
}

private val LightScheme = lightColorScheme(
    primary = RippleColors.WarmGreen,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE8C9),
    onPrimaryContainer = RippleColors.DeepGreen,
    secondary = RippleColors.BrickRed,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF2D5C9),
    onSecondaryContainer = RippleColors.DeepBrick,
    tertiary = RippleColors.SkyBlue,
    onTertiary = RippleColors.Ink,
    background = RippleColors.Cream,
    onBackground = RippleColors.Ink,
    surface = RippleColors.Parchment,
    onSurface = RippleColors.Ink,
    surfaceVariant = Color(0xFFE6D9BC),
    onSurfaceVariant = RippleColors.SoftInk,
    outline = Color(0xFFB9A98C),
    error = RippleColors.DeepBrick
)

private val RippleTypography = Typography(
    displaySmall = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 30.sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 24.sp),
    headlineSmall = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 20.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, fontSize = 19.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 10.sp)
)

private val RippleShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun RippleTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightScheme,
        typography = RippleTypography,
        shapes = RippleShapes,
        content = content
    )
}
