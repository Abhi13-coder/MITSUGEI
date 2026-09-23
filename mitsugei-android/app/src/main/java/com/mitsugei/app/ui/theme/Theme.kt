package com.mitsugei.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Brand palette from logo (purple → pink → peach)
val MitsugeiPurple = Color(0xFFC084FC)
val MitsugeiPink = Color(0xFFF9A8D4)
val MitsugeiPeach = Color(0xFFFDBA74)
val LinkBlue = Color(0xFF1A0DAB)
val LinkBlueDark = Color(0xFF8AB4F8)
val GoogleGrey = Color(0xFF70757A)
val ResultTitle = Color(0xFF202124)
val ResultTitleDark = Color(0xFFE8EAED)

private val LightColors = lightColorScheme(
    primary = MitsugeiPurple,
    onPrimary = Color.White,
    secondary = MitsugeiPink,
    tertiary = MitsugeiPeach,
    background = Color(0xFFFFFFFF),
    surface = Color(0xFFFFFFFF),
    onBackground = Color(0xFF202124),
    onSurface = Color(0xFF202124),
    surfaceVariant = Color(0xFFF1F3F4),
    outline = Color(0xFFDADCE0),
)

private val DarkColors = darkColorScheme(
    primary = MitsugeiPurple,
    onPrimary = Color.Black,
    secondary = MitsugeiPink,
    tertiary = MitsugeiPeach,
    background = Color(0xFF202124),
    surface = Color(0xFF303134),
    onBackground = Color(0xFFE8EAED),
    onSurface = Color(0xFFE8EAED),
    surfaceVariant = Color(0xFF3C4043),
    outline = Color(0xFF5F6368),
)

enum class AppThemeMode { SYSTEM, LIGHT, DARK }

@Composable
fun MitsugeiTheme(
    themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val dark = when (themeMode) {
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK -> true
    }
    val scheme = if (dark) DarkColors else LightColors
    val context = LocalContext.current
    val colorScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(context).copy(
            primary = MitsugeiPurple,
            secondary = MitsugeiPink
        ) else dynamicLightColorScheme(context).copy(
            primary = MitsugeiPurple,
            secondary = MitsugeiPink
        )
    } else scheme

    MaterialTheme(
        colorScheme = if (themeMode == AppThemeMode.SYSTEM && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            colorScheme else scheme,
        typography = Typography(),
        content = content
    )
}
