package com.byd.tripstats.data

import com.byd.tripstats.data.local.entity.TripEntity
import com.byd.tripstats.data.local.entity.combinedEfficiency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Unit tests for [combinedEfficiency] — consumption across trips as total energy over total distance. */
class CombinedEfficiencyTest {

    private fun trip(km: Double?, kwh: Double?) = TripEntity(
        startTime = 0L,
        startOdometer = 1_000.0,
        endOdometer = km?.let { 1_000.0 + it },
        startSoc = 80.0,
        startTotalDischarge = 500.0,
        endTotalDischarge = kwh?.let { 500.0 + it },
        isActive = false,
    )

    @Test
    fun `a short hop weighs by its distance, not as one trip of two`() {
        // 100 km at 18 and 2 km at 40: the plain mean says 29, the drive was 18.4.
        val trips = listOf(trip(100.0, 18.0), trip(2.0, 0.8))
        assertEquals(18.43, trips.combinedEfficiency()!!, 0.01)
    }

    @Test
    fun `a single trip gives its own figure`() {
        assertEquals(20.0, listOf(trip(40.0, 8.0)).combinedEfficiency()!!, 1e-9)
    }

    @Test
    fun `trips with no figure are left out`() {
        val trips = listOf(trip(40.0, 8.0), trip(null, 3.0), trip(10.0, null), trip(0.0, 1.0))
        assertEquals(20.0, trips.combinedEfficiency()!!, 1e-9)
    }

    @Test
    fun `no trip with a figure gives null`() {
        assertNull(emptyList<TripEntity>().combinedEfficiency())
        assertNull(listOf(trip(0.0, 1.0), trip(null, 2.0)).combinedEfficiency())
    }
}
