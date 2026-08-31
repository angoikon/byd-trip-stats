package com.byd.tripstats.data.analysis

import com.byd.tripstats.data.config.CarConfig
import com.byd.tripstats.data.local.entity.TripDataPointEntity
import com.byd.tripstats.data.local.entity.TripEntity
import com.byd.tripstats.data.local.entity.TripStatsEntity
import org.json.JSONObject
import kotlin.math.abs

/**
 * The Overview-tab numbers and the derived analyses of a finished trip, as one JSON
 * object shared by every HTML surface: the embedded trip viewer ("Save as HTML" /
 * "Send HTML viewer to Telegram") and the web companion's live API.
 *
 * The formulas live here once so the three renderings can't drift apart — the app's
 * Overview tab, the exported viewer, and the companion all read the same values.
 * Numbers stay numbers (each front-end formats them); only the two sanity-gated
 * battery-temperature composites are emitted pre-formatted, since their validity
 * rules are display rules, not data.
 *
 * Everything is nullable-tolerant: a trip still running, a car with no mass/CdA in
 * the catalog, or a non-PHEV simply yields fewer keys rather than a broken payload.
 */
object TripReport {

    /**
     * Regenerated share of the gross energy that flowed through the pack, in percent.
     * Extracted from DashboardViewModel so the history list, the detail screen, and
     * the HTML surfaces all agree.
     */
    fun regenEfficiencyPct(trip: TripEntity, stats: TripStatsEntity?): Double? {
        val consumed = trip.energyConsumed ?: return null
        if (stats == null || consumed <= 0) return null
        val regen = stats.totalRegenEnergy
        return (regen / (consumed + regen)) * 100.0
    }

    /**
     * The 0–100 trip score: efficiency (0–40) + regen share (0–30) + smoothness (0–30).
     * [avgSpeedKmh] is the persisted trip-stat average; when absent the score falls back
     * to distance ÷ duration. Null for trips too short to score (< 0.5 km) or without a
     * consumption figure.
     */
    fun tripScore(trip: TripEntity, avgSpeedKmh: Double?): Int? {
        val eff  = trip.efficiency ?: return null
        val dist = trip.distance   ?: return null
        val dur  = trip.duration   ?: return null
        if (dist < 0.5 || dur <= 0) return null

        val effScore = when {
            eff <= 17.0 -> 40
            eff >= 25.0 -> 0
            else        -> ((25.0 - eff) / (25.0 - 15.0) * 40).toInt()
        }
        val maxRegen = abs(trip.maxRegenPower)
        val maxPower = trip.maxPower
        val regenScore = if (maxPower + maxRegen > 0)
            ((maxRegen / (maxPower + maxRegen)) * 30).toInt().coerceIn(0, 30) else 0
        val smoothAvg = avgSpeedKmh ?: (dist / (dur / 3_600_000.0))
        val smoothScore = if (trip.maxSpeed > 0)
            ((smoothAvg / trip.maxSpeed) * 30).toInt().coerceIn(0, 30) else 0
        return (effScore + regenScore + smoothScore).coerceIn(0, 100)
    }

    /**
     * JSONObject rejects NaN/Infinity outright, so one bad reading would otherwise sink
     * the whole report — for the live API that means a 500, for an export a crash.
     * Non-finite values are dropped exactly like nulls: the key is simply absent and the
     * front-end shows "—".
     */
    private fun JSONObject.putNum(key: String, value: Double?) {
        if (value != null && value.isFinite()) put(key, value)
    }

    /** Cell readings outside this band are sensor noise / unset sentinels. */
    private fun Int.validCellTemp(): Int? = takeIf { it in -40..120 }

    /**
     * "12°C - 19°C" / "12°C" / "-" — a spread wider than 25°C means one of the two
     * sentinels was never overwritten, so the range is not trustworthy as a range.
     */
    fun batteryTempRangeLabel(trip: TripEntity): String {
        val min = trip.minBatteryCellTemp.validCellTemp()
        val max = trip.maxBatteryCellTemp.validCellTemp()
        val rangeValid = min != null && max != null && max >= min && (max - min) <= 25
        return when {
            rangeValid  -> "${min}°C - ${max}°C"
            min != null -> "${min}°C"
            max != null && max in -40..80 -> "${max}°C"
            else -> "-"
        }
    }

    /** Midpoint of a trustworthy cell range, else the recorded pack average. */
    fun avgBatteryTempLabel(trip: TripEntity): String {
        val min = trip.minBatteryCellTemp.validCellTemp()?.toDouble()
        val max = trip.maxBatteryCellTemp.validCellTemp()?.toDouble()
        if (min != null && max != null && max >= min && (max - min) <= 25) {
            return "${((min + max) / 2.0).toInt()}°C"
        }
        val avg = trip.avgBatteryTemp.takeIf { it.isFinite() && it in -40.0..120.0 } ?: return "-"
        return "${avg.toInt()}°C"
    }

