package com.byd.tripstats.ui.viewmodel

import com.byd.tripstats.ui.components.RangeDataPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [DashboardViewModel.projectedEvRangeKm] — the EV range projection
 * with the PHEV consumption floor that fixes the "87 km projected at 19 % SoC
 * vs a 10 km BMS reading" over-projection on plug-in hybrids.
 */
class DashboardViewModelProjectionTest {

    // Seal U DM-i: 15.2 kWh usable EV pack, 19.0 kWh/100km reference (= 190 Wh/km).
    private val phevUsableKwh = 15.2
    private val phevBaselineWhPerKm = 190.0

    @Test
    fun `PHEV floors ICE-diluted consumption at reference so the projection stays physical`() {
        // Reproduces the reported bug: at 19 % SoC the remaining EV energy is
        // ~2.89 kWh. The ICE was propelling / charge-sustaining, so the measured
        // battery Wh/km collapsed to ~33 — dividing by it produced 87 km.
        val remainingWh = phevUsableKwh * 1000.0 * 0.19
        val dilutedWhPerKm = 33.0

        val rawDivision = remainingWh / dilutedWhPerKm
        assertTrue("precondition: raw division balloons past physical range", rawDivision > 80.0)

        val projected = DashboardViewModel.projectedEvRangeKm(
            remainingEnergyWh = remainingWh,
            whPerKm = dilutedWhPerKm,
            baselineWhPerKm = phevBaselineWhPerKm,
            isPhev = true
        )

        // Softened floor: max(measured, PHEV_BASELINE_FLOOR_FRACTION × baseline). The ICE-diluted
        // 33 Wh/km is lifted to 0.7 × 190 = 133 Wh/km → 2888 / 133 ≈ 21.7 km. Still physical (not
        // the 87 km balloon), just less aggressive than a full-baseline floor. Derived from the
        // constant so a retune of the fraction keeps the test honest.
        val flooredRate = phevBaselineWhPerKm * DashboardViewModel.PHEV_BASELINE_FLOOR_FRACTION
        assertEquals(remainingWh / flooredRate, projected, 0.2)
        assertTrue("floor must keep the projection physical, far below the raw balloon",
            projected < rawDivision / 2.0)
    }

    @Test
    fun `PHEV passes through an honest measured rate unchanged`() {
        // Genuine EV driving at the rated rate — the floor must not alter it.
        val remainingWh = phevUsableKwh * 1000.0 * 0.50
        val projected = DashboardViewModel.projectedEvRangeKm(
            remainingEnergyWh = remainingWh,
            whPerKm = phevBaselineWhPerKm,
            baselineWhPerKm = phevBaselineWhPerKm,
            isPhev = true
        )
        assertEquals(remainingWh / phevBaselineWhPerKm, projected, 0.0001)
    }

    @Test
    fun `PHEV inefficient driving still projects below the rated range`() {
        // Worse-than-rated consumption is above the floor, so it passes through:
        // the "you're burning EV charge faster than rated" signal is preserved.
        val remainingWh = phevUsableKwh * 1000.0 * 0.50
        val thirstyWhPerKm = 260.0
        val projected = DashboardViewModel.projectedEvRangeKm(
            remainingEnergyWh = remainingWh,
            whPerKm = thirstyWhPerKm,
            baselineWhPerKm = phevBaselineWhPerKm,
            isPhev = true
        )
        val ratedRange = remainingWh / phevBaselineWhPerKm
        assertEquals(remainingWh / thirstyWhPerKm, projected, 0.0001)
        assertTrue("inefficient driving must project below rated range", projected < ratedRange)
    }

    @Test
    fun `BEV is never floored - a low measured rate is genuine`() {
        // On a BEV every kilometre is an EV kilometre, so a low measured rate is
        // real (downhill / gentle) and must pass through; the chart's WLTP cap
        // handles any genuine over-projection.
        val remainingWh = 82_500.0 * 0.50
        val efficientWhPerKm = 120.0
        val projected = DashboardViewModel.projectedEvRangeKm(
            remainingEnergyWh = remainingWh,
            whPerKm = efficientWhPerKm,
            baselineWhPerKm = 185.0,
            isPhev = false
        )
        assertEquals(remainingWh / efficientWhPerKm, projected, 0.0001)
    }

