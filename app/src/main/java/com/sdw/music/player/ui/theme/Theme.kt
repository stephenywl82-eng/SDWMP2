package com.sdw.music.player.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val SDWDarkColorScheme = darkColorScheme(
    primary = Purple60,
    onPrimary = TextPrimary,
    primaryContainer = Purple20,
    onPrimaryContainer = Purple80,
    secondary = Gold80,
    onSecondary = DarkBg,
    secondaryContainer = Gold60,
    tertiary = Purple80,
    background = DarkBg,
    onBackground = TextPrimary,
    surface = DarkCard,
    onSurface = TextPrimary,
    surfaceVariant = DarkSurface,
    onSurfaceVariant = TextSecondary,
    outline = DarkSurface,
    outlineVariant = TextTertiary,
    error = AccentRed,
    onError = TextPrimary,
)

@Composable
fun SDWMusicTheme(
    content: @Composable () -> Unit
) {
    // Moto Music always uses dark theme
    val colorScheme = SDWDarkColorScheme

    // Edge-to-edge status bar — enableEdgeToEdge() handles color in Activity;
    // only set dark status bar (light icons) here since this is always dark theme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = SDWTypography,
        content = content
    )
}
