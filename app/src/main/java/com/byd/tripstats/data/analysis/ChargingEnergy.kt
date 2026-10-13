package com.byd.tripstats.data.analysis

import com.byd.tripstats.data.local.entity.ChargingDataPointEntity

/**
 * Energy the car measured arriving over a charging session, integrated from the power samples
 * the session already recorded.
 *
 * This is a different quantity from `ChargingSessionEntity.kwhAdded`, which is the SoC change
 * times the pack's capacity. That one is a level read twice, so it carries whatever the SoC
 * reading rounds away and nothing of what the charge lost on the way in. This one is a rate
 * watched throughout.
 *
 * It is the car's own measurement, and where the car takes it decides what the figure includes:
 *
 * - **DC.** The charger feeds the pack directly and the car reads the power coming in, so the
 *   figure tracks the charger's own meter closely. One metered session landed 2.0% under the
 *   meter where the SoC delta was 8.4% under.
 * - **AC.** The car reports what leaves its on-board charger, and nothing in the car can see
 *   what went into it. A 7 kW wallbox reads about 6.2 kW here, so this figure excludes the
 *   conversion loss and sits below a wall-side meter — around 10% below, and further on a
 *   slower charge.
 *
 * So it is not a billing figure, and nothing is priced from it. Cost stays on the pack figure
 * and on the per-charge price the owner enters, exactly as before this existed. What this adds
 * is the measurement itself: what the car saw arrive, beside what the pack ended up holding.
 */
object ChargingEnergy {

    private const val MS_PER_HOUR = 3_600_000.0

    /**
     * Longest silence between two samples still treated as one continuous stretch of charging.
     *
     * A data point is written for every telemetry packet that arrives while a session is open.
     * The reference DC session ran at 30 s with one 61 s hiccup, so two minutes is twice the
     * worst gap a charge that was being watched has produced, and a trapezoid drawn across
     * anything wider would invent an average nothing measured.
     *
     * It also has to stay under DI5_CHARGING_PAUSE_HOLD_MS. Unlocking the car pauses an AC
     * charge, and DiLink-5 now holds the session open for five minutes rather than ending it,
     * recording nothing while no power flows. Bridging that would count a stretch where nothing
     * was flowing as charging.
     *
     * Erring short is the safe direction: a dropped interval makes the integral smaller, and an
     * integral that no longer clears the pack's gain is withheld rather than stored, so the
     * session reports no measurement instead of an overstated one.
     */
    private const val MAX_SAMPLE_GAP_MS = 2 * 60 * 1000L

    /** Below this, a reading is the SDK idling rather than current actually flowing. */
    private const val MIN_SIGNIFICANT_KW = 0.1

    /**
     * How much bigger than the pack's gain the integral has to be, and how much bigger it is
     * allowed to get, before the pair of figures stops describing one charge.
     *
     * Both numbers describe the same pack, so what separates them is the loss between where the
     * car reads the power and where the energy ends up: a few percent, more into a cold pack. An
     * integral that lands within 1% of the pack figure has not measured a charge at all — pack
     * voltage times current is one of the sources `chargingPower` can come from, and integrating
     * that just reproduces the SoC delta under a second name. Past half, one of the two readings
     * is wrong and neither is worth reporting.
     *
     * The floor is on the strict side, and on an efficient AC charge it can withhold a figure
     * that was real. That reads as "not measured", which every consumer of this already has to
     * handle, where a reproduction of the SoC delta would read as a second measurement agreeing
     * with the first.
     */
    private const val MIN_PLAUSIBLE_LOSS_RATIO = 1.01
    private const val MAX_PLAUSIBLE_LOSS_RATIO = 1.5

    /**
     * @return kWh the car measured arriving over [points], or null when that cannot be said.
     *
     * [packGainKwh] is the same session's SoC-derived energy, and it is the sanity check rather
     * than a fallback. More arrives than the pack keeps, so an integral that does not clear it
     * has not measured the whole charge, and the two ways that happens both end in a figure
     * that understates what the car saw:
     *
     * - **The app missed part of the charge.** A session the app was killed through is resumed
     *   into the same row for a hole of up to 15 minutes, and the SoC delta spans that hole while
     *   the samples do not.
     * - **The samples are not the charger's.** `chargingPower` comes from one of three sources
     *   (see BydVehicleDataSource) and only the gross getter reads the power coming in. The
     *   pack-side fallback integrates to roughly [packGainKwh], which is why near-equality is
     *   rejected rather than accepted, and the capacity-rate source under-reports badly — a flat
     *   2 kW for a measured 4.6 kW charge on one car.
     *
     * Null is also what a session with no usable samples gets. `chargingPower` reads a flat 0 on
     * any car whose SDK never binds the charging listener — it did exactly that on DiLink 5
     * through DC sessions that filled the pack — so a session of nothing but zeros is silence,
     * not a charge that drew no power, and a reconstructed session has no samples at all.
     */
    fun receivedKwh(points: List<ChargingDataPointEntity>, packGainKwh: Double): Double? {
        val integrated = integrate(points) ?: return null
        return integrated.takeIf { plausible(it, packGainKwh) }
    }

    /**
     * Whether [receivedKwh] and [packGainKwh] can still be two readings of one charge.
     *
     * Separate from [receivedKwh] because the pack figure moves after the session closes. A
     * near-full charge that ends with the car parked keeps settling for minutes while the BMS
     * revises SoC upward, and `ChargingRepository.applyPostChargeSettle` writes the larger
     * figure back. A stored integral the revised figure overtakes would have less energy
     * arriving than the pack ended up holding, so it is dropped there on the same test it had
     * to pass to be stored.
     */
    internal fun plausible(receivedKwh: Double, packGainKwh: Double): Boolean =
        packGainKwh > 0.0 &&
            receivedKwh >= packGainKwh * MIN_PLAUSIBLE_LOSS_RATIO &&
            receivedKwh <= packGainKwh * MAX_PLAUSIBLE_LOSS_RATIO

    /** The trapezoid itself, without the plausibility check. Visible so it can be tested alone. */
    internal fun integrate(points: List<ChargingDataPointEntity>): Double? {
        if (points.size < 2) return null
        if (points.none { it.chargingPower > MIN_SIGNIFICANT_KW }) return null

        // The DAO returns these in timestamp order, but an integral is order-dependent and a
        // caller holding an unsorted list would silently get a smaller number, not an error.
        val ordered = points.sortedBy { it.timestamp }

        var kwh = 0.0
        for (i in 0 until ordered.size - 1) {
            val spanMs = ordered[i + 1].timestamp - ordered[i].timestamp
            if (spanMs <= 0L || spanMs > MAX_SAMPLE_GAP_MS) continue
            val from = ordered[i].chargingPower.coerceAtLeast(0.0)
            val to   = ordered[i + 1].chargingPower.coerceAtLeast(0.0)
            kwh += (from + to) / 2.0 * (spanMs / MS_PER_HOUR)
        }
        return kwh.takeIf { it > 0.0 }
    }
}
