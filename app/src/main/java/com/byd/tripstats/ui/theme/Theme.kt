package com.byd.tripstats.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = BydCyanAccent,           // Cyan accent (matches car UI)
    secondary = BydLightCyan,          // Lighter cyan for secondary elements
    tertiary = Teal80,                 // Teal for tertiary (no more pink!)
    background = BydDarkBackground,    // Very dark blue (matches car background)
    surface = BydDarkSurface,          // Dark blue for cards (matches car cards)
    surfaceVariant = BydDarkSurfaceVariant,  // Slightly lighter variant
    onPrimary = Color.White,           // White text on cyan
    onSecondary = Color.Black,         // Black text on light cyan
    onTertiary = Color.Black,          // Black text on teal
    onBackground = Color.White,        // White text on dark blue background
    onSurface = Color.White,           // White text on dark blue surface
)

private val LightColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Teal40,  // Changed from Pink40 to Teal40
    background = Color(0xFFFFFBFE),
    surface = Color(0xFFFFFBFE),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color(0xFF1C1B1F),
    onSurface = Color(0xFF1C1B1F),
)

@Composable
fun BydTripStatsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // Use background color for status bar (seamless look like car UI)
            window.statusBarColor = if (darkTheme) {
                BydDarkBackground.toArgb()
            } else {
                colorScheme.primary.toArgb()
            }
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }
    
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}