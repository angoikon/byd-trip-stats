package com.byd.tripstats.data.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for [InstantConsumptionEstimator] — the Power tile's instant consumption. */
class InstantConsumptionEstimatorTest {

    /**
     * Drives at a steady [speedKmh] for [seconds], one reading a second, with the motors at
     * [motorKw] and the battery giving [batteryKw] (the motors plus climate and the like), its
     * counter stepping in 0.1 kWh like the car's. Returns the last figure.
     */
    private fun InstantConsumptionEstimator.drive(
        startMs: Long, seconds: Int, motorKw: Double, speedKmh: Double, batteryKw: Double, startKwh: Double = 7000.0
    ): Double? {
        var last: Double? = null
        for (s in 0..seconds) {
            val exactKwh = startKwh + batteryKw * s / 3600.0
            val counter = Math.floor(exactKwh * 10.0) / 10.0
            last = update(startMs + s * 1000L, motorKw, speedKmh, counter)
        }
        return last
    }

    @Test
    fun `steady driving reads motor power over speed`() {
        // 15 kW at 100 km/h and no extra load: 15 kWh/100 km.
        val e = InstantConsumptionEstimator()
        val shown = e.drive(0L, seconds = 60, motorKw = 15.0, speedKmh = 100.0, batteryKw = 15.0)!!
        assertEquals(15.0, shown, 0.01)
    }

    @Test
    fun `the extra load the motors leave out is learned and added`() {
        // Motors 15 kW, battery 17 kW (2 kW of climate): after the learning time the figure
        // reads 17 kWh/100 km at 100 km/h, not 15.
        val e = InstantConsumptionEstimator()
        val early = e.drive(0L, seconds = 120, motorKw = 15.0, speedKmh = 100.0, batteryKw = 17.0)!!
        assertEquals(15.0, early, 0.01)                       // nothing learned in the first 5 min
        val settled = e.drive(121_000L, seconds = 1800, motorKw = 15.0, speedKmh = 100.0, batteryKw = 17.0,
            startKwh = 7000.0 + 17.0 * 121 / 3600.0)!!
        assertEquals(2.0, e.extraLoadKw, 0.15)
        assertEquals(17.0, settled, 0.2)
    }

    @Test
    fun `no figure below walking pace, and regen reads negative`() {
        val e = InstantConsumptionEstimator()
        assertNull(e.drive(0L, seconds = 30, motorKw = 3.0, speedKmh = 5.0, batteryKw = 3.0))
        val regen = InstantConsumptionEstimator().drive(0L, seconds = 30, motorKw = -20.0, speedKmh = 50.0, batteryKw = -20.0)!!
        assertTrue("regen should read negative (was $regen)", regen < 0.0)
        assertEquals(-40.0, regen, 0.01)
    }

    @Test
    fun `a gap keeps what was learned and a new trip starts over`() {
        val e = InstantConsumptionEstimator()
        e.drive(0L, seconds = 1800, motorKw = 15.0, speedKmh = 100.0, batteryKw = 17.0)
        val learned = e.extraLoadKw
        assertTrue(learned > 1.5)
        // Dashboard hidden for a minute: smoothing restarts, the extra load stays.
        e.update(1_861_000L, 15.0, 100.0, 7008.6)
        assertEquals(learned, e.extraLoadKw, 1e-9)
        e.resetExtraLoad()
        assertEquals(0.0, e.extraLoadKw, 1e-9)
    }

    @Test
    fun `a counter glitch is ignored`() {
        // A 50 kWh jump between two readings isn't driving; the learned load stays sane.
        val e = InstantConsumptionEstimator()
        e.drive(0L, seconds = 600, motorKw = 15.0, speedKmh = 100.0, batteryKw = 15.0)
        e.update(601_000L, 15.0, 100.0, 7050.0)
        e.drive(602_000L, seconds = 600, motorKw = 15.0, speedKmh = 100.0, batteryKw = 15.0, startKwh = 7050.0)
        assertTrue("extra load ${e.extraLoadKw} kW", e.extraLoadKw < 0.5)
    }
}