    @Test
    fun `non-positive rate yields zero instead of infinity or NaN`() {
        assertEquals(0.0, DashboardViewModel.projectedEvRangeKm(5000.0, 0.0, 190.0, isPhev = false), 0.0)
        assertEquals(0.0, DashboardViewModel.projectedEvRangeKm(5000.0, -10.0, 190.0, isPhev = false), 0.0)
    }

    // ── remainingEvEnergyWh — the numerator fix for large-battery PHEVs ──────────

    @Test
    fun `PHEV trusts the BMS remaining-EV-energy reading over capacity times SoC`() {
        // Tang DM-i: 44 kWh usable pack at 19 % computes ~8.36 kWh, but the BMS —
        // having netted out the charge-sustaining reserve — reports only 2.1 kWh.
        val socProduct = 44.0 * 1000.0 * 0.19
        val energy = DashboardViewModel.remainingEvEnergyWh(
            batteryKwh = 44.0,
            socPercent = 19.0,
            bmsRemainingEvKwh = 2.1,
            isPhev = true
        )
        assertEquals(2100.0, energy, 0.0001)
        assertTrue("precondition: SoC product overstates the energy", socProduct > energy)
    }

    @Test
    fun `PHEV falls back to capacity times SoC when the BMS reading is absent or out of envelope`() {
        val socProduct = 15.2 * 1000.0 * 0.19
        // Null (firmware never reported it)
        assertEquals(
            socProduct,
            DashboardViewModel.remainingEvEnergyWh(15.2, 19.0, null, isPhev = true),
            0.0001
        )
        // Implausible (> pack capacity) → rejected
        assertEquals(
            socProduct,
            DashboardViewModel.remainingEvEnergyWh(15.2, 19.0, 99.0, isPhev = true),
            0.0001
        )
        // Non-positive → rejected
        assertEquals(
            socProduct,
            DashboardViewModel.remainingEvEnergyWh(15.2, 19.0, 0.0, isPhev = true),
            0.0001
        )
    }

    @Test
    fun `BEV numerator is never affected by the BMS remaining-EV-energy reading`() {
        // Even with a present, in-envelope reading, a BEV must keep capacity × SoC.
        val socProduct = 82.5 * 1000.0 * 0.449
        val energy = DashboardViewModel.remainingEvEnergyWh(
            batteryKwh = 82.5,
            socPercent = 44.9,
            bmsRemainingEvKwh = 30.0,
            isPhev = false
        )
        assertEquals(socProduct, energy, 0.0001)
    }

    // ── EV reserve (CarConfig.phevEvReserveSocPct) — Shark 6 first ───────────────

    private val shark = com.byd.tripstats.data.config.CarCatalog.BYD_SHARK_6_AWD

    private fun sharkEnergyWh(soc: Double, bms: Double? = 128.0) = DashboardViewModel.remainingEvEnergyWh(
        batteryKwh = shark.phevUsableBatteryKwh!!, socPercent = soc, bmsRemainingEvKwh = bms, isPhev = true,
        packKwh = shark.batteryKwh, evReserveSocPct = shark.phevEvReserveSocPct,
    )

    @Test
    fun `Shark counts only the energy above its reserve`() {
        // Duncs 2026-10-14: car 19 km ⇒ SoC ≈ 42.6 % by the car's own 1.106 km/% × (SoC − 25.4 %).
        // The old SoC product was 26 kWh × 42.6 % ≈ 11.1 kWh — 54 km projected.
        assertEquals(29_580.0 * 0.176, sharkEnergyWh(42.6), 0.01)
        // At ~260 Wh/km (trips 39/41) that is ≈ 20 km, against the car's 19.
        assertEquals(20.0, sharkEnergyWh(42.6) / 260.0, 0.5)
    }

    @Test
    fun `Shark matches its own EV range across the charge`() {
        // (SoC %, car's EV range km) from trips 33/39/41 and the 13:37 / 15:52 screenshots
        for ((soc, carKm) in listOf(100.0 to 85.0, 95.0 to 79.0, 79.0 to 62.0, 42.0 to 20.0)) {
            assertEquals("SoC $soc", carKm, sharkEnergyWh(soc) / 267.0, 4.0)
        }
    }

    @Test
    fun `the reserve is never negative energy`() {
        assertEquals(0.0, sharkEnergyWh(20.0), 0.0)
    }

    @Test
    fun `a plausible BMS reading still wins over the reserve model`() {
        assertEquals(3_000.0, sharkEnergyWh(42.6, bms = 3.0), 0.0001)
    }

