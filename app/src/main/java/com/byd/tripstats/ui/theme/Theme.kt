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
//   Deep Slate Blue background (#0E121A), Electric Blue accent (#00CCFF),
//   Eco Teal secondary (#00FAD9), Arctic Blue tertiary.
private val DarkColorScheme = darkColorScheme(
    // Primary — Electric Blue (#00CCFF): active buttons, highlights, selected tabs
    primary                = BydElectricBlue,
    onPrimary              = BydOnElectricBlue,
    primaryContainer       = BydElectricBlueDeep,
    onPrimaryContainer     = BydElectricBlue,

    // Secondary — Eco Teal (#00FAD9): regen/energy indicators, secondary actions
    secondary              = BydEcoTeal,
    onSecondary            = BydOnEcoTeal,
    secondaryContainer     = BydEcoTealDeep,
    onSecondaryContainer   = BydEcoTeal,

    // Tertiary — Arctic Blue: softer accent for informational elements
    tertiary               = BydArcticBlue,
    onTertiary             = BydTextOnDark,
    tertiaryContainer      = BydArcticBlueDeep,
    onTertiaryContainer    = BydArcticBlue,

    // Backgrounds
    background             = BydBackground,        // #0E121A Deep Slate Blue
    onBackground           = BydTextPrimary,

    // Surfaces — card layers lifting above the background
    surface                = BydSurface,           // #1A1F2B Dark Charcoal Blue
    onSurface              = BydTextPrimary,
    surfaceVariant         = BydSurfaceVariant,    // #243040 lifted variant
    onSurfaceVariant       = BydTextSecondary,     // #A0AEC0 muted grey

    // Outline / dividers
    outline                = BydOutline,
    outlineVariant         = BydOutlineVariant,

    // Inverse (used for snackbars, tooltips)
    inverseSurface         = BydTextPrimary,
    inverseOnSurface       = BydBackground,
    inversePrimary         = BydOceanBlue,

    // Error
    error                  = BydErrorRed,
    onError                = Color.White,
    errorContainer         = BydErrorContainer,
    onErrorContainer       = BydOnErrorContainer,

    // Scrim (modal overlays)
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
    secondary              = BydTealLight,
    onSecondary            = Color.White,
    secondaryContainer     = BydTealLightContainer,
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
                BydOceanBlue.toArgb()   // deep blue on light mode
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