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
    val minBatteryCellTemp: Int = Int.MAX_VALUE    // sentinel: unset until first reading
) {
    val duration: Long?
        get() = endTime?.let { it - startTime }

    val distance: Double?
        get() = endOdometer?.let { it - startOdometer }

    val energyConsumed: Double?
        get() = endTotalDischarge?.let { it - startTotalDischarge }

    val socDelta: Double?
        get() = endSoc?.let { it - startSoc }

    val efficiency: Double?
        get() {
            val dist   = distance     ?: return null
            val energy = energyConsumed ?: return null
            if (dist == 0.0) return null
            return (energy / dist) * 100.0 // kWh per 100 km
        }
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
    // Escape hatch for future MQTT keys that don't yet have a first-class column.
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