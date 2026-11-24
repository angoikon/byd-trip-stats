package com.byd.tripstats.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.graphics.luminance
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** WCAG contrast ratio of two opaque colours, 1 (the same) to 21 (black on white). */
internal fun contrastRatio(a: Color, b: Color): Float {
    val la = a.luminance()
    val lb = b.luminance()
    return (max(la, lb) + 0.05f) / (min(la, lb) + 0.05f)
}

/**
 * [color] darkened only as far as [ratio] against a light [background] needs, keeping its hue and as
 * much of its colourfulness as sRGB can show at that lightness. Mixing with black instead takes the
 * colour away along with the brightness: `lerp` works in Oklab, where 45 % black is also 45 % less
 * chroma. Unchanged when it already reads at [ratio]; opaque colours only.
 */
internal fun darkenedToContrast(color: Color, background: Color, ratio: Float): Color {
    if (contrastRatio(color, background) >= ratio) return color
    val lab = color.convert(ColorSpaces.Oklab)
    val chroma = hypot(lab.green, lab.blue)
    val hue = atan2(lab.blue, lab.green)
    // Bisect on lightness for the lightest colour that still reads: 0 (black) always does.
    var dark = 0f
    var light = lab.red
    repeat(16) {
        val mid = (dark + light) / 2f
        if (contrastRatio(inGamut(mid, chroma, hue), background) >= ratio) dark = mid else light = mid
    }
    return inGamut(dark, chroma, hue)
}

/** The Oklab colour of this lightness and hue with the most chroma, up to [chroma], that sRGB has. */
private fun inGamut(lightness: Float, chroma: Float, hue: Float): Color {
    var low = 0f
    var high = chroma
    if (linearSrgb(lightness, chroma, hue).all { it in GAMUT }) low = chroma
    else repeat(16) {
        val mid = (low + high) / 2f
        if (linearSrgb(lightness, mid, hue).all { it in GAMUT }) low = mid else high = mid
    }
    val (r, g, b) = linearSrgb(lightness, low, hue).map(::encoded)
    return Color(r, g, b)
}

private val GAMUT = -1e-4f..1.0001f

private fun linearSrgb(lightness: Float, chroma: Float, hue: Float): List<Float> {
    val a = chroma * cos(hue)
    val b = chroma * sin(hue)
    val l = (lightness + 0.3963377774f * a + 0.2158037573f * b).let { it * it * it }
    val m = (lightness - 0.1055613458f * a - 0.0638541728f * b).let { it * it * it }
    val s = (lightness - 0.0894841775f * a - 1.2914855480f * b).let { it * it * it }
    return listOf(
        4.0767416621f * l - 3.3077115913f * m + 0.2309699292f * s,
        -1.2684380046f * l + 2.6097574011f * m - 0.3413193965f * s,
        -0.0041960863f * l - 0.7034186147f * m + 1.7076147010f * s,
    )
}

private fun encoded(linear: Float): Float {
    val c = linear.coerceIn(0f, 1f)
    return (if (c <= 0.0031308f) 12.92f * c else 1.055f * c.pow(1f / 2.4f) - 0.055f).coerceIn(0f, 1f)
}