    @Test
    fun `without a reserve nothing changes`() {
        // A PHEV that doesn't declare one keeps capacity × SoC, whatever pack size is passed.
        assertEquals(
            15.2 * 1000.0 * 0.19,
            DashboardViewModel.remainingEvEnergyWh(15.2, 19.0, null, isPhev = true, packKwh = 18.3, evReserveSocPct = null),
            0.0001,
        )
        // And a BEV ignores a reserve even if one were passed.
        assertEquals(
            82.5 * 1000.0 * 0.449,
            DashboardViewModel.remainingEvEnergyWh(82.5, 44.9, null, isPhev = false, packKwh = 82.5, evReserveSocPct = 25.0),
            0.0001,
        )
    }

    @Test
    fun `a PHEV with a measured reserve doesn't take its window from trip history`() {
        // Trip kWh ÷ all km is engine-diluted on a PHEV — it floored the Shark's start at 103 km.
        assertTrue(!DashboardViewModel.historyApplies(shark))
    }

    @Test
    fun `every other car takes its window from trip history`() {
        val seal = com.byd.tripstats.data.config.CarCatalog.BYD_SEAL_EXCELLENCE
        assertTrue(DashboardViewModel.historyApplies(seal))
        assertTrue(DashboardViewModel.historyApplies(null))
        // A PHEV without a measured reserve too.
        val phev = com.byd.tripstats.data.config.CarCatalog.allCars.first { it.isPhev && it.phevEvReserveSocPct == null }
        assertTrue(DashboardViewModel.historyApplies(phev))
    }

    // ── mergeProjectionCurve — cached live curve vs DB rebuild on reopen ─────────

    private fun pt(distanceKm: Double, projected: Double?) =
        RangeDataPoint(
            distanceKm = distanceKm,
            soc = 50.0,
            electricDrivingRangeKm = 200,
            projectedRangeKm = projected,
            isStabilised = projected != null
        )

    @Test
    fun `merge with no cache returns the rebuild unchanged`() {
        val rebuilt = listOf(pt(0.0, 100.0), pt(1.0, 98.0))
        assertEquals(rebuilt, DashboardViewModel.mergeProjectionCurve(null, rebuilt, 1.0))
        assertEquals(rebuilt, DashboardViewModel.mergeProjectionCurve(emptyList(), rebuilt, 1.0))
    }

    @Test
    fun `merge with a fresh cache returns the cached curve verbatim (back-press reopen)`() {
        // Cache reaches the live distance, so there is no tail to append: the
        // high-fidelity live curve is shown as-is rather than the rebuild.
        val cached = listOf(pt(0.0, 120.0), pt(0.5, 118.0), pt(1.0, 117.0))
        val rebuilt = listOf(pt(0.0, 130.0), pt(0.5, 130.0), pt(1.0, 130.0)) // flat single-rate
        val merged = DashboardViewModel.mergeProjectionCurve(cached, rebuilt, liveDistanceKm = 1.0)
        assertEquals(cached, merged)
    }

    @Test
    fun `merge with a stale cache keeps the cached head and appends the rebuilt tail`() {
        // The cache only reached 1.0 km (process died there); the DB rebuild reaches
        // 3.0 km. Expect the cached head (≤ 1.0) plus the rebuilt points beyond it,
        // with no gap and strictly increasing distance.
        val cached = listOf(pt(0.0, 120.0), pt(0.5, 118.0), pt(1.0, 117.0))
        val rebuilt = listOf(pt(0.0, 130.0), pt(1.0, 128.0), pt(2.0, 126.0), pt(3.0, 124.0))
        val merged = DashboardViewModel.mergeProjectionCurve(cached, rebuilt, liveDistanceKm = 3.0)

        assertEquals(listOf(0.0, 0.5, 1.0, 2.0, 3.0), merged.map { it.distanceKm })
        // Head came from the cache (fidelity preserved), tail from the rebuild.
        assertEquals(117.0, merged[2].projectedRangeKm!!, 0.0001) // cached @1.0
        assertEquals(126.0, merged[3].projectedRangeKm!!, 0.0001) // rebuilt @2.0
        // Strictly increasing distance — no duplicate at the seam.
        assertTrue(merged.zipWithNext().all { (a, b) -> b.distanceKm > a.distanceKm })
    }

