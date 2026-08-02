package com.byd.tripstats.data.analysis

import com.byd.tripstats.data.local.entity.TripDataPointEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PhevTripAnalysisTest {

    /**
     * 20 km trip: first 10 km electric, last 10 km on the ICE burning 0.6 L
     * (lifetime counters: fuel 100.0→100.6 L, ICE 5000→5010 km, EV 8000→8010 km).
     */
    @Test
    fun splitsEvAndIceDistanceFromLifetimeCounters() {
        val points = listOf(
            point(ts = 0L, odo = 1000.0, raw = phevRaw(fuelL = 100.0, iceKm = 5000, evKm = 8000, mode = 1)),
            point(ts = 600_000L, odo = 1010.0, raw = phevRaw(fuelL = 100.0, iceKm = 5000, evKm = 8010, mode = 1)),
            point(ts = 1_200_000L, odo = 1020.0, raw = phevRaw(fuelL = 100.6, iceKm = 5010, evKm = 8010, mode = 3)),
        )

        val b = PhevTripAnalysis.analyze(points, tripEnergyKwh = 1.5)

        assertNotNull(b)
        b!!
        assertEquals(20.0, b.totalKm, 0.001)
        assertEquals(10.0, b.iceKm, 0.001)
        assertEquals(10.0, b.evKm, 0.001)
        assertEquals(50.0, b.evSharePct, 0.1)
        assertEquals(0.6, b.fuelLiters, 0.0001)
        // 0.6 L over 10 ICE km → 6.0 L/100km; over 20 total km → 3.0 L/100km
        assertEquals(6.0, b.fuelLPer100IceKm!!, 0.001)
        assertEquals(3.0, b.fuelLPer100TotalKm!!, 0.001)
        // 1.5 kWh over 10 EV km → 15 kWh/100km
        assertEquals(15.0, b.evKwhPer100EvKm!!, 0.001)
    }

    /** Pure-EV PHEV trip: 100 % EV share, no fuel figures. */
    @Test
    fun pureEvTripIsFullyElectric() {
        val points = listOf(
            point(ts = 0L, odo = 500.0, raw = phevRaw(fuelL = 50.0, iceKm = 2000, evKm = 3000, mode = 1)),
            point(ts = 600_000L, odo = 512.0, raw = phevRaw(fuelL = 50.0, iceKm = 2000, evKm = 3012, mode = 1)),
        )

        val b = PhevTripAnalysis.analyze(points, tripEnergyKwh = 2.0)

        assertNotNull(b)
        b!!
        assertEquals(100.0, b.evSharePct, 0.1)
        assertEquals(0.0, b.fuelLiters, 0.0001)
        assertNull(b.fuelLPer100IceKm)
        assertNull(b.fuelLPer100TotalKm)
    }

    /**
     * Short trip below the 1 km ICE-counter resolution: the counters never tick,
     * so ICE distance falls back to odometer integration over HEV-mode points.
     */
    @Test
    fun shortTripFallsBackToModeGatedOdometer() {
        val points = listOf(
            point(ts = 0L, odo = 100.0, raw = phevRaw(fuelL = 10.0, iceKm = 500, evKm = 900, mode = 3)),
            point(ts = 60_000L, odo = 100.4, raw = phevRaw(fuelL = 10.02, iceKm = 500, evKm = 900, mode = 3)),
            point(ts = 120_000L, odo = 100.8, raw = phevRaw(fuelL = 10.03, iceKm = 500, evKm = 900, mode = 1)),
        )

        val b = PhevTripAnalysis.analyze(points, tripEnergyKwh = null)

        assertNotNull(b)
        b!!
        // Both pairs led by mode-3 points: 0.4 + 0.4 km of ICE-propelled odometer.
        assertEquals(0.8, b.iceKm, 0.001)
        assertEquals(0.03, b.fuelLiters, 0.0001)
        assertNull(b.evKwhPer100EvKm)
    }

    /** A counter reset mid-trip is clamped away instead of corrupting the totals. */
    @Test
    fun counterResetDoesNotCorruptFuelTotal() {
        val points = listOf(
            point(ts = 0L, odo = 0.0, raw = phevRaw(fuelL = 999.0, iceKm = 100, evKm = 100, mode = 3)),
            point(ts = 60_000L, odo = 1.0, raw = phevRaw(fuelL = 999.1, iceKm = 101, evKm = 100, mode = 3)),
            // Lifetime fuel counter resets to 0 → pair delta is negative → ignored.
            point(ts = 120_000L, odo = 2.0, raw = phevRaw(fuelL = 0.05, iceKm = 102, evKm = 100, mode = 3)),
        )

        val b = PhevTripAnalysis.analyze(points, tripEnergyKwh = null)

        assertNotNull(b)
        assertEquals(0.1, b!!.fuelLiters, 0.0001)
    }

    /** BEV trips (no PHEV signals in any point) yield no breakdown. */
    @Test
    fun bevTripHasNoBreakdown() {
        val points = listOf(
            point(ts = 0L, odo = 10.0, raw = "{}"),
            point(ts = 60_000L, odo = 20.0, raw = "{}"),
        )
        assertNull(PhevTripAnalysis.analyze(points, tripEnergyKwh = 3.0))
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun phevRaw(fuelL: Double, iceKm: Int, evKm: Int, mode: Int): String =
        """{"total_fuel_consumption":$fuelL,"ice_mileage_km":$iceKm,"ev_mileage_km":$evKm,"energy_mode":$mode}"""

    private fun point(ts: Long, odo: Double, raw: String) = TripDataPointEntity(
        tripId         = 1,
        timestamp      = ts,
        latitude       = 0.0,
        longitude      = 0.0,
        altitude       = 0.0,
        speed          = 50.0,
        power          = 10.0,
        soc            = 50.0,
        odometer       = odo,
        batteryTemp    = 20.0,
        totalDischarge = 100.0,
        gear           = "D",
        isRegenerating = false,
        rawJson        = raw
    )
}
