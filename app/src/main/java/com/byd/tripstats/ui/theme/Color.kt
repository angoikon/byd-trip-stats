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
val BydBackground        = Color(0xFF0D1525)   // ← was #0E121A (add more blue)
val BydSurface           = Color(0xFF1A2840)   // ← was #1A1F2B (too grey/charcoal)
val BydSurfaceVariant    = Color(0xFF1F3050)   // ← was #243040 (too grey)
val BydSurfaceHigh       = Color(0xFF283D5E)   // ← was #2C3A50 (adjust accordingly)

// Text
val BydTextPrimary       = Color(0xFFFFFFFF)
val BydTextSecondary     = Color(0xFFA0AEC0)   // muted grey for sub-text / disabled
val BydTextOnDark        = Color(0xFF001A26)   // dark text for use on bright accents

// Outline / dividers
val BydOutline           = Color(0xFF3B4A5E)
val BydOutlineVariant    = Color(0xFF2A3648)

// ── BYD DiLink Ocean Series — Light UI Palette ────────────────────────────────
// The car's lighter theme references Aurora White bodywork + ocean depth blues.

val BydOceanBlue         = Color(0xFF1A6EC8)   // ← was #005FA3 (too dark navy), now: cobalt blue matching Sport btn
val BydOceanBlueLight    = Color(0xFFDEEAF7)   // ← was #CCEEFF (too cyan-tinted), now: subtle blue-white wash
val BydOceanBlueDark     = Color(0xFF0D2A4A)   // ← was #002233 (ok, slightly warmer)
val BydSecondaryLight         = Color(0xFF5B8DB8)   // ← was #006B61 (TEAL — wrong!)
                                                //   reuse the same cobalt as secondary,
                                                //   or pick a muted blue-grey (see note)
val BydSecondaryLightContainer = Color(0xFFDEEAF7)  // ← was #B2FFF5 (mint green, very off)
val BydAuroraWhite       = Color(0xFFF5F7F9)   // ← was #F7F9FB (fine, minimal change)
val BydAtlantisGrey      = Color(0xFF374151)   // ← was #3B444B (slightly cooler grey)
val BydSurfaceLight      = Color(0xFFFFFFFF)   // ← was #ECF4FA (too blue-tinted for cards)
val BydSurfaceVariantLight = Color(0xFFF0F4F8) // ← was #DCECF5 (too saturated blue)
val BydOutlineLight      = Color(0xFF8FA3B8)   // ← was #7BA5BE (ok, very close)
val BydOutlineVariantLight = Color(0xFFCDD8E3) // ← was #BDD6E6 (slightly less blue)

// The actual interactive button color used in both themes:
val BydCobaltBlue        = Color(0xFF1A6EC8)   // segment buttons, toggles, active tabs
val BydCobaltBlueDeep    = Color(0xFF0D2A4A)   // container / deep variant

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