    @Test
    fun `merge drops cached points that read ahead of the live distance`() {
        // A cache reading beyond reality (e.g. odometer blip) must not paint points
        // past where the trip actually is.
        val cached = listOf(pt(0.0, 120.0), pt(1.0, 118.0), pt(5.0, 110.0))
        val rebuilt = listOf(pt(0.0, 130.0), pt(1.0, 128.0))
        val merged = DashboardViewModel.mergeProjectionCurve(cached, rebuilt, liveDistanceKm = 1.0)
        assertEquals(listOf(0.0, 1.0), merged.map { it.distanceKm })
    }

    // ── windowRate / buildDrivingHistory — the last N km, across trips ────────────

    /** A trip's cumulative (km, Wh) series at [whPerKm], 100 m apart. */
    private fun steadyTrip(fromKm: Double, toKm: Double, whPerKm: Double, startWh: Double = 0.0) =
        (0..Math.round((toKm - fromKm) * 10).toInt()).map { i ->
            val km = fromKm + i * 0.1
            km to startWh + (km - fromKm) * whPerKm
        }

    private fun history(vararg tripsNewestFirst: Pair<Double, Double>) =
        DashboardViewModel.buildDrivingHistory(
            tripsNewestFirst.map { (km, whPerKm) -> DashboardViewModel.Companion.HistoryTrip(km, km * whPerKm) },
            DashboardViewModel.MAX_WINDOW_KM
        )

    @Test
    fun `at switch-on the window is the driving before the trip`() {
        // Last trip 40 km at 150 Wh/km, the one before 100 km at 250. Nothing driven yet.
        val h = history(40.0 to 150.0, 100.0 to 250.0)
        val ten = DashboardViewModel.windowRate(emptyList(), h, 10.0)!!
        assertEquals(150.0, ten.whPerKm, 1e-6)
        assertEquals(10.0, ten.coveredKm, 1e-6)
        // 100 km: all 40 of the last trip and 60 of the one before.
        val hundred = DashboardViewModel.windowRate(emptyList(), h, 100.0)!!
        assertEquals((40 * 150.0 + 60 * 250.0) / 100.0, hundred.whPerKm, 1e-6)
        // 500 km reaches past the history: it averages what there is.
        val fiveHundred = DashboardViewModel.windowRate(emptyList(), h, 500.0)!!
        assertEquals(140.0, fiveHundred.coveredKm, 1e-6)
    }

    @Test
    fun `the trip takes over the window as it is driven`() {
        val h = history(100.0 to 150.0)
        val trip = steadyTrip(0.0, 30.0, 250.0)
        // 30 km into the trip, a 100 km window is 30 km of trip and 70 of history.
        val w = DashboardViewModel.windowRate(trip, h, 100.0)!!
        assertEquals((30 * 250.0 + 70 * 150.0) / 100.0, w.whPerKm, 1e-6)
        // A 10 km window is the trip's own last 10 km.
        assertEquals(250.0, DashboardViewModel.windowRate(trip, h, 10.0)!!.whPerKm, 1e-6)
    }

    @Test
    fun `past the window length the trip's own last N km is all that counts`() {
        // 5 km gentle, then 15 km thirsty: the last 10 km are all thirsty.
        val gentle = steadyTrip(0.0, 5.0, 100.0)
        val thirsty = steadyTrip(5.1, 20.0, 300.0, startWh = gentle.last().second + 0.1 * 300.0)
        val w = DashboardViewModel.windowRate(gentle + thirsty, history(100.0 to 150.0), 10.0)!!
        assertEquals(300.0, w.whPerKm, 1e-6)
        assertEquals(10.0, w.coveredKm, 1e-6)
    }

    @Test
    fun `no history and no driving yet has no window rate`() {
        assertEquals(null, DashboardViewModel.windowRate(emptyList(), null, 100.0))
        // Under MIN_WINDOW_DIST_KM, or before any energy is recorded, still nothing.
        assertEquals(null, DashboardViewModel.windowRate(listOf(0.0 to 0.0, 0.1 to 20.0), null, 100.0))
        assertEquals(null, DashboardViewModel.windowRate(listOf(0.0 to 0.0, 1.0 to 0.0), null, 100.0))
    }

