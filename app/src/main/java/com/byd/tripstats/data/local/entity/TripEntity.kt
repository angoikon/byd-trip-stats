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
    val maxBatteryCellTemp: Int = 0,
    val minBatteryCellTemp: Int = 0
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
    val engineSpeedRear: Int = 0
)

@Entity(tableName = "trip_stats")
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
    @TypeConverters(Converters::class)
    val powerDistribution: Map<String, Double>, // Power ranges histogram
    @TypeConverters(Converters::class)
    val speedDistribution: Map<String, Double>, // Speed ranges histogram
    val startLatitude: Double,
    val startLongitude: Double,
    val endLatitude: Double,
    val endLongitude: Double
)
