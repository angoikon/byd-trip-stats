package com.byd.tripstats.ui.screens.dashboard

import com.byd.tripstats.data.preferences.UnitSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Speed tile's second view: the GPS altitude as height above sea level. */
class SpeedTileAltitudeTest {

    @Test
    fun `shows height above sea level in whole metres`() {
        // Turin, where sea level is about 48 m above the ellipsoid.
        val value = formatAltitudeAboveSeaLevel(265.0, 45.07, 7.69, UnitSystem.METRIC)
        assertTrue(value, value.toInt() in 216..218)
    }

    @Test
    fun `shows feet in imperial units`() {
        val value = formatAltitudeAboveSeaLevel(265.0, 45.07, 7.69, UnitSystem.IMPERIAL)
        assertTrue(value, value.toInt() in 708..716)
    }

    @Test
    fun `shows a height below sea level`() {
        // A polder near Amsterdam, where sea level is 43 m above the ellipsoid.
        assertEquals("-3", formatAltitudeAboveSeaLevel(40.0, 52.37, 4.90, UnitSystem.METRIC))
    }

    @Test
    fun `shows a dash without a GPS altitude`() {
        assertEquals("—", formatAltitudeAboveSeaLevel(0.0, 45.07, 7.69, UnitSystem.METRIC))
        assertEquals("—", formatAltitudeAboveSeaLevel(Double.NaN, 45.07, 7.69, UnitSystem.METRIC))
    }
}
