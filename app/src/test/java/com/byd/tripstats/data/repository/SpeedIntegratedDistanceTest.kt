package com.byd.tripstats.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [integrateSpeedKm] — a recovered trip's distance rebuilt from its stored points on
 * cars that report no odometer (DiLink 100), where a resume used to restart the distance at 0.
 */
class SpeedIntegratedDistanceTest {

    private fun steady(kmh: Double, everyMs: Long, forMs: Long, fromMs: Long = 0L) =
        (0..(forMs / everyMs)).map { fromMs + it * everyMs to kmh }

    @Test
    fun `constant speed integrates to speed times time`() {
        // 60 km/h for 10 minutes, a point every 10 s → 10 km.
        assertEquals(10.0, integrateSpeedKm(steady(60.0, 10_000L, 600_000L)), 1e-9)
    }

    @Test
    fun `uneven point spacing gives the same distance`() {
        val samples = listOf(0L to 50.0, 3_000L to 50.0, 13_000L to 50.0, 15_000L to 50.0, 72_000L to 50.0)
        // 72 s at 50 km/h = 1.0 km.
        assertEquals(1.0, integrateSpeedKm(samples), 1e-9)
    }

    @Test
    fun `speed changes use the trapezoid between points`() {
        // 0 → 72 km/h over 10 s: average 36 km/h for 10 s = 0.1 km.
        assertEquals(0.1, integrateSpeedKm(listOf(0L to 0.0, 10_000L to 72.0)), 1e-9)
    }

    @Test
    fun `a gap longer than a minute is not counted`() {
        val before = steady(60.0, 10_000L, 60_000L)                       // 1 km
        val after = steady(60.0, 10_000L, 60_000L, fromMs = 300_000L)     // 1 km, after a 4-min gap
        assertEquals(2.0, integrateSpeedKm(before + after), 1e-9)
    }

    @Test
    fun `standing still adds nothing`() {
        assertEquals(0.0, integrateSpeedKm(steady(0.0, 5_000L, 120_000L)), 0.0)
    }

    @Test
    fun `implausible and broken speeds are skipped`() {
        val samples = listOf(0L to 60.0, 10_000L to 900.0, 20_000L to 60.0, 30_000L to Double.NaN, 40_000L to 60.0)
        assertEquals(0.0, integrateSpeedKm(samples), 0.0)
    }

    @Test
    fun `fewer than two points give no distance`() {
        assertEquals(0.0, integrateSpeedKm(emptyList()), 0.0)
        assertEquals(0.0, integrateSpeedKm(listOf(0L to 80.0)), 0.0)
    }
}
