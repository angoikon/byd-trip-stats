package com.byd.tripstats.util

import com.byd.tripstats.data.local.entity.TripDataPointEntity
import com.byd.tripstats.ui.screens.tripdetail.buildTripCsv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoidTest {

    @Test
    fun `undulation matches the check points NGA publishes for EGM96`() {
        // Within what bilinear interpolation on a 30' grid can reach; the 15' source is within 6 cm.
        listOf(
            Triple(38.6281550, 269.7791550, -31.628),
            Triple(-14.6212170, 305.0211140, -2.969),
            Triple(46.8743190, 102.4487290, -43.575),
            Triple(-23.6174460, 133.8747120, 15.871),
            Triple(38.6254730, 359.9995000, 50.066),
            Triple(-0.4667440, 0.0023000, 17.329),
        ).forEach { (latitude, longitude, expectedM) ->
            assertEquals(expectedM, Geoid.undulationM(latitude, longitude)!!, 0.3)
        }
    }

    @Test
    fun `sea level is 40 to 50 m above the ellipsoid across Italy`() {
        listOf(45.46 to 9.19, 44.49 to 11.34, 41.90 to 12.50, 40.85 to 14.27, 38.12 to 13.36)
            .forEach { (latitude, longitude) ->
                val undulationM = Geoid.undulationM(latitude, longitude)!!
                assertTrue("$latitude, $longitude: $undulationM", undulationM in 40.0..50.0)
            }
    }

    @Test
    fun `a reading of 265 m in Italy is about 220 m above sea level`() {
        assertEquals(217.0, Geoid.aboveSeaLevel(265.0, 45.07, 7.69), 1.0)
    }

    @Test
    fun `sea level below the ellipsoid raises a negative GPS reading`() {
        // Chennai: on the coast, the GPS reads about -90 m.
        assertEquals(2.0, Geoid.aboveSeaLevel(-90.0, 13.08, 80.27), 1.0)
    }

    @Test
    fun `a height can come out below sea level`() {
        // A polder near Amsterdam, where sea level is 43 m above the ellipsoid.
        assertEquals(-3.0, Geoid.aboveSeaLevel(40.0, 52.37, 4.90), 1.0)
    }

    @Test
    fun `no altitude and no position leave the reading alone`() {
        assertEquals(0.0, Geoid.aboveSeaLevel(0.0, 45.0, 9.0), 0.0)
        assertEquals(120.0, Geoid.aboveSeaLevel(120.0, 0.0, 0.0), 0.0)
        assertEquals(120.0, Geoid.aboveSeaLevel(120.0, Double.NaN, 9.0), 0.0)
        assertEquals(120.0, Geoid.aboveSeaLevel(120.0, 91.0, 9.0), 0.0)
        assertEquals(120.0, Geoid.aboveSeaLevel(120.0, 45.0, Double.POSITIVE_INFINITY), 0.0)
        assertTrue(Geoid.aboveSeaLevel(Double.NaN, 45.0, 9.0).isNaN())
    }

    @Test
    fun `invalid positions have no undulation`() {
        assertNull(Geoid.undulationM(90.1, 0.0))
        assertNull(Geoid.undulationM(-90.1, 0.0))
        assertNull(Geoid.undulationM(Double.NaN, 0.0))
        assertNull(Geoid.undulationM(0.0, Double.NaN))
    }

    @Test
    fun `both poles read the published values, whatever the longitude`() {
        assertEquals(13.61, Geoid.undulationM(90.0, 0.0)!!, 0.01)
        assertEquals(13.61, Geoid.undulationM(90.0, 123.4)!!, 0.01)
        assertEquals(-29.53, Geoid.undulationM(-90.0, 0.0)!!, 0.01)
        assertEquals(-29.53, Geoid.undulationM(-90.0, 123.4)!!, 0.01)
    }

    @Test
    fun `longitude wraps around the grid`() {
        val atGreenwich = Geoid.undulationM(51.5, 0.0)!!
        assertEquals(atGreenwich, Geoid.undulationM(51.5, 360.0)!!, 0.0)
        assertEquals(atGreenwich, Geoid.undulationM(51.5, -360.0)!!, 0.0)
        assertEquals(Geoid.undulationM(-33.87, 151.21)!!, Geoid.undulationM(-33.87, -208.79)!!, 1e-6)
        assertEquals(Geoid.undulationM(-17.0, 180.0)!!, Geoid.undulationM(-17.0, -180.0)!!, 0.0)
        // Between the last column and the first, the value stays between its two neighbours.
        val west = Geoid.undulationM(51.5, 359.5)!!
        val middle = Geoid.undulationM(51.5, 359.75)!!
        assertEquals((west + atGreenwich) / 2, middle, 1e-6)
    }

    @Test
    fun `a data point converts from its own position and keeps the raw reading`() {
        val point = point(altitude = 265.0, latitude = 45.07, longitude = 7.69)
        assertEquals(217.0, point.altitudeAboveSeaLevel, 1.0)
        assertEquals(265.0, point.altitude, 0.0)
    }

    @Test
    fun `the export keeps the GPS reading and adds the height, moving no existing column`() {
        val (header, row) = buildTripCsv(listOf(point(altitude = 265.0, latitude = 45.07, longitude = 7.69)))
            .trim().lines()
        val columns = header.split(",")
        val values = row.split(",")
        assertEquals("altitude", columns[3])
        assertEquals(265.0, values[3].toDouble(), 0.0)
        assertEquals("rawJson", columns.last())
        assertEquals("altitudeAboveSeaLevel", columns[columns.size - 2])
        assertEquals(217.0, values[columns.size - 2].toDouble(), 1.0)
    }

    private fun point(altitude: Double, latitude: Double, longitude: Double) = TripDataPointEntity(
        tripId = 1L,
        timestamp = 0L,
        latitude = latitude,
        longitude = longitude,
        altitude = altitude,
        speed = 0.0,
        power = 0.0,
        soc = 80.0,
        odometer = 1000.0,
        batteryTemp = 25.0,
        totalDischarge = 500.0,
        gear = "D",
        isRegenerating = false,
    )
}
