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
val BydStatusBar         = Color(0xFF060B12)   // top bar / deepest layer #060B12
val BydBackground        = Color(0xFF08101A)   // ← was #0D1525 (add more blue)
val BydSurface           = Color(0xFF182540)   // ← was #1A2840 (too grey/charcoal)
val BydSurfaceVariant    = Color(0xFF1D2E4C)   // ← was #1F3050 (too grey)
val BydSurfaceHigh       = Color(0xFF253858)   // ← was #283D5E (adjust accordingly)

// Text
val BydTextPrimary       = Color(0xFFFFFFFF)
val BydTextSecondary     = Color(0xFFA0AEC0)   // muted grey for sub-text / disabled
val BydTextOnDark        = Color(0xFF001A26)   // dark text for use on bright accents

// Outline / dividers
val BydOutline           = Color(0xFF3B4A5E)
val BydOutlineVariant    = Color(0xFF2A3648)

// ── BYD DiLink Ocean Series — Light UI Palette ────────────────────────────────
// The car's lighter theme references Aurora White bodywork + ocean depth blues.

val BydOceanBlue         = Color(0xFF2E74D4)   // ← was #1A6EC8 (too dark navy), now: cobalt blue matching Sport btn
val BydOceanBlueLight    = Color(0xFFDEEAF7)   // ← was #CDEEAF7 (too cyan-tinted), now: subtle blue-white wash
val BydOceanBlueDark     = Color(0xFF0D2A4A)   // ← was #002233 (ok, slightly warmer)
val BydSecondaryLight         = Color(0xFF5B8DB8)   // #5B8DB8
val BydSecondaryLightContainer = Color(0xFFE5EBF3)  // ← was #DEEAF7 (mint green, very off)
val BydAuroraWhite       = Color(0xFFF5F7F9)   // ← was #F7F9FB (fine, minimal change)
val BydAtlantisGrey      = Color(0xFF374151)   // #374151
val BydSurfaceLight      = Color(0xFFFFFFFF)   // ← was #ECF4FA (too blue-tinted for cards)
val BydSurfaceVariantLight = Color(0xFFF4F8FC) // ← was #DCECF5 (too saturated blue)
val BydOutlineLight      = Color(0xFF8FA3B8)   // ← was #7BA5BE (ok, very close)
val BydOutlineVariantLight = Color(0xFFCDD8E3) // ← was #BDD6E6 (slightly less blue)

// The actual interactive button color used in both themes:
val BydElectricAzure        = Color(0xFF2196F3)   // segment buttons, toggles, active tabs
val BydElectricAzureDeep    = Color(0xFF0D2340)   // container / deep variant
// #f4f8fc
// ── Toggle / Switch unchecked state ──────────────────────────────────────────
val ToggleUncheckedTrack  = Color(0xFFBDBDBD)   // light silver track when off — matches native BYD toggle
val ToggleUncheckedThumb  = Color(0xFFF5F5F5)   // near-white thumb when off — matches native BYD toggle

// ── Semantic / functional colors (shared across themes) ───────────────────────

// Energy / EV telemetry
val BatteryBlue          = Color(0xFF2196F3)
val RegenGreen           = Color(0xFF4CAF50)
val AccelerationOrange   = Color(0xFFFF9800)
val ChargingYellow       = Color(0xFFFFC107)

// Motor chart — violet for front motor, pairs with BydElectricAzure (rear)
val MotorViolet          = Color(0xFFA78BFA)   // soft lavender-violet

// ── Power metrics — Range & Distance ────────────────────────────────────────
// Slot A  → Amber Gold: warm, "how far can I go" feel; distinct from AccelerationOrange
val IndigoDark       = Color(0xFFFFB300)   // vivid amber for dark theme
val IndigoLight      = Color(0xFFE65100)   // deep burnt-amber for light theme

// Slot B → Indigo Periwinkle: calm, "how far have I gone" feel; distinct from all blues
val AmberDark       = Color(0xFF7986CB)   // soft periwinkle for dark theme
val AmberLight      = Color(0xFF3949AB)   // deep indigo for light theme

// Slot C → Rose Coral: warm pink-red; distinct from error red and acceleration orange
val RoseCoralDark        = Color(0xFFF48FB1)   // soft rose for dark theme
val RoseCoralLight       = Color(0xFFC2185B)   // deep magenta-rose for light theme

// Range → Lime Chartreuse: yellow-green; distinct from RegenGreen and EcoTeal
val LimeChartreuseDark   = Color(0xFFD4E157)   // bright lime for dark theme
val LimeChartreuseLight  = Color(0xFF9E9D24)   // olive-lime for light theme

// Error — vivid red replacing Material 3's default pinkish error
val BydErrorRed          = Color(0xFFE53935)
val BydErrorRedLight     = Color(0xFFFF6B6B)   // brighter for dark backgrounds
val BydErrorContainer    = Color(0xFF7F0000)
val BydErrorContainerLight = Color(0xFFFFDAD6)
val BydOnErrorContainer  = Color(0xFFFFDAD6)
val BydOnErrorContainerLight = Color(0xFF410002)