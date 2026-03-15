package com.byd.tripstats.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One row per charging session.
 *
 * Energy added is computed on session close as:
 *   kwhAdded = (socEnd - socStart) / 100.0 × batteryKwh
 *
 * batteryKwh is snapshotted from CarConfig at recording time so the figure
 * remains correct even if the user later switches car models.
 *
 * avgKw is computed from chargingPower readings; peakKw is the max observed.
 */
@Entity(tableName = "charging_sessions")
data class ChargingSessionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val startTime: Long,
    val endTime: Long? = null,

    val socStart: Double,
    val socEnd: Double? = null,

    /** kWh added — computed on close, null while session is still active */
    val kwhAdded: Double? = null,

    val peakKw: Double = 0.0,
    val avgKw: Double = 0.0,

    val batteryTempStart: Double = 0.0,
    val batteryTempEnd: Double? = null,

    val voltageStart: Int = 0,
    val voltageEnd: Int? = null,

    /** CarConfig.batteryKwh snapshotted at session start */
    val batteryKwh: Double = 0.0,

    /** CarConfig.id snapshotted at session start — for display / reference */
    val carConfigId: String = "",

    val isActive: Boolean = true
) {
    val durationSeconds: Long?
        get() = endTime?.let { (it - startTime) / 1000L }

    val socDelta: Double?
        get() = socEnd?.let { it - socStart }
}

/**
 * One row per MQTT message received during a charging session.
 *
 * Mirrors TripDataPointEntity in structure but contains only the fields
 * relevant during charging. Motor RPM, gear, and speed are intentionally
 * omitted — they are meaningless while the car is stationary and plugged in.
 */
@Entity(
    tableName = "charging_data_points",
    foreignKeys = [
        ForeignKey(
            entity = ChargingSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sessionId")]
)
data class ChargingDataPointEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val sessionId: Long,
    val timestamp: Long,

    val soc: Double,
    val socPanel: Int = 0,

    /** chargingPower from VehicleTelemetry — positive kW value */
    val chargingPower: Double,

    val batteryTotalVoltage: Int = 0,
    val battery12vVoltage: Double = 0.0,

    val batteryTempAvg: Double = 0.0,
    val batteryCellTempMin: Int = 0,
    val batteryCellTempMax: Int = 0,

    val batteryCellVoltageMin: Double = 0.0,
    val batteryCellVoltageMax: Double = 0.0
)