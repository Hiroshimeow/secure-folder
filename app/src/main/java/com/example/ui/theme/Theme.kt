package com.example.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = OneUiTealPrimary,
    onPrimary = OneUiOnTeal,
    primaryContainer = OneUiTealContainer,
    onPrimaryContainer = OneUiTealBright,
    secondary = OneUiTealBright,
    onSecondary = OneUiOnTeal,
    background = OneUiDarkBackground,
    onBackground = OneUiDarkTextPrimary,
    surface = OneUiDarkSurface,
    onSurface = OneUiDarkTextPrimary,
    surfaceVariant = OneUiDarkSurfaceVariant,
    onSurfaceVariant = OneUiDarkTextSecondary,
    outline = OneUiDarkBorder,
    error = OneUiLockRed
)

private val LightColorScheme = lightColorScheme(
    primary = OneUiTealPrimary,
    onPrimary = OneUiOnTeal,
    primaryContainer = Color(0xFFD4F3EB),
    onPrimaryContainer = Color(0xFF00382E),
    secondary = Color(0xFF007563),
    onSecondary = Color(0xFFFFFFFF),
    background = OneUiLightBackground,
    onBackground = OneUiLightTextPrimary,
    surface = OneUiLightSurface,
    onSurface = OneUiLightTextPrimary,
    surfaceVariant = OneUiLightSurfaceVariant,
    onSurfaceVariant = OneUiLightTextSecondary,
    outline = OneUiLightBorder,
    error = OneUiLockRed
)

@Composable
fun SecureFolderTheme(
    darkTheme: Boolean = true, // Default to deep OLED Dark mode for Samsung Knox signature look
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
