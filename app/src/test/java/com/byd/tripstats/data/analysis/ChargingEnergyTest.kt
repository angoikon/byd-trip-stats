package com.byd.tripstats.data.analysis

import com.byd.tripstats.data.local.entity.ChargingDataPointEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ChargingEnergyTest {

    private fun point(atSeconds: Long, kw: Double) = ChargingDataPointEntity(
        sessionId = 1L,
        timestamp = atSeconds * 1000L,
        soc = 50.0,
        chargingPower = kw,
    )

    @Test
    fun `a steady charge integrates to power times time`() {
        val points = (0..60).map { point(it * 60L, 10.0) }
        assertEquals(10.0, ChargingEnergy.integrate(points)!!, 1e-9)
    }

    @Test
    fun `a ramp is integrated as a trapezoid, not as its endpoints`() {
        // 0 kW to 20 kW over two minutes averages 10 kW, so 0.333 kWh. Carrying the last
        // reading across the whole interval would bill for 0.667.
        val points = listOf(point(0, 0.0), point(120, 20.0))
        assertEquals(10.0 * 120 / 3600.0, ChargingEnergy.integrate(points)!!, 1e-9)
    }

    /**
     * `chargingPower` read a flat zero on DiLink 5 until the SDK listener was fixed, through DC
     * sessions that filled the pack. A session of nothing but zeros is a listener that never
     * bound, so there is no measurement here to report — and reporting 0.00 kWh would state
     * that every one of those charges drew no power.
     */
    @Test
    fun `a session that never reported power has no received energy, not zero`() {
        val points = (0..20).map { point(it * 30L, 0.0) }
        assertNull(ChargingEnergy.integrate(points))
    }

    @Test
    fun `a session with nothing to integrate across reports nothing`() {
        assertNull(ChargingEnergy.integrate(emptyList()))
        assertNull(ChargingEnergy.integrate(listOf(point(0, 40.0))))
    }

    /**
     * The app can be force-stopped mid-charge and revived into the same session. Nothing was
     * observed across that hole, and a trapezoid drawn over it would bill the owner for an
     * average the charger never confirmed.
     */
    @Test
    fun `a hole where the app was not running is left out of the integral`() {
        val before = (0..10).map { point(it * 30L, 12.0) }          // 5 min at 12 kW = 1.0 kWh
        val after  = (0..10).map { point(3600L + it * 30L, 12.0) }  // another 1.0 kWh, an hour later
        val kwh = ChargingEnergy.integrate(before + after)!!
        assertEquals(2.0, kwh, 1e-9)
    }

    @Test
    fun `the integral does not depend on the order the samples arrive in`() {
        val points = (0..40).map { point(it * 30L, 5.0 + it) }
        assertEquals(
            ChargingEnergy.integrate(points)!!,
            ChargingEnergy.integrate(points.reversed())!!,
            1e-9,
        )
    }

    /**
     * One real DC session, recorded on a Sealion 7 (DiLink 5) at a Chargefox 50 kW charger on
     * 2026-10-11: 107 samples at the app's own 30 s charging cadence, 49% to 81%, peak 43.3 kW.
     * The pack gained 26.5 kWh (40.1 → 66.4 kWh remaining) and the charger's meter read
     * 28.93 kWh, billed at a confirmed A$0.59/kWh for A$17.07.
     *
     * Each pair below is "seconds since the session opened,kW". Nothing else from the capture is
     * included: a charging sample carries no position and no identifiers.
     *
     * This is what says the integral is worth storing. On DC the car reads the power on its way
     * in, so the figure can be checked against a meter: the SoC delta is 8.4% under it, and
     * integrating the same samples the app already stores is 2.0% under. An AC charge has no
     * such check, because the car can only see what leaves its own on-board charger.
     */
    @Test
    fun `a real DC session integrates close to the charger meter, where the SoC delta does not`() {
        val meterKwh = 28.93
        val packGainKwh = 26.5

        // Pinned, not bounded. A band wide enough to call the integral "close to the meter" is
        // also wide enough to hide an interval being dropped, which is the mistake this guards.
        val received = ChargingEnergy.receivedKwh(REFERENCE_DC_SESSION, packGainKwh)!!
        assertEquals(28.36, received, 0.01)

        // The claim being checked: 2.0% under the meter against the SoC delta's 8.4%.
        assertTrue("integral was $received kWh against a meter reading of $meterKwh",
            abs(received - meterKwh) / meterKwh < 0.025)
        assertTrue("the SoC delta reads lower than the integral", packGainKwh < received)
    }

    /**
     * The fixture is a capture, so a hand-edit could quietly drop samples or open a hole wider
     * than the gap limit, and the integral would just come out smaller with every test still
     * green. These are the properties the figure above depends on.
     */
    @Test
    fun `the reference capture is still the session it claims to be`() {
        assertEquals(107, REFERENCE_DC_SESSION.size)
        val widest = REFERENCE_DC_SESSION.zipWithNext()
            .maxOf { (a, b) -> b.timestamp - a.timestamp }
        assertTrue("widest sample gap was ${widest}ms", widest < 5 * 60 * 1000L)
    }

    /**
     * More arrives than the pack keeps, so an integral that does not clear the SoC delta has not
     * seen the whole charge. Two real cases end up here, and both would understate what arrived:
     * the app was killed for part of it and the row was resumed over the hole, or the samples
     * came from a source that reads the pack side rather than the power coming in.
     */
    @Test
    fun `an integral that does not beat the pack gain is not a measurement of the charge`() {
        // 10 kW for an hour, against a pack that somehow gained more than that.
        val points = (0..120).map { point(it * 30L, 10.0) }
        assertEquals(10.0, ChargingEnergy.integrate(points)!!, 1e-9)
        assertNull(ChargingEnergy.receivedKwh(points, packGainKwh = 10.5))
        // Equal is rejected too: pack voltage times current is one of the sources chargingPower
        // can come from, and integrating that reproduces the SoC delta under a second name.
        assertNull(ChargingEnergy.receivedKwh(points, packGainKwh = 10.0))
        assertEquals(10.0, ChargingEnergy.receivedKwh(points, packGainKwh = 9.0)!!, 1e-9)
    }

    /** Past a third of the charge the loss stops being physical: one of the two readings is wrong. */
    @Test
    fun `an implausible gap between the two figures is not published either`() {
        val points = (0..120).map { point(it * 30L, 10.0) }
        assertNull(ChargingEnergy.receivedKwh(points, packGainKwh = 6.0))
        assertEquals(10.0, ChargingEnergy.receivedKwh(points, packGainKwh = 6.7)!!, 1e-9)
    }

    /** A reconstructed session has no pack gain to check against and no samples either. */
    @Test
    fun `a session with no pack gain reports no received energy`() {
        val points = (0..120).map { point(it * 30L, 10.0) }
        assertNull(ChargingEnergy.receivedKwh(points, packGainKwh = 0.0))
        assertNull(ChargingEnergy.receivedKwh(emptyList(), packGainKwh = 10.0))
    }

    /** Regen or a garbage reading must not be able to subtract from the total. */
    @Test
    fun `a negative reading is floored rather than credited back`() {
        val clean = listOf(point(0, 10.0), point(30, 10.0), point(60, 10.0))
        val dirty = listOf(point(0, 10.0), point(30, -50.0), point(60, 10.0))
        assertTrue(ChargingEnergy.integrate(dirty)!! < ChargingEnergy.integrate(clean)!!)
        assertTrue(ChargingEnergy.integrate(dirty)!! > 0.0)
    }

    /** Two packets can carry the same device timestamp; neither adds energy. */
    @Test
    fun `samples sharing a timestamp contribute nothing`() {
        val points = listOf(point(0, 10.0), point(0, 10.0), point(120, 10.0))
        assertEquals(10.0 * 120 / 3600.0, ChargingEnergy.integrate(points)!!, 1e-9)
    }

    /** The gap rule is a boundary, and a boundary that moves by one sample is a silent change. */
    @Test
    fun `a gap of exactly two minutes still counts, and a longer one does not`() {
        val exactly = listOf(point(0, 12.0), point(120, 12.0))
        val longer  = listOf(point(0, 12.0), point(121, 12.0))
        assertEquals(12.0 * 120 / 3600.0, ChargingEnergy.integrate(exactly)!!, 1e-9)
        assertNull(ChargingEnergy.integrate(longer))
    }

    /**
     * Unlocking the car pauses an AC charge, and DiLink-5 holds the session open for five
     * minutes rather than ending it, recording nothing while no power flows. The charge either
     * side is real; the stretch between is the charger sitting idle, and counting it at the
     * surrounding rate would invent energy that never flowed.
     */
    @Test
    fun `a pause the DiLink-5 hold covers is not counted as charging`() {
        val before = (0..4).map { point(it * 30L, 11.0) }                    // 2 min at 11 kW
        val after  = (0..4).map { point(120L + 240L + it * 30L, 11.0) }      // 4 min later, 2 min more
        val kwh = ChargingEnergy.integrate(before + after)!!
        assertEquals(2 * 11.0 * 120 / 3600.0, kwh, 1e-9)
    }

    /**
     * A near-full charge keeps settling after it closes: the BMS revises SoC upward for minutes
     * and the pack figure grows with it, while the integral is finished and cannot. Past the
     * point where the pack catches up, less energy would have arrived than the battery
     * kept, so the pair stops describing one charge.
     */
    @Test
    fun `a received figure the settled pack figure overtakes stops being plausible`() {
        // 4.95 kWh into the pack, 5.20 out of the charger: a 5% loss, entirely ordinary.
        assertTrue(ChargingEnergy.plausible(5.20, 4.95))
        // Settle finds another 1% of an 82.5 kWh pack and the pack figure passes the integral.
        assertFalse(ChargingEnergy.plausible(5.20, 5.775))
        // It also fails on the way there, as soon as the margin drops under the 1% floor.
        assertFalse(ChargingEnergy.plausible(5.20, 5.19))
    }

    /** Idle noise below the significance floor is not a charge. */
    @Test
    fun `a session that never rises above the noise floor reports nothing`() {
        assertNull(ChargingEnergy.integrate((0..20).map { point(it * 30L, 0.05) }))
    }

    private companion object {
        private const val REFERENCE_DC_SAMPLES =
        "0,19.7 31,20.3 75,40.4 106,42.7 136,42.8 166,42.8 196,42.8 227,42.7 257,42.7 287,42.7 317,42.7 " +
        "347,42.8 377,42.8 408,42.9 438,42.9 468,42.9 498,42.9 528,42.9 559,42.9 589,42.9 619,42.9 649,43 " +
        "679,43 709,43 739,43.1 770,43.1 800,43.1 830,43.1 860,43.1 890,43.2 920,43.2 951,43.2 981,43.2 " +
        "1011,43.2 1041,43.2 1071,43.2 1101,43.2 1132,43.2 1162,43.2 1192,43.3 1222,43.3 1252,43.3 " +
        "1282,38.1 1327,21.1 1357,21.1 1387,21.1 1417,21.1 1447,21.1 1507,21.1 1537,21.1 1567,21.1 " +
        "1598,21.1 1628,21.1 1688,21.1 1718,21.1 1748,21.1 1778,21.1 1838,21.1 1868,21.1 1898,21.1 " +
        "1928,21.1 1958,21.1 1988,21.1 2018,21.1 2048,21.1 2078,21.1 2108,21.1 2138,21.1 2198,21.1 " +
        "2228,21.1 2258,21.1 2288,21.1 2349,21.1 2379,21.1 2409,21.2 2439,21.1 2469,21.2 2529,21.2 " +
        "2559,21.2 2589,21.2 2619,21.2 2649,21.2 2709,21.2 2739,21.2 2769,21.2 2799,21.2 2829,21.2 " +
        "2889,21.2 2919,21.2 2949,21.2 2979,21.2 3009,21.2 3040,21.2 3070,21.2 3130,21.2 3160,21.2 " +
        "3190,21.2 3220,21.2 3280,21.2 3310,21.2 3340,21.2 3370,21.2 3400,21.2 3460,21.2 3490,21.2 " +
        "3520,21.2 3550,21.2"

        val REFERENCE_DC_SESSION: List<ChargingDataPointEntity> =
            REFERENCE_DC_SAMPLES.split(" ").map { pair ->
                val (seconds, kw) = pair.split(",")
                ChargingDataPointEntity(
                    sessionId = 1L,
                    timestamp = seconds.toLong() * 1000L,
                    soc = 50.0,
                    chargingPower = kw.toDouble(),
                )
            }
    }
}
