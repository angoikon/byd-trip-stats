package com.byd.tripstats.util

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The grid ships as a Java resource, and a grid that fails to load only shows as altitudes quietly
 * staying raw. This runs the lookup on Android's own class loader, which the JVM tests can't.
 */
@RunWith(AndroidJUnit4::class)
class GeoidResourceTest {

    @Test
    fun theGridLoadsFromTheApk() {
        assertEquals(48.5, Geoid.undulationM(41.90, 12.50)!!, 0.5)
        assertEquals(216.5, Geoid.aboveSeaLevel(265.0, 41.90, 12.50), 0.5)
    }
}
