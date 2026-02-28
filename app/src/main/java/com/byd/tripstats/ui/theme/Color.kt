package com.byd.tripstats.ui.theme

import androidx.compose.ui.graphics.Color

// ── BYD DiLink Ocean Series — Dark UI Palette ─────────────────────────────────
// Sourced from the Seal / Di3+ infotainment default "Ocean" theme.

// Primary accent — the vivid Electric Blue used on active buttons, sliders, highlights
val BydElectricBlue      = Color(0xFF00CCFF)
val BydElectricBlueDim   = Color(0xFF0099CC)   // slightly dimmed for containers
val BydElectricBlueDeep  = Color(0xFF003355)   // deep container background
val BydOnElectricBlue    = Color(0xFF001A26)   // near-black text/icons on electric blue

// Secondary accent — Eco Teal used for energy flow, regen indicators
val BydEcoTeal           = Color(0xFF00FAD9)
val BydEcoTealDim        = Color(0xFF00B89E)
val BydEcoTealDeep       = Color(0xFF00332E)
val BydOnEcoTeal         = Color(0xFF001A16)

// Tertiary — Arctic Blue (matches Seal paint option, used for softer accents)
val BydArcticBlue        = Color(0xFF79B5CE)
val BydArcticBlueDeep    = Color(0xFF1A3A4A)

// Backgrounds — the "Midnight Navy" gradient layers the car uses
val BydStatusBar         = Color(0xFF0A0E14)   // top bar / deepest layer
val BydBackground        = Color(0xFF0E121A)   // main background (Deep Slate Blue)
val BydSurface           = Color(0xFF1A1F2B)   // card surface (Dark Charcoal Blue)
val BydSurfaceVariant    = Color(0xFF243040)   // slightly lifted surface
val BydSurfaceHigh       = Color(0xFF2C3A50)   // active widget / glow layer

// Text
val BydTextPrimary       = Color(0xFFFFFFFF)
val BydTextSecondary     = Color(0xFFA0AEC0)   // muted grey for sub-text / disabled
val BydTextOnDark        = Color(0xFF001A26)   // dark text for use on bright accents

// Outline / dividers
val BydOutline           = Color(0xFF3B4A5E)
val BydOutlineVariant    = Color(0xFF2A3648)

// ── BYD DiLink Ocean Series — Light UI Palette ────────────────────────────────
// The car's lighter theme references Aurora White bodywork + ocean depth blues.

val BydOceanBlue         = Color(0xFF005FA3)   // deep ocean blue — primary for light mode
val BydOceanBlueLight    = Color(0xFFCCEEFF)   // washed-out container on white
val BydOceanBlueDark     = Color(0xFF002233)   // text/icons on light containers
val BydTealLight         = Color(0xFF006B61)   // teal secondary for light mode
val BydTealLightContainer = Color(0xFFB2FFF5)
val BydAuroraWhite       = Color(0xFFF7F9FB)   // Aurora White — cool-toned background
val BydAtlantisGrey      = Color(0xFF3B444B)   // Atlantis Grey — secondary text on white
val BydSurfaceLight      = Color(0xFFECF4FA)   // light blue-tinted card surface
val BydSurfaceVariantLight = Color(0xFFDCECF5) // slightly more saturated variant
val BydOutlineLight      = Color(0xFF7BA5BE)
val BydOutlineVariantLight = Color(0xFFBDD6E6)

// ── Semantic / functional colors (shared across themes) ───────────────────────

// Energy / EV telemetry
val BatteryBlue          = Color(0xFF2196F3)
val RegenGreen           = Color(0xFF4CAF50)
val AccelerationOrange   = Color(0xFFFF9800)
val ChargingYellow       = Color(0xFFFFC107)

// Error — vivid red replacing Material 3's default pinkish error
val BydErrorRed          = Color(0xFFE53935)
val BydErrorRedLight     = Color(0xFFFF6B6B)   // brighter for dark backgrounds
val BydErrorContainer    = Color(0xFF7F0000)
val BydErrorContainerLight = Color(0xFFFFDAD6)
val BydOnErrorContainer  = Color(0xFFFFDAD6)
val BydOnErrorContainerLight = Color(0xFF410002)