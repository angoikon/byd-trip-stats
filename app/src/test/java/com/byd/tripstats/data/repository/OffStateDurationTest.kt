package com.byd.tripstats.data.repository

import com.byd.tripstats.data.local.entity.TripDataPointEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [TripRepository.computeOffStateDurationMs] and the per-head-unit gap threshold.
 *
 * The DiLink-5 case is modelled on a real trip (id 122 on a Sealion 7, 2026-09-25): the telemetry
 * loop slept 30 s every time the car slowed below 2 km/h, the gaps were stored as off-state, and a
 * ~20 min drive was shown as 12 min.
 */
class OffStateDurationTest {

    private val di3Threshold = TripRepository.offStateGapThresholdMs(isDiLink5 = false)
    private val di5Threshold = TripRepository.offStateGapThresholdMs(isDiLink5 = true)

    private fun pt(ts: Long) = TripDataPointEntity(
        tripId = 1L, timestamp = ts, latitude = 0.0, longitude = 0.0, altitude = 0.0,
        speed = 0.0, power = 0.0, soc = 50.0, odometer = 0.0, batteryTemp = 20.0,
        totalDischarge = 0.0, gear = "D", isRegenerating = false
    )

    /** Runs of points 1 s apart, separated by each of [gapsSec] in turn. */
    private fun trip(vararg gapsSec: Long): List<TripDataPointEntity> {
        val stepsMs = mutableListOf<Long>()
        repeat(4) { stepsMs += 1_000L }
        for (gap in gapsSec) {
            stepsMs += gap * 1_000L
            repeat(4) { stepsMs += 1_000L }
        }
        var ts = 0L
        return listOf(pt(0L)) + stepsMs.map { ts += it; pt(ts) }
    }

    @Test
    fun `thresholds - DiLink-3 keeps 20 s, DiLink-5 needs 2 min`() {
        assertEquals(20_000L, di3Threshold)
        assertEquals(120_000L, di5Threshold)
    }

    @Test
    fun `a 30 s red light is parked time on DiLink-3 but trip time on DiLink-5`() {
        val points = trip(30)
        assertEquals(30_000L, TripRepository.computeOffStateDurationMs(points, null, di3Threshold))
        assertEquals(0L, TripRepository.computeOffStateDurationMs(points, null, di5Threshold))
    }

    @Test
    fun `a long in-trip stop is still parked time on DiLink-5`() {
        val points = trip(5 * 60)
        assertEquals(300_000L, TripRepository.computeOffStateDurationMs(points, null, di5Threshold))
    }

    @Test
    fun `the trailing car-off window counts on every head unit`() {
        val points = trip()
        val end = points.last().timestamp + 180_000L     // car-off timeout after the last point
        assertEquals(180_000L, TripRepository.computeOffStateDurationMs(points, end, di3Threshold))
        assertEquals(180_000L, TripRepository.computeOffStateDurationMs(points, end, di5Threshold))
    }

    @Test
    fun `the default threshold is unchanged for existing callers`() {
        val points = trip(30, 25)
        assertEquals(
            TripRepository.computeOffStateDurationMs(points, null, di3Threshold),
            TripRepository.computeOffStateDurationMs(points, null)
        )
    }

    @Test
    fun `trip 122 shape - only the trailing window is parked on DiLink-5`() {
        // The stored gaps of the real trip, then the 3 min car-off timeout.
        val points = trip(60, 30, 30, 30, 60, 30, 30, 30, 30, 30, 30, 30, 30, 90)
        val end = points.last().timestamp + 180_000L
        assertEquals(720_000L, TripRepository.computeOffStateDurationMs(points, end, di3Threshold))
        assertEquals(180_000L, TripRepository.computeOffStateDurationMs(points, end, di5Threshold))
    }

    @Test
    fun `no points means no off-state`() {
        assertEquals(0L, TripRepository.computeOffStateDurationMs(emptyList(), 1_000L, di5Threshold))
    }
}