    @Test
    fun `history points give the last trip's real last kilometres`() {
        // 20 km trip: the first 10 km cost 1 kWh, the last 10 km cost 3 kWh. Its average is
        // 200 Wh/km, but at switch-on the last 10 km of driving ran at 300.
        val points = (0..200).map { i ->
            val km = 1000.0 + i * 0.1
            val kwh = if (i <= 100) i * 0.01 else 1.0 + (i - 100) * 0.03
            km to kwh
        }
        val h = DashboardViewModel.buildDrivingHistory(
            listOf(DashboardViewModel.Companion.HistoryTrip(20.0, 4000.0, points)), DashboardViewModel.MAX_WINDOW_KM
        )!!
        assertEquals(3000.0, h.energyOverLastKm(10.0), 1.0)
        // The trip's totals still hold at its boundary.
        assertEquals(4000.0, h.energyOverLastKm(20.0), 1e-6)
        // Without points, the same trip is spread evenly.
        val flat = history(20.0 to 200.0)!!
        assertEquals(2000.0, flat.energyOverLastKm(10.0), 1e-6)
    }

    @Test
    fun `history stops once the longest window is covered`() {
        val h = history(*Array(20) { 100.0 to 150.0 })!!
        // 500 km is reached on the fifth trip; nothing beyond it is laid out.
        assertEquals(500.0, h.totalKm, 1e-6)
    }

    @Test
    fun `a fresh install starts on the catalog rate and earns its own`() {
        // No history: the first few hundred metres are shrunk toward the baseline...
        val early = DashboardViewModel.rangeRate(steadyTrip(0.0, 0.3, 900.0), null, 100.0, 180.0)
        assertEquals(DashboardViewModel.RangeModel.BASELINE, early.model)
        assertEquals(0.1 * 900.0 + 0.9 * 180.0, early.whPerKm, 1e-6)
        // ...and by STABILISATION_KM it is the driving alone.
        val settled = DashboardViewModel.rangeRate(steadyTrip(0.0, 5.0, 200.0), null, 100.0, 180.0)
        assertEquals(DashboardViewModel.RangeModel.RECENT_DRIVING, settled.model)
        assertEquals(200.0, settled.whPerKm, 1e-6)
    }

    @Test
    fun `with history the projection is the driver's own from switch-on`() {
        val r = DashboardViewModel.rangeRate(emptyList(), history(60.0 to 150.0), 100.0, 180.0)
        assertEquals(DashboardViewModel.RangeModel.RECENT_DRIVING, r.model)
        assertEquals(150.0, r.whPerKm, 1e-6)
    }

    @Test
    fun `the optimism cap's reference is the longest window`() {
        // A 10 km descent at 20 Wh/km after 400 km at 180: the 10 km window reads 20, but the
        // cap is held to the 500 km figure, so the projection can't balloon.
        val trip = steadyTrip(0.0, 10.0, 20.0)
        val r = DashboardViewModel.rangeRate(trip, history(400.0 to 180.0), 10.0, 180.0)
        assertEquals(20.0, r.whPerKm, 1e-6)
        assertEquals((10 * 20.0 + 400 * 180.0) / 410.0, r.referenceWhPerKm, 1e-6)
    }

    // ── windowStepWh — the window series keeps regen on BEVs ───────────────────────

    /**
     * A stop-and-go net-counter trace, 100 m apart: every odd step uses 45 Wh, every even one
     * regenerates 15 Wh more than it uses — 150 Wh/km net — built into the window series the way
     * the live loop does.
     */
    private fun stopAndGoSeries(km: Double, isPhev: Boolean): List<Pair<Double, Double>> {
        val series = arrayListOf(0.0 to 0.0)
        var raw = 0.0
        var cumulative = 0.0
        val steps = Math.round(km * 10).toInt()
        for (i in 1..steps) {
            val previousRaw = raw
            raw += if (i % 2 == 1) 45.0 else -15.0
            cumulative += DashboardViewModel.windowStepWh(raw - previousRaw, 0.1, isPhev)
            series += (i * 0.1) to cumulative
        }
        return series
    }

    @Test
    fun `a BEV window reads the same net consumption as the trip, regen included`() {
        // The trip's own figure (end − start of the net counter) is 150 Wh/km; the window must
        // agree. Clamping each step at 0 read 225 — the regen thrown away.
        val w = DashboardViewModel.windowRate(stopAndGoSeries(20.0, isPhev = false), null, 10.0)!!
        assertEquals(150.0, w.whPerKm, 1e-6)
    }

