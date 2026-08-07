package com.sdw.music.player.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
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
    // Android 12+ (API 31): use system MD3 dynamic color, follow light/dark
    // Older devices: fallback to custom dark theme
    val darkTheme = isSystemInDarkTheme()
    val colorScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val context = LocalContext.current
        try {
            val dyn = if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            android.util.Log.d("SDWTheme", "Dynamic color OK: primary=${dyn.primary}")
            dyn
        } catch (e: Exception) {
            android.util.Log.w("SDWTheme", "Dynamic color FAILED, fallback: ${e.message}")
            SDWDarkColorScheme
        }
    } else {
        SDWDarkColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = SDWTypography,
        content = content
    )
}
