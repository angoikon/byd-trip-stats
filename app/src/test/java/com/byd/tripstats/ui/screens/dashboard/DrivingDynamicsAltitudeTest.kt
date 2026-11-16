package com.byd.tripstats.ui.screens.dashboard

import com.byd.tripstats.data.preferences.UnitSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DrivingDynamicsAltitudeTest {

    @Test
    fun `shows height above sea level in whole metres`() {
        // Turin, where sea level is about 48 m above the ellipsoid.
        val text = dashboardAltitudeText(265.0, 45.07, 7.69, UnitSystem.METRIC)!!
        assertTrue(text, text.endsWith(" m"))
        assertTrue(text, text.removeSuffix(" m").toInt() in 216..218)
    }

    @Test
    fun `shows feet in imperial units`() {
        val text = dashboardAltitudeText(265.0, 45.07, 7.69, UnitSystem.IMPERIAL)!!
        assertTrue(text, text.endsWith(" ft"))
        assertTrue(text, text.removeSuffix(" ft").toInt() in 708..716)
    }

    @Test
    fun `shows a height below sea level`() {
        // A polder near Amsterdam, where sea level is 43 m above the ellipsoid.
        assertEquals("-3 m", dashboardAltitudeText(40.0, 52.37, 4.90, UnitSystem.METRIC))
    }

    @Test
    fun `shows nothing without a GPS altitude`() {
        assertNull(dashboardAltitudeText(0.0, 45.07, 7.69, UnitSystem.METRIC))
        assertNull(dashboardAltitudeText(Double.NaN, 45.07, 7.69, UnitSystem.METRIC))
    }
}