    @Test
    fun `a PHEV window still floors each step at zero`() {
        // A PHEV's counter also steps back while the engine charges the pack, which isn't
        // electric driving, so its steps stay ≥ 0.
        val w = DashboardViewModel.windowRate(stopAndGoSeries(20.0, isPhev = true), null, 10.0)!!
        assertEquals(225.0, w.whPerKm, 1e-6)
    }

    @Test
    fun `a window step is bounded both ways and a broken read counts nothing`() {
        // A 5 kWh counter rebase inside 100 m is clipped to 150 Wh, either sign; a real step isn't.
        assertEquals(150.0, DashboardViewModel.windowStepWh(5_000.0, 0.1, isPhev = false), 1e-9)
        assertEquals(-150.0, DashboardViewModel.windowStepWh(-5_000.0, 0.1, isPhev = false), 1e-9)
        assertEquals(-20.0, DashboardViewModel.windowStepWh(-20.0, 0.1, isPhev = false), 1e-9)
        assertEquals(0.0, DashboardViewModel.windowStepWh(-20.0, 0.1, isPhev = true), 1e-9)
        assertEquals(0.0, DashboardViewModel.windowStepWh(Double.NaN, 0.1, isPhev = false), 1e-9)
    }

    // ── WindowStepper — stored points book a stop the way the live window did ──────────

    /** 10 s points over 1.9 h of standing with the climate on: 1.3 kWh, the odometer still. */
    private val standingPairs = 684
    private val standingWhPerPair = 1300.0 / standingPairs

    @Test
    fun `a long stop is booked at move-off, bounded like one live sample`() {
        val stepper = DashboardViewModel.Companion.WindowStepper(isPhev = false)
        assertEquals(18.0, stepper.add(18.0, 0.1), 1e-9)
        var booked = 0.0
        repeat(standingPairs) { booked += stepper.add(standingWhPerPair, 0.0) }
        assertEquals(0.0, booked, 1e-9)
        assertEquals(1300.0, stepper.pendingWh, 1e-6)
        // The first 100 m after it carries the stop, clipped to 150 Wh — not 1.3 kWh.
        assertEquals(150.0, stepper.add(18.0, 0.1), 1e-9)
        assertEquals(0.0, stepper.pendingWh, 1e-9)
        // An odometer count read through a Float can land under 0.1; it is still a step.
        assertEquals(18.0, stepper.add(18.0, 10000.1f.toDouble() - 10000.0f.toDouble()), 1e-9)
    }

    @Test
    fun `stored points give the window the live loop built`() {
        // 5 km at 180 Wh/km, the 1.3 kWh stop, 5 km more: (odometer, Wh) as the data points hold it.
        val points = arrayListOf(1000.0 to 0.0)
        for (i in 1..100) {
            if (i == 51) repeat(standingPairs) { points += points.last().first to points.last().second + standingWhPerPair }
            points += (1000.0 + i * 0.1) to points.last().second + 18.0
        }
        // Live: a sample each time the car has moved 100 m, the energy since the one before bounded.
        val live = arrayListOf(0.0 to 0.0)
        var lastSample = points.first()
        for (p in points.drop(1)) {
            if (p.first - lastSample.first < 0.1 - 1e-9) continue
            live += (p.first - 1000.0) to live.last().second + DashboardViewModel.windowStepWh(p.second - lastSample.second, 0.1, false)
            lastSample = p
        }
        // Rebuilt: every stored pair through the stepper, one sample per point.
        val stepper = DashboardViewModel.Companion.WindowStepper(isPhev = false)
        val rebuilt = arrayListOf(0.0 to 0.0)
        points.zipWithNext { a, b ->
            rebuilt += (b.first - 1000.0) to rebuilt.last().second + stepper.add(b.second - a.second, b.first - a.first)
        }
        val liveRate = DashboardViewModel.windowRate(live, null, 10.0)!!.whPerKm
        // 99 steps at 18 Wh and the move-off step at 150: the stop adds 13.2 Wh/km, not 130.
        assertEquals((99 * 18.0 + 150.0) / 10.0, liveRate, 1e-6)
        assertEquals(liveRate, DashboardViewModel.windowRate(rebuilt, null, 10.0)!!.whPerKm, 1e-6)
    }

