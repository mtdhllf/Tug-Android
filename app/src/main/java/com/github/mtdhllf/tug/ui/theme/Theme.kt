package com.github.mtdhllf.tug.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    primaryContainer = AccentSoft,
    onPrimaryContainer = AccentInk,
    inversePrimary = AccentDark,
    secondary = Info,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE6E4BF),
    onSecondaryContainer = Color(0xFF535337),
    tertiary = Color(0xFF7D5F2B),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF9D191),
    onTertiaryContainer = Color(0xFF614613),
    background = BgLight,
    onBackground = InkLight,
    surface = SurfaceLight,
    onSurface = InkLight,
    surfaceVariant = Surface3Light,
    onSurfaceVariant = SubLight,
    surfaceTint = Accent,
    inverseSurface = BgDark,
    inverseOnSurface = InkDark,
    error = Danger,
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    outline = MutedLight,
    outlineVariant = LineLight,
    scrim = Color.Black,
    surfaceBright = SurfaceLight,
    surfaceDim = Color(0xFFE6E3D0),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Surface2Light,
    surfaceContainer = Color(0xFFF7F4E4),
    surfaceContainerHigh = Color(0xFFF1EEDD),
    surfaceContainerHighest = Surface3Light,
)

private val DarkColors = darkColorScheme(
    primary = AccentDark,
    onPrimary = Color(0xFF424214),
    primaryContainer = Color(0xFF545525),
    onPrimaryContainer = Color(0xFFE7E7A9),
    inversePrimary = Accent,
    secondary = Color(0xFFCAC8A5),
    onSecondary = Color(0xFF424227),
    secondaryContainer = Color(0xFF3D3D23),
    onSecondaryContainer = Color(0xFFC2C19E),
    tertiary = Color(0xFFFFE1B5),
    onTertiary = Color(0xFF6B4F1B),
    tertiaryContainer = Color(0xFFF9D191),
    onTertiaryContainer = Color(0xFF614613),
    background = BgDark,
    onBackground = InkDark,
    surface = SurfaceDark,
    onSurface = InkDark,
    surfaceVariant = Surface3Dark,
    onSurfaceVariant = SubDark,
    surfaceTint = AccentDark,
    inverseSurface = BgLight,
    inverseOnSurface = InkLight,
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
    outline = MutedDark,
    outlineVariant = LineDark,
    scrim = Color.Black,
    surfaceBright = Color(0xFF2D2D20),
    surfaceDim = BgDark,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Surface2Dark,
    surfaceContainer = Color(0xFF1A1A11),
    surfaceContainerHigh = Color(0xFF202016),
    surfaceContainerHighest = Surface3Dark,
)

/**
 * 主题：跟随设置的深浅色 +（可选）Android 12+ 动态取色。
 */
@Composable
fun TugTheme(
    darkTheme: Boolean,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = TugTypography,
        content = content,
    )
}
