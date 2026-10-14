package com.byd.tripstats.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [TripRepository.odometerShowsMovement] — when an odometer step may open a trip.
 */
class OdometerMovementTest {

    private fun moved(prev: Double, cur: Double, di5: Boolean = true) =
        TripRepository.odometerShowsMovement(prev, cur, di5)

    @Test fun `a DiLink-5 boot's whole-number reading giving way to the decimal is not a drive`() {
        // Shark 6, 2026-10-14: parked at 10702.5, read 10702.0 after the boot, then 10702.5 → trip 44 (0 km)
        assertFalse(moved(10702.0, 10702.5))
        // 2026-10-13: 10674.0 → 10674.9 opened trip 40 the same way
        assertFalse(moved(10674.0, 10674.9))
    }

    @Test fun `a decimal reading moving on is a drive`() {
        assertTrue(moved(10702.5, 10702.6))
        assertTrue(moved(10674.9, 10675.6))
    }

    @Test fun `a whole-number reading moving a full kilometre or more is a drive`() {
        assertTrue(moved(10702.0, 10703.0))
        assertTrue(moved(10702.0, 10704.2))
    }

    @Test fun `DiLink-3 keeps the old rule`() {
        assertTrue(moved(10702.0, 10702.5, di5 = false))
    }

    // ── anchoredStartOdometer: where a trip opened on the post-boot whole number starts ──

    private fun start(startKm: Double, lastEndKm: Double?, di5: Boolean = true) =
        TripRepository.anchoredStartOdometer(startKm, lastEndKm, di5)

    @Test fun `a trip opened on the post-boot whole number starts where the car parked`() {
        // Shark 6 trip 45: read 10702.0, last trip ended 10702.5 → 5.20 km recorded for 4.71 km
        assertEquals(10702.5, start(10702.0, 10702.5), 0.0)
    }

    @Test fun `a decimal reading is kept`() {
        assertEquals(10707.2, start(10707.2, 10707.2), 0.0)
        assertEquals(10702.6, start(10702.6, 10702.5), 0.0)
    }

    @Test fun `a previous end outside the kilometre, or none, changes nothing`() {
        assertEquals(10702.0, start(10702.0, 10703.4), 0.0)   // moved without the app since
        assertEquals(10702.0, start(10702.0, 10701.5), 0.0)   // behind the reading
        assertEquals(10702.0, start(10702.0, 10702.0), 0.0)
        assertEquals(10702.0, start(10702.0, null), 0.0)
    }

    @Test fun `DiLink-3 keeps the reading`() {
        assertEquals(10702.0, start(10702.0, 10702.5, di5 = false), 0.0)
    }

    @Test fun `no reading, or no step, is never a drive`() {
        assertFalse(moved(0.0, 14355.0))            // the car coming online
        assertFalse(moved(10702.5, 10702.5))
        assertFalse(moved(10702.5, 10702.505))      // below the 10 m step
        assertFalse(moved(10702.5, 10702.4))        // backwards
    }
}
