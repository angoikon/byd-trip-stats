@file:OptIn(kotlinx.serialization.InternalSerializationApi::class)

package com.byd.tripstats.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val telemetryJsonEncoder = Json { encodeDefaults = true }

@Serializable
data class VehicleTelemetry(
        // ── Required on all supported models ──────────────────────────────────────
        @SerialName("soc") val soc: Double,
        @SerialName("speed") val speed: Double,
        @SerialName("gear") val gear: String,
        @SerialName("odometer") val odometer: Double,
        @SerialName("engine_power") val enginePower: Int = 0,
        // Speed getter wedged at 0 while GPS shows motion — surfaces the "restart the car
        // after a mid-session update" hint on the dashboard. Not persisted/transmitted state.
        @SerialName("speed_getter_wedged") val speedGetterWedged: Boolean = false,
        @SerialName("total_discharge") val totalDischarge: Double,
        /** BYD PHM average consumption rate (kWh/100km). Null if the statistic device hasn't reported it. */
        @SerialName("total_elec_con_phm") val totalElecConPHM: Double? = null,

        // ── Optional — safe defaults so parsing never fails on missing keys ────────
        // FWD models (Atto 3, Dolphin) omit engine_speed_rear entirely.
        // Legacy history rows may omit soh, cell voltages, location, or wifi_ssid.
        @SerialName("battery_12v_voltage") val battery12vVoltage: Double = 0.0,
        @SerialName("battery_pack_temp") val batteryPackTemp: Double = 0.0,
        @SerialName("battery_cell_temp_max") val batteryCellTempMax: Int = 0,
        @SerialName("battery_cell_voltage_max") val batteryCellVoltageMax: Double = 0.0,
        @SerialName("battery_cell_temp_min") val batteryCellTempMin: Int = 0,
        @SerialName("battery_cell_voltage_min") val batteryCellVoltageMin: Double = 0.0,
        @SerialName("current_datetime") val currentDatetime: String = "",
        @SerialName("soh") val soh: Int = 0,
        @SerialName("soh_estimated") val sohEstimated: Boolean = false,
        @SerialName("location_altitude") val locationAltitude: Double = 0.0,
        @SerialName("is_charging") val chargingActive: Boolean = false,
        @SerialName("charging_power") val chargingPower: Double = 0.0,
        @SerialName("engine_speed_front") val engineSpeedFront: Int = 0,
        @SerialName("location_latitude") val locationLatitude: Double = 0.0,
        @SerialName("location_longitude") val locationLongitude: Double = 0.0,
        @SerialName("location_gps_speed") val locationGpsSpeed: Double? = null,
        @SerialName("location_orientation") val locationOrientation: Double? = null,
        @SerialName("engine_speed_rear") val engineSpeedRear: Int = 0, // absent on FWD
        @SerialName("wifi_ssid") val wifiSsid: String = "",
        @SerialName("battery_total_voltage") val batteryTotalVoltage: Int = 0,
        @SerialName("battery_total_current") val batteryTotalCurrent: Double? = null,
        @SerialName("electric_driving_range_km") val electricDrivingRangeKm: Int = 0,
        /** BYD car power state. 0 = off, 1 = awake/accessory, 2 = ready to drive. */
        @SerialName("car_on") val carOn: Int = 0,
        @SerialName("tyre_pressure_left_front_psi") val tyrePressureLF: Double = 0.0,
        @SerialName("tyre_pressure_right_front_psi") val tyrePressureRF: Double = 0.0,
        @SerialName("tyre_pressure_left_rear_psi") val tyrePressureLR: Double = 0.0,
        @SerialName("tyre_pressure_right_rear_psi") val tyrePressureRR: Double = 0.0,
        // ── Extended telemetry fields ──────────────────────────────────────────────
        @SerialName("tyre_temperature_left_front_c") val tyreTempLF: Int = 0,
        @SerialName("tyre_temperature_right_front_c") val tyreTempRF: Int = 0,
        @SerialName("tyre_temperature_left_rear_c") val tyreTempLR: Int = 0,
        @SerialName("tyre_temperature_right_rear_c") val tyreTempRR: Int = 0,
        /** Displayed SoC on the car's instrument panel — may differ slightly from raw soc */
        @SerialName("soc_panel") val socPanel: Int = 0,
        @SerialName("car_locked") val carLocked: Int = 0,
        @SerialName("any_door_opened") val anyDoorOpened: Int = 0,
        /** PHEV-only — always 0 on BEV models */
        @SerialName("fuel_percentage") val fuelPercentage: Int = 0,
        /** PHEV-only — always 0 on BEV models */
        @SerialName("fuel_driving_range_km") val fuelDrivingRangeKm: Int = 0,

        // ── Direct overlay fields ───────────────────────────────────────────────
        // These are merged in from the live vehicle snapshot so the app can use a
        // single telemetry model across live collection and stored history.
        @SerialName("battery_remain_power_ev") val batteryRemainPowerEV: Double? = null,
        @SerialName("speed_accelerate_deepness") val speedAccelerateDeepness: Int? = null,
        @SerialName("speed_brake_deepness") val speedBrakeDeepness: Int? = null,
        @SerialName("gearbox_brake_pedal_state") val gearboxBrakePedalState: Int? = null,
        @SerialName("average_speed") val averageSpeed: Double? = null,
        @SerialName("current_journey_drive_mileage") val currentJourneyDriveMileage: Double? = null,
        @SerialName("current_journey_drive_time") val currentJourneyDriveTime: Double? = null,
        @SerialName("turn_signal_flash_state") val turnSignalFlashState: Int? = null,
        @SerialName("turn_signal_left") val turnSignalLeft: Boolean? = null,
        @SerialName("turn_signal_right") val turnSignalRight: Boolean? = null,
        @SerialName("instrument_last50km_power_consume")
        val instrumentLast50KmPowerConsume: Double? = null,
        @SerialName("instrument_out_car_temperature") val instrumentOutCarTemperature: Int? = null,
        @SerialName("cabin_temperature") val cabinTemperature: Double? = null,
        @SerialName("instrument_mileage_unit") val instrumentMileageUnit: Int? = null,
        @SerialName("instrument_safety_belt_driver_status")
        val instrumentSafetyBeltDriverStatus: Int? = null,
        @SerialName("instrument_safety_belt_passenger_status")
        val instrumentSafetyBeltPassengerStatus: Int? = null,
        @SerialName("bodywork_auto_system_state") val bodyworkAutoSystemState: Int? = null,
        @SerialName("bodywork_battery_capacity") val bodyworkBatteryCapacity: Int? = null,
        @SerialName("bodywork_battery_power_hev") val bodyworkBatteryPowerHEV: Double? = null,
        @SerialName("bodywork_battery_power_value") val bodyworkBatteryPowerValue: Int? = null,
        @SerialName("bodywork_battery_voltage_level") val bodyworkBatteryVoltageLevel: Int? = null,
        @SerialName("bodywork_power_level") val bodyworkPowerLevel: Int? = null,
        @SerialName("bodywork_auto_vin") val bodyworkAutoVin: String? = null,
        @SerialName("tbox_serial_number") val tboxSerialNumber: String? = null,
        @SerialName("power_battery_remain_power_ev") val powerBatteryRemainPowerEV: Double? = null,
        @SerialName("sensor_temperature_value") val sensorTemperatureValue: Double? = null,
        // ── PHEV engine/fuel fields (null on pure BEV) ─────────────────────────────
        @SerialName("avg_fuel_consumption") val avgFuelConsumption: Double? = null,
        @SerialName("instant_fuel_consumption") val instantFuelConsumption: Double? = null,
        @SerialName("total_fuel_consumption") val totalFuelConsumption: Double? = null,
        @SerialName("ev_mileage_km") val evMileageKm: Int? = null,
        @SerialName("ice_mileage_km") val iceMileageKm: Int? = null,
        @SerialName("engine_coolant_temp") val engineCoolantTemp: Int? = null,
        // ── Statistic live-event battery fields (confirmed from the event stream) ──
        @SerialName("statistic_cell_voltage_min") val statisticCellVoltageMin: Double? = null,
        @SerialName("statistic_cell_voltage_max") val statisticCellVoltageMax: Double? = null,
        @SerialName("statistic_cell_temp_min") val statisticCellTempMin: Double? = null,
        @SerialName("statistic_cell_temp_max") val statisticCellTempMax: Double? = null,
        @SerialName("statistic_cell_temp_avg") val statisticCellTempAvg: Double? = null,
        @SerialName("statistic_battery_current") val statisticBatteryCurrent: Double? = null,
        @SerialName("statistic_battery_soh") val statisticBatterySoh: Double? = null,
        @SerialName("statistic_soc_bms") val statisticSocBms: Double? = null,
        @SerialName("statistic_avail_power") val statisticAvailPower: Double? = null,
        @SerialName("probe_values") val probeValues: Map<String, Double> = emptyMap(),
        @SerialName("drive_mode") val driveMode: Int = 0,
        @SerialName("energy_mode") val energyMode: Int = 0,
        @SerialName("regen_mode") val regenMode: Int = 0,
        /** 0=none, 1=AWD, 2=FWD, 3=RWD, 4=AWD-regen, 5=FWD-regen, 6=RWD-regen, 19=series-RWD */
        @SerialName("drivetrain_state") val drivetrainState: Int = 0,
        // ── HVAC fields ─────────────────────────────────────────────────────────
        /** 0 = off, non-zero = compressor running. Sourced from getAcCompressorMode(). */
        @SerialName("ac_compressor_mode") val acCompressorMode: Int? = null,
        /** Non-zero when the PTC resistive heater is active. */
        @SerialName("ac_ptc_preheat_signal") val acPtcPreheatSignal: Int? = null,
        /** Raw refrigerant line temperature from TEC controller (÷1000 = °C). */
        @SerialName("tec_control_temp_raw") val tecControlTempRaw: Int? = null,
        /** Estimated total HVAC draw in kW, derived from compressor line temp + PTC signal. */
        @SerialName("hvac_estimated_kw") val hvacEstimatedKw: Double? = null,
) {

    // ── Computed helpers ──────────────────────────────────────────────────────

    /**
     * True when the car is on (ignition / ready-to-drive). Gear alone is NOT a reliable signal —
     * the car can be in D/N/P while off. 12V voltage alone is also not reliable, because BYD can
     * keep DC/DC alive while the car is awake but not ready.
     *
     * Gear=D or gear=R is included as a strong proxy: the car is unambiguously on while
     * in a drive range, even when stopped at a red light.
     */
    val isCarOn: Boolean
        get() =
                carOn > 0 ||
                        gear in listOf("D", "R") ||
                        speed > 2.0 ||
                        enginePower > 5

    val isAwake: Boolean
        get() = carOn == 1

    val isReadyToDrive: Boolean
        get() = carOn >= 2

    val isCharging: Boolean
        get() = chargingActive || chargingPower > 0

    val isRegenerating: Boolean
        // -1.0 kW threshold filters out sensor noise at standstill.
        get() = enginePower < -1

    val isDriving: Boolean
        get() = gear in listOf("D", "R")

    val isMoving: Boolean
        get() = gear in listOf("D", "R") && speed > 0

    val isParked: Boolean
        get() = gear == "P"

    val batteryTempAvg: Double
        get() {
            // Source priority for the displayed battery temp:
            //   1. Charging device cell range (m39/m40) — direct °C, no encoding ambiguity,
            //      polled every fast tick whether charging or not. Surfaces here via
            //      batteryCellTempMin/Max (which falls back to statistic cellTMin/Max when
            //      the Charging device isn't registered yet).
            //   2. Statistic device cellTAvg — used only when no cell range is available.
            // batteryPackTemp is intentionally NOT consulted: m51 (Sensor device) appears
            // to be a coolant probe, and m36 (Charging) reads several °C below cells under
            // active battery cooling, both of which produce avgs inconsistent with the
            // displayed cell range.
            if (batteryCellTempMin > 0 && batteryCellTempMax > 0 && batteryCellTempMax >= batteryCellTempMin) {
                return (batteryCellTempMax + batteryCellTempMin) / 2.0
            }
            return statisticCellTempAvg?.takeIf { it.isFinite() } ?: 0.0
        }

    /**
     * Human-readable name for the operation/drive mode (Eco/Sport/Normal/Snow/Sand/Mud).
     * Sourced from the Gearbox device — valid on both BEV and PHEV.
     */
    val driveModeName: String
        get() =
                when (driveMode) {
                    1 -> "Eco"
                    2 -> "Sport"
                    3 -> "Normal"
                    4 -> "Snow"
                    5 -> "Mud"
                    6 -> "Sand"
                    else -> "Unknown"
                }

    /**
     * Human-readable name for the PHEV energy/powertrain source mode.
     * Sourced from the Energy device (m09). Only meaningful on PHEV models.
     *
     * NOT zero on BEVs — the m09 poll stores getEnergyMode for every car with no PHEV check,
     * and a BEV Seal reports 1 (=EV). Anything user-facing must gate on the selected car's
     * [com.byd.tripstats.data.config.CarConfig.isPhev], never on `energyMode != 0` alone.
     */
    val energyModeName: String
        get() =
                when (energyMode) {
                    1 -> "EV"
                    2 -> "Force EV"
                    3 -> "HEV"
                    4 -> "Fuel"
                    5 -> "Keep"
                    else -> "Unknown"
                }

    val regenModeName: String
        get() =
                when (regenMode) {
                    1 -> "Standard"
                    2 -> "High"
                    0 -> "Auto"
                    else -> "UNKNOWN($regenMode)"
                }

    /**
     * Serialises only non-default fields to rawJson to minimise storage.
     *
     * On a non-charging BEV during normal driving every field is at its default, so the result is
     * "{}" (2 bytes) vs the previous static string (38+ bytes) — a ~94% reduction on the rawJson
     * column for the vast majority of rows.
     *
     * Fields stored here (not promoted to first-class columns): chargingPower — available via
     * isCharging; not worth a column yet wifiSsid — informational only carLocked — display state,
     * not analytically useful per-point anyDoorOpened — same fuelPercentage / fuelDrivingRangeKm —
     * PHEV-only, always 0 on BEV Direct overlay fields below — captured so history exports can
     * preserve the direct-car telemetry even before we promote new DB columns.
     *
     * To promote a field: add column to TripDataPointEntity, bump DB version, write ALTER TABLE
     * migration, map it in recordDataPoint(), remove from here.
     */
    fun toRawJson(isPhev: Boolean = false): String {
        val fields = buildList {
            if (chargingActive) add(""""is_charging":true""")
            if (chargingPower > 0.0) add(""""charging_power":$chargingPower""")
            if (wifiSsid.isNotBlank()) add(""""wifi_ssid":"$wifiSsid"""")
            if (carLocked != 0) add(""""car_locked":$carLocked""")
            if (anyDoorOpened != 0) add(""""any_door_opened":$anyDoorOpened""")
            // Only write PHEV fields when the selected car is actually a PHEV
            if (isPhev && fuelPercentage != 0) add(""""fuel_percentage":$fuelPercentage""")
            if (isPhev && fuelDrivingRangeKm != 0)
                    add(""""fuel_driving_range_km":$fuelDrivingRangeKm""")
            // PHEV fuel/ICE counters — persisted per point so trip analysis can split
            // EV vs ICE distance and fuel burn (and the restore path can re-gate the
            // EV projection). Null on BEVs, so this costs BEV rows nothing.
            if (isPhev) {
                avgFuelConsumption?.let { add(""""avg_fuel_consumption":$it""") }
                instantFuelConsumption?.let { add(""""instant_fuel_consumption":$it""") }
                totalFuelConsumption?.let { add(""""total_fuel_consumption":$it""") }
                evMileageKm?.let { add(""""ev_mileage_km":$it""") }
                iceMileageKm?.let { add(""""ice_mileage_km":$it""") }
                engineCoolantTemp?.let { add(""""engine_coolant_temp":$it""") }
            }
            batteryRemainPowerEV?.let { add(""""battery_remain_power_ev":$it""") }
            speedAccelerateDeepness?.let { add(""""speed_accelerate_deepness":$it""") }
            speedBrakeDeepness?.let { add(""""speed_brake_deepness":$it""") }
            gearboxBrakePedalState?.let { add(""""gearbox_brake_pedal_state":$it""") }
            averageSpeed?.let { add(""""average_speed":$it""") }
            currentJourneyDriveMileage?.let { add(""""current_journey_drive_mileage":$it""") }
            currentJourneyDriveTime?.let { add(""""current_journey_drive_time":$it""") }
            locationGpsSpeed?.let { add(""""location_gps_speed":$it""") }
            locationOrientation?.let { add(""""location_orientation":$it""") }
            turnSignalFlashState?.let { add(""""turn_signal_flash_state":$it""") }
            turnSignalLeft?.let { add(""""turn_signal_left":$it""") }
            turnSignalRight?.let { add(""""turn_signal_right":$it""") }
            instrumentLast50KmPowerConsume?.let {
                add(""""instrument_last50km_power_consume":$it""")
            }
            instrumentOutCarTemperature?.let { add(""""instrument_out_car_temperature":$it""") }
            cabinTemperature?.let { add(""""cabin_temperature":$it""") }
            instrumentMileageUnit?.let { add(""""instrument_mileage_unit":$it""") }
            instrumentSafetyBeltDriverStatus?.let {
                add(""""instrument_safety_belt_driver_status":$it""")
            }
            instrumentSafetyBeltPassengerStatus?.let {
                add(""""instrument_safety_belt_passenger_status":$it""")
            }
            bodyworkAutoSystemState?.let { add(""""bodywork_auto_system_state":$it""") }
            bodyworkBatteryCapacity?.let { add(""""bodywork_battery_capacity":$it""") }
            bodyworkBatteryPowerHEV?.let { add(""""bodywork_battery_power_hev":$it""") }
            bodyworkBatteryPowerValue?.let { add(""""bodywork_battery_power_value":$it""") }
            bodyworkBatteryVoltageLevel?.let { add(""""bodywork_battery_voltage_level":$it""") }
            bodyworkPowerLevel?.let { add(""""bodywork_power_level":$it""") }
            bodyworkAutoVin?.takeIf { it.isNotBlank() }?.let {
                add(""""bodywork_auto_vin":"$it"""")
            }
            powerBatteryRemainPowerEV?.let { add(""""power_battery_remain_power_ev":$it""") }
            sensorTemperatureValue?.let { add(""""sensor_temperature_value":$it""") }
            statisticCellVoltageMin?.let { add(""""statistic_cell_voltage_min":$it""") }
            statisticCellVoltageMax?.let { add(""""statistic_cell_voltage_max":$it""") }
            statisticCellTempMin?.let { add(""""statistic_cell_temp_min":$it""") }
            statisticCellTempMax?.let { add(""""statistic_cell_temp_max":$it""") }
            statisticCellTempAvg?.let { add(""""statistic_cell_temp_avg":$it""") }
            statisticBatteryCurrent?.let { add(""""statistic_battery_current":$it""") }
            statisticBatterySoh?.let { add(""""statistic_battery_soh":$it""") }
            statisticSocBms?.let { add(""""statistic_soc_bms":$it""") }
            statisticAvailPower?.let { add(""""statistic_avail_power":$it""") }
            if (probeValues.isNotEmpty()) {
                // Sort keys deterministically for stable JSON output. Use a
                // LinkedHashMap<String, Double> (not toSortedMap() which returns
                // a TreeMap); kotlinx.serialization cannot resolve the runtime
                // TreeMap/SortedMap type against its registered serializers and
                // throws SerializationException, which would drop this entire
                // data-point row.
                val sortedProbes: Map<String, Double> =
                    probeValues.entries.sortedBy { it.key }
                        .associateTo(LinkedHashMap()) { it.key to it.value }
                add(""""probe_values":${telemetryJsonEncoder.encodeToString(sortedProbes)}""")
            }
            if (sohEstimated) add(""""soh_estimated":true""")
            if (batteryPackTemp > 0.0) add(""""battery_pack_temp":$batteryPackTemp""")
            if (driveMode != 0) add(""""drive_mode":$driveMode""")
            if (energyMode != 0) add(""""energy_mode":$energyMode""")
            if (regenMode != 0) add(""""regen_mode":$regenMode""")
        }
        return if (fields.isEmpty()) "{}" else "{${fields.joinToString(",")}}"
    }

    /**
     * Serialises the full schema so history/export callers can keep every field the app knows
     * about, even when the live value is still default or null.
     */
    fun toSchemaJson(isPhev: Boolean = false): String {
        val fields = buildList {
            // Fields omitted here are already persisted in dedicated DB columns:
            // soc, speed, gear, odometer, enginePower (power), totalDischarge,
            // battery12vVoltage, batteryCellVoltageMax/Min, batteryTotalVoltage, soh,
            // engineSpeedFront/Rear, latitude, longitude, altitude, electricDrivingRangeKm,
            // tyrePressure*, tyreTemp*, socPanel.
            // Only non-column fields are stored in rawJson to avoid ~700 bytes/row duplication.
            add(""""battery_pack_temp":$batteryPackTemp""")
            add(""""battery_cell_temp_max":$batteryCellTempMax""")
            add(""""battery_cell_temp_min":$batteryCellTempMin""")
            if (currentDatetime.isNotBlank()) add(""""current_datetime":"$currentDatetime"""")
            add(""""soh_estimated":$sohEstimated""")
            add(""""is_charging":$chargingActive""")
            add(""""charging_power":$chargingPower""")
            locationGpsSpeed?.let { add(""""location_gps_speed":$it""") }
            locationOrientation?.let { add(""""location_orientation":$it""") }
            if (wifiSsid.isNotBlank()) add(""""wifi_ssid":"$wifiSsid"""")
            add(""""car_on":$carOn""")
            add(""""car_locked":$carLocked""")
            add(""""any_door_opened":$anyDoorOpened""")
            if (isPhev) add(""""fuel_percentage":$fuelPercentage""")
            if (isPhev) add(""""fuel_driving_range_km":$fuelDrivingRangeKm""")
            if (isPhev) {
                avgFuelConsumption?.let { add(""""avg_fuel_consumption":$it""") }
                instantFuelConsumption?.let { add(""""instant_fuel_consumption":$it""") }
                totalFuelConsumption?.let { add(""""total_fuel_consumption":$it""") }
                evMileageKm?.let { add(""""ev_mileage_km":$it""") }
                iceMileageKm?.let { add(""""ice_mileage_km":$it""") }
                engineCoolantTemp?.let { add(""""engine_coolant_temp":$it""") }
            }
            batteryRemainPowerEV?.let { add(""""battery_remain_power_ev":$it""") }
            speedAccelerateDeepness?.let { add(""""speed_accelerate_deepness":$it""") }
            speedBrakeDeepness?.let { add(""""speed_brake_deepness":$it""") }
            gearboxBrakePedalState?.let { add(""""gearbox_brake_pedal_state":$it""") }
            averageSpeed?.let { add(""""average_speed":$it""") }
            currentJourneyDriveMileage?.let { add(""""current_journey_drive_mileage":$it""") }
            currentJourneyDriveTime?.let { add(""""current_journey_drive_time":$it""") }
            turnSignalFlashState?.let { add(""""turn_signal_flash_state":$it""") }
            turnSignalLeft?.let { add(""""turn_signal_left":$it""") }
            turnSignalRight?.let { add(""""turn_signal_right":$it""") }
            instrumentLast50KmPowerConsume?.let {
                add(""""instrument_last50km_power_consume":$it""")
            }
            instrumentOutCarTemperature?.let { add(""""instrument_out_car_temperature":$it""") }
            cabinTemperature?.let { add(""""cabin_temperature":$it""") }
            instrumentMileageUnit?.let { add(""""instrument_mileage_unit":$it""") }
            instrumentSafetyBeltDriverStatus?.let {
                add(""""instrument_safety_belt_driver_status":$it""")
            }
            instrumentSafetyBeltPassengerStatus?.let {
                add(""""instrument_safety_belt_passenger_status":$it""")
            }
            bodyworkAutoSystemState?.let { add(""""bodywork_auto_system_state":$it""") }
            bodyworkBatteryCapacity?.let { add(""""bodywork_battery_capacity":$it""") }
            bodyworkBatteryPowerHEV?.let { add(""""bodywork_battery_power_hev":$it""") }
            bodyworkBatteryPowerValue?.let { add(""""bodywork_battery_power_value":$it""") }
            bodyworkBatteryVoltageLevel?.let { add(""""bodywork_battery_voltage_level":$it""") }
            bodyworkPowerLevel?.let { add(""""bodywork_power_level":$it""") }
            bodyworkAutoVin?.takeIf { it.isNotBlank() }?.let {
                add(""""bodywork_auto_vin":"$it"""")
            }
            powerBatteryRemainPowerEV?.let { add(""""power_battery_remain_power_ev":$it""") }
            sensorTemperatureValue?.let { add(""""sensor_temperature_value":$it""") }
            statisticCellVoltageMin?.let { add(""""statistic_cell_voltage_min":$it""") }
            statisticCellVoltageMax?.let { add(""""statistic_cell_voltage_max":$it""") }
            statisticCellTempMin?.let { add(""""statistic_cell_temp_min":$it""") }
            statisticCellTempMax?.let { add(""""statistic_cell_temp_max":$it""") }
            statisticCellTempAvg?.let { add(""""statistic_cell_temp_avg":$it""") }
            statisticBatteryCurrent?.let { add(""""statistic_battery_current":$it""") }
            statisticBatterySoh?.let { add(""""statistic_battery_soh":$it""") }
            statisticSocBms?.let { add(""""statistic_soc_bms":$it""") }
            statisticAvailPower?.let { add(""""statistic_avail_power":$it""") }
            if (probeValues.isNotEmpty()) {
                // Sort keys deterministically for stable JSON output. Use a
                // LinkedHashMap<String, Double> (not toSortedMap() which returns
                // a TreeMap); kotlinx.serialization cannot resolve the runtime
                // TreeMap/SortedMap type against its registered serializers and
                // throws SerializationException, which would drop this entire
                // data-point row.
                val sortedProbes: Map<String, Double> =
                    probeValues.entries.sortedBy { it.key }
                        .associateTo(LinkedHashMap()) { it.key to it.value }
                add(""""probe_values":${telemetryJsonEncoder.encodeToString(sortedProbes)}""")
            }
            add(""""drive_mode":$driveMode""")
            if (energyMode != 0) add(""""energy_mode":$energyMode""")
            add(""""regen_mode":$regenMode""")
        }
        return "{${fields.joinToString(",")}}"
    }
}