    @Test
    fun `history leaves out the part of a long stop the live window never booked`() {
        // 20 km at 200 Wh/km with the 1.3 kWh stop 5 km before the end: the trip used 5.3 kWh.
        val points = arrayListOf(1000.0 to 0.0)
        for (i in 1..200) {
            if (i == 151) repeat(standingPairs) { points += points.last().first to points.last().second + standingWhPerPair / 1000.0 }
            points += (1000.0 + i * 0.1) to points.last().second + 0.02
        }
        val tripWh = points.last().second * 1000.0
        assertEquals(5300.0, tripWh, 1e-6)
        val h = DashboardViewModel.buildDrivingHistory(
            listOf(DashboardViewModel.Companion.HistoryTrip(20.0, tripWh, points)), DashboardViewModel.MAX_WINDOW_KM
        )!!
        // The last 10 km: 100 steps at 20 Wh, one of them carrying the stop clipped to 150.
        assertEquals(2130.0, h.energyOverLastKm(10.0), 1e-6)
        // The whole trip, less the 1170 Wh the window never booked.
        assertEquals(4130.0, h.energyOverLastKm(20.0), 1e-6)
    }

    @Test
    fun `history point detail keeps the regen in a trip's last kilometres`() {
        // 20 km trip: the first 10 km at 200 Wh/km, the last 10 km stop-and-go at 100 Wh/km net
        // (+30 / −10 Wh per 100 m). 3 kWh in all. The last 10 km used 1 kWh, not the 1.29 kWh a
        // regen-less shape gave it.
        var kwh = 0.0
        val points = (0..200).map { i ->
            if (i in 1..100) kwh += 0.02
            else if (i > 100) kwh += if (i % 2 == 1) 0.03 else -0.01
            (1000.0 + i * 0.1) to kwh
        }
        val h = DashboardViewModel.buildDrivingHistory(
            listOf(DashboardViewModel.Companion.HistoryTrip(20.0, kwh * 1000.0, points)),
            DashboardViewModel.MAX_WINDOW_KM
        )!!
        assertEquals(3000.0, h.energyOverLastKm(20.0), 1e-6)
        assertEquals(1000.0, h.energyOverLastKm(10.0), 15.0)
    }

    // ── projectedEvRangeKm optimism cap — bounds the downhill balloon ────────────

    @Test
    fun `optimism cap bounds an over-optimistic rate to a fraction of the reference`() {
        // A downhill rolling rate of 60 Wh/km against a demonstrated trip average of 180.
        // The cap floors the effective rate at OPTIMISM_CAP × 180, so the projection can't
        // claim much more than the average-based range no matter how low the window read.
        val remainingWh = 40_000.0
        val cappedRate = DashboardViewModel.OPTIMISM_CAP * 180.0
        val projected = DashboardViewModel.projectedEvRangeKm(
            remainingEnergyWh = remainingWh,
            whPerKm = 60.0,
            baselineWhPerKm = 150.0,
            isPhev = false,
            referenceWhPerKm = 180.0
        )
        assertEquals(remainingWh / cappedRate, projected, 0.5)
        assertTrue("cap must bound below the uncapped 60 Wh/km projection",
            projected < remainingWh / 60.0)
    }

    @Test
    fun `optimism cap is asymmetric - a rate above the reference passes through`() {
        // A climb: measured 240 Wh/km vs a 180 average. The pessimistic side is never
        // dampened, so the (shorter, honest) range shows through untouched.
        val remainingWh = 40_000.0
        val projected = DashboardViewModel.projectedEvRangeKm(
            remainingEnergyWh = remainingWh,
            whPerKm = 240.0,
            baselineWhPerKm = 150.0,
            isPhev = false,
            referenceWhPerKm = 180.0
        )
        assertEquals(remainingWh / 240.0, projected, 0.0001)
    }

    @Test
    fun `optimism cap is a no-op without a reference rate`() {
        val remainingWh = 40_000.0
        val projected = DashboardViewModel.projectedEvRangeKm(
            remainingEnergyWh = remainingWh,
            whPerKm = 60.0,
            baselineWhPerKm = 150.0,
            isPhev = false
        )
        assertEquals(remainingWh / 60.0, projected, 0.0001)
    }

    // ---- PHEV ICE gating: the EV-only distance axis --------------------------------

