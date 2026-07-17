package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF35D477),
    primaryContainer = Color(0xFF0C3B24),
    secondary = Color(0xFF9CC7B1),
    secondaryContainer = Color(0xFF23362D),
    tertiary = Color(0xFFF2C15D),
    tertiaryContainer = Color(0xFF4D3711),
    background = Color(0xFF0D1117),
    surface = Color(0xFF151A21),
    surfaceVariant = Color(0xFF27313B),
    onPrimary = Color(0xFF000000),
    onPrimaryContainer = Color(0xFFB8F7CF),
    onSecondary = Color(0xFF0C1F15),
    onSecondaryContainer = Color(0xFFD0E8D9),
    onTertiary = Color(0xFF2E2105),
    onTertiaryContainer = Color(0xFFFFE2A1),
    onBackground = Color(0xFFE8EEF3),
    onSurface = Color(0xFFE8EEF3),
    onSurfaceVariant = Color(0xFFCBD5DD),
    outline = Color(0xFF79858F),
    error = Color(0xFFFFB4AB)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF027A48),
    primaryContainer = Color(0xFFDCFCE7),
    secondary = Color(0xFF3E6650),
    secondaryContainer = Color(0xFFE1F1E7),
    tertiary = Color(0xFF9A6A09),
    tertiaryContainer = Color(0xFFFFE7AE),
    background = Color(0xFFF8FAF7),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE2E8DF),
    onPrimary = Color(0xFFFFFFFF),
    onPrimaryContainer = Color(0xFF063D25),
    onSecondary = Color(0xFFFFFFFF),
    onSecondaryContainer = Color(0xFF173725),
    onTertiary = Color(0xFFFFFFFF),
    onTertiaryContainer = Color(0xFF382300),
    onBackground = Color(0xFF101814),
    onSurface = Color(0xFF101814),
    onSurfaceVariant = Color(0xFF48534D),
    outline = Color(0xFF737E77),
    error = Color(0xFFBA1A1A)
)

private val CustomTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 32.sp,
        lineHeight = 38.sp,
        letterSpacing = 0.sp
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = 0.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = 0.sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.sp
    )
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(8.dp),
    extraLarge = RoundedCornerShape(8.dp)
)

@Composable
fun KipotifyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = CustomTypography,
        shapes = AppShapes,
        content = content
    )
}
