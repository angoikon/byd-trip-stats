package com.byd.tripstats.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.byd.tripstats.data.local.Converters
import kotlinx.serialization.Serializable

/**
 * Shared coordinate type used by compressedRoute in TripStatsEntity and by
 * TripRepository's Ramer-Douglas-Peucker compression. Defined here (entity
 * package) so Converters.kt can reference it without a circular dependency.
 */
@Serializable
data class LatLng(val lat: Double, val lon: Double)

@Entity(tableName = "trips")
data class TripEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val startTime: Long,
    val endTime: Long? = null,
    val startOdometer: Double,
    val endOdometer: Double? = null,
    val startSoc: Double,
    val endSoc: Double? = null,
    val startSocPanel: Double = 0.0,
    val endSocPanel: Double? = null,
    val startTotalDischarge: Double,
    val endTotalDischarge: Double? = null,
    val isActive: Boolean = true,
    val isManual: Boolean = false,
    val maxSpeed: Double = 0.0,
    val maxPower: Double = 0.0,
    val maxRegenPower: Double = 0.0,
    val avgBatteryTemp: Double = 0.0,
    val minSoc: Double = 100.0,
    val maxBatteryCellTemp: Int = Int.MIN_VALUE,   // sentinel: unset until first reading
    val minBatteryCellTemp: Int = Int.MAX_VALUE,   // sentinel: unset until first reading
    /**
     * Wallclock ms during which the trip was open but the car was off — i.e. the
     * configurable engine-off resume window between trip segments, plus the trailing
     * window between the last data point and the timeout-triggered trip close.
     *
     * Subtracted from [duration] so that displayed trip duration and average speed
     * reflect actual driving time, not driving + parked-with-the-trip-still-open.
     * Computed from per-pair gaps in the recorded data points at trip-close time
     * (see TripRepository.finalizeTripCore / backfillOffStateDuration); defaults to
     * 0 for trips recorded before the 2.5.0 schema bump so behaviour is unchanged
     * for legacy rows until the backfill job runs.
     */
    val offStateDurationMs: Long = 0,
    /**
     * User-flagged favourite. Favourited trips are exempt from both automatic
     * point-thinning (DatabaseMaintenanceWorker) and the manual DatabaseTrimmer,
     * so their full data-point density and rawJson diagnostics are preserved
     * indefinitely. Defaults to false.
     */
    val isFavourite: Boolean = false
) {
    /** Active driving time only — the wallclock span minus [offStateDurationMs]. */
    val duration: Long?
        get() = endTime?.let { (it - startTime - offStateDurationMs).coerceAtLeast(0L) }

    /** Wallclock span from start to end, including any off-state windows. */
    val wallclockDuration: Long?
        get() = endTime?.let { (it - startTime).coerceAtLeast(0L) }

    val distance: Double?
        get() = endOdometer?.let { it - startOdometer }

    val energyConsumed: Double?
        get() = endTotalDischarge?.let { it - startTotalDischarge }

    val socDelta: Double?
        get() = endSoc?.let { it - startSoc }

    val socPanelDelta: Double?
        get() = endSocPanel?.let { it - startSocPanel }

    val efficiency: Double?
        get() {
            val dist   = distance     ?: return null
            val energy = energyConsumed ?: return null
            if (dist == 0.0) return null
            return (energy / dist) * 100.0 // kWh per 100 km
        }
}

/**
 * Consumption across several trips in kWh/100 km: their total energy over their total distance,
 * the way the car works out its own average. A plain mean of [TripEntity.efficiency] lets a 2 km
 * hop with a cold cabin weigh as much as a 200 km drive. Counts the trips that have a figure; null
 * when none does.
 */
fun List<TripEntity>.combinedEfficiency(): Double? {
    val counted = filter { it.efficiency != null && (it.distance ?: 0.0) > 0.0 }
    val distanceKm = counted.sumOf { it.distance ?: 0.0 }
    if (distanceKm <= 0.0) return null
    return counted.sumOf { it.energyConsumed ?: 0.0 } / distanceKm * 100.0
}

