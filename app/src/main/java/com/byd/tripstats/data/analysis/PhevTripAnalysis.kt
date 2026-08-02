package com.byd.tripstats.data.analysis

import com.byd.tripstats.data.local.entity.TripDataPointEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * PHEV trip breakdown: splits a recorded trip into its electric and combustion
 * halves from the per-point statistic counters persisted in rawJson
 * (total_fuel_consumption, ice_mileage_km / ev_mileage_km, energy_mode,
 * instant_fuel_consumption).
 *
 * All figures are computed from *deltas of the car's own lifetime counters* summed
 * pairwise with sanity clamps, so a counter reset mid-trip (the same quirk the BYD
 * discharge counter shows across trip merges) skews one pair at most, not the trip.
 *
 * The ICE mileage counter is whole km, so short trips fall back to integrating the
 * odometer across ticks where the powertrain reports the ICE active
 * (energy_mode = HEV/Fuel, or fuel visibly flowing).
 */
data class PhevTripBreakdown(
    /** Total trip distance covered by the analysed points, km. */
    val totalKm: Double,
    /** Distance the ICE propelled (or co-propelled) the car, km. */
    val iceKm: Double,
    /** Distance driven purely electric, km. */
    val evKm: Double,
    /** 0–100: share of the trip driven electric. */
    val evSharePct: Double,
    /** Litres of petrol burned during the trip. */
    val fuelLiters: Double,
    /** Petrol consumption over ICE-propelled distance only (L/100km), null if no meaningful ICE distance. */
    val fuelLPer100IceKm: Double?,
    /** Petrol consumption spread over the whole trip distance (L/100km), the "combined" figure. */
    val fuelLPer100TotalKm: Double?,
    /** Electric consumption over EV-propelled distance only (kWh/100km), null if no meaningful EV distance. */
    val evKwhPer100EvKm: Double?,
)

object PhevTripAnalysis {

    private val json = Json { ignoreUnknownKeys = true }

    // Per-pair sanity clamps: a data-point pair spans seconds to (after thinning)
    // a few minutes, so anything above these is a counter reset/glitch, not driving.
    private const val MAX_PAIR_FUEL_LITERS = 2.0
    private const val MAX_PAIR_MILEAGE_KM = 50.0

    /** Energy modes in which the ICE is (co-)propelling: 3 = HEV, 4 = Fuel. */
    private fun isIceMode(energyMode: Int) = energyMode == 3 || energyMode == 4

    private data class PointSignals(
        val odometer: Double,
        val totalFuelL: Double?,
        val iceKm: Int?,
        val evKm: Int?,
        val energyMode: Int,
        val instantFuel: Double?,
    )

    private fun extract(point: TripDataPointEntity): PointSignals {
        val obj: JsonObject? = point.rawJson
            .takeIf { it.isNotBlank() && it != "{}" }
            ?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
        fun int(key: String) = obj?.get(key)?.jsonPrimitive?.intOrNull
        fun dbl(key: String) = obj?.get(key)?.jsonPrimitive?.doubleOrNull
        return PointSignals(
            odometer = point.odometer,
            totalFuelL = dbl("total_fuel_consumption"),
            iceKm = int("ice_mileage_km"),
            evKm = int("ev_mileage_km"),
            energyMode = int("energy_mode") ?: 0,
            instantFuel = dbl("instant_fuel_consumption"),
        )
    }

    /**
     * Computes the PHEV breakdown for a trip, or null when the points carry no
     * PHEV signals at all (BEV trips, or PHEV history recorded before the fuel
     * counters were persisted).
     *
     * @param tripEnergyKwh the trip's recorded battery energy (TripEntity.energyConsumed);
     *        used for the EV kWh/100km figure.
     */
    fun analyze(
        dataPoints: List<TripDataPointEntity>,
        tripEnergyKwh: Double?,
    ): PhevTripBreakdown? {
        if (dataPoints.size < 2) return null
        val signals = dataPoints.sortedBy { it.timestamp }.map { extract(it) }

        val hasPhevSignals = signals.any {
            it.totalFuelL != null || it.iceKm != null || it.evKm != null || it.energyMode != 0
        }
        if (!hasPhevSignals) return null

        var fuelLiters = 0.0
        var iceCounterKm = 0.0
        var evCounterKm = 0.0
        var iceModeOdoKm = 0.0
        var sawIceCounter = false
        var sawEvCounter = false

        signals.zipWithNext { a, b ->
            pairDelta(a.totalFuelL, b.totalFuelL, MAX_PAIR_FUEL_LITERS)?.let { fuelLiters += it }
            pairDelta(a.iceKm?.toDouble(), b.iceKm?.toDouble(), MAX_PAIR_MILEAGE_KM)?.let {
                iceCounterKm += it
                sawIceCounter = true
            }
            pairDelta(a.evKm?.toDouble(), b.evKm?.toDouble(), MAX_PAIR_MILEAGE_KM)?.let {
                evCounterKm += it
                sawEvCounter = true
            }
            // Odometer distance accrued while the powertrain reported the ICE active —
            // the fallback ICE distance for trips shorter than the counter's 1 km step.
            val iceActive = isIceMode(a.energyMode) || (a.instantFuel ?: 0.0) > 0.0
            if (iceActive) {
                pairDelta(a.odometer, b.odometer, MAX_PAIR_MILEAGE_KM)?.let { iceModeOdoKm += it }
            }
        }

        val totalKm = (signals.last().odometer - signals.first().odometer)
            .takeIf { it.isFinite() && it > 0.0 } ?: return null

        // The lifetime ICE counter only ticks in whole km; prefer it once it has
        // actually moved, otherwise fall back to the mode-gated odometer integral.
        val iceKm = (if (sawIceCounter && iceCounterKm > 0.0) iceCounterKm else iceModeOdoKm)
            .coerceIn(0.0, totalKm)
        val evKm = (if (sawEvCounter && evCounterKm > 0.0) evCounterKm else totalKm - iceKm)
            .coerceIn(0.0, totalKm)

        return PhevTripBreakdown(
            totalKm = totalKm,
            iceKm = iceKm,
            evKm = evKm,
            evSharePct = (evKm / totalKm * 100.0).coerceIn(0.0, 100.0),
            fuelLiters = fuelLiters,
            fuelLPer100IceKm = if (iceKm >= 0.5 && fuelLiters > 0.0) fuelLiters / iceKm * 100.0 else null,
            fuelLPer100TotalKm = if (fuelLiters > 0.0) fuelLiters / totalKm * 100.0 else null,
            evKwhPer100EvKm = tripEnergyKwh
                ?.takeIf { it > 0.0 && evKm >= 0.5 }
                ?.let { it / evKm * 100.0 },
        )
    }

    /** Positive, clamped counter delta for one point pair; null when either side is absent. */
    private fun pairDelta(a: Double?, b: Double?, maxDelta: Double): Double? {
        if (a == null || b == null) return null
        val d = b - a
        return d.takeIf { it.isFinite() && it > 0.0 && it <= maxDelta }
    }
}
