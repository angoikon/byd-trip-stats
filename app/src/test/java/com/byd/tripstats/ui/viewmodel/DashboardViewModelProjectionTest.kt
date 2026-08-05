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

    // ── rollingWhPerKmSeries — per-point rate replay that un-flattens the rebuild ─

    @Test
    fun `rolling series tracks a changing consumption rate instead of one average`() {
        // First 5 km gentle (100 Wh/km), next 5 km thirsty (300 Wh/km). The whole-trip
        // average is 200 Wh/km; the rolling series must instead climb toward the recent
        // rate so a reconstructed projection built from it VARIES rather than flattening.
        val samples = ArrayList<Pair<Double, Double>>()
        var energy = 0.0
        var d = 0.0
        while (d < 5.0) { samples.add(d to energy); d += 0.1; energy += 0.1 * 100.0 }
        while (d <= 10.0 + 1e-9) { samples.add(d to energy); energy += 0.1 * 300.0; d += 0.1 }

        // Use a short window so the late-trip rate isn't diluted by the gentle start.
        val series = DashboardViewModel.rollingWhPerKmSeries(samples, windowKm = 2.0, emaAlpha = 1.0)

        val early = series[20]!!   // ~2 km in, fully within the gentle stretch
        val late = series.last()!! // end of the thirsty stretch
        assertEquals(100.0, early, 5.0)
        assertTrue("rate must rise on the thirsty stretch ($early -> $late)", late > early + 100.0)
    }

    @Test
    fun `rolling series is null until the first positive consumption is seen`() {
        // Leading flat-energy samples (no discharge yet) have no rate; once energy
        // starts accruing a rate appears and persists.
        val samples = listOf(0.0 to 0.0, 0.5 to 0.0, 1.0 to 0.0, 1.5 to 75.0, 2.0 to 150.0)
        val series = DashboardViewModel.rollingWhPerKmSeries(samples, windowKm = 10.0, emaAlpha = 1.0)
        assertEquals(null, series[0])
        assertEquals(null, series[2])           // still no consumption by 1.0 km
        assertTrue(series.last() != null && series.last()!! > 0.0)
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

    // ── windowSlopeWhPerKm — noise-robust rate vs a two-endpoint difference ──────

    @Test
    fun `window slope returns the least-squares slope of a linear series`() {
        val samples = (0..50).map { i -> (i * 0.1) to (i * 0.1 * 160.0) }  // 160 Wh/km
        assertEquals(160.0, DashboardViewModel.windowSlopeWhPerKm(samples)!!, 1e-6)
    }

    @Test
    fun `window slope shrugs off endpoint quantization a two-point estimate takes raw`() {
        // A clean 160 Wh/km ramp, but the two endpoints carry opposite ±100 Wh quantization.
        // A two-point (last − first) estimate takes the full ±200 Wh; the regression barely moves.
        val clean = (0..50).map { i -> (i * 0.1) to (i * 0.1 * 160.0) }.toMutableList()
        val n = clean.size
        clean[0] = clean[0].first to (clean[0].second + 100.0)
        clean[n - 1] = clean[n - 1].first to (clean[n - 1].second - 100.0)
        val twoPoint = (clean.last().second - clean.first().second) /
            (clean.last().first - clean.first().first)
        val slope = DashboardViewModel.windowSlopeWhPerKm(clean)!!
        assertTrue("two-point estimate is pulled well off 160 (was $twoPoint)", twoPoint < 150.0)
        assertEquals(160.0, slope, 6.0)
    }

    @Test
    fun `window slope is null for too few points, flat energy, or a negative slope`() {
        assertEquals(null, DashboardViewModel.windowSlopeWhPerKm(listOf(1.0 to 100.0)))
        assertEquals(null, DashboardViewModel.windowSlopeWhPerKm(listOf(0.0 to 50.0, 1.0 to 50.0)))
        assertEquals(null, DashboardViewModel.windowSlopeWhPerKm(listOf(0.0 to 100.0, 1.0 to 0.0)))
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
