package com.byd.tripstats.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.byd.tripstats.data.local.Converters

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
    // Computed properties
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
            val dist = distance ?: return null
            val energy = energyConsumed ?: return null
            if (dist == 0.0) return null
            return (energy / dist) * 100.0 // kWh per 100km
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
    // BMS-reported remaining range — stored so TripDetailScreen can replay the
    // range projection chart from historical data, not just from live telemetry.
    val electricDrivingRangeKm: Int = 0,
    // Tyre pressures (PSI) — stored per data point so pressure history is
    // available for future analysis. 0.0 means not reported by this firmware.
    val tyrePressureLF: Double = 0.0,
    val tyrePressureRF: Double = 0.0,
    val tyrePressureLR: Double = 0.0,
    val tyrePressureRR: Double = 0.0,
    // Battery health & voltage snapshot per data point
    val soh: Int = 0,
    val batteryTotalVoltage: Int = 0,
    val battery12vVoltage: Double = 0.0,
    val batteryCellVoltageMax: Double = 0.0,
    val batteryCellVoltageMin: Double = 0.0,
    // Escape hatch for future MQTT keys that don't yet have a first-class column.
    // Store as JSON: {"tyrePressureFL": 2.5, "hvacPower": 1.2, ...}
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
    val endLongitude: Double
)