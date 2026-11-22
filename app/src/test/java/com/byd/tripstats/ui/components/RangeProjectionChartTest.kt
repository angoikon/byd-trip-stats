package com.byd.tripstats.ui.components

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    // ── rangeAxisFor: the y axis fitted to the lines ────────────────────────────────────────

    private fun point(distanceKm: Double, bmsKm: Int, projectedKm: Double?) =
        RangeDataPoint(distanceKm = distanceKm, soc = 80.0, electricDrivingRangeKm = bmsKm, projectedRangeKm = projectedKm)

    private fun assertAxis(min: Double, max: Double, step: Double, axis: RangeAxis) {
        assertEquals("min", min, axis.min, 1e-9)
        assertEquals("max", max, axis.max, 1e-9)
        assertEquals("step", step, axis.step, 1e-9)
    }

    @Test
    fun `fits the lines instead of running up from zero`() {
        // The dashboard of 2026-11-21: BMS 457 km, projected 390 km. The 67 km gap was 13 % of
        // the old 0–525 axis; on this one it is 45 %.
        assertAxis(350.0, 500.0, 50.0, rangeAxisFor(listOf(point(0.0, 457, 390.0)), 570.0, 457.0))
    }

    @Test
    fun `keeps its axis while the lines stay inside it`() {
        // Shaped like a 16 km Seal Excellence trip on a 10 km window: BMS ~390 km, the projection
        // opening at 330 from earlier driving, dipping to 309, then climbing to 460 as the window
        // fills with the trip's own lighter consumption.
        val opening = listOf(point(0.0, 391, 330.0))
        assertAxis(300.0, 450.0, 50.0, rangeAxisFor(opening, 520.0, 391.0))
        // The dip stays inside 300–450, so the axis holds: refitting every point (the first
        // version) dropped the floor to 250 here, 9 km above the line, for nothing.
        val dipped = opening + point(2.3, 389, 309.0)
        assertAxis(300.0, 450.0, 50.0, rangeAxisFor(dipped, 520.0, 391.0))
        // Past the top it widens, and only as far as the line needs.
        val climbed = dipped + point(11.9, 380, 460.0)
        assertAxis(300.0, 500.0, 50.0, rangeAxisFor(climbed, 520.0, 391.0))
    }

    @Test
    fun `a plug-in's short EV range zooms in as well`() {
        // A Shark 6 replay: BMS 80 → 72 km, projection 78 → 48 km. A fixed 100 km least span put
        // this on 0–150, worse than the old 0–92; a quarter of the range lets it fill 25–100.
        val points = (0..49).map { i -> point(i * 0.1, 80 - (i * 8) / 49, 78.0 - i * 30.0 / 49) }
        assertAxis(25.0, 100.0, 25.0, rangeAxisFor(points, 100.0, 80.0))
    }

    @Test
    fun `a long drive widens the axis and coarsens its gridlines`() {
        val points = (0..400).map { point(it.toDouble(), 500 - it, 480.0 - it) }
        // 80–520 km padded; at most four steps of 200 before rounding out.
        assertAxis(0.0, 600.0, 200.0, rangeAxisFor(points, 570.0, 500.0))
    }

    @Test
    fun `leaves out a BMS read of zero and a projection above WLTP`() {
        val points = listOf(point(0.0, 0, null), point(0.1, 300, 700.0), point(0.2, 300, null))
        // Only the 300 km reading counts: a 75 km window around it, rounded out to 25s.
        assertAxis(250.0, 350.0, 25.0, rangeAxisFor(points, 570.0, 300.0))
    }

    @Test
    fun `never runs below zero`() {
        assertAxis(0.0, 25.0, 25.0, rangeAxisFor(listOf(point(0.0, 5, 3.0)), 570.0, 5.0))
    }

    @Test
    fun `falls back when there is nothing to fit`() {
        assertAxis(250.0, 350.0, 25.0, rangeAxisFor(emptyList(), 570.0, 300.0))
        assertAxis(250.0, 350.0, 25.0, rangeAxisFor(listOf(point(0.0, 0, 900.0)), 570.0, 300.0))
    }

    @Test
    fun `only ever widens as a trip adds points, and holds every value it fitted`() {
        // The property the axis exists for: a new point may push an edge out but never pull one
        // in, so the chart can't jump back and forth. Trips with noise, BMS steps up and down,
        // early catalog-rate swings and saturated stretches.
        val random = Random(11)
        repeat(300) {
            var bms = 150.0 + random.nextDouble(400.0)
            var projected = bms + random.nextDouble(-120.0, 120.0)
            val points = List(random.nextInt(1, 600)) { i ->
                bms = (bms - random.nextDouble(0.0, 1.2) + if (random.nextInt(40) == 0) random.nextDouble(-15.0, 15.0) else 0.0)
                    .coerceAtLeast(1.0)
                projected += random.nextDouble(-6.0, 5.0)
                val p = when {
                    random.nextInt(25) == 0 -> null
                    random.nextInt(60) == 0 -> 650.0      // above the 570 km ceiling
                    else -> projected.coerceAtLeast(1.0)
                }
                point(i * 0.1, bms.toInt(), p)
            }
            var before: RangeAxis? = null
            for (k in 1..points.size) {
                val axis = rangeAxisFor(points.subList(0, k), 570.0, 400.0)
                before?.let {
                    assertTrue("floor rose: ${it.min} → ${axis.min}", axis.min <= it.min)
                    assertTrue("ceiling fell: ${it.max} → ${axis.max}", axis.max >= it.max)
                    assertTrue("step shrank: ${it.step} → ${axis.step}", axis.step >= it.step)
                }
                assertTrue("below zero: $axis", axis.min >= 0.0)
                assertTrue("too many gridlines: $axis", (axis.max - axis.min) / axis.step <= 6.0 + 1e-9)
                before = axis
            }
            val axis = before!!
            points.forEach { p ->
                if (p.electricDrivingRangeKm > 0) assertTrue("BMS outside $axis", p.electricDrivingRangeKm.toDouble() in axis.min..axis.max)
                p.projectedRangeKm?.takeIf { it <= 570.0 }?.let { assertTrue("$it outside $axis", it in axis.min..axis.max) }
            }
        }
    }
}
