package com.byd.tripstats.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [TripRepository.startSpeedKmh] — the speed that may open a trip by itself.
 */
class TripStartSpeedTest {

    private fun start(speed: Double, gps: Double?, fromGps: Boolean, di5: Boolean = true) =
        TripRepository.startSpeedKmh(speed, gps, fromGps, di5)

    @Test fun `GPS alone doesn't open a DiLink-5 trip`() {
        // Shark 6, 2026-10-17/18, parked under a roof: trip 48 opened on 7.0 kn, trip 60 on 14.3 kn
        assertEquals(0.0, start(12.964000225067139, 12.964000225067139, fromGps = true), 0.0)
        assertEquals(0.0, start(26.483601379394532, 26.483601379394532, fromGps = true), 0.0)
    }

    @Test fun `the car's own speed opens a DiLink-5 trip`() {
        assertEquals(34.0, start(34.0, 33.1, fromGps = false), 0.0)
        assertEquals(34.0, start(34.0, null, fromGps = false), 0.0)
    }

    @Test fun `GPS wandering above the car's own crawl doesn't count on DiLink-5`() {
        assertEquals(3.0, start(3.0, 19.4, fromGps = false), 0.0)
    }

    @Test fun `DiLink-3 keeps the higher of car speed and GPS`() {
        // its getters can wedge at 0 mid-drive, leaving GPS the only sign of a drive
        assertEquals(12.964000225067139, start(12.964000225067139, 12.964000225067139, fromGps = true, di5 = false), 0.0)
        assertEquals(19.4, start(3.0, 19.4, fromGps = false, di5 = false), 0.0)
        assertEquals(3.0, start(3.0, null, fromGps = false, di5 = false), 0.0)
    }
}