    /**
     * Builds the full report. [blendedRate] is the FIFO cost-basis rate for the energy
     * this trip drew (currency/kWh) — pass null when no rate is resolvable and the cost
     * rows are dropped.
     */
    fun build(
        trip: TripEntity,
        stats: TripStatsEntity?,
        dataPoints: List<TripDataPointEntity>,
        carConfig: CarConfig?,
        blendedRate: Double? = null,
        currencySymbol: String = "€",
    ): JSONObject = JSONObject().apply {
        put("overview", buildOverview(trip, stats, blendedRate, currencySymbol))
        calculateTripEnergyBreakdown(dataPoints, carConfig, trip.energyConsumed)
            ?.let { put("energyBreakdown", buildEnergyBreakdown(it)) }
        if (carConfig?.isPhev == true) {
            PhevTripAnalysis.analyze(dataPoints, trip.energyConsumed)
                ?.let { put("phev", buildPhev(it)) }
        }
        carConfig?.let {
            put("car", JSONObject().apply {
                put("id",         it.id)
                put("name",       it.displayName)
                putNum("batteryKwh", it.batteryKwh)
                put("isPhev",     it.isPhev)
            })
        }
    }

    private fun buildOverview(
        trip: TripEntity,
        stats: TripStatsEntity?,
        blendedRate: Double?,
        currencySymbol: String,
    ) = JSONObject().apply {
        put("startTime",          trip.startTime)
        put("endTime",            trip.endTime)
        put("duration",           trip.duration)
        put("wallclockDuration",  trip.wallclockDuration)
        put("offStateDurationMs", trip.offStateDurationMs)
        put("isManual",           trip.isManual)

        putNum("startOdometer",      trip.startOdometer)
        putNum("endOdometer",        trip.endOdometer)
        putNum("distance",           trip.distance)

        putNum("startSoc",           trip.startSoc)
        putNum("endSoc",             trip.endSoc)
        putNum("socDelta",           trip.socDelta)
        putNum("startSocPanel",      trip.startSocPanel)
        putNum("endSocPanel",        trip.endSocPanel)
        putNum("socPanelDelta",      trip.socPanelDelta)
        putNum("minSoc",             trip.minSoc)

        putNum("maxSpeed",           trip.maxSpeed)
        putNum("avgSpeed",           stats?.avgSpeed?.takeIf { it > 0.0 })
        putNum("maxPower",           trip.maxPower)
        putNum("maxRegenPower",      abs(trip.maxRegenPower))

        val consumed = trip.energyConsumed
        val regen    = stats?.totalRegenEnergy
        putNum("energyConsumed",     consumed)
        putNum("regenEnergy",        regen)
        putNum("grossEnergy",        if (consumed != null) consumed + (regen ?: 0.0) else null)
        putNum("efficiency",         trip.efficiency)
        putNum("regenEfficiencyPct", regenEfficiencyPct(trip, stats))
        put("tripScore",          tripScore(trip, stats?.avgSpeed?.takeIf { it > 0.0 }))

        put("batteryTempRangeLabel", batteryTempRangeLabel(trip))
        put("avgBatteryTempLabel",   avgBatteryTempLabel(trip))

        // Read-only: prices are edited on charging sessions and the global tariff and
        // flow through to trips, so the report only ever reports the resolved rate.
        if (blendedRate != null) {
            putNum("energyRatePerKwh", blendedRate)
            putNum("tripCost",         consumed?.let { it * blendedRate })
            put("currencySymbol",   currencySymbol)
        }
    }

    /**
     * Mirrors the Overview tab's display maths: the modelled forces are scaled down if
     * they exceed the measured total, and auxiliary is whatever the model can't explain.
     * Percentages are of the measured total, so they read as shares of the real energy.
     */
    private fun buildEnergyBreakdown(b: TripEnergyBreakdown) = JSONObject().apply {
        val total = b.totalConsumedKwh
        val modelledRaw = b.rollingResistanceKwh + b.aeroDragKwh + b.netGradientKwh.coerceAtLeast(0.0)
        val scale = if (modelledRaw > total && modelledRaw > 0.0) total / modelledRaw else 1.0
        val aux = (total - modelledRaw * scale).coerceAtLeast(0.0)
        fun pct(kwh: Double): Double? = total.takeIf { it > 0.0 }?.let { kwh / it * 100.0 }

        putNum("totalConsumedKwh", total)
        putNum("rollingKwh",       b.rollingResistanceKwh * scale)
        putNum("aeroKwh",          b.aeroDragKwh * scale)
        putNum("climbKwh",         b.climbKwh * scale)
        putNum("descentKwh",       b.descentKwh * scale)
        putNum("netGradientKwh",   b.netGradientKwh * scale)
        putNum("auxiliaryKwh",     aux)

        putNum("rollingPct",       pct(b.rollingResistanceKwh * scale))
        putNum("aeroPct",          pct(b.aeroDragKwh * scale))
        putNum("gradientPct",      pct((b.netGradientKwh * scale).coerceAtLeast(0.0)))
        putNum("auxiliaryPct",     pct(aux))

        put("hasPhysicsBreakdown", b.hasPhysicsBreakdown)
        put("hasAeroEstimate",     b.hasAeroEstimate)
        put("hasGradientEstimate", b.hasGradientEstimate)
        putNum("estimatedKerbMassKg", b.estimatedKerbMassKg)
        putNum("cdA",                 b.cdA)
    }

    private fun buildPhev(p: PhevTripBreakdown) = JSONObject().apply {
        putNum("totalKm",             p.totalKm)
        putNum("evKm",                p.evKm)
        putNum("iceKm",               p.iceKm)
        putNum("evSharePct",          p.evSharePct)
        putNum("fuelLiters",          p.fuelLiters)
        putNum("fuelLPer100IceKm",    p.fuelLPer100IceKm)
        putNum("fuelLPer100TotalKm",  p.fuelLPer100TotalKm)
        putNum("evKwhPer100EvKm",     p.evKwhPer100EvKm)
    }
}
