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

    val socStartPanel: Double = 0.0,
    val socEndPanel: Double? = null,

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

    val isActive: Boolean = true,

    /**
     * User-flagged favourite. Favourited sessions are exempt from the manual
     * DatabaseTrimmer (their charging data points and rawJson are preserved).
     * Defaults to false.
     */
    val isFavourite: Boolean = false,

    /**
     * Odometer (km) captured when charging started — the anchor for
     * "distance driven since the previous charge". Null on legacy rows
     * (pre-v11) and when the reading was unavailable; the UI then derives
     * the value from the nearest trip's odometer instead.
     */
    val startOdometer: Double? = null,

    /**
     * User-set electricity price for THIS charge, in currency/kWh.
     *   null → use the global tariff (the charge cost is an estimate).
     *   0.0  → free charging (explicitly free, a valid value).
     *   >0.0 → the real price paid, entered directly or derived from a total.
     * Drives the real per-charge cost and, via the charge→trip interval link,
     * the derived cost of the trips that drew their energy from this charge.
     */
    val pricePerKwh: Double? = null
) {
    val durationSeconds: Long?
        get() = endTime?.let { (it - startTime) / 1000L }

    val socDelta: Double?
        get() = socEnd?.let { it - socStart }

    val socPanelDelta: Double?
        get() = socEndPanel?.let { it - socStartPanel }

    /**
     * Explicit cost of this charge when a per-charge price is set (including 0.0
     * for a free charge); null when the price is unset, so callers fall back to
     * the global tariff at display time.
     */
    val explicitCost: Double?
        get() = pricePerKwh?.let { rate -> (kwhAdded ?: 0.0) * rate }
}

/**
 * One row per telemetry sample received during a charging session.
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
    val batteryCellVoltageMax: Double = 0.0,

    /** Full telemetry payload captured alongside the charging sample. */
    val rawJson: String = "{}"
)
