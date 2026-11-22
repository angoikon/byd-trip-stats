package com.byd.tripstats.ui.screens.dashboard

import com.byd.tripstats.ui.screens.dashboard.ReadingPart.FIGURE
import com.byd.tripstats.ui.screens.dashboard.ReadingPart.SEPARATOR
import com.byd.tripstats.ui.screens.dashboard.ReadingPart.UNIT
import org.junit.Assert.assertEquals
import org.junit.Test

/** [readingParts]: the Classic grid's readings split into full-size figures and smaller grey units. */
class StatReadingTest {

    private fun parts(value: String) = readingParts(value)

    @Test
    fun `figures lead and units recede`() {
        assertEquals(
            listOf("571" to FIGURE, " V " to UNIT, "/" to SEPARATOR, " " to UNIT, "13.70" to FIGURE, " V" to UNIT),
            parts("571 V / 13.70 V")
        )
        assertEquals(listOf("38048.7" to FIGURE, " km" to UNIT), parts("38048.7 km"))
        assertEquals(
            listOf("0" to FIGURE, " " to UNIT, "/" to SEPARATOR, " " to UNIT, "0" to FIGURE, " RPM" to UNIT),
            parts("0 / 0 RPM")
        )
    }

    @Test
    fun `a label before the figure and a source tag after it are both secondary`() {
        assertEquals(listOf("SoH: " to UNIT, "97.0" to FIGURE, "% (stat)" to UNIT), parts("SoH: 97.0% (stat)"))
        assertEquals(listOf("SoH：" to UNIT, "97.0" to FIGURE, "% (BMS)" to UNIT), parts("SoH：97.0% (BMS)"))
        assertEquals(listOf("Außentemperatur: " to UNIT, "18" to FIGURE, " °C" to UNIT), parts("Außentemperatur: 18 °C"))
    }

    @Test
    fun `the dash standing in for a missing figure is a figure`() {
        assertEquals(listOf("SoH: " to UNIT, "—" to FIGURE), parts("SoH: —"))
        assertEquals(
            listOf("—" to FIGURE, " V " to UNIT, "/" to SEPARATOR, " " to UNIT, "13.00" to FIGURE, " V" to UNIT),
            parts("— V / 13.00 V")
        )
        assertEquals(listOf("—" to FIGURE), parts("—"))
    }

    @Test
    fun `a minus sign and a decimal comma belong to the number`() {
        assertEquals(listOf("Ambient: " to UNIT, "-5" to FIGURE, " °C" to UNIT), parts("Ambient: -5 °C"))
        assertEquals(listOf("13,70" to FIGURE, " V" to UNIT), parts("13,70 V"))
        // A full stop that ends a word is not a decimal point.
        assertEquals(listOf("Temp. " to UNIT, "16" to FIGURE), parts("Temp. 16"))
    }

    @Test
    fun `a reading without figures keeps its words full size`() {
        assertEquals(
            listOf("Standard " to FIGURE, "/" to SEPARATOR, " Normal" to FIGURE),
            parts("Standard / Normal")
        )
        assertEquals(listOf("Eco" to FIGURE), parts("Eco"))
    }

    @Test
    fun `the parts put back together are the reading`() {
        listOf("571 V / 13.70 V", "SoH: 97.0% (stat)", "Standard / Normal", "", "— V / — V", "1015.1 kWh")
            .forEach { value -> assertEquals(value, parts(value).joinToString("") { it.first }) }
    }
}
