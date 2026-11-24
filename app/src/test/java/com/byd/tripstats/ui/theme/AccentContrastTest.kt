package com.byd.tripstats.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.graphics.lerp
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [darkenedToContrast]: the accents as text on the light theme's card. */
class AccentContrastTest {

    private val card = BydOceanBlueLight
    private val accents = listOf(
        BydElectricBlue, BatteryBlue, RegenGreen, BydEcoTealDim, AccelerationOrange, BydOceanBlue, BydErrorRed,
    )

    private fun chroma(c: Color) = c.convert(ColorSpaces.Oklab).let { hypot(it.green, it.blue) }
    private fun hue(c: Color) = c.convert(ColorSpaces.Oklab).let { atan2(it.blue, it.green) }

    @Test
    fun `every accent reads on the card, and is no darker than that needs`() {
        accents.forEach { accent ->
            val ratio = contrastRatio(darkenedToContrast(accent, card, 4.8f), card)
            assertTrue("$accent at $ratio", ratio in 4.75f..4.95f)
        }
    }

    @Test
    fun `the hue stays, and more of the colour than a mix with black leaves`() {
        accents.forEach { accent ->
            val result = darkenedToContrast(accent, card, 4.8f)
            assertTrue("$accent hue", abs(hue(accent) - hue(result)) < 0.03f)
            assertTrue("$accent chroma", chroma(result) > chroma(lerp(accent, Color.Black, 0.45f)))
        }
    }

    @Test
    fun `a colour that already reads is left alone`() {
        assertEquals(BydOceanBlueDark, darkenedToContrast(BydOceanBlueDark, card, 4.8f))
    }
}
