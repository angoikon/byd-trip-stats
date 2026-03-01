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

// ── Dark scheme — BYD DiLink "Ocean" night mode ───────────────────────────────
// Matches the Seal / Di3+ infotainment default dark theme:
private val DarkColorScheme = darkColorScheme(
    // Primary — COBALT blue for buttons, selected states (NOT cyan)
    // The car uses the same cobalt in both light and dark themes for interactive chrome
    primary                = BydCobaltBlue,        // ← was BydElectricBlue (#00CCFF)
    onPrimary              = Color.White,           // ← was BydOnElectricBlue (near-black)
    primaryContainer       = BydCobaltBlueDeep,    // ← was BydElectricBlueDeep
    onPrimaryContainer     = BydElectricBlue,      // cyan label on deep container — ok

    // Secondary — Electric Cyan (#00CCFF): toggles, sliders, energy indicators
    // This is what the toggle track in the photo actually is
    secondary              = BydElectricBlue,      // ← was BydEcoTeal
    onSecondary            = BydOnElectricBlue,
    secondaryContainer     = BydElectricBlueDeep,
    onSecondaryContainer   = BydElectricBlue,

    // Tertiary — Eco Teal: regen / energy-specific accents only
    tertiary               = BydEcoTeal,
    onTertiary             = BydOnEcoTeal,
    tertiaryContainer      = BydEcoTealDeep,
    onTertiaryContainer    = BydEcoTeal,

    // Backgrounds — bluer navy, matching the photo
    background             = BydBackground,        // #0D1525
    onBackground           = BydTextPrimary,

    surface                = BydSurface,           // #1A2840 — blue-navy cards
    onSurface              = BydTextPrimary,
    surfaceVariant         = BydSurfaceVariant,    // #1F3050
    onSurfaceVariant       = BydTextSecondary,

    outline                = BydOutline,
    outlineVariant         = BydOutlineVariant,

    inverseSurface         = BydTextPrimary,
    inverseOnSurface       = BydBackground,
    inversePrimary         = BydCobaltBlue,        // ← was BydOceanBlue (now unified)

    error                  = BydErrorRed,
    onError                = Color.White,
    errorContainer         = BydErrorContainer,
    onErrorContainer       = BydOnErrorContainer,

    scrim                  = Color(0xFF000000),
)

// ── Light scheme — BYD DiLink "Ocean" day mode ────────────────────────────────
// References the Aurora White exterior paint + deep ocean blues for contrast.
// Keeps the brand DNA while being comfortable in daylight.
private val LightColorScheme = lightColorScheme(
    // Primary — deep Ocean Blue (#005FA3): readable on white, unmistakably BYD
    primary                = BydOceanBlue,
    onPrimary              = Color.White,
    primaryContainer       = BydOceanBlueLight,    // #CCEFFF very light wash
    onPrimaryContainer     = BydOceanBlueDark,     // #002233 dark text on light container

    // Secondary — muted teal
    secondary              = BydSecondaryLight,   // #5B8DB8 muted cobalt (replacing teal, which is too close to primary)
    onSecondary            = Color.White,
    secondaryContainer     = BydSecondaryLightContainer, // #DEEAF7 very light wash (replacing mint green, which is too far)
    onSecondaryContainer   = BydEcoTealDeep,

    // Tertiary — Atlantis Grey (stormy blue-grey, from Seal paint palette)
    tertiary               = BydAtlantisGrey,
    onTertiary             = Color.White,
    tertiaryContainer      = BydSurfaceVariantLight,
    onTertiaryContainer    = BydOceanBlueDark,

    // Backgrounds — Aurora White (#F7F9FB): cool-toned, matches car's light theme
    background             = BydAuroraWhite,
    onBackground           = BydOceanBlueDark,

    // Surfaces
    surface                = BydAuroraWhite,
    onSurface              = BydOceanBlueDark,
    surfaceVariant         = BydSurfaceVariantLight,
    onSurfaceVariant       = BydAtlantisGrey,

    // Outline / dividers
    outline                = BydOutlineLight,
    outlineVariant         = BydOutlineVariantLight,

    // Inverse
    inverseSurface         = BydOceanBlueDark,
    inverseOnSurface       = BydAuroraWhite,
    inversePrimary         = BydElectricBlue,

    // Error
    error                  = BydErrorRed,
    onError                = Color.White,
    errorContainer         = BydErrorContainerLight,
    onErrorContainer       = BydOnErrorContainerLight,

    // Scrim
    scrim                  = Color(0xFF000000),
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
            // Status bar matches the deepest background layer for a seamless look
            window.statusBarColor = if (darkTheme) {
                BydStatusBar.toArgb()    // #0A0E14 — even darker than the background
            } else {
                BydAuroraWhite.toArgb()     // ← was BydOceanBlue (deep blue, wrong for light mode)
            }
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography  = Typography,
        content     = content
    )
}