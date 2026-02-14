package com.byd.sealstats.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class VehicleTelemetry(
    @SerialName("battery_12v_voltage") val battery12vVoltage: Double,
    @SerialName("battery_cell_temp_max") val batteryCellTempMax: Int,
    @SerialName("battery_cell_voltage_max") val batteryCellVoltageMax: Double,
    @SerialName("battery_cell_temp_min") val batteryCellTempMin: Int,
    @SerialName("battery_cell_voltage_min") val batteryCellVoltageMin: Double,
    @SerialName("current_datetime") val currentDatetime: String,
    @SerialName("odometer") val odometer: Double,
    @SerialName("soc") val soc: Double,
    @SerialName("soh") val soh: Int,
    @SerialName("location_altitude") val locationAltitude: Double,
    @SerialName("charging_power") val chargingPower: Double,
    @SerialName("engine_power") val enginePower: Double,
    @SerialName("engine_speed_front") val engineSpeedFront: Int,
    @SerialName("gear") val gear: String,
    @SerialName("location_latitude") val locationLatitude: Double,
    @SerialName("location_longitude") val locationLongitude: Double,
    @SerialName("engine_speed_rear") val engineSpeedRear: Int,
    @SerialName("speed") val speed: Double,
    @SerialName("wifi_ssid") val wifiSsid: String,
    @SerialName("battery_total_voltage") val batteryTotalVoltage: Int,
    @SerialName("electric_driving_range_km") val electricDrivingRangeKm: Int,
    @SerialName("total_discharge") val totalDischarge: Double
) {
    // Helper properties for calculations
    val isCharging: Boolean
        get() = chargingPower > 0
    
    val isRegenerating: Boolean
        get() = enginePower < -1.0 // Negative power indicates regeneration
    
    val isMoving: Boolean
        get() = speed > 0.5
    
    val isDriving: Boolean
        get() = gear in listOf("D", "R") && speed > 0
    
    val isParked: Boolean
        get() = gear == "P"
    
    val batteryTempAvg: Double
        get() = (batteryCellTempMax + batteryCellTempMin) / 2.0
}