    @Test
    fun `ICE counter step ignores stalls, rollbacks and resets`() {
        assertEquals(1.0, DashboardViewModel.iceCounterStepKm(3038, 3039), 0.0)
        assertEquals(0.0, DashboardViewModel.iceCounterStepKm(3038, 3038), 0.0)
        assertEquals(0.0, DashboardViewModel.iceCounterStepKm(3038, 3000), 0.0)
        // Beyond MAX_ICE_COUNTER_STEP_KM this is a counter reset, not 9000 km of driving.
        assertEquals(0.0, DashboardViewModel.iceCounterStepKm(0, 9000), 0.0)
        assertEquals(0.0, DashboardViewModel.iceCounterStepKm(null, 3039), 0.0)
        assertEquals(0.0, DashboardViewModel.iceCounterStepKm(3038, null), 0.0)
    }

    @Test
    fun `debt larger than the step carries forward instead of being dropped`() {
        // The counter ticks a whole km on a tick that only drove 300 m: the 700 m
        // surplus must stay owed, or the EV axis silently over-counts.
        val first = DashboardViewModel.withholdIceDebt(distKm = 0.3, debtKm = 1.0)
        assertEquals(0.0, first.evKm, 1e-9)
        assertEquals(0.7, first.debtKm, 1e-9)

        val second = DashboardViewModel.withholdIceDebt(distKm = 0.5, debtKm = first.debtKm)
        assertEquals(0.0, second.evKm, 1e-9)
        assertEquals(0.2, second.debtKm, 1e-9)

        // Debt cleared mid-step: the remainder of that step is genuinely electric.
        val third = DashboardViewModel.withholdIceDebt(distKm = 0.5, debtKm = second.debtKm)
        assertEquals(0.3, third.evKm, 1e-9)
        assertEquals(0.0, third.debtKm, 1e-9)
    }

    @Test
    fun `no debt passes distance through and a zero step never goes negative`() {
        val clean = DashboardViewModel.withholdIceDebt(distKm = 1.4, debtKm = 0.0)
        assertEquals(1.4, clean.evKm, 1e-9)
        assertEquals(0.0, clean.debtKm, 1e-9)

        val stalled = DashboardViewModel.withholdIceDebt(distKm = 0.0, debtKm = 2.0)
        assertEquals(0.0, stalled.evKm, 1e-9)
        assertEquals(2.0, stalled.debtKm, 1e-9)

        val negative = DashboardViewModel.withholdIceDebt(distKm = -1.0, debtKm = -5.0)
        assertEquals(0.0, negative.evKm, 1e-9)
        assertEquals(0.0, negative.debtKm, 1e-9)
    }

    @Test
    fun `EV axis tracks the ICE counter, not the far wider HEV-mode stretch`() {
        // Shaped after a recorded Shark 6 DM-O drive: 63 km total, HEV mode selected
        // for 47 km of it, but the car's own counter attributes only 22 km to the ICE
        // (the pack propels the rest). Gating on energy_mode alone left a 16 km EV
        // axis and quartered the projected range; the counter debt must yield ~41 km.
        val totalKm = 63.0
        val iceKm = 22
        var odo = 0.0
        var counter = 3038
        var debt = 0.0
        var evAxisKm = 0.0
        // 630 ticks of 100 m; the ICE counter ticks over the middle 47 km of driving
        // (a whole km every ~2.1 km of HEV-mode distance) for 22 km in total.
        repeat(630) { i ->
            val prevCounter = counter
            if (i in 80 until 550 && (i - 80) % 21 == 0 && counter - 3038 < iceKm) counter++
            debt += DashboardViewModel.iceCounterStepKm(prevCounter, counter)
            odo += 0.1
            val step = DashboardViewModel.withholdIceDebt(0.1, debt)
            debt = step.debtKm
            evAxisKm += step.evKm
        }
        assertEquals("the counter must have ticked exactly $iceKm km", iceKm, counter - 3038)
        assertEquals(totalKm, odo, 1e-6)
        assertEquals("EV axis should be total − ICE counter", 41.0, evAxisKm, 0.15)

        // The rate the projection would use, against 13.9 kWh of battery energy.
        val gatedWhPerKm = 13_900.0 / evAxisKm
        assertEquals(339.0, gatedWhPerKm, 5.0)
        // Sanity: the old energy_mode gating (47 km excluded) was 2.5× worse.
        val oldWhPerKm = 13_900.0 / (totalKm - 47.0)
        assertTrue("old mode gating inflated the rate (was $oldWhPerKm)", oldWhPerKm > 800.0)
    }
}