@Entity(tableName = "trip_data_points")
data class TripDataPointEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val tripId: Long,
    val timestamp: Long,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,
    val speed: Double,
    val power: Double,
    val soc: Double,
    val odometer: Double,
    val batteryTemp: Double,
    val totalDischarge: Double,
    val gear: String,
    val isRegenerating: Boolean,
    val engineSpeedFront: Int = 0,
    val engineSpeedRear: Int = 0,
    val electricDrivingRangeKm: Int = 0,
    val tyrePressureLF: Double = 0.0,
    val tyrePressureRF: Double = 0.0,
    val tyrePressureLR: Double = 0.0,
    val tyrePressureRR: Double = 0.0,
    val soh: Int = 0,
    /** Precise SoH (statistic-feature float, e.g. 97.6) when the car exposes it; 0.0 when unknown.
     *  Promoted from rawJson in 2.9.1 so the degradation chart/report keep the decimal the
     *  dashboard shows. AVG queries fall back to the integer [soh] for pre-2.9.1 rows. */
    val sohPrecise: Double = 0.0,
    val batteryTotalVoltage: Int = 0,
    val battery12vVoltage: Double = 0.0,
    val batteryCellVoltageMax: Double = 0.0,
    val batteryCellVoltageMin: Double = 0.0,
    // ── New columns (DB v2 / Electro telemetry v2) ────────────────────────────
    /** Displayed SoC on the instrument panel — compare with soc for BMS calibration insight */
    val socPanel: Int = 0,
    val tyreTempLF: Int = 0,
    val tyreTempRF: Int = 0,
    val tyreTempLR: Int = 0,
    val tyreTempRR: Int = 0,
    // ── DB v10 — EV-only remaining energy (PHEV charge-sustaining aware) ───────
    /** BMS-reported remaining EV-usable energy (kWh), from `battery_remain_power_ev`.
     *  Null on BEVs / firmwares that don't report it and on pre-v10 rows. Lets
     *  restoreTripState reproduce the live EV-range projection exactly — the BMS
     *  value already nets out the PHEV charge-sustaining reserve — instead of
     *  capacity × SoC, which overstates EV energy near the charge-sustaining floor. */
    val batteryRemainPowerEV: Double? = null,
    // Escape hatch for future telemetry keys that don't yet have a first-class column.
    // Store as JSON: {"hvacPower": 1.2, ...}
    // When a new key becomes stable/important, promote it to its own column
    // via a migration and remove it from this blob. This way new telemetry
    // fields are captured immediately without a schema change.
    val rawJson: String = "{}"
)

@Entity(tableName = "trip_stats")
@TypeConverters(Converters::class)
data class TripStatsEntity(
    @PrimaryKey
    val tripId: Long,
    val totalDistance: Double,
    val totalDuration: Long,
    val totalEnergyConsumed: Double,
    val totalRegenEnergy: Double,
    val avgSpeed: Double,
    val avgEfficiency: Double,
    val maxSpeed: Double,
    val maxPower: Double,
    val maxRegenPower: Double,
    val powerDistribution: Map<String, Double>,
    val speedDistribution: Map<String, Double>,
    val startLatitude: Double,
    val startLongitude: Double,
    val endLatitude: Double,
    val endLongitude: Double,
    val matrixDistribution: Map<String, Int>,
    val energyConsumptionBySpeed: Map<String, Double>,
    val regenEnergy: Double,
    val mechanicalEnergy: Double,
    val compressedRoute: List<LatLng>
)

@Entity(tableName = "trip_segments")
data class TripSegmentEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val tripId: Long,
    val startTime: Long,
    val endTime: Long,
    val startLat: Double?,
    val startLon: Double?,
    val endLat: Double?,
    val endLon: Double?,
    val avgSpeed: Double,
    val avgPower: Double,
    val distance: Double,
    val energyUsed: Double
)
