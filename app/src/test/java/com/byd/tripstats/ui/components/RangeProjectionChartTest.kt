package com.byd.tripstats.ui.components

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

/**
 * Tests for [bmsAtDistances], the one-pass BMS interpolation behind the range chart's
 * green/orange fill. It replaced a per-point search, so it must give exactly what that did.
 */
class RangeProjectionChartTest {

    /** The per-point search it replaced — the reference. */
    private fun interpolateBmsAt(targetDist: Double, bmsPoints: List<Pair<Double, Double>>): Double {
        if (bmsPoints.isEmpty()) return 0.0
        if (targetDist <= bmsPoints.first().first) return bmsPoints.first().second
        if (targetDist >= bmsPoints.last().first)  return bmsPoints.last().second
        val hi = bmsPoints.indexOfFirst { it.first >= targetDist }
        if (hi <= 0) return bmsPoints.first().second
        val (d0, r0) = bmsPoints[hi - 1]
        val (d1, r1) = bmsPoints[hi]
        val t = if (d1 > d0) (targetDist - d0) / (d1 - d0) else 0.0
        return r0 + t * (r1 - r0)
    }

    private fun assertMatchesReference(projected: List<Pair<Double, Double>>, bms: List<Pair<Double, Double>>) {
        val expected = projected.map { interpolateBmsAt(it.first, bms) }.toDoubleArray()
        assertArrayEquals(expected, bmsAtDistances(projected, bms), 0.0)
    }

    @Test
    fun `matches the per-point search on chart-shaped trips`() {
        // As the chart builds them: one distance-sorted point list every ~100 m, the BMS series
        // from all of it and the projected series from the points that carry a projection.
        val random = Random(7)
        repeat(200) {
            var distance = 0.0
            var bmsKm = 300.0 + random.nextDouble(200.0)
            val points = List(random.nextInt(1, 400)) {
                distance += if (random.nextInt(10) == 0) 0.0 else 0.1 + random.nextDouble(0.05)
                bmsKm -= random.nextDouble(0.3)
                Triple(distance, bmsKm, random.nextInt(4) != 0)   // a quarter carry no projection
            }
            val bms = points.map { (d, r, _) -> d to r }
            val projected = points.filter { it.third }.map { (d, r, _) -> d to r + 20.0 }
            assertMatchesReference(projected, bms)
        }
    }

    @Test
    fun `holds at the edges`() {
        val bms = listOf(1.0 to 100.0, 2.0 to 90.0, 2.0 to 85.0, 3.0 to 70.0)
        // Before the first point, on a repeated distance, between points, and past the last.
        assertMatchesReference(listOf(0.5 to 0.0, 1.0 to 0.0, 2.0 to 0.0, 2.5 to 0.0, 3.0 to 0.0, 9.0 to 0.0), bms)
        assertMatchesReference(listOf(1.5 to 0.0), listOf(1.0 to 50.0))
        assertMatchesReference(emptyList(), bms)
    }

    @Test
    fun `an empty BMS series reads zero, as the search did`() {
        assertArrayEquals(doubleArrayOf(0.0, 0.0), bmsAtDistances(listOf(1.0 to 5.0, 2.0 to 6.0), emptyList()), 0.0)
    }

    @Test
    fun `interpolates between neighbouring BMS points`() {
        val result = bmsAtDistances(listOf(1.25 to 0.0), listOf(1.0 to 100.0, 2.0 to 80.0))
        assertEquals(95.0, result.single(), 1e-9)
    }
}
