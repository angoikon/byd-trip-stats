package com.byd.tripstats.sdk

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.bydauto.charging.AbsBYDAutoChargingListener
import android.hardware.bydauto.charging.BYDAutoChargingDevice
import android.hardware.bydauto.BYDAutoEventValue
import android.hardware.bydauto.gearbox.AbsBYDAutoGearboxListener
import android.hardware.bydauto.gearbox.BYDAutoGearboxDevice
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Looper
import android.os.SystemClock
import android.hardware.bydauto.tyre.AbsBYDAutoTyreListener
import android.hardware.bydauto.tyre.BYDAutoTyreDevice
import android.util.Log
import androidx.core.content.ContextCompat
import com.byd.tripstats.BuildConfig
import com.byd.tripstats.data.config.CarConfig
import com.byd.tripstats.data.model.VehicleTelemetry
import com.byd.tripstats.data.preferences.PreferencesManager
import com.byd.tripstats.runtimebridge.RuntimeExtensionBridge
import com.byd.tripstats.util.DiagLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import kotlin.math.abs

private const val TAG = "BydVehicleDataSource"
private const val BYD_BLADE_REFERENCE_CELL_VOLTAGE = 3.22
private val ENABLE_LAB_DIAGNOSTICS = BuildConfig.SENSITIVE_DIAGNOSTICS_ENABLED
private const val ENABLE_VERBOSE_SNAPSHOT_LOGS = false
private const val ENABLE_VERBOSE_RAW_EVENT_LOGS = false
private val ENABLE_MODE_STATE_LOGS = BuildConfig.SENSITIVE_DIAGNOSTICS_ENABLED && RuntimeExtensionBridge.isAvailable
private const val WIFI_SSID_TTL_MS = 30_000L
private const val VEHICLE_STATE_CACHE = "vehicle_state_cache"
private const val KEY_CHARGER_SIGNALS_UNRELIABLE = "charger_signals_unreliable"
// Bump this when the persisted value mapping changes so stale cached values are cleared.
// v2: remapped an auxiliary housing sensor away from cellTempMin.
// v3: persist last confirmed drive/regen modes so weak startup samples do not regress the UI.
// v6: ambient-heuristic temperature fix — cached temps written by the old raw-only path may be
//     wrong (e.g. raw=62 stored as 62°C instead of 62÷3≈20.7°C on Seal Excellence firmware).
// v8: remap statistic thermal feature IDs to BYD's raw-40 encoding:
//     0x44700010=low, 0x44700020=high, 0x44700038=avg.
private const val STAT_CACHE_VERSION = 8
private const val DIRECT_CHARGING_POWER_TIMEOUT_MS = 4_000L
// DC-charge inference fallback: minimum |pack power| (kW flowing into the battery, read from the
// synchronous enginePower getter) for treating a parked, car-on charge as active when the BMS
// charging listener is silent (its pushed callbacks can wedge; a DC charge started while the head
// unit is fully on may never populate gun/work/capacity). Sits above the 22 kW three-phase-AC
// ceiling (AC tops out ~21 kW at the pack), so AC charging is left entirely to the listener path,
// and matches the service's existing >23 kW DC-fast cadence threshold. BEV-only at the call site
// to avoid a PHEV's engine-charging-in-Park reading as a plug-in charge. See toTelemetry().
private const val DC_CHARGE_INFER_MIN_KW = 23.0
// Some firmwares (notably DM-i PHEV) don't return chargerWorkState to 0 after
// charging completes — the BMS only resets it when the user manually clears
// charging-data in the car UI. If we've seen capacity activity during this
// session AND nothing has happened (no power, no capacity increase) for longer
// than this, treat the work state as stale and force it back to 0. Generous
// margin so a normal taper / pause / brief BMS silence never trips it.
private const val STALE_CHARGER_WORK_STATE_TIMEOUT_MS = 10 * 60_000L
// Grace window for bridging a stale 0 from the speed getter when no independent
// motion signal (power / GPS) is available. The getter updates slower than the
// 1 s poll, so a 0 lasting under this window during a drive is treated as a glitch
// and the last known speed is preserved. ~3.5 s covers several missed poll cycles
// while a genuine stop (sustained 0 with no power/GPS) commits shortly after.
private const val SPEED_STALE_GRACE_MS = 3_500L
// How recently the Speed device's event stream must have fired for it to "own" the
// displayed speed (so the poll getter, which can freeze at a stale 0, doesn't clobber it).
private const val SPEED_EVENT_FRESHNESS_MS = 3_000L
// Min spacing between committed typed-speed pushes (onSpeedChanged). The cluster can fire
// these faster than the UI needs; ~100 ms (10 Hz) keeps the speedometer visually instant
// without recomposing on every sub-frame event. Matches Kinex's onSpeedChanged throttle.
private const val SPEED_PUSH_MIN_INTERVAL_MS = 100L
// Absolute ceiling for a single speed reading. The BYD SpeedDevice (poll getter and event
// stream) occasionally emits a one-off garbage value; anything above any production BYD's
// electronically-limited top speed is treated as a glitch and dropped at the source, so it
// can't drive the speedometer or poison the learned event→km/h scale. In-range spikes that
// slip under this ceiling are caught per-sample by the Max-Speed guard in TripRepository.
private const val MAX_PLAUSIBLE_SPEED_KMH = 250.0
// A single-poll BMS SoC step larger than this (percentage points) is treated as a glitch and
// held once — far above any real per-second SoC change, far below the observed ~1.5 pt dips.
private const val SOC_BMS_GLITCH_STEP = 1.0
// Minimum GPS speed (km/h) treated as clear motion when bridging a wedged/zero speed getter.
// Above typical standstill GPS jitter so a parked car still reads 0.
private const val GPS_BRIDGE_MIN_KMH = 2.0
// Wedge detection: getCurrentSpeed stuck at 0 while GPS shows clear motion for this long ⇒ the
// getter is wedged (the post-mid-session-install state that only a car off/on clears).
private const val WEDGE_GPS_THRESHOLD_KMH = 8.0
private const val WEDGE_CONFIRM_MS = 10_000L
private const val DRIVE_MODE_UNKNOWN = 0

private enum class InstrumentTyrePressureEncoding {
    CENTI_BAR,
    DECI_PSI,
    LEGACY
}

data class VehicleTelemetrySnapshot(
    val isChargingActive: Boolean = false,
    val chargingPower: Double = 0.0,
    /** True when [chargingPower] came from the capacity-rate tier (NET energy into the cells, which
     *  under-reports the charger's gross output). False when it came from a gross getter
     *  (instrument getChargePower / m33) — those are decimal-accurate and match the BYD app, so the
     *  whole-kW enginePower fallback must NOT override them. */
    val chargingPowerIsNet: Boolean = false,
    val chargingPowerRaw: Double = 0.0,
    val chargingCapacity: Double = 0.0,
    val chargingCapState: Int = 0,
    val chargingCapValue: Int = 0,
    val chargingEventPowerCandidateRaw: Double = 0.0,
    val chargingEventCapacityRaw: Double = 0.0,
    val chargingEventUnknownInt27Raw: Int = 0,
    val chargingEventUnknownCounterRaw: Int = 0,
    val gear: String = "P",
    val chargeState: Int = 0,
    val remainMinutes: Int = 0,
    val remainHours: Int = 0,
    val chargerState: Int = 0,
    val chargerWorkState: Int = 0,
    val chargingType: Int = 0,
    val chargingGunState: Int = 0,
    val chargingMode: Int = 0,
    val gearboxState: Int = 0,
    val currentGearRaw: Int? = null,
    val tyrePressureLFPsi: Double = 0.0,
    val tyrePressureRFPsi: Double = 0.0,
    val tyrePressureLRPsi: Double = 0.0,
    val tyrePressureRRPsi: Double = 0.0,
    val tyrePressureLFBar: Double = 0.0,
    val tyrePressureRFBar: Double = 0.0,
    val tyrePressureLRBar: Double = 0.0,
    val tyrePressureRRBar: Double = 0.0,
    val tyrePressureLFState: Int? = null,
    val tyrePressureRFState: Int? = null,
    val tyrePressureLRState: Int? = null,
    val tyrePressureRRState: Int? = null,
    // Tyre temperatures in °C — null when not yet received from TPMS sensors
    val tyreTempLF: Int? = null,
    val tyreTempRF: Int? = null,
    val tyreTempLR: Int? = null,
    val tyreTempRR: Int? = null,
    val directSpeedKmh: Double = 0.0,
    // True when getCurrentSpeed() is wedged at 0 while GPS shows the car is clearly moving —
    // the "needs a head-unit (car off/on) cycle after a mid-session install" state.
    val speedGetterWedged: Boolean = false,
    val speedAccelerateDeepness: Int? = null,
    val speedBrakeDeepness: Int? = null,
    val gearboxBrakePedalState: Int? = null,
    val instrumentAverageSpeed: Double? = null,
    val instrumentCurrentJourneyDriveMileage: Double? = null,
    val driveMode: Int = 0,
    /** PHEV energy/powertrain source mode from Energy device (m09). 0 on BEVs. */
    val energyMode: Int = 0,
    val regenMode: Int = 0,
    /**
     * Drivetrain engagement state from Energy device getEnergyState().
     * 0=none, 1=AWD, 2=FWD, 3=RWD, 4=AWD-regen, 5=FWD-regen, 6=RWD-regen, 19=series-RWD.
     * Used to zero engineSpeedFront when the front motor is confirmed off.
     */
    val drivetrainState: Int = 0,
    val instrumentCurrentJourneyDriveTime: Double? = null,
    val instrumentBatteryPercent: Double? = null,
    val instrumentChargePercent: Double? = null,
    val instrumentOdometerDisplay: Double? = null,
    val instrumentPowerUnit: Int? = null,
    val turnSignalFlashState: Int? = null,
    val turnSignalLeft: Boolean? = null,
    val turnSignalRight: Boolean? = null,
    val bodyworkAutoSystemState: Int? = null,
    val bodyworkBatteryCapacity: Int? = null,
    val bodyworkBatteryPowerHEV: Double? = null,
    val bodyworkBatteryPowerValue: Int? = null,
    val bodyworkBatteryVoltageLevel: Int? = null,
    val bodyworkPowerLevel: Int? = null,
    val bodyworkAutoVin: String? = null,
    val tboxSerialNumber: String? = null,   // non-PII license device id (DiLink-5), replaces VIN
    val powerBatteryRemainPowerEV: Double? = null,
    val powerMcuStatus: Int? = null,
    val sensorTemperatureValue: Double? = null,
    val cabinTemperature: Double? = null,
    val powerStateRaw: Int? = null,
    val instrumentLast50KmPowerConsume: Double? = null,
    val instrumentOutCarTemperature: Int? = null,
    val pm25InCar: Int? = null,
    val pm25OutCar: Int? = null,
    val instrumentMileageUnit: Int? = null,
    val instrumentSafetyBeltDriverStatus: Int? = null,
    val instrumentSafetyBeltPassengerStatus: Int? = null,
    val locationLatitude: Double? = null,
    val locationLongitude: Double? = null,
    val locationAltitude: Double? = null,
    val locationGpsSpeed: Double? = null,
    val locationVisibleSatelliteNumber: Int? = null,
    val locationFixPosition: Int? = null,
    val locationOrientation: Double? = null,
    val enginePower: Int? = null,
    val engineSpeedFront: Int? = null,
    val engineSpeedRear: Int? = null,
    // ── Battery device fields ───────────────────────────────────────────────────
    val batterySoh: Int? = null,
    val batteryTotalVoltage: Int? = null,
    val batteryTotalCurrent: Double? = null,
    val battery12vVoltage: Double? = null,
    val batteryPackTemp: Double? = null,
    val batteryCellTempMax: Int? = null,
    val batteryCellTempMin: Int? = null,
    val batteryCellVoltageMax: Double? = null,
    val batteryCellVoltageMin: Double? = null,
    // ── Body state fields ───────────────────────────────────────────────────────
    val carLocked: Int? = null,
    val anyDoorOpened: Int? = null,
    // ── Statistic fields ─────────────────────────────────────────────────────
    val statisticElecPercentageValue: Double? = null,
    val statisticElecDrivingRangeValue: Int? = null,
    val statisticFuelPercentageValue: Int? = null,    // null if 0xFF sentinel
    val statisticFuelDrivingRangeValue: Int? = null,  // null if 0x7FE sentinel
    val statisticTotalElecConValue: Double? = null,
    val statisticTotalElecConPHMValue: Double? = null,
    val statisticTotalMileageValue: Int? = null,
    val statisticTotalMileageDecimal: Double? = null,
    val statisticDrivingTimeValue: Double? = null,
    val statisticWaterTemperature: Int? = null,       // null if sentinel (<-50 or >200)
    // ── PHEV-specific statistic fields ───────────────────────────────────────
    val statisticAvgFuelConsumption: Double? = null,
    val statisticInstantFuelCon: Double? = null,
    val statisticTotalFuelConValue: Double? = null,
    val statisticEvMileageValue: Int? = null,         // cumulative EV-only km
    val statisticHevMileageValue: Int? = null,        // cumulative HEV (ICE) km
    val statisticSocBatteryPct: Double? = null,       // SoC from statistic device
    // ── Statistic live-event battery fields pushed by the car ────────────────────
    val statisticCellVoltageMin: Double? = null,
    val statisticCellVoltageMax: Double? = null,
    val statisticCellTempMin: Double? = null,
    val statisticCellTempMax: Double? = null,
    val statisticCellTempAvg: Double? = null,
    val statisticBatteryCurrent: Double? = null,
    val statisticBatterySoh: Double? = null,
    /** True only when SOH came from a non-confirmed source derived from car telemetry. */
    val sohEstimated: Boolean = false,
    val statisticSocBms: Double? = null,
    val statisticAvailPower: Double? = null,
    val probeValues: Map<String, Double> = emptyMap(),
    // ── HVAC fields ──────────────────────────────────────────────────────────
    val acCompressorMode: Int? = null,
    val acPtcPreheatSignal: Int? = null,
    val tecControlTempRaw: Int? = null,
    val hvacEstimatedKw: Double? = null,
    val roadSlopeDeg: Double? = null,
    /** Connected Wi-Fi SSID (head unit), populated by the data source; "" if unavailable. */
    val wifiSsid: String = "",
) {
    private fun estimateSohFromBodyworkCapacity(carConfig: CarConfig?): Double? {
        val capacityAh = bodyworkBatteryCapacity?.toDouble()?.takeIf { it > 0.0 } ?: return null
        val batteryKwh = carConfig?.batteryKwh?.takeIf { it > 0.0 } ?: return null
        val cellCount = carConfig.cellCount.takeIf { it > 0 } ?: return null
        val nominalCapacityAh = (batteryKwh * 1000.0) / (cellCount * BYD_BLADE_REFERENCE_CELL_VOLTAGE)
        return ((capacityAh / nominalCapacityAh) * 100.0)
            .coerceIn(50.0, 100.0)
    }

    private fun estimateSohFromRemainingEnergy(carConfig: CarConfig?): Double? {
        val remainingKwh = powerBatteryRemainPowerEV?.takeIf { it > 0.0 } ?: return null
        val batteryKwh = carConfig?.batteryKwh?.takeIf { it > 0.0 } ?: return null
        val socPercent = statisticSocBms
            ?: statisticElecPercentageValue
            ?: statisticSocBatteryPct
            ?: instrumentBatteryPercent
        val normalizedSoc = socPercent
            ?.takeIf { it in 1.0..100.0 }
            ?.div(100.0)
            ?: return null
        return ((remainingKwh / normalizedSoc) / batteryKwh * 100.0)
            .coerceIn(50.0, 100.0)
    }

    fun asSchemaMap(): Map<String, Any?> {
        fun tyrePressureSchemaValue(psi: Double, state: Int?): Double? {
            if (state != null && state != 0) return null
            return if (psi > 0.0) psi else null
        }

        return linkedMapOf(
            "charging_power" to chargingPower,
            "gear" to gear,
            "tyre_pressure_left_front_psi" to tyrePressureSchemaValue(tyrePressureLFPsi, tyrePressureLFState),
            "tyre_pressure_right_front_psi" to tyrePressureSchemaValue(tyrePressureRFPsi, tyrePressureRFState),
            "tyre_pressure_left_rear_psi" to tyrePressureSchemaValue(tyrePressureLRPsi, tyrePressureLRState),
            "tyre_pressure_right_rear_psi" to tyrePressureSchemaValue(tyrePressureRRPsi, tyrePressureRRState),
            "engine_power" to (enginePower ?: 0),
            "odometer" to (statisticTotalMileageValue ?: 0),
            "electric_driving_range_km" to (statisticElecDrivingRangeValue ?: 0),
        )
    }

    fun toTelemetry(carConfig: CarConfig? = null): VehicleTelemetry {
        val batteryKwh = carConfig?.batteryKwh ?: 0.0
        val fallbackSoh = statisticBatterySoh
            ?: estimateSohFromBodyworkCapacity(carConfig)
            ?: estimateSohFromRemainingEnergy(carConfig)
        val inferredSoh = (batterySoh ?: fallbackSoh?.toInt())?.coerceIn(0, 100)
        val inferredSohEstimated = sohEstimated ||
            (batterySoh == null && statisticBatterySoh == null && fallbackSoh != null)
        val panelSoc = statisticElecPercentageValue
            ?.takeIf { it in 0.0..100.0 }
            ?: instrumentBatteryPercent?.takeIf { it in 0.0..100.0 }
            ?: statisticSocBatteryPct?.takeIf { it in 0.0..100.0 }
        val derivedBmsSoc = powerBatteryRemainPowerEV
            ?.takeIf { batteryKwh > 0.0 }
            ?.let { (it / batteryKwh) * 100.0 }
            ?.takeIf { it in 0.0..100.0 }
        val directBmsSoc = statisticSocBms
            ?.takeIf { it in 0.0..100.0 }
            ?.takeIf { candidate ->
                when {
                    derivedBmsSoc != null && abs(candidate - derivedBmsSoc) <= 1.5 -> true
                    panelSoc != null && abs(candidate - panelSoc) <= 0.5 && derivedBmsSoc != null &&
                        abs(derivedBmsSoc - panelSoc) >= 1.5 -> false
                    panelSoc != null && abs(candidate - panelSoc) <= 0.5 -> false
                    else -> true
                }
            }
        // On DiLink-5 the derived usable-kWh SoC is the intended (decimal) BMS source. statisticSocBms
        // can still be set live on CI builds — the raw-signal statistic decode that feeds it is gated by
        // private-telemetry, which a contributor's local build lacks — and being an integer within 1.5 of
        // the derived value it wins the directBmsSoc check above, flooring the displayed BMS SoC to a whole
        // number. Prefer the derived decimal on DiLink-5; DiLink-3 keeps the direct BMS reading as before.
        val effectiveBmsSoc = if (DiLink5Platform.isDiLink5) (derivedBmsSoc ?: directBmsSoc)
                              else (directBmsSoc ?: derivedBmsSoc)
        val socEstimate = when {
            effectiveBmsSoc != null ->
                effectiveBmsSoc
            statisticSocBatteryPct != null && statisticSocBatteryPct in 0.0..100.0 ->
                statisticSocBatteryPct
            panelSoc != null ->
                panelSoc
            else -> 0.0
        }
        val inferredPackVoltage = batteryTotalVoltage
            ?: run {
                val cellV = statisticCellVoltageMin ?: statisticCellVoltageMax
                val n = carConfig?.cellCount ?: 0
                if (cellV != null && n > 0) (cellV * n).toInt() else null
            }
        // getOdometerDisplay() looks like a display/state code on current Seal firmware
        // (for example a constant "3"), not a trustworthy mileage value. Only use
        // the statistic mileage path here.
        val odometerCandidate = statisticTotalMileageDecimal
            ?.takeIf { it.isFinite() && it > 1000.0 }
        val odometerValue = odometerCandidate
            ?: statisticTotalMileageValue?.toDouble()
            ?: 0.0
        val currentDatetime = Instant.now().toString()
        val derivedCarOn = powerStateRaw?.coerceIn(0, 2)
            ?: powerMcuStatus?.coerceIn(0, 2)
            ?: 0

        // DC-charge synchronous fallback. The BMS charging listener (gun/work/capacity) is the
        // primary detector, but its *pushed* callbacks can go silent — the SDK event-delivery
        // channel wedges on this firmware, and a DC charge begun while the head unit is fully on
        // has been observed to never populate them: the charge runs, yet isChargingActive stays
        // false and chargingPower stays 0, so no session is ever opened. The one signal that
        // survives is the *synchronous* pack-power getter (enginePower), which reads strongly
        // negative while current flows into the pack. In Park there is no regen and the 12 V DC-DC
        // draw is sub-kW, so a large sustained negative pack power can only be a charge. Gated to:
        //   • gear P  — charging requires Park (the reliable gear reading; never wedges to D/R here);
        //   • BEV     — a PHEV can push power into the pack from its engine while parked, which is
        //               not a plug-in charge;
        //   • |power| above the 22 kW three-phase-AC ceiling, so AC charging is left to the listener;
        //   • listener silent (isChargingActive false, chargingPower 0), so a working detection is
        //     never overridden.
        // NB: deliberately NOT gated on carOn — an on-car AC test showed the power-state device
        // reports null (carOn=-) throughout a charge, so a carOn>0 gate would defeat this in the very
        // scenario it exists for. The threshold is self-validating instead: a sustained pack power
        // below -23 kW in Park requires a live HV system pushing big power, which only a charge does,
        // and enginePower reads ~0 once current stops (it tracked reality at -5 kW during a 3 kW AC
        // charge), so a value can't latch a phantom after the car powers off.
        // chargingPower is taken straight from the pack reading so DC sessions get a real power
        // curve instead of the 0 kW the listener path records when it can't see the magnitude.
        // Pack power for the charging magnitude. On the Seal this is only available as a whole-kW
        // integer (getEnginePower / m23); the decimal-precise sources — getChargingPower (m33),
        // the Power device V/I (m50) and total voltage (m37) — all read 0/null on this firmware,
        // so the charge power is quantized to whole kW here (a ~6.1 kW charge reports 6).
        val packPowerKw = enginePower ?: 0
        val parkedBev = gear == "P" && carConfig?.isPhev != true
        val dcChargeInferred = parkedBev &&
            !isChargingActive &&
            chargingPower <= 0.0 &&
            packPowerKw <= -DC_CHARGE_INFER_MIN_KW

        val chargingActive = isChargingActive ||
            chargingPower > 0.0 ||
            dcChargeInferred

        // Charging-power magnitude. Source priority:
        //   1. chargingPower from a GROSS getter (instrument getChargePower / m33) — decimal-accurate
        //      and matches the BYD app (e.g. the Seal reads 6.14 kW). chargingPowerIsNet is false here,
        //      so it wins outright; never override an accurate decimal with the whole-kW enginePower.
        //   2. enginePower (pack V×I, whole-kW) — used when chargingPower is either absent (the
        //      DC-charge-while-on wedge: listener silent, isChargingActive false, chargingPower 0 —
        //      enginePower is the only surviving signal) OR is the capacity-rate NET value, which
        //      under-reports the charger's gross output badly on some firmware (an Atto 3 reported a
        //      flat ~2 kW for a ~4 kW charge, wall socket confirmed 4.6 kW). enginePower tracks the
        //      real gross rate and even follows aux draw (it dips when the AC compressor runs).
        //   3. chargingPower (net) as a last resort when enginePower shows no charge (e.g. car fully
        //      off → pack getter idle; off-state charging is reconstructed from SoC anyway).
        // kwhAdded still comes from the SoC delta, so energy totals are unaffected regardless — only
        // the live/recorded power curve and the MQTT charging_power sensor.
        val packChargeKw = if (chargingActive && parkedBev && packPowerKw < 0) -packPowerKw.toDouble() else 0.0
        val inferredChargingPower = when {
            chargingPower > 0.0 && !chargingPowerIsNet -> chargingPower  // gross getter — accurate, keep decimals
            packChargeKw > 0.0 -> packChargeKw                           // enginePower gross fallback
            chargingPower > 0.0 -> chargingPower                         // capacity-net, only if enginePower absent
            else -> 0.0
        }

        // Use GPS speed as fallback when SpeedDevice returns 0 — ensures
        // shouldAutoStart and trip recording have a valid speed even if
        // SpeedDevice hasn't initialized yet.
        val effectiveSpeed = when {
            directSpeedKmh > 0.1 -> directSpeedKmh
            locationGpsSpeed != null && locationGpsSpeed > 0.1 -> locationGpsSpeed
            else -> 0.0
        }

        return VehicleTelemetry(
            soc = socEstimate,
            speed = effectiveSpeed,
            gear = gear,
            odometer = odometerValue,
            enginePower = enginePower ?: 0,
            speedGetterWedged = speedGetterWedged,
            totalDischarge = statisticTotalElecConValue ?: 0.0,
            totalElecConPHM = statisticTotalElecConPHMValue,
            battery12vVoltage = battery12vVoltage ?: 0.0,
            batteryPackTemp = batteryPackTemp ?: 0.0,
            // Prefer direct battery/charging values; fall back to aggregate values only if direct data is absent.
            batteryCellTempMax = batteryCellTempMax ?: statisticCellTempMax?.toInt() ?: 0,
            batteryCellVoltageMax = statisticCellVoltageMax ?: batteryCellVoltageMax ?: 0.0,
            batteryCellTempMin = batteryCellTempMin ?: statisticCellTempMin?.toInt() ?: 0,
            batteryCellVoltageMin = statisticCellVoltageMin ?: batteryCellVoltageMin ?: 0.0,
            currentDatetime = currentDatetime,
            soh = inferredSoh ?: 0,
            sohEstimated = inferredSohEstimated,
            locationAltitude = locationAltitude ?: 0.0,
            chargingActive = chargingActive,
            chargingPower = inferredChargingPower,
            engineSpeedFront = engineSpeedFront ?: 0,
            locationLatitude = locationLatitude ?: 0.0,
            locationLongitude = locationLongitude ?: 0.0,
            locationGpsSpeed = locationGpsSpeed,
            locationOrientation = locationOrientation,
            engineSpeedRear = engineSpeedRear ?: 0,
            driveMode = driveMode,
            energyMode = energyMode,
            regenMode = regenMode,
            drivetrainState = drivetrainState,
            wifiSsid = wifiSsid,
            // Derive HV from live cell voltage × cell count if Battery Device is silent
            batteryTotalVoltage = inferredPackVoltage ?: 0,
            batteryTotalCurrent = batteryTotalCurrent,
            electricDrivingRangeKm = statisticElecDrivingRangeValue ?: 0,
            // Prefer the direct power-state source when it exists; keep MCU status as a fallback only.
            carOn = derivedCarOn,
            tyrePressureLF = tyrePressureLFPsi,
            tyrePressureRF = tyrePressureRFPsi,
            tyrePressureLR = tyrePressureLRPsi,
            tyrePressureRR = tyrePressureRRPsi,
            tyreTempLF = tyreTempLF ?: 0,
            tyreTempRF = tyreTempRF ?: 0,
            tyreTempLR = tyreTempLR ?: 0,
            tyreTempRR = tyreTempRR ?: 0,
            socPanel = panelSoc?.toInt() ?: 0,
            carLocked = carLocked ?: 0,
            anyDoorOpened = anyDoorOpened ?: 0,
            fuelPercentage = statisticFuelPercentageValue ?: 0,
            fuelDrivingRangeKm = statisticFuelDrivingRangeValue ?: 0,
            // PHEV fuel/engine data (null when in pure EV mode or not available)
            avgFuelConsumption = statisticAvgFuelConsumption,
            instantFuelConsumption = statisticInstantFuelCon,
            totalFuelConsumption = statisticTotalFuelConValue,
            evMileageKm = statisticEvMileageValue,
            iceMileageKm = statisticHevMileageValue,
            engineCoolantTemp = statisticWaterTemperature,
            batteryRemainPowerEV = powerBatteryRemainPowerEV,
            speedAccelerateDeepness = speedAccelerateDeepness,
            speedBrakeDeepness = speedBrakeDeepness,
            gearboxBrakePedalState = gearboxBrakePedalState,
            averageSpeed = instrumentAverageSpeed,
            currentJourneyDriveMileage = instrumentCurrentJourneyDriveMileage,
            currentJourneyDriveTime = instrumentCurrentJourneyDriveTime,
            turnSignalFlashState = turnSignalFlashState,
            turnSignalLeft = turnSignalLeft,
            turnSignalRight = turnSignalRight,
            instrumentLast50KmPowerConsume = instrumentLast50KmPowerConsume,
            instrumentOutCarTemperature = instrumentOutCarTemperature,
            cabinTemperature = cabinTemperature,
            instrumentMileageUnit = instrumentMileageUnit,
            instrumentSafetyBeltDriverStatus = instrumentSafetyBeltDriverStatus,
            instrumentSafetyBeltPassengerStatus = instrumentSafetyBeltPassengerStatus,
            bodyworkAutoSystemState = bodyworkAutoSystemState,
            bodyworkBatteryCapacity = bodyworkBatteryCapacity,
            bodyworkBatteryPowerHEV = bodyworkBatteryPowerHEV,
            bodyworkBatteryPowerValue = bodyworkBatteryPowerValue,
            bodyworkBatteryVoltageLevel = bodyworkBatteryVoltageLevel,
            bodyworkPowerLevel = bodyworkPowerLevel,
            bodyworkAutoVin = bodyworkAutoVin,
            tboxSerialNumber = tboxSerialNumber,
            powerBatteryRemainPowerEV = powerBatteryRemainPowerEV,
            sensorTemperatureValue = sensorTemperatureValue,
            statisticBatteryCurrent = statisticBatteryCurrent,
            statisticCellVoltageMin = statisticCellVoltageMin,
            statisticCellVoltageMax = statisticCellVoltageMax,
            statisticCellTempMin = statisticCellTempMin,
            statisticCellTempMax = statisticCellTempMax,
            statisticCellTempAvg = statisticCellTempAvg,
            statisticBatterySoh = (statisticBatterySoh ?: fallbackSoh)?.coerceIn(0.0, 100.0),
            statisticSocBms = effectiveBmsSoc ?: statisticSocBms,
            statisticAvailPower = statisticAvailPower,
            probeValues = probeValues,
            acCompressorMode = acCompressorMode,
            acPtcPreheatSignal = acPtcPreheatSignal,
            tecControlTempRaw = tecControlTempRaw,
            hvacEstimatedKw = hvacEstimatedKw,
        )
    }
}

/**
 * Reads vehicle data directly from the car using a VehicleContextWrapper.
 * This is the app's primary live telemetry source.
 */
class BydVehicleDataSource(context: Context) {

    // Wrap the real context so BYD permission checks become no-ops
    private val appContext = context.applicationContext

    // Connected Wi-Fi SSID, read at most once per TTL (the telemetry build is a hot path).
    // Reading the SSID needs ACCESS_FINE_LOCATION (granted) — without it, or with location
    // off, the system returns "<unknown ssid>", which we normalise to blank.
    @Volatile private var cachedWifiSsid: String = ""
    @Volatile private var lastWifiSsidReadMs: Long = 0L

    private fun currentWifiSsid(): String {
        val now = SystemClock.elapsedRealtime()
        if (lastWifiSsidReadMs != 0L && now - lastWifiSsidReadMs < WIFI_SSID_TTL_MS) return cachedWifiSsid
        lastWifiSsidReadMs = now
        cachedWifiSsid = try {
            val wm = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            @Suppress("DEPRECATION")
            val raw = wm?.connectionInfo?.ssid?.trim('"').orEmpty()
            if (raw.isBlank() || raw.equals("<unknown ssid>", ignoreCase = true)) "" else raw
        } catch (e: Exception) {
            ""
        }
        return cachedWifiSsid
    }

    private val runtimeScale01: Double by lazy { RuntimeExtensionBridge.doubleValue("d01", 3.0) }

    // Battery-temp encoding lock. The BYD SDK reports cell temps in either direct °C
    // or 1/d01-scaled °C, and the encoding varies across firmwares (Seal Excellence
    // uses scaled, most others use direct). The decoder normally disambiguates using
    // the statistic cell temp as an anchor — but on app startup, before the statistic
    // device has reported anything, the only fallback is ambient temperature, which is
    // an unreliable tie-breaker when cells are warmer than ambient (driving/charging)
    // or cooler than ambient (active cooling). To avoid overrating/underrating, we
    /**
     * Sanity-check an already-decoded battery temperature in °C. Now that decoding is
     * unambiguous (raw - 40), the ambient deviation guard from the dual-encoding era is
     * gone — it would have incorrectly rejected legitimately hot cells (e.g. during fast
     * charging, when pack temp can be 40°C+ above ambient).
     */
    private fun validatePackTemp(raw: Double?): Double? {
        if (raw == null || !raw.isFinite()) return null
        return raw.takeIf { it in -40.0..80.0 }
    }

    /**
     * Decode a raw BMS battery/cell temperature value into Celsius.
     *
     * BYD encoding (confirmed against `BydDataCollector` and `BydFeatureIds`,
     * which were decompiled from BYD's official SDK): raw values are
     * offset-encoded as `raw - 40 = °C`, with the valid raw range being 0..120
     * (representing −40°C to 80°C). Sentinels (195, -60, BMS_UNAVAILABLE=-10011, the
     * SDK INVALID_VALUE constants) and out-of-range values are rejected.
     *
     * This replaces the earlier "direct °C vs ÷d01" dual-encoding guesser. That logic
     * was wrong: in cases where both interpretations happened to land near a plausible
     * temperature (e.g. raw 61 gives 20.3°C via ÷3 and 21°C via −40), the bug stayed
     * invisible until a side-by-side comparison with another apps
     * showed our values were off by several degrees.
     */
    private fun decodePackTempCelsius(rawValue: Double?): Double? {
        if (rawValue == null || !rawValue.isFinite()) return null
        // Sentinels: 195 = TEMPERATURE_INVALID, -60 = TEMPERATURE_MIN, -10011 = BMS_UNAVAILABLE,
        // -2147482645 / -2147482648 = INVALID_VALUE from get(int[], Class) signature.
        if (rawValue == 195.0 || rawValue == -60.0 || rawValue == -10011.0) return null
        if (rawValue == -2147482645.0 || rawValue == -2147482648.0) return null
        val raw = rawValue
        // Valid raw range: 0..120 (i.e. -40°C to 80°C decoded).
        if (raw < 0.0 || raw > 120.0) return null
        return raw - 40.0
    }

    /**
     * Derive cellTMax from already-stored cellTMin and cellTAvg using the symmetric
     * distribution approximation max = 2·avg − min. BYD doesn't expose a reliable cellTMax
     * feature; this keeps the temperature-range view meaningful instead of showing only min.
     * No-op when min/avg are missing or inconsistent (avg < min).
     */
    private fun recomputeStatisticCellTempMax() {
        val min = _statisticCellTempMin.value
        val avg = _statisticCellTempAvg.value
        if (min != null && avg != null && avg >= min) {
            _statisticCellTempMax.value = (2 * avg - min).coerceIn(min, 80.0)
        }
    }

    private fun decodeStatisticRawMinus40Temp(rawValue: Int?): Double? {
        val raw = rawValue ?: return null
        if (raw == 0 || raw == 195 || raw == 65535 || raw == -60 || raw == -10011 || raw == Int.MIN_VALUE) return null
        if (raw !in 0..120) return null
        val temp = raw - 40.0
        return temp.takeIf { it in -40.0..80.0 }
    }

    /**
     * Decode a raw statistic-device cell-temp value (cellTMin / cellTAvg / cellTMax /
     * cellTAux / cellTCandidate) into Celsius.
     *
     * Encoding: `raw - 40 = °C`, with valid raw range 0..120 (i.e. -40°C to 80°C).
     * Confirmed against `BydDataCollector.collectStatTemp`, which subscribes 
     * to the same statistic feature IDs and applies this exact transform.
     *
     * The `anchorTemp` parameter is kept for source-compatibility with existing call sites
     * but is no longer needed — the encoding is unambiguous so no anchor-based disambiguation
     * is required.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun resolveStatisticTempCelsius(rawValue: Double, anchorTemp: Double? = null): Double? {
        if (!rawValue.isFinite()) return null
        // Sentinels: 195 = TEMPERATURE_INVALID, -60 = TEMPERATURE_MIN, -10011 = BMS_UNAVAILABLE,
        // -2147482645 / -2147482648 = INVALID_VALUE from the get(int[], Class) signature.
        if (rawValue == 195.0 || rawValue == -60.0 || rawValue == -10011.0) return null
        if (rawValue == -2147482645.0 || rawValue == -2147482648.0) return null
        if (rawValue < 0.0 || rawValue > 120.0) return null
        return rawValue - 40.0
    }
    private val chargingEventIds: Map<String, Int> by lazy {
        RuntimeExtensionBridge.labeledIntGroup("l01")
    }
    private val statisticPollFields: Map<String, Int> by lazy {
        RuntimeExtensionBridge.labeledIntGroup("l02")
    }
    private val statisticDispatchFields: Map<String, Int> by lazy {
        RuntimeExtensionBridge.labeledIntGroup("l03")
    }
    private val climateProbeFields: Map<String, Int> by lazy {
        RuntimeExtensionBridge.labeledIntGroup("l05")
    }
    private val energyProbeFields: Map<String, Int> by lazy {
        RuntimeExtensionBridge.labeledIntGroup("l06")
    }
    private val engineRpmFeatureIds: IntArray by lazy {
        RuntimeExtensionBridge.intGroup("i01")
    }
    private val powerStateFeatureIds: IntArray by lazy {
        RuntimeExtensionBridge.intGroup("i02")
    }
    private val speedAuxFeatureIds: IntArray by lazy {
        RuntimeExtensionBridge.intGroup("i03")
    }

    private fun runtimeMethodNames(key: String): Array<String> {
        return RuntimeExtensionBridge.methodGroups(key)
            .flatMap { it.second }
            .toTypedArray()
    }

    private fun runtimeGroupMap(key: String): Map<String, List<String>> =
        RuntimeExtensionBridge.methodGroups(key).toMap()

    private val m26: Map<String, String> by lazy {
        RuntimeExtensionBridge.methodGroups("m26").associate { (k, v) -> k to (v.firstOrNull().orEmpty()) }
    }
    private val m27: Map<String, String> by lazy {
        RuntimeExtensionBridge.methodGroups("m27").associate { (k, v) -> k to (v.firstOrNull().orEmpty()) }
    }
    private val m28: Map<String, String> by lazy {
        RuntimeExtensionBridge.methodGroups("m28").associate { (k, v) -> k to (v.firstOrNull().orEmpty()) }
    }
    private val m29: Map<String, String> by lazy {
        RuntimeExtensionBridge.methodGroups("m29").associate { (k, v) -> k to (v.firstOrNull().orEmpty()) }
    }
    private val m34: Map<String, String> by lazy {
        RuntimeExtensionBridge.methodGroups("m34").associate { (k, v) -> k to (v.firstOrNull().orEmpty()) }
    }
    private val m45: Map<String, String> by lazy {
        RuntimeExtensionBridge.methodGroups("m45").associate { (k, v) -> k to (v.firstOrNull().orEmpty()) }
    }
    private val m46: Map<String, String> by lazy {
        RuntimeExtensionBridge.methodGroups("m46").associate { (k, v) -> k to (v.firstOrNull().orEmpty()) }
    }

    /**
     * BYD statistic percentage fields are not fully consistent across firmware.
     * Some units emit whole percentages (94), others tenths (940 -> 94.0).
     * When we have a trustworthy comparison source such as the panel SoC,
     * choose the decoding closest to that value; otherwise prefer the raw
     * whole-percent interpretation when both are plausible.
     */
    private fun decodeStatisticPercentRaw(raw: Int, comparisonPercent: Double? = null): Double? {
        val d = raw.toDouble()
        val whole = d.takeIf { it in 0.0..100.0 }
        val tenths = (d / 10.0).takeIf { it in 0.0..100.0 }

        return when {
            whole == null && tenths == null -> null
            whole != null && tenths == null -> whole
            whole == null && tenths != null -> tenths
            comparisonPercent != null && comparisonPercent in 0.0..100.0 -> {
                val wholeDiff = abs(whole!! - comparisonPercent)
                val tenthsDiff = abs(tenths!! - comparisonPercent)
                if (wholeDiff <= tenthsDiff) whole else tenths
            }
            raw > 100 -> tenths
            else -> whole
        }
    }

    /**
     * Some meter-style percentages are emitted as raw doubles and may use whole
     * percent, tenths, or hundredths. Prefer the first plausible candidate,
     * or the closest candidate to a comparison source when available.
     */
    private fun decodeMeterPercentRaw(raw: Double?, comparisonPercent: Double? = null): Double? {
        val value = raw?.takeIf { it.isFinite() } ?: return null
        val whole = value.takeIf { it in 0.0..100.0 }
        val tenths = (value / 10.0).takeIf { it in 0.0..100.0 }
        val hundredths = (value / 100.0).takeIf { it in 0.0..100.0 }

        return when {
            whole == null && tenths == null && hundredths == null -> null
            comparisonPercent != null && comparisonPercent in 0.0..100.0 -> {
                listOfNotNull(whole, tenths, hundredths).minByOrNull { abs(it - comparisonPercent) }
            }
            whole != null -> whole
            tenths != null -> tenths
            else -> hundredths
        }
    }

    private fun readSohDeltaValue(device: Any): Double? {
        return invokeNumericDoubleGetter(device, *runtimeMethodNames("m43"))
            ?.takeIf { it.isFinite() && it in -20.0..20.0 }
    }

    private fun describeSohDeltaCandidate(delta: Double?): String {
        if (delta == null) return "n/a"
        val plus = (100.0 + delta).takeIf { it in 50.0..110.0 }
        val minusAbs = (100.0 - abs(delta)).takeIf { it in 50.0..110.0 }
        fun fmt(v: Double?) = v?.let { String.format("%.1f", it) } ?: "n/a"
        return "${String.format("%.1f", delta)} -> plus=${fmt(plus)} minusAbs=${fmt(minusAbs)}"
    }

    private fun describeExtraStatisticValue(rawNumber: Number?): String? {
        val raw = rawNumber?.toDouble() ?: return null
        if (!raw.isFinite()) return null

        val hints = buildList {
            decodeStatisticPercentRaw(raw.toInt())?.takeIf { it in 50.0..110.0 }?.let {
                add("percent≈${String.format("%.1f", it)}%")
            }
            if (raw in -20.0..20.0) {
                add("sohDeltaRaw≈${String.format("%.1f", raw)}")
                val minusAbs = 100.0 - abs(raw)
                if (minusAbs in 50.0..110.0) add("100-abs≈${String.format("%.1f", minusAbs)}%")
            }
            if (raw in -1000.0..1000.0) {
                val div3 = raw / runtimeScale01
                if (div3 in -30.0..80.0) add("tempDiv3≈${String.format("%.1f", div3)}°C")
                val div10 = raw / 10.0
                if (div10 in -30.0..80.0) add("tempDiv10≈${String.format("%.1f", div10)}°C")
            }
        }.distinct()

        return if (hints.isEmpty()) null else "raw=$raw ${hints.joinToString(" ")}"
    }

    private fun normalizeRemainingBatteryPowerKwh(raw: Double?): Double? {
        val value = raw?.takeIf { it.isFinite() } ?: return null
        return when {
            value in 0.0..200.0 -> value
            value in 10.0..200000.0 -> value / 10.0
            else -> null
        }
    }

    private val ctx = VehicleContextWrapper(appContext)
    private val preferencesManager by lazy { PreferencesManager(appContext) }
    private val statCache: SharedPreferences by lazy {
        appContext.getSharedPreferences(VEHICLE_STATE_CACHE, Context.MODE_PRIVATE)
    }
    private val snapshotPublishLock = Any()
    private var snapshotBatchDepth = 0
    private var snapshotPublishPending = false

    private val _isAvailable = MutableStateFlow(false)
    val isAvailable: StateFlow<Boolean> = _isAvailable.asStateFlow()

    // Expose individual fields as flows so callers can merge them into telemetry/history models
    private val _chargingPowerKw   = MutableStateFlow(0.0)
    private val _chargingPowerRaw  = MutableStateFlow(0.0)
    private val _chargingPowerCandidateKw = MutableStateFlow(0.0)
    // Charging device getChargingPower() (m33) — instantaneous pack power (V×I, up to 500 kW), the
    // only source that sees DC (the instrument getter is clamped 0.1..50, onChargingPowerChanged is
    // inert). Kept separate from _chargingPowerRaw so the instrument path's null-clears can't wipe a
    // live DC reading.
    private val _packChargingPowerKw = MutableStateFlow(0.0)
    private val _chargingCapacity  = MutableStateFlow(0.0)
    private val _chargingCapState  = MutableStateFlow(0)
    private val _chargingCapValue  = MutableStateFlow(0)
    private val _chargingEventPowerCandidateRaw = MutableStateFlow(0.0)
    private val _chargingEventCapacityRaw = MutableStateFlow(0.0)
    private val _chargingEventUnknownInt27Raw = MutableStateFlow(0)
    private val _chargingEventUnknownCounterRaw = MutableStateFlow(0)
    private val _chargeState       = MutableStateFlow(0)
    private val _remainMinutes     = MutableStateFlow(0)
    private val _remainHours       = MutableStateFlow(0)
    private val _chargerState      = MutableStateFlow(0)
    private val _chargerWorkState  = MutableStateFlow(0)
    private val _chargingType      = MutableStateFlow(0)
    private val _chargingGunState  = MutableStateFlow(0)
    private val _chargingMode      = MutableStateFlow(0)
    private val _gearboxState      = MutableStateFlow(0)
    private val _currentGearRaw    = MutableStateFlow<Int?>(null)
    private val _gear              = MutableStateFlow("P")
    private val _tecControlTempRaw  = MutableStateFlow<Int?>(null)
    private val _tyrePressureLF    = MutableStateFlow(0.0)
    private val _tyrePressureRF    = MutableStateFlow(0.0)
    private val _tyrePressureLR    = MutableStateFlow(0.0)
    private val _tyrePressureRR    = MutableStateFlow(0.0)
    private val _tyrePressureLFBar = MutableStateFlow(0.0)
    private val _tyrePressureRFBar = MutableStateFlow(0.0)
    private val _tyrePressureLRBar = MutableStateFlow(0.0)
    private val _tyrePressureRRBar = MutableStateFlow(0.0)
    private val _tyrePressureLFState = MutableStateFlow<Int?>(null)
    private val _tyrePressureRFState = MutableStateFlow<Int?>(null)
    private val _tyrePressureLRState = MutableStateFlow<Int?>(null)
    private val _tyrePressureRRState = MutableStateFlow<Int?>(null)
    // Tyre temperatures in °C — populated via listener callbacks or polled getters.
    // The device may surface them through different callbacks depending on firmware.
    private val _tyreTempLF = MutableStateFlow<Int?>(null)
    private val _tyreTempRF = MutableStateFlow<Int?>(null)
    private val _tyreTempLR = MutableStateFlow<Int?>(null)
    private val _tyreTempRR = MutableStateFlow<Int?>(null)
    private val _vehicleSnapshot = MutableStateFlow(VehicleTelemetrySnapshot())
    // PM2.5 debounce: only publish to snapshot when the same value is seen
    // on two consecutive polls. This suppresses single-cycle oscillation
    // (e.g. sensor alternating 18/22 every second) while still reflecting
    // genuine sustained changes within one extra poll cycle (~3 s).
    private var pendingPm25In:  Int? = null
    private var pendingPm25Out: Int? = null
    // elapsedRealtime of the last poll where the speed getter committed a positive
    // speed. Used to bridge brief getter glitches: a 0 from the getter is only
    // committed once independent motion signals (power / GPS) also agree the car is
    // stopped, or after a short grace window with no such signal.
    private var lastPositiveSpeedElapsedMs = 0L
    // Last time the POLLED getter (getCurrentSpeed) returned a fresh, plausible
    // non-zero reading. Distinct from lastPositiveSpeedElapsedMs (which the event
    // stream also bumps). The event stream is only allowed to drive speed once the
    // getter goes stale by this clock, so a healthy getter is always authoritative.
    @Volatile private var lastGetterPositiveElapsedMs = 0L
    // Throttle for persisting the speed-stall diagnostic to DiagLog.
    private var lastSpeedStallDiagMs = 0L
    // Live speed from the Speed device's EVENT stream. The polled getCurrentSpeed getter
    // can freeze at a stale 0 (until an engine cycle), but these events keep flowing — so
    // once we learn the raw→km/h scale (from the getter while it still works) we drive
    // speed from events instead. See the "Speed" branch of dispatchDynamicRawFeatureEvent.
    @Volatile private var lastSpeedEventRaw: Double? = null
    @Volatile private var lastSpeedEventElapsedMs: Long = 0L
    @Volatile private var speedEventScale: Double? = null
    // Last time the Speed device's TYPED speed push (onCurrentSpeedChanged) delivered a
    // value. This push survives getCurrentSpeed() wedging at 0 after a mid-session process
    // restart, so when it is fresh it is authoritative over both the poll and the GPS bridge.
    @Volatile private var lastSpeedPushElapsedMs: Long = 0L
    // Last time the daemon pushed an engine-power value — makes the 1 s poll yield to it.
    @Volatile private var lastPowerPushElapsedMs: Long = 0L
    // Last time the daemon pushed motor RPM — makes the 1 s getter poll yield to it (realtime RPM).
    @Volatile private var lastRpmPushElapsedMs: Long = 0L
    private var lastSpeedEventDiagMs = 0L
    // Throttle for the typed-speed-push (onSpeedChanged) confirmation diagnostic.
    private var lastSpeedPushDiagMs = 0L
    // Throttle for the gear-source diagnostic (what the gearbox getter returns vs committed gear).
    private var lastGearDiagMs = 0L
    // When the getCurrentSpeed-stuck-while-GPS-moving condition first started (wedge timer).
    @Volatile private var speedWedgeSinceMs = 0L
    // Name of the device-refresh stage currently running. If a getter hangs, this is
    // left pointing at it so the wedge guard can name the culprit. Read from the service.
    @Volatile private var currentRefreshStage: String = "idle"
    val refreshStage: String get() = currentRefreshStage

    private var lastChargingCapacityRaw: Double? = null
    private val chargingCapacityHistory = ArrayDeque<Pair<Long, Double>>()
    private var lastChargingActivityElapsedMs: Long = 0L
    // Tracks when chargerWorkState first went non-zero in this session. Used as the stale
    // baseline when no capacity activity has been seen yet (e.g. fresh install connecting
    // to a PHEV whose BMS never cleared chargerWorkState after the last charge).
    private var chargerWorkStateSetElapsedMs: Long = 0L
    // Latched once this car's BMS is seen reporting charger gun/work non-zero while driving — proof
    // those flags are spurious here, so charging must thereafter be backed by power/capacity, not a
    // bare work=1. See computeChargingActive.
    @Volatile private var chargerSignalsUnreliable =
        runCatching { statCache.getBoolean(KEY_CHARGER_SIGNALS_UNRELIABLE, false) }.getOrDefault(false)
    private var lastChargingPowerCandidateElapsedMs: Long = 0L
    private var lastPackChargingPowerElapsedMs: Long = 0L
    private var lastChargingDiagMs: Long = 0L
    private val _speedAccelerateDeepness = MutableStateFlow<Int?>(null)
    private val _speedBrakeDeepness = MutableStateFlow<Int?>(null)
    private val _gearboxBrakePedalState = MutableStateFlow<Int?>(null)
    private val _instrumentAverageSpeed = MutableStateFlow<Double?>(null)
    private val _instrumentCurrentJourneyDriveMileage = MutableStateFlow<Double?>(null)
    private val _instrumentCurrentJourneyDriveTime = MutableStateFlow<Double?>(null)
    private val _driveMode         = MutableStateFlow(DRIVE_MODE_UNKNOWN)
    private val _energyMode        = MutableStateFlow(0)
    private val _regenMode         = MutableStateFlow(0)
    private val _instrumentBatteryPercent = MutableStateFlow<Double?>(null)
    private val _instrumentChargePercent = MutableStateFlow<Double?>(null)
    private val _instrumentOdometerDisplay = MutableStateFlow<Double?>(null)
    private val _instrumentPowerUnit = MutableStateFlow<Int?>(null)
    private val _turnSignalFlashState = MutableStateFlow<Int?>(null)
    private val _turnSignalLeft = MutableStateFlow<Boolean?>(null)
    private val _turnSignalRight = MutableStateFlow<Boolean?>(null)
    // Battery device state
    private val _batterySoh = MutableStateFlow<Int?>(null)
    private val _batteryTotalVoltage = MutableStateFlow<Int?>(null)
    private val _battery12vVoltage = MutableStateFlow<Double?>(null)
    private val _batteryCellTempMax = MutableStateFlow<Int?>(null)
    private val _batteryCellTempMin = MutableStateFlow<Int?>(null)
    private val _batteryCellVoltageMax = MutableStateFlow<Double?>(null)
    private val _batteryCellVoltageMin = MutableStateFlow<Double?>(null)
    // Body lock / door state
    private val _carLocked = MutableStateFlow<Int?>(null)
    private val _anyDoorOpened = MutableStateFlow<Int?>(null)
    // Statistic listener values (written by dispatchStatisticFeatureEvent)
    private val _statisticCellVoltageMin  = MutableStateFlow<Double?>(null)
    private val _statisticCellVoltageMax  = MutableStateFlow<Double?>(null)
    private val _statisticCellTempMin     = MutableStateFlow<Double?>(null)
    private val _statisticCellTempAvg     = MutableStateFlow<Double?>(null)
    private val _statisticCellTempMax     = MutableStateFlow<Double?>(null)
    private val _statisticBatteryCurrent  = MutableStateFlow<Double?>(null)
    private val _statisticBatterySoh      = MutableStateFlow<Double?>(null)
    private val _statisticSocBms          = MutableStateFlow<Double?>(null)
    private val _statisticAvailPower      = MutableStateFlow<Double?>(null)

    // ── BMS SoC glitch suppression ────────────────────────────────────────────
    // The BMS occasionally reports a single isolated SoC sample that immediately reverts
    // (observed: 36.5 → 35.0 → 36.5 and 35.6 → 34.1 → 35.6 while driving). Real SoC moves far
    // slower than this between polls (well under 0.1 %/s even under hard load or fast charge),
    // so a jump larger than SOC_BMS_GLITCH_STEP from the last shown value is treated as an
    // outlier and the previous value is held — but only ONCE: the very next reading is always
    // accepted, so a genuine large change (gap after sleep, post-charge re-baseline) is delayed
    // by at most one sample and the filter can never get stuck on a stale value.
    @Volatile private var lastSocBmsFiltered: Double? = null
    @Volatile private var socBmsHeldOnce = false
    private var lastSocBmsGlitchDiagMs = 0L

    private fun filterBmsSocGlitch(candidate: Double): Double {
        val last = lastSocBmsFiltered
        if (last == null || socBmsHeldOnce || kotlin.math.abs(candidate - last) <= SOC_BMS_GLITCH_STEP) {
            lastSocBmsFiltered = candidate
            socBmsHeldOnce = false
            return candidate
        }
        // Isolated large jump and we didn't just hold — suppress this one sample.
        socBmsHeldOnce = true
        val now = SystemClock.elapsedRealtime()
        if (now - lastSocBmsGlitchDiagMs > 10_000L) {
            lastSocBmsGlitchDiagMs = now
            runCatching { DiagLog.event(appContext, TAG, "🔋 BMS SoC glitch suppressed: held ${"%.1f".format(last)}, ignored ${"%.1f".format(candidate)}") }
        }
        return last
    }

    private val lastDiagnosticLogs = mutableMapOf<String, String>()
    private val lastRegenDiagMessages = mutableMapOf<String, String>()
    private val listenerReferences = mutableListOf<Any>()
    // Tracks the generic mirror listeners (device + proxy + interface) so they can be unregistered
    // and re-registered when the SDK's event-callback channel wedges (see recoverEventDelivery).
    private class MirrorReg(val device: Any, val proxy: Any, val label: String, val iface: Class<*>)
    private val mirrorRegs = java.util.concurrent.CopyOnWriteArrayList<MirrorReg>()
    @Volatile private var lastWedgeRecoveryMs = 0L
    // Method cache: eliminates repeated Class.getMethod() lookups (~12,000/min without this).
    private val methodCache = java.util.concurrent.ConcurrentHashMap<Pair<Class<*>, String>, java.lang.reflect.Method?>()
    // Tick counter for tiered polling
    // Updated on every SDK feature event (dispatchDynamicRawFeatureEvent).
    // Used by the service to widen the poll interval when the car stops talking.
    @Volatile var lastFeatureEventElapsedMs: Long = 0L
        private set

    private var snapshotTick = 0
    private var mqttSnapshotLogged = false
    private var hasConfirmedDriveMode = false
    private var hasConfirmedRegenMode = false
    private var locationManager: LocationManager? = null
    private var hasDirectLocationSignal = false
    private var systemLocationUpdatesRegistered = false
    private var latestSystemLocation: Location? = null
    // All four LocationListener methods must be overridden on Android 10 (API 29).
    // Omitting onProviderDisabled/onProviderEnabled causes AbstractMethodError
    // when the GPS provider is disabled (e.g. car shutdown) — fatal crash.
    private val systemLocationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            latestSystemLocation = location
            applySystemLocationFallback(location, "listener")
        }
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Deprecated in Android LocationListener")
        @Suppress("DEPRECATION")
        override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
    }


    val chargingPowerKw: StateFlow<Double> = _chargingPowerKw.asStateFlow()
    val chargingPowerRaw: StateFlow<Double> = _chargingPowerRaw.asStateFlow()
    val chargingCapacity: StateFlow<Double> = _chargingCapacity.asStateFlow()
    val chargingCapState: StateFlow<Int> = _chargingCapState.asStateFlow()
    val chargingCapValue: StateFlow<Int> = _chargingCapValue.asStateFlow()
    val chargingEventPowerCandidateRaw: StateFlow<Double> = _chargingEventPowerCandidateRaw.asStateFlow()
    val chargingEventCapacityRaw: StateFlow<Double> = _chargingEventCapacityRaw.asStateFlow()
    val chargingEventUnknownInt27Raw: StateFlow<Int> = _chargingEventUnknownInt27Raw.asStateFlow()
    val chargingEventUnknownCounterRaw: StateFlow<Int> = _chargingEventUnknownCounterRaw.asStateFlow()
    val chargeState:     StateFlow<Int>    = _chargeState.asStateFlow()
    val remainMinutes:   StateFlow<Int>    = _remainMinutes.asStateFlow()
    val remainHours:     StateFlow<Int>    = _remainHours.asStateFlow()
    val chargerState:    StateFlow<Int>    = _chargerState.asStateFlow()
    val chargerWorkState: StateFlow<Int>   = _chargerWorkState.asStateFlow()
    val chargingType:    StateFlow<Int>    = _chargingType.asStateFlow()
    val chargingGunState: StateFlow<Int>   = _chargingGunState.asStateFlow()
    val chargingMode:    StateFlow<Int>    = _chargingMode.asStateFlow()
    val gearboxState:    StateFlow<Int>    = _gearboxState.asStateFlow()
    val currentGearRaw:  StateFlow<Int?>   = _currentGearRaw.asStateFlow()
    val gear:            StateFlow<String> = _gear.asStateFlow()
    val tyrePressureLF:  StateFlow<Double> = _tyrePressureLF.asStateFlow()
    val tyrePressureRF:  StateFlow<Double> = _tyrePressureRF.asStateFlow()
    val tyrePressureLR:  StateFlow<Double> = _tyrePressureLR.asStateFlow()
    val tyrePressureRR:  StateFlow<Double> = _tyrePressureRR.asStateFlow()
    val tyrePressureLFBar: StateFlow<Double> = _tyrePressureLFBar.asStateFlow()
    val tyrePressureRFBar: StateFlow<Double> = _tyrePressureRFBar.asStateFlow()
    val tyrePressureLRBar: StateFlow<Double> = _tyrePressureLRBar.asStateFlow()
    val tyrePressureRRBar: StateFlow<Double> = _tyrePressureRRBar.asStateFlow()
    val tyrePressureLFState: StateFlow<Int?> = _tyrePressureLFState.asStateFlow()
    val tyrePressureRFState: StateFlow<Int?> = _tyrePressureRFState.asStateFlow()
    val tyrePressureLRState: StateFlow<Int?> = _tyrePressureLRState.asStateFlow()
    val tyrePressureRRState: StateFlow<Int?> = _tyrePressureRRState.asStateFlow()
    val driveMode:       StateFlow<Int>    = _driveMode.asStateFlow()
    val energyMode:      StateFlow<Int>    = _energyMode.asStateFlow()
    val regenMode:       StateFlow<Int>    = _regenMode.asStateFlow()
    val vehicleSnapshot: StateFlow<VehicleTelemetrySnapshot> = _vehicleSnapshot.asStateFlow()

    private var chargingDevice: BYDAutoChargingDevice? = null
    private var gearboxDevice:  BYDAutoGearboxDevice?  = null
    // True once InstrumentDevice has successfully returned a gear value while gearboxDevice
    // is absent. Used to gate the speed-based D/P fallback so we don't override a real R.
    @Volatile private var instrumentGearKnown: Boolean = false
    // True once the privileged telemetry daemon has supplied a real gear — makes the daemon gear
    // authoritative over the in-process speed-inference fallback (see toTelemetry gear resolution).
    @Volatile private var daemonGearKnown: Boolean = false
    // Client for the privileged telemetry daemon (instant speed/gear the app process can't get itself).
    private val daemonClient = com.byd.tripstats.util.TelemetryDaemonClient { speedKmh, gear, powerKw, frontRpm, rearRpm ->
        applyDaemonTelemetry(speedKmh, gear, powerKw, frontRpm, rearRpm)
    }
    private var tyreDevice:     BYDAutoTyreDevice?     = null
    private var bodyworkDevice: Any? = null
    private var batteryDevice: Any? = null
    private var bmsDevice: Any? = null
    private var engineDevice: Any? = null
    private var statisticDevice: Any? = null
    private var speedDevice: Any? = null
    private var lightDevice: Any? = null
    private var powerDevice: Any? = null
    private var sensorDevice: Any? = null
    private var instrumentDevice: Any? = null
    private var climateDevice: Any? = null
    private var mqttDevice: Any? = null
    private var vemManager: Any? = null
    private var locationDevice: Any? = null
    private var energyDevice: Any? = null
    private var otaDevice: Any? = null
    private var signalDevice: Any? = null
    private var motorDevice: Any? = null
    private var dtcDevice: Any? = null
    private var pollingScope: CoroutineScope? = null
    private var pollingJob: Job? = null
    private var readLogsMonitorJob: Job? = null
    private var lastChargingPowerRawElapsedMs = 0L
    // lazy: on DiLink-5 without a bundled jar, constructing this touches OEM types that may not be
    // resolvable yet (before consent+injection). Deferring to first access means any failure happens
    // inside the tryDevice{} block in start() (which already catches Throwable/LinkageError), not
    // during this class's own construction (which would crash the service — see Dilink5SdkInjector).
    private val chargingListener by lazy { object : AbsBYDAutoChargingListener() {
        override fun onChargingPowerChanged(power: Double) {
            if (ENABLE_VERBOSE_RAW_EVENT_LOGS) {
                Log.d(TAG, "🔋 chargingPower callback(raw)=$power kW")
            }
        }
        override fun onChargingCapacityChanged(capacity: Double) {
            noteChargingCapacityActivity(capacity)
            _chargingCapacity.value = capacity
            publishSnapshot()
            if (ENABLE_VERBOSE_RAW_EVENT_LOGS) {
                Log.d(TAG, "🔋 chargingCapacity=$capacity")
            }
        }
        override fun onChargingCapStateChanged(state: Int, value: Int) {
            _chargingCapState.value = state
            _chargingCapValue.value = value
            publishSnapshot()
            if (ENABLE_VERBOSE_RAW_EVENT_LOGS) {
                Log.d(TAG, "🔋 chargingCapState state=$state value=$value")
            }
        }
        override fun onChargingStateChanged(state: Int) {
            _chargeState.value = state
            publishSnapshot()
            if (ENABLE_VERBOSE_RAW_EVENT_LOGS) {
                Log.d(TAG, "🔋 chargeState=$state")
            }
        }
        override fun onChargerStateChanged(state: Int) {
            _chargerState.value = state
            publishSnapshot()
            if (ENABLE_VERBOSE_RAW_EVENT_LOGS) {
                Log.d(TAG, "🔋 chargerState=$state")
            }
        }
        override fun onChargerWorkStateChanged(state: Int) {
            if (state > 0 && _chargerWorkState.value == 0) chargerWorkStateSetElapsedMs = SystemClock.elapsedRealtime()
            else if (state == 0) chargerWorkStateSetElapsedMs = 0L
            _chargerWorkState.value = state
            publishSnapshot()
            if (ENABLE_VERBOSE_RAW_EVENT_LOGS) {
                Log.d(TAG, "🔋 chargerWorkState=$state")
            }
        }
        override fun onChargingTypeChanged(type: Int) {
            _chargingType.value = type
            publishSnapshot()
            if (ENABLE_VERBOSE_RAW_EVENT_LOGS) {
                Log.d(TAG, "🔋 chargingType=$type")
            }
        }
        override fun onChargingGunStateChanged(state: Int) {
            _chargingGunState.value = state
            publishSnapshot()
            if (ENABLE_VERBOSE_RAW_EVENT_LOGS) {
                Log.d(TAG, "🔋 chargingGunState=$state")
            }
        }
        override fun onChargingModeChanged(mode: Int) {
            _chargingMode.value = mode
            publishSnapshot()
            if (ENABLE_VERBOSE_RAW_EVENT_LOGS) {
                Log.d(TAG, "🔋 chargingMode=$mode")
            }
        }
        override fun onDataEventChanged(eventId: Int, value: BYDAutoEventValue) {
            handleChargingEvent(eventId, value)
            if (ENABLE_VERBOSE_RAW_EVENT_LOGS) {
                val rawNumber = extractEventDouble(value) ?: extractEventInt(value)?.toDouble()
                val rawHint = describeGeneralEventValue(rawNumber)
                logInfoIfChanged(
                    "chargingDataEvent-$eventId",
                    "🔬 Charging event raw=${rawNumber ?: "n/a"}" +
                        (rawHint?.let { " $it" } ?: "") +
                        " detail=${compactDiagnosticDetail(describeEventValue(value))}"
                )
            }
        }
        override fun onChargingRestTimeChanged(minutes: Int, seconds: Int) {
            _remainMinutes.value = minutes
            publishSnapshot()
            if (ENABLE_VERBOSE_RAW_EVENT_LOGS) {
                Log.d(TAG, "🔋 remainTime=${minutes}m ${seconds}s")
            }
        }
        override fun onError(code: Int, msg: String) {
            Log.w(TAG, "ChargingListener error $code: $msg")
        }
    } }

    // lazy: see chargingListener comment above (already dormant on D5 firmware even with the bundled
    // jar — GearboxDevice never registers — so this only matters for D3).
    private val gearboxListener by lazy { object : AbsBYDAutoGearboxListener() {
        override fun onCurrentGearChanged(gear: Int) {
            val label = mapGearValue(gear)
            _currentGearRaw.value = gear
            _gear.value = label
            publishSnapshot()
            // Release-safe: confirms the gear EVENT fires on a physical shift (independent of
            // speed). If this never appears, gear is poll-only on this firmware.
            DiagLog.event(appContext, TAG, "⚙️ gear EVENT: $label (raw=$gear)")
        }
        override fun onGearboxAutoModeTypeChanged(type: Int) {
            // DORMANT (see CLAUDE.md): the GearboxDevice does NOT register on this firmware
            // (gearboxDeviceRegistered=false), so this never fires here and gear stays
            // speed-inference only. Kinex reads gear via com.ts.lib.caradapter, not bydauto.
            // Correct/harmless; would work on firmwares where the gearbox device registers — keep it.
            //
            // The gear SELECTOR position is reported here as the "auto mode type"
            // (1=P 2=R 3=N 4=D 5=M 6=S). On firmwares where onCurrentGearChanged
            // never fires and the gear getters return NULL, this is the only event
            // that reflects a physical P→D shift — and it fires instantly, even while
            // stationary. Drive gear from it so the dashboard updates the moment you shift.
            mapGearboxAutoModeToGear(type)?.let { label ->
                if (_gear.value != label) {
                    _gear.value = label
                    publishSnapshot()
                    DiagLog.event(appContext, TAG, "⚙️ gear EVENT (autoModeType): $label (type=$type)")
                }
            }
            if (!ENABLE_MODE_STATE_LOGS) return
            logInfoIfChanged(
                "modeState-gearboxAutoModeType",
                "🔎 DriveModeState event gearboxAutoModeType=$type current=${_driveMode.value} confirmed=$hasConfirmedDriveMode"
            )
        }
        override fun onGearboxManualModeLevelChanged(level: Int) {
            if (!ENABLE_MODE_STATE_LOGS) return
            logInfoIfChanged(
                "modeState-gearboxManualModeLevel",
                "🔎 DriveModeState event gearboxManualModeLevel=$level current=${_driveMode.value} confirmed=$hasConfirmedDriveMode"
            )
        }
        override fun onDataEventChanged(eventId: Int, value: BYDAutoEventValue) {
            if (ENABLE_MODE_STATE_LOGS) {
                val rawNumber = extractEventDouble(value) ?: extractEventInt(value)?.toDouble()
                logInfoIfChanged(
                    "modeState-gearboxEvent-$eventId",
                    "🔎 DriveModeState event gearbox value=${rawNumber ?: "n/a"} detail=${compactDiagnosticDetail(describeEventValue(value))}"
                )
            }
        }
        override fun onError(code: Int, msg: String) {
            Log.w(TAG, "GearboxListener error $code: $msg")
        }
    } }

    // lazy: see chargingListener comment above.
    private val tyreListener by lazy { object : AbsBYDAutoTyreListener() {
        /**
         * BYD delivers 5 wheel slots (0-4).
         * Slot 0 is a generic/dummy entry that always returns 0.0.
         * Real wheels start at index 1:
         *   wheel=1 → Left Front (LF)
         *   wheel=2 → Right Front (RF)
         *   wheel=3 → Left Rear (LR)   (formerly mapped as RR — was wrong)
         *   wheel=4 → Right Rear (RR)
         */
        override fun onTyrePressureValueChanged(wheel: Int, value: Int) {
            // Log ALL slots raw so we can verify the mapping
            Log.d(TAG, "🛞 tyrePressureRaw wheel=$wheel raw=$value")
            if (wheel == 0) return  // slot 0 is dummy — ignore

            val mappedWheel = wheel - 1  // shift: 1→0(LF), 2→1(RF), 3→2(LR), 4→3(RR)
            val currentState = when (mappedWheel) {
                0 -> _tyrePressureLFState.value
                1 -> _tyrePressureRFState.value
                2 -> _tyrePressureLRState.value
                3 -> _tyrePressureRRState.value
                else -> null
            }
            val bar = normalizeTyrePressureBar(value.toDouble(), currentState)
            val normalizedPsi = barToPsi(bar)
            when (mappedWheel) {
                0 -> { _tyrePressureLFBar.value = bar; _tyrePressureLF.value = normalizedPsi }
                1 -> { _tyrePressureRFBar.value = bar; _tyrePressureRF.value = normalizedPsi }
                2 -> { _tyrePressureLRBar.value = bar; _tyrePressureLR.value = normalizedPsi }
                3 -> { _tyrePressureRRBar.value = bar; _tyrePressureRR.value = normalizedPsi }
            }
            publishSnapshot()
            Log.d(
                TAG,
                "🛞 tyrePressure ${wheelName(mappedWheel)} (car wheel=$wheel) raw=$value " +
                    "state=$currentState bar=$bar psi=$normalizedPsi | all: " +
                    "lf=${_tyrePressureLF.value}(s=${_tyrePressureLFState.value}) " +
                    "rf=${_tyrePressureRF.value}(s=${_tyrePressureRFState.value}) " +
                    "lr=${_tyrePressureLR.value}(s=${_tyrePressureLRState.value}) " +
                    "rr=${_tyrePressureRR.value}(s=${_tyrePressureRRState.value})"
            )
        }
        /**
         * DiLink-5: reliable, all-4-wheel pressure source (unlike onTyrePressureValueChanged above,
         * whose wheel attribution is shared with temp/airLeak/signal/battery via a single rotating
         * getTyreArea() pointer and, on the dev car, never fired for LF/RR at all). Confirmed on-car:
         * fires immediately on a dash unit change too. Raw value is unit-ambiguous — decoded via
         * decodeDilink5PressureByTypeBar() using the live state from applyDilink5PressureUnit(); a
         * no-op (and harmless) on D3, where that unit is never set. Same wheel numbering as above
         * (LF=1/RF=2/LR=3/RR=4).
         */
        override fun onTyrePressureValueByTypeChanged(wheel: Int, value: Float) {
            val bar = decodeDilink5PressureByTypeBar(value) ?: return
            val psi = barToPsi(bar)
            when (wheel) {
                1 -> { _tyrePressureLFBar.value = bar; _tyrePressureLF.value = psi }
                2 -> { _tyrePressureRFBar.value = bar; _tyrePressureRF.value = psi }
                3 -> { _tyrePressureLRBar.value = bar; _tyrePressureLR.value = psi }
                4 -> { _tyrePressureRRBar.value = bar; _tyrePressureRR.value = psi }
                else -> return
            }
            publishSnapshot()
            Log.d(TAG, "🛞 tyrePressureByType wheel=$wheel raw=$value unit=$dilink5PressureUnit bar=$bar psi=$psi")
        }
        override fun onTyrePressureStateChanged(wheel: Int, state: Int) {
            Log.d(TAG, "🛞 tyrePressureStateRaw wheel=$wheel state=$state")
            if (wheel == 0) return  // slot 0 is dummy
            val mappedWheel = wheel - 1
            when (mappedWheel) {
                0 -> _tyrePressureLFState.value = state
                1 -> _tyrePressureRFState.value = state
                2 -> _tyrePressureLRState.value = state
                3 -> _tyrePressureRRState.value = state
            }
            publishSnapshot()
            Log.d(TAG, "🛞 tyrePressureState ${wheelName(mappedWheel)} (car wheel=$wheel)=$state all=lf=${_tyrePressureLFState.value} rf=${_tyrePressureRFState.value} lr=${_tyrePressureLRState.value} rr=${_tyrePressureRRState.value}")
        }
        // onTyreBatteryValueChanged delivers tyre temperature in °C despite its name.
        // Confirmed via HiveMQ: Electro publishes tyre_temperature_*_c from this callback.
        // Wheel index matches pressure: 1=LF, 2=RF, 3=LR, 4=RR.
        override fun onTyreBatteryValueChanged(wheel: Int, value: Double) {
            Log.i(TAG, "🛞 tyreBatteryValue wheel=$wheel value=$value")
            if (wheel !in 1..4) return
            // Temperature range sanity check: TPMS sensors report -40 to 125°C
            val tempC = value.takeIf { it in -40.0..125.0 }?.toInt() ?: return
            when (wheel) {
                1 -> _tyreTempLF.value = tempC
                2 -> _tyreTempRF.value = tempC
                3 -> _tyreTempLR.value = tempC
                4 -> _tyreTempRR.value = tempC
            }
            Log.i(TAG, "🛞 tyreTemp ${wheelName(wheel-1)} = ${tempC}°C")
            publishSnapshot()
        }
        override fun onTyreTemperatureStateChanged(state: Int) {
            Log.i(TAG, "🛞 tyreTemperatureState=$state")
        }
        override fun onTyreAirLeakStateChanged(wheel: Int, state: Int) {
            Log.i(TAG, "🛞 tyreAirLeakState wheel=$wheel state=$state")
        }
        override fun onTyreSignalStateChanged(wheel: Int, state: Int) {
            Log.d(TAG, "🛞 tyreSignalState wheel=$wheel state=$state")
        }
        override fun onIndirectTyreSystemStateChanged(state: Int) {
            Log.d(TAG, "🛞 indirectTyreSystemState=$state")
        }
        override fun onTyreSystemStateChanged(state: Int) {
            Log.d(TAG, "🛞 tyreSystemState=$state")
        }
        override fun onTyreBatteryStateChanged(state: Int) {
            Log.d(TAG, "🛞 tyreBatteryState=$state")
        }
        override fun onError(code: Int, msg: String) {
            Log.w(TAG, "TyreListener error $code: $msg")
        }
    } }

    fun start() {
        // PROTOTYPE: on DiLink-5, try to make the OEM bydauto classes resolvable on our own
        // classloader (injecting the installed com.byd.data.collect apk) before anything below
        // touches those types (chargingListener/gearboxListener/tyreListener are lazy; the tryDevice{}
        // blocks below already tolerate the classes being unavailable). Reflective + no-op if the
        // class isn't present (dilink3 build, or dilink5-sdk.jar already bundled) or injection fails.
        if (DiLink5Platform.isDiLink5) {
            runCatching {
                val inj = Class.forName("com.byd.tripstats.sdk.Dilink5SdkInjector")
                val instance = inj.getField("INSTANCE").get(null)
                val ok = inj.getMethod("ensure", Context::class.java).invoke(instance, ctx) as? Boolean
                Log.i(TAG, "DiLink-5 SDK injector: bydauto available=$ok")
            }.onFailure { Log.w(TAG, "DiLink-5 SDK injector failed: ${it.message}") }
        }
        try {
            appContext.contentResolver.delete(
                android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                "${android.provider.MediaStore.Downloads.DISPLAY_NAME} = ? AND ${android.provider.MediaStore.Downloads.RELATIVE_PATH} = ?",
                arrayOf("regen_diag.txt", "Download/BydTripStats/")
            )
        } catch (_: Exception) { }
        var anySuccess = false
        if (RuntimeExtensionBridge.isAvailable) {
            Log.i(TAG, "Optional runtime module: ${RuntimeExtensionBridge.describe()}")
            RuntimeExtensionBridge.onDataSourceStarted(ctx)
        }
        restorePersistedStatisticState()
        daemonClient.start()
        // DiLink-5: start the typed-listener client (present only in the dilink5 flavor; reflective
        // so the dilink3 build, which lacks the class, simply no-ops). It pushes via applyDilink5Telemetry.
        if (DiLink5Platform.isDiLink5) startDilink5Client()
        publishSnapshot()
        if (RuntimeExtensionBridge.isAvailable) {
            startReadLogsMonitor()
        }

        tryDevice("Charging") {
            chargingDevice = BYDAutoChargingDevice.getInstance(ctx)?.also {
                it.registerListener(chargingListener)
                Log.i(TAG, "✅ ChargingDevice registered")
                logChargingSnapshot(it)
                registerEventMirrorListener(it, "Charging")
                anySuccess = true
            }
        }

        tryDevice("Gearbox") {
            gearboxDevice = BYDAutoGearboxDevice.getInstance(ctx)?.also {
                it.registerListener(gearboxListener)
                Log.i(TAG, "✅ GearboxDevice registered")
                logGearboxSnapshot(it)
                anySuccess = true
            }
        }

        tryDevice("Tyre") {
            tyreDevice = BYDAutoTyreDevice.getInstance(ctx)?.also {
                it.registerListener(tyreListener)
                // Log listener shapes only when diagnosing firmware callback behavior.
                val tyreMethods = it.javaClass.methods.filter { m -> m.name == "registerListener" }
                Log.i(TAG, "🛞 TyreDevice registerListener overloads: " +
                    tyreMethods.joinToString { m -> m.parameterTypes.joinToString(",") { p -> p.simpleName } })
                registerEventMirrorListener(it, "Tyre")
                try {
                    val m = it.javaClass.methods.firstOrNull { m ->
                        m.name == "registerListener" &&
                        m.parameterTypes.size == 2 &&
                        m.parameterTypes[1] == IntArray::class.java
                    }
                    if (m != null) {
                        val listenerType = m.parameterTypes[0]
                        val proxy = java.lang.reflect.Proxy.newProxyInstance(
                            listenerType.classLoader,
                            arrayOf(listenerType)
                        ) { _, _, args ->
                            val fid = args?.getOrNull(0) as? Int
                            val value = args?.getOrNull(1)
                            if (fid != null && value != null) {
                                val raw = extractRawEventNumber(value)
                                if (raw != null) {
                                    logInfoIfChanged("tyre-event-$fid", "🛞 TyreEvent value=$raw")
                                    dispatchDynamicRawFeatureEvent("Tyre", fid, raw)
                                }
                            }
                            null
                        }
                        val tyreFeatureIds = RuntimeExtensionBridge.intGroup("i04")
                        m.invoke(it, proxy, tyreFeatureIds)
                        Log.i(TAG, "🛞 TyreDevice event listener registered")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "🛞 TyreDevice int[] overload failed: ${e.message}")
                }
                // Use the alternate listener shape when firmware exposes it.
                try {
                    val m2 = it.javaClass.methods.firstOrNull { m ->
                        m.name == "registerListener" &&
                        m.parameterTypes.size == 2 &&
                        m.parameterTypes[1] == IntArray::class.java &&
                        !m.parameterTypes[0].isInterface
                    }
                    if (m2 != null) {
                        val tyreFeatureIds2 = RuntimeExtensionBridge.intGroup("i04")
                        // Build a subclass proxy to intercept all callbacks
                        val proxyInstance = try {
                            // Try creating an anonymous subclass proxy using the class loader
                            val loggingProxy = object : AbsBYDAutoTyreListener() {
                                override fun onTyreBatteryValueChanged(wheel: Int, value: Double) {
                                    Log.i(TAG, "🛞 onTyreBatteryValueChanged wheel=$wheel value=$value")
                                    if (wheel in 1..4 && value in -40.0..125.0) {
                                        val t = value.toInt()
                                        when (wheel) { 1->_tyreTempLF.value=t; 2->_tyreTempRF.value=t; 3->_tyreTempLR.value=t; 4->_tyreTempRR.value=t }
                                        publishSnapshot()
                                    }
                                }
                                override fun onTyreTemperatureStateChanged(state: Int) {
                                    Log.i(TAG, "🛞 onTyreTemperatureStateChanged state=$state")
                                }
                                override fun onTyreAirLeakStateChanged(wheel: Int, state: Int) {
                                    Log.i(TAG, "🛞 onTyreAirLeakStateChanged wheel=$wheel state=$state")
                                }
                                override fun onTyrePressureValueChanged(wheel: Int, value: Int) {
                                    Log.i(TAG, "🛞 int[] onTyrePressureValueChanged wheel=$wheel value=$value")
                                }
                                override fun onError(code: Int, msg: String) {
                                    Log.w(TAG, "🛞 int[] onError code=$code msg=$msg")
                                }
                            }
                            loggingProxy
                        } catch (e: Exception) { null }
                        if (proxyInstance != null) {
                            m2.invoke(it, proxyInstance, tyreFeatureIds2)
                            Log.i(TAG, "🛞 TyreDevice AbsBYDAutoTyreListener+int[] registered with logging proxy")
                        } else {
                            m2.invoke(it, tyreListener, tyreFeatureIds2)
                            Log.i(TAG, "🛞 TyreDevice AbsBYDAutoTyreListener+int[] registered (no proxy)")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "🛞 TyreDevice AbsBYDAutoTyreListener+int[] failed: ${e.message}")
                }
                Log.i(TAG, "✅ TyreDevice registered")
                logTyreSnapshot(it)
                anySuccess = true
            }
        }

        // Bodywork is the only device exposing getBatteryCapacity(), which is how other apps
        // derive SoH on PHEV/DM-i (where the statistic "soh" feature is bogus). On these
        // firmwares the reflective s10 class name doesn't resolve, so also try the canonical
        // SDK class directly (Electro registers it this way). BEV-safe: purely additive — BEV
        // SoH still comes from the statistic feature, this only adds a capacity source.
        tryDynamicDeviceCandidates(
            label = "Bodywork",
            classNames = (RuntimeExtensionBridge.stringList("s10") +
                "android.hardware.bydauto.bodywork.BYDAutoBodyworkDevice")
                .filter { it.isNotEmpty() }
                .distinct(),
            listenerInterfaceName = null,
            onRegistered = { device ->
                bodyworkDevice = device
                Log.i(TAG, "✅ BodyworkDevice registered (${device.javaClass.name})")
                dumpForCompatProbe("bodywork", device)
                logBodyworkSnapshot(device)
                registerEventMirrorListener(device, "Bodywork")
                anySuccess = true
            },
            onEvent = null
        )

        tryDynamicDevice(
            label = "Location",
            className = RuntimeExtensionBridge.stringList("s11").firstOrNull().orEmpty(),
            listenerInterfaceName = null,
            onRegistered = { device ->
                locationDevice = device
                Log.i(TAG, "✅ LocationDevice registered")
                dumpForCompatProbe("location", device)
                logLocationSnapshot(device)
                registerEventMirrorListener(device, "Location")
                anySuccess = true
            },
            onEvent = null
        )

        tryDynamicDevice(
            label = "Energy",
            className = RuntimeExtensionBridge.stringList("s03").firstOrNull().orEmpty(),
            listenerInterfaceName = null,
            onRegistered = { device ->
                energyDevice = device
                Log.i(TAG, "✅ EnergyDevice registered")
                logEnergySnapshot(device)
                registerEventMirrorListener(device, "Energy")
                anySuccess = true
            },
            onEvent = null
        )

        tryDynamicDevice(
            label = "Mqtt",
            className = RuntimeExtensionBridge.stringList("s04").firstOrNull().orEmpty(),
            listenerInterfaceName = null,
            onRegistered = { device ->
                mqttDevice = device
                Log.i(TAG, "✅ MqttDevice registered")
                registerEventMirrorListener(device, "Mqtt")
                trySubscribeMqttFeatures(device)
                anySuccess = true
            },
            onEvent = null
        )
        if (mqttDevice == null) {
            Log.i(TAG, "ℹ️ MqttDevice not available after setup")
        }

        tryDynamicDevice(
            label = "Ota",
            className = RuntimeExtensionBridge.stringList("s12").firstOrNull().orEmpty(),
            listenerInterfaceName = null,
            onRegistered = { device ->
                otaDevice = device
                Log.i(TAG, "✅ OtaDevice registered")
                dumpForCompatProbe("ota", device)
                logOtaSnapshot(device)
                anySuccess = true
            },
            onEvent = null
        )

        tryDynamicDevice(
            label = "Engine",
            className = RuntimeExtensionBridge.stringList("s05").firstOrNull().orEmpty(),
            listenerInterfaceName = null,   // AbsBYDAutoEngineListener is abstract, not an interface — skip proxy
            onRegistered = { device ->
                engineDevice = device
                Log.i(TAG, "✅ EngineDevice registered")
                dumpForCompatProbe("engine", device)
                logEngineSnapshot(device)
                registerEventMirrorListener(device, "Engine")
                anySuccess = true
            },
            onEvent = null
        )

        tryDynamicDevice(
            label = "Statistic",
            className = RuntimeExtensionBridge.stringList("s06").firstOrNull().orEmpty(),
            listenerInterfaceName = null,
            onRegistered = { device ->
                statisticDevice = device
                Log.i(TAG, "✅ StatisticDevice registered")
                dumpForCompatProbe("statistic", device)
                logStatisticSnapshot(device)
                anySuccess = true
                registerEventMirrorListener(device, "Statistic")
                trySubscribeStatisticFeatures(device)
            },
            onEvent = null
        )

        tryDynamicDevice(
            label = "Speed",
            className = RuntimeExtensionBridge.stringList("s13").firstOrNull().orEmpty(),
            listenerInterfaceName = null,
            onRegistered = { device ->
                speedDevice = device
                Log.i(TAG, "✅ SpeedDevice registered")
                dumpForCompatProbe("speed", device)
                logSpeedSnapshot(device)
                registerEventMirrorListener(device, "Speed")
                anySuccess = true
            },
            onEvent = null
        )

        tryDynamicDevice(
            label = "Light",
            className = RuntimeExtensionBridge.stringList("s14").firstOrNull().orEmpty(),
            listenerInterfaceName = null,
            onRegistered = { device ->
                lightDevice = device
                Log.i(TAG, "✅ LightDevice registered")
                dumpForCompatProbe("light", device)
                logLightSnapshot(device)
                anySuccess = true
            },
            onEvent = null
        )

        tryDynamicDevice(
            label = "Power",
            className = RuntimeExtensionBridge.stringList("s02").firstOrNull().orEmpty(),
            listenerInterfaceName = null,
            onRegistered = { device ->
                powerDevice = device
                Log.i(TAG, "✅ PowerDevice registered")
                dumpForCompatProbe("power", device)
                logPowerSnapshot(device)
                registerEventMirrorListener(device, "Power")

                // Register a typed listener using the proxy approach.
                // whose method signatures may differ from our compile-time stub.
                try {
                    val register2 = device.javaClass.methods.firstOrNull { m ->
                        m.name == "registerListener" &&
                            m.parameterTypes.size == 2 &&
                            m.parameterTypes[0].name.contains("BYDAutoPower") &&
                            m.parameterTypes[1] == IntArray::class.java
                    }
                    val register1 = device.javaClass.methods.firstOrNull { m ->
                        m.name == "registerListener" &&
                            m.parameterTypes.size == 1 &&
                            m.parameterTypes[0].name.contains("BYDAutoPower")
                    }
                    val listenerType = register2?.parameterTypes?.get(0) ?: register1?.parameterTypes?.get(0)

                    if (listenerType != null && listenerType.isInterface) {
                        val proxy = java.lang.reflect.Proxy.newProxyInstance(
                            listenerType.classLoader, arrayOf(listenerType)
                        ) { _, method, args ->
                            when (method.name) {
                                "onMcuStatusChanged" -> {
                                    val status = args?.firstOrNull() as? Int ?: return@newProxyInstance null
                                    _vehicleSnapshot.value = _vehicleSnapshot.value.copy(powerMcuStatus = status)
                                    publishSnapshot()
                                    Log.d(TAG, "⚡ powerMcuStatus=$status")
                                }
                                "onBatteryRemainPowerEVChanged" -> {
                                    val power = args?.firstOrNull() as? Double ?: return@newProxyInstance null
                                    normalizeRemainingBatteryPowerKwh(power)?.let { kw ->
                                        _vehicleSnapshot.value = _vehicleSnapshot.value.copy(powerBatteryRemainPowerEV = kw)
                                        publishSnapshot()
                                        Log.d(TAG, "⚡ batteryRemainPowerEV=$kw kWh")
                                    }
                                }
                                "onDataEventChanged" -> {
                                    val eventArgs = args ?: return@newProxyInstance null
                                    val fid = eventArgs.getOrNull(0) as? Int ?: return@newProxyInstance null
                                    val ev  = eventArgs.getOrNull(1) ?: return@newProxyInstance null
                                    val raw = extractRawEventNumber(ev)?.toDouble()
                                    if (raw != null) dispatchDynamicRawFeatureEvent("Power", fid, raw)
                                }
                                "onError" -> Log.w(TAG, "PowerListener error: ${args?.getOrNull(1)}")
                                "equals"   -> return@newProxyInstance false
                                "hashCode" -> return@newProxyInstance 0
                                "toString" -> return@newProxyInstance "BYDAutoPowerListener"
                            }
                            null
                        }
                        val powerFeatureIds = powerStateFeatureIds
                        if (register2 != null) {
                            register2.invoke(device, proxy, powerFeatureIds)
                            Log.i(TAG, "✅ Power proxy listener registered via 2-arg interface")
                        } else {
                            register1!!.invoke(device, proxy)
                            Log.i(TAG, "✅ Power proxy listener registered via 1-arg interface")
                        }
                        listenerReferences += proxy
                    } else if (listenerType != null) {
                        Log.i(TAG, "ℹ️ AbsBYDAutoPowerListener is abstract class — generic event listener covers power events")
                    } else {
                        Log.i(TAG, "ℹ️ No typed BYDAutoPower registerListener found")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Power typed listener registration failed: ${e.message}")
                }
                anySuccess = true
            },
            onEvent = null
        )

        tryDynamicDevice(
            label = "Sensor",
            className = RuntimeExtensionBridge.stringList("s15").firstOrNull().orEmpty(),
            listenerInterfaceName = null,
            onRegistered = { device ->
                sensorDevice = device
                Log.i(TAG, "✅ SensorDevice registered")
                dumpForCompatProbe("sensor", device)
                logSensorSnapshot(device)
                registerEventMirrorListener(device, "Sensor")
                anySuccess = true
            },
            onEvent = null
        )

        tryDynamicDeviceCandidates(
            label = "Climate",
            classNames = RuntimeExtensionBridge.stringList("s16"),
            listenerInterfaceName = null,
            onRegistered = { device ->
                climateDevice = device
                Log.i(TAG, "✅ ClimateDevice registered")
                logClimateSnapshot(device)
                registerEventMirrorListener(device, "Climate")
                anySuccess = true
            },
            onEvent = null
        )

        try {
            val vemClassName = RuntimeExtensionBridge.stringList("s17").firstOrNull().orEmpty()
            if (vemClassName.isEmpty()) throw ClassNotFoundException("s17 not provided")
            val managerClass = Class.forName(vemClassName)
            val manager = managerClass.getMethod("get").invoke(null)
            if (manager != null) {
                vemManager = manager
                Log.i(TAG, "✅ VehicleEnergyModelManager registered")
                logVemSnapshot(manager)
                anySuccess = true
            }
        } catch (t: Throwable) {
            Log.i(TAG, "ℹ️ VehicleEnergyModelManager unavailable: ${t.javaClass.simpleName}: ${t.message}")
        }

        tryDynamicDevice(
            label = "Instrument",
            className = RuntimeExtensionBridge.stringList("s07").firstOrNull().orEmpty(),
            listenerInterfaceName = null,
            onRegistered = { device ->
                instrumentDevice = device
                Log.i(TAG, "✅ InstrumentDevice registered")
                logInstrumentSnapshot(device)
                registerEventMirrorListener(device, "Instrument")
                anySuccess = true
            },
            onEvent = null
        )

        tryDynamicDevice(
            label = "Battery",
            className = RuntimeExtensionBridge.stringList("s08").firstOrNull().orEmpty(),
            listenerInterfaceName = null,
            onRegistered = { device ->
                batteryDevice = device
                Log.i(TAG, "✅ BatteryDevice registered")
                dumpForCompatProbe("battery", device)
                logBatterySnapshot(device)
                registerEventMirrorListener(device, "Battery")
                anySuccess = true
            },
            onEvent = null
        )

        tryDynamicDeviceCandidates(
            label = "BMS",
            classNames = RuntimeExtensionBridge.stringList("s18"),
            listenerInterfaceName = null,
            onRegistered = { device ->
                bmsDevice = device
                Log.i(TAG, "✅ BmsDevice registered")
                dumpForCompatProbe("bms", device)
                logBatterySnapshot(device)
                registerEventMirrorListener(device, "BMS")
                anySuccess = true
            },
            onEvent = null
        )

        tryDynamicDevice(
            label = "Motor",
            className = RuntimeExtensionBridge.stringList("s19").firstOrNull().orEmpty(),
            listenerInterfaceName = null,
            onRegistered = { device ->
                motorDevice = device
                Log.i(TAG, "✅ MotorDevice registered")
                dumpForCompatProbe("motor", device)
                logMotorSnapshot(device)
                registerEventMirrorListener(device, "Motor")
                anySuccess = true
            },
            onEvent = null
        )

        // ── Battery health values ────────────────────────────────────────────────
        tryDynamicDevice(
            label = "Signal",
            className = RuntimeExtensionBridge.stringList("s09").firstOrNull().orEmpty(),
            listenerInterfaceName = null,
            onRegistered = { device ->
                signalDevice = device
                Log.i(TAG, "✅ SignalDevice registered")
                dumpForCompatProbe("signal", device)
                logSignalSnapshot(device)
                registerEventMirrorListener(device, "Signal")
                anySuccess = true
            },
            onEvent = null
        )

        tryDynamicDevice(
            label = "Dtc",
            className = RuntimeExtensionBridge.stringList("s20").firstOrNull().orEmpty(),
            listenerInterfaceName = null,
            onRegistered = { device ->
                dtcDevice = device
                Log.i(TAG, "✅ DtcDevice registered")
                dumpForCompatProbe("dtc", device)
                logDtcSnapshot(device)
                anySuccess = true
            },
            onEvent = null
        )

        _isAvailable.value = anySuccess
        Log.i(TAG, "📋 Device setup summary: " +
            "power=${powerDevice != null} engine=${engineDevice != null} speed=${speedDevice != null} " +
            "motor=${motorDevice != null} energy=${energyDevice != null} instrument=${instrumentDevice != null} " +
            "statistic=${statisticDevice != null} battery=${batteryDevice != null} bms=${bmsDevice != null} " +
            "gearbox=${gearboxDevice != null} tyre=${tyreDevice != null} location=${locationDevice != null} " +
            "bodywork=${bodyworkDevice != null} sensor=${sensorDevice != null} signal=${signalDevice != null} " +
            "climate=${climateDevice != null} light=${lightDevice != null} motor=${motorDevice != null} " +
            "dtc=${dtcDevice != null}")
        if (!anySuccess) Log.w(TAG, "⚠️ No supported car telemetry devices available")
        tryDevice("System location fallback setup") { refreshSystemLocationFallback() }

        // Start 1-second polling loop for values the car only pushes when they change.
        val scope = CoroutineScope(Dispatchers.IO)
        pollingScope = scope

        // Proactive wedge clear after a fresh start. Installing a new build over the old app
        // (adb install -r) kills the previous process, leaving stale SDK listener registrations that
        // block the new process's callbacks — the "always wedged right after install" symptom. GPS-
        // based detection can't catch this (often no GPS fix in a garage), so re-register a couple of
        // times early to kick the SDK's listener state. Harmless if the car is off (no events to lose).
        scope.launch {
            listOf(12_000L, 35_000L).forEach { waitMs ->
                delay(waitMs)
                runCatching { recoverEventDelivery(force = true) }
            }
        }
        pollingJob = scope.launch {
            Log.i(TAG, "⏱️ Cell voltage polling loop started (2s interval)")
            statisticDevice?.let { dev ->
                repeat(3) {
                    tryDevice("poll warmup") { pollAndUpdateCellFeatures(dev) }
                    delay(500L)
                }
            }
            // 2s interval: cell voltages change slowly; refreshSnapshots() also
            // hits car every second, so 1s here was redundant.
            // if/else avoids continue-in-lambda which requires experimental flag.
            while (true) {
                val dev = statisticDevice
                if (dev == null) {
                    delay(2_000L)
                } else {
                    tryDevice("poll") { pollAndUpdateCellFeatures(dev) }
                    delay(idlePollMs(2_000L))   // 2s active; backs off to 60s when parked/idle
                }
            }
        }
        statisticDevice?.let { dev ->
            scope.launch {
                listOf(500L, 1_500L, 3_000L).forEach { waitMs ->
                    delay(waitMs)
                    tryDevice("statistic bootstrap refresh") { logStatisticSnapshot(dev) }
                    tryDevice("statistic bootstrap poll") { pollAndUpdateCellFeatures(dev) }
                }
            }
        }
        // Poll HVAC compressor and PTC state every 3 seconds.
        // m19a / m19b getters are not event-driven on this firmware — they must be polled.
        // The climate device itself is event-driven for raw values, but these mode getters
        // are separate synchronous calls that only succeed when the device is active.
        scope.launch {
            delay(2_000L) // let device registration settle first
            while (true) {
                val dev = climateDevice
                if (dev != null) {
                    tryDevice("hvac poll") { pollHvacState(dev) }
                }
                delay(idlePollMs(3_000L))   // 3s active; backs off to 60s when parked/idle (HVAC off)
            }
        }
    }

    /**
     * Battery: true when the car is parked/idle — not charging, not moving, and the SDK has been
     * silent for >60s (no events). Used to back off background polls and drop the GPS fallback;
     * the polled values don't change while parked, so nothing is missed.
     */
    private fun isParkedIdle(): Boolean {
        val s = _vehicleSnapshot.value
        val charging = s.isChargingActive || s.chargingPower > 0.0
        val moving = (s.directSpeedKmh ?: 0.0) > 2.0
        val sdkSilent = lastFeatureEventElapsedMs > 0L &&
            (android.os.SystemClock.elapsedRealtime() - lastFeatureEventElapsedMs) > 60_000L
        return !charging && !moving && sdkSilent
    }

    /** [activeMs] while driving/charging/awake; 60s when parked-idle. */
    private fun idlePollMs(activeMs: Long): Long = if (isParkedIdle()) 60_000L else activeMs

    /**
     * Called right before an in-app update commits the install. An update force-kills this process
     * (skipping onDestroy/stop), so we proactively unregister all SDK listeners here while still
     * alive — leaving none stale to wedge the SDK service-wide for the freshly-installed process.
     * Does NOT tear down devices/polling, so a failed install self-heals via the re-register recovery.
     */
    fun prepareForUpdate() {
        runCatching { chargingDevice?.unregisterListener(chargingListener) }
        runCatching { gearboxDevice?.unregisterListener(gearboxListener) }
        runCatching { tyreDevice?.unregisterListener(tyreListener) }
        for (reg in mirrorRegs.toList()) {
            runCatching {
                reg.device.javaClass.methods.firstOrNull {
                    it.name == "unregisterListener" && it.parameterTypes.size == 1 &&
                        it.parameterTypes[0].isAssignableFrom(reg.proxy.javaClass)
                }?.invoke(reg.device, reg.proxy)
            }
        }
        mirrorRegs.clear()
        runCatching { DiagLog.event(appContext, TAG, "🔄 update prep — unregistered all SDK listeners") }
    }

    fun stop() {
        RuntimeExtensionBridge.onDataSourceStopped()
        daemonClient.stop()
        stopDilink5Client()
        pollingJob?.cancel()
        pollingJob = null
        readLogsMonitorJob?.cancel()
        readLogsMonitorJob = null
        pollingScope?.cancel()
        pollingScope = null
        try { chargingDevice?.unregisterListener(chargingListener) } catch (_: Exception) {}
        try { gearboxDevice?.unregisterListener(gearboxListener)   } catch (_: Exception) {}
        try { tyreDevice?.unregisterListener(tyreListener)         } catch (_: Exception) {}
        // Unregister the generic mirror listeners too. Leaving them registered means that on the next
        // (re)start the BYD SDK still holds this now-dead process's Binder proxies — stale registrations
        // that stall the SDK's event-callback delivery service-wide (the "wedge"). Clean shutdown here
        // avoids leaking them; force-kills (adb install / OOM) that skip stop() are handled by the
        // re-register recovery + proactive re-register on start.
        for (reg in mirrorRegs.toList()) {
            runCatching {
                reg.device.javaClass.methods.firstOrNull {
                    it.name == "unregisterListener" && it.parameterTypes.size == 1 &&
                        it.parameterTypes[0].isAssignableFrom(reg.proxy.javaClass)
                }?.invoke(reg.device, reg.proxy)
            }
        }
        mirrorRegs.clear()
        bodyworkDevice = null
        locationDevice = null
        energyDevice = null
        otaDevice = null
        signalDevice = null
        motorDevice = null
        dtcDevice = null
        batteryDevice = null
        bmsDevice = null
        engineDevice = null
        statisticDevice = null
        speedDevice = null
        lastPositiveSpeedElapsedMs = 0L
        lastGetterPositiveElapsedMs = 0L
        lightDevice = null
        powerDevice = null
        sensorDevice = null
        instrumentDevice = null
        instrumentGearKnown = false
        climateDevice = null
        mqttDevice = null
        mqttSnapshotLogged = false
        vemManager = null
        hasDirectLocationSignal = false
        runCatching { locationManager?.removeUpdates(systemLocationListener) }
        locationManager = null
        systemLocationUpdatesRegistered = false
        latestSystemLocation = null
        listenerReferences.clear()
        _isAvailable.value = false
        Log.i(TAG, "VehicleDataSource stopped")
    }

    fun refreshSnapshots() {
        snapshotTick++
        val slowTick   = snapshotTick % 5  == 0  // every 5 s
        val staticTick = snapshotTick % 30 == 0  // every 30 s

        runSnapshotBatch {
            // ── Fast tier (every 1 s) — driving-critical ──────────────────────
            tryDevice("Gearbox refresh")   { gearboxDevice?.let   { logGearboxSnapshot(it)   } }
            tryDevice("Speed refresh")     { speedDevice?.let     { logSpeedSnapshot(it)     } }
            tryDevice("Engine refresh")    { engineDevice?.let    { logEngineSnapshot(it)    } }
            tryDevice("Motor refresh")     { motorDevice?.let     { logMotorSnapshot(it)     } }
            tryDevice("Power refresh")     { powerDevice?.let     { logPowerSnapshot(it)     } }
            tryDevice("Charging refresh")  { chargingDevice?.let  { logChargingSnapshot(it)  } }
            tryDevice("Location refresh")  { locationDevice?.let  { logLocationSnapshot(it)  } }
            tryDevice("Instrument fast")   { instrumentDevice?.let { logInstrumentSnapshot(it) } }
            tryDevice("Energy fast")       { energyDevice?.let     { logEnergySnapshot(it)     } }

            // ── Slow tier (every 5 s) — useful but low rate of change ─────────
            if (slowTick || snapshotTick <= 1) {
                tryDevice("Tyre refresh")       { tyreDevice?.let       { logTyreSnapshot(it)       } }
                tryDevice("Bodywork refresh")   { bodyworkDevice?.let   { logBodyworkSnapshot(it)   } }
                tryDevice("Sensor refresh")     { sensorDevice?.let     { logSensorSnapshot(it)     } }
                tryDevice("Battery refresh")    { batteryDevice?.let    { logBatterySnapshot(it)    } }
                tryDevice("BMS refresh")        { bmsDevice?.let        { logBatterySnapshot(it)    } }
                // PM2.5 debounce requires two consecutive identical readings before publishing.
                // The climate device is only polled once at registration, so a second read never
                // arrives unless we re-poll here. Cars where the instrument device doesn't expose
                // PM2.5 getters (e.g. PHEV firmware) rely entirely on this periodic climate poll.
                tryDevice("PM2.5 refresh") {
                    climateDevice?.let { dev ->
                        val pm25In  = invokeIntGetter(dev, *runtimeMethodNames("m15"))?.takeIf { it in 1..999 }
                        val pm25Out = invokeIntGetter(dev, *runtimeMethodNames("m16"))?.takeIf { it in 1..999 }
                        if (pm25In != null || pm25Out != null) {
                            updatePm25Snapshot(inCar = pm25In, outCar = pm25Out, source = "climate-refresh")
                        }
                    }
                }
                // Statistic refresh — without this, elecDrivingRange (and other statistic
                // metrics like totalMileage, avgFuelCon, waterTemperature) are only read at
                // bootstrap. The onElecDrivingRangeChanged callback doesn't fire on every
                // firmware, so the dashboard's BMS range would freeze at the initial value.
                tryDevice("Statistic refresh") { statisticDevice?.let { logStatisticSnapshot(it) } }
                tryDevice("System location fallback") { refreshSystemLocationFallback() }

                // PHEV probe sweep — attempts known PHEV-specific named getters on every
                // registered device and records the results for compat report analysis.
                // No-op when probe is disabled.
                if (VehicleCompatibilityProbe.isEnabled.value) {
                    val probeDevices = mapOf(
                        "power"      to powerDevice,
                        "engine"     to engineDevice,
                        "statistic"  to statisticDevice,
                        "energy"     to energyDevice,
                        "instrument" to instrumentDevice,
                        "charging"   to chargingDevice,
                        "battery"    to batteryDevice,
                        "bms"        to bmsDevice,
                        "motor"      to motorDevice,
                        "sensor"     to sensorDevice,
                    )
                    VehicleCompatibilityProbe.recordPhevSection(probeDevices)
                    VehicleCompatibilityProbe.recordTemperatureSection(probeDevices)
                    // Re-dump statistic and instrument devices so the probe captures all
                    // no-arg getter values even when enabled after the initial registration.
                    statisticDevice?.let  { dumpForCompatProbe("statistic",  it) }
                    instrumentDevice?.let { dumpForCompatProbe("instrument", it) }
                    sensorDevice?.let     { dumpForCompatProbe("sensor",     it) }
                }
            }

            // ── Static tier (every 30 s) — essentially never changes ──────────
            if (staticTick || snapshotTick <= 1) {
                tryDevice("Light refresh")  { lightDevice?.let  { logLightSnapshot(it)  } }
                tryDevice("Ota refresh")    { otaDevice?.let    { logOtaSnapshot(it)    } }
                tryDevice("Signal refresh") { signalDevice?.let { logSignalSnapshot(it) } }
                tryDevice("VEM refresh")    { vemManager?.let   { logVemSnapshot(it)    } }
                if (ENABLE_LAB_DIAGNOSTICS && !mqttSnapshotLogged) {
                    tryDevice("Mqtt refresh") { mqttDevice?.let { logMqttSnapshot(it) } }
                }
            }
        }
        // Batch finished cleanly — mark idle so a later wedge is attributed to the
        // actual stage that hangs, not a leftover from this completed pass.
        currentRefreshStage = "idle"
    }

    private fun tryDevice(name: String, block: () -> Unit) {
        // Record the stage we're entering. If a BYD getter inside [block] hangs, this
        // value is left pointing at the culprit (the next tryDevice never runs to
        // overwrite it), so the service's wedge guard can report exactly which device's
        // getter is blocking — turning a frozen-speed reproduction into a precise fix.
        currentRefreshStage = name
        try {
            block()
        } catch (e: SecurityException) {
            Log.w(TAG, "⚠️ $name SecurityException: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ $name failed: ${e.javaClass.simpleName}: ${e.message}")
        } catch (e: Throwable) {
            // MUST catch Throwable, not just Exception: on DiLink-5 the bundled OEM bydauto SDK
            // makes getInstance() execute real code, and a firmware/SDK version skew throws a
            // LinkageError (e.g. NoSuchMethodError: CarAdapterManager.getInstance(Context) — the
            // OEM TsManagerImpl expects a ts-framework.jar API this head unit doesn't expose).
            // That is an Error, not an Exception, so without this it escaped onCreate and crashed
            // the whole service on launch. Degrade gracefully: this device just doesn't bind.
            Log.w(TAG, "⚠️ $name link/error: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    // NOTE — do NOT reintroduce an "ensureDeviceContext"/mContext-overwrite helper here.
    //
    // A previous beta (commit 1e983fe) added one to "repair" the BYD device singleton's
    // internal `mContext` after getInstance, on the theory that a stale mContext caused the
    // "speed/motor stuck at 0 after a mid-session restart" wedge. It did the OPPOSITE:
    // clobbering the freshly-established singleton's mContext corrupts the bind and freezes
    // the value getters (getCurrentSpeed/getEnginePower/RPM) at 0 over IPC while the event
    // stream keeps flowing. Proven by comparison — 2.3.0/2.6.0 never touch mContext and bind
    // cleanly on a cold install; the beta with the overwrite wedged on every clean install and
    // only "recovered" via a head-unit power-cycle (a fresh process re-establishing the bind).
    // Leave the device exactly as getInstance(ctx) returns it.

    private fun tryDynamicDevice(
        label: String,
        className: String,
        listenerInterfaceName: String?,
        onRegistered: (Any) -> Unit,
        onEvent: ((String, Array<out Any?>?) -> Unit)?,
    ) {
        try {
            val deviceClass = Class.forName(className)
            val getInstance = deviceClass.getMethod("getInstance", Context::class.java)
            val device = getInstance.invoke(null, ctx) ?: return
            // Use the singleton exactly as returned — do NOT overwrite its mContext (see the
            // note by the former ensureDeviceContext: it corrupts a fresh bind and wedges the
            // value getters at 0).

            if (listenerInterfaceName != null && onEvent != null) {
                val listenerInterface = Class.forName(listenerInterfaceName)
                val proxy = Proxy.newProxyInstance(
                    listenerInterface.classLoader,
                    arrayOf(listenerInterface),
                ) { _, method, args ->
                    if (method.name != "toString" && method.name != "hashCode" && method.name != "equals") {
                        onEvent(method.name, args)
                    }
                    null
                }
                val register = deviceClass.methods.firstOrNull {
                    it.name == "registerListener" && it.parameterTypes.size == 1
                }
                register?.invoke(device, proxy)
                listenerReferences += proxy
            }

            onRegistered(device)
        } catch (e: ClassNotFoundException) {
            Log.w(TAG, "⚠️ $label unavailable: ${e.message}")
        } catch (e: NoSuchMethodException) {
            Log.w(TAG, "⚠️ $label getInstance/registerListener missing: ${e.message}")
        } catch (e: Throwable) {
            Log.w(TAG, "⚠️ $label failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun tryDynamicDeviceCandidates(
        label: String,
        classNames: List<String>,
        listenerInterfaceName: String?,
        onRegistered: (Any) -> Unit,
        onEvent: ((String, Array<out Any?>?) -> Unit)?,
    ) {
        var lastError: String? = null
        classNames.forEach { className ->
            try {
                val deviceClass = Class.forName(className)
                val getInstance = deviceClass.getMethod("getInstance", Context::class.java)
                val device = getInstance.invoke(null, ctx) ?: return@forEach
                // Do NOT overwrite the singleton's mContext (see note by former
                // ensureDeviceContext) — it corrupts a fresh bind and wedges the getters.

                if (listenerInterfaceName != null && onEvent != null) {
                    val listenerInterface = Class.forName(listenerInterfaceName)
                    val proxy = Proxy.newProxyInstance(
                        listenerInterface.classLoader,
                        arrayOf(listenerInterface),
                    ) { _, method, args ->
                        if (method.name != "toString" && method.name != "hashCode" && method.name != "equals") {
                            onEvent(method.name, args)
                        }
                        null
                    }
                    val register = deviceClass.methods.firstOrNull {
                        it.name == "registerListener" && it.parameterTypes.size == 1
                    }
                    register?.invoke(device, proxy)
                    listenerReferences += proxy
                }

                onRegistered(device)
                return
            } catch (e: ClassNotFoundException) {
                lastError = e.message
            } catch (e: NoSuchMethodException) {
                lastError = e.message
            } catch (e: Throwable) {
                lastError = "${e.javaClass.simpleName}: ${e.message}"
            }
        }
        Log.w(TAG, "⚠️ $label unavailable: tried ${classNames.joinToString()} last=$lastError")
    }

    private fun logChargingSnapshot(device: BYDAutoChargingDevice) {
        try {
            val state = invokeIntGetter(device, *runtimeMethodNames("m32"))
            val power = invokeDoubleGetter(device, *runtimeMethodNames("m33"))
            val batteryTemp = decodePackTempCelsius(invokeDoubleGetter(device, *runtimeMethodNames("m36")))
            val soh = invokeIntGetter(device, *runtimeMethodNames("m35"))
            val totalVoltage = invokeIntGetter(device, *runtimeMethodNames("m37"))
            val voltage12v = invokeDoubleGetter(device, *runtimeMethodNames("m38"))
            // m39/m40 are direct °C on most firmwares but 1/d01 °C on some (e.g. Seal Excellence
            // returns raw 68 = 22.7°C with d01=3). decodePackTempCelsius picks the right
            // interpretation using the statistic cell temp / ambient as a reference. 0 is the
            // BMS-uninitialized sentinel — filtering it out preserves the last good reading
            // instead of letting it overwrite real values on every quiet poll.
            val cellTempMax = decodePackTempCelsius(invokeIntGetter(device, *runtimeMethodNames("m39"))?.toDouble()?.takeIf { it > 0.0 })?.toInt()
            val cellTempMin = decodePackTempCelsius(invokeIntGetter(device, *runtimeMethodNames("m40"))?.toDouble()?.takeIf { it > 0.0 })?.toInt()
            val cellVoltMax = invokeDoubleGetter(device, *runtimeMethodNames("m41"))
            val cellVoltMin = invokeDoubleGetter(device, *runtimeMethodNames("m42"))
            val sohDelta = readSohDeltaValue(device)
            val extraValues = readNumericGetterMap(device, RuntimeExtensionBridge.methodGroups("m44"))
            val sohFromExtra = extraValues["soh"]?.takeIf { it in 0.0..100.0 }?.toInt()
            val effectiveSoh = soh ?: sohFromExtra
            val chargingCapState = invokeCapState(device)
            val chargingCapacity = m34["capacity"]?.takeIf { it.isNotEmpty() }?.let { invokeDoubleGetter(device, it) }
            val chargingRestTimeRaw = m34["restTime"]?.takeIf { it.isNotEmpty() }?.let { invokeGetter(device, it) }
            val chargingRestTime = extractRestTime(chargingRestTimeRaw)
            val chargerState = m34["chargerState"]?.takeIf { it.isNotEmpty() }?.let { invokeIntGetter(device, it) }
            val chargerWorkState = m34["chargerWork"]?.takeIf { it.isNotEmpty() }?.let { invokeIntGetter(device, it) }
            val chargingType = m34["chargingType"]?.takeIf { it.isNotEmpty() }?.let { invokeIntGetter(device, it) }
            val chargingGunState = m34["gunState"]?.takeIf { it.isNotEmpty() }?.let { invokeIntGetter(device, it) }
            val chargingMode = m34["chargingMode"]?.takeIf { it.isNotEmpty() }?.let { invokeIntGetter(device, it) }

            state?.let { _chargeState.value = it }
            // m33 = getChargingPower() — instantaneous pack power (V×I, up to 500 kW) and the only
            // getter that sees DC (the instrument getter is clamped 0.1..50, onChargingPowerChanged
            // is inert). Feed it as the pack-power source while parked: idle pack power is ~0 so it
            // can't assert a phantom charge, and the driving gate in computeChargingActive clears it.
            // This drives both powerActive (so a DC-while-on charge is detected even when the pushed
            // callbacks are silent) and the session's real kW.
            if (_vehicleSnapshot.value.gear !in setOf("D", "R")) updatePackChargingPower(power)
            // Prefer the instrument external-charging-power path over charging-device
            // callbacks, which can stay stale on some firmware.
            chargingCapState?.first?.let { _chargingCapState.value = it }
            chargingCapState?.second?.let { _chargingCapValue.value = it }
            chargingCapacity?.let { _chargingCapacity.value = it }
            batteryTemp?.let { _vehicleSnapshot.value = _vehicleSnapshot.value.copy(batteryPackTemp = it) }
            chargingRestTime?.first?.let { _remainHours.value = it }
            chargingRestTime?.second?.let { _remainMinutes.value = it }
            chargerState?.let { _chargerState.value = it }
            chargerWorkState?.let {
                if (it > 0 && _chargerWorkState.value == 0) chargerWorkStateSetElapsedMs = SystemClock.elapsedRealtime()
                else if (it == 0) chargerWorkStateSetElapsedMs = 0L
                _chargerWorkState.value = it
            }
            chargingType?.let { _chargingType.value = it }
            chargingGunState?.let { _chargingGunState.value = it }
            chargingMode?.let { _chargingMode.value = it }
            // Store battery cell data if charging device exposes it
            if (effectiveSoh != null || totalVoltage != null || voltage12v != null ||
                cellTempMax != null || cellTempMin != null || cellVoltMax != null || cellVoltMin != null) {
                val previous = _vehicleSnapshot.value
                _batterySoh.value = effectiveSoh ?: _batterySoh.value
                _batteryTotalVoltage.value = totalVoltage ?: _batteryTotalVoltage.value
                _battery12vVoltage.value = voltage12v ?: _battery12vVoltage.value
                // cellTempMax/Min are pre-decoded above (decodePackTempCelsius). null here means
                // the BMS reading was missing, zero, or a known sentinel — preserve the last
                // good value instead of overwriting it.
                _batteryCellTempMax.value = cellTempMax ?: _batteryCellTempMax.value
                _batteryCellTempMin.value = cellTempMin ?: _batteryCellTempMin.value
                _batteryCellVoltageMax.value = cellVoltMax ?: _batteryCellVoltageMax.value
                _batteryCellVoltageMin.value = cellVoltMin ?: _batteryCellVoltageMin.value
                _vehicleSnapshot.value = previous.copy(
                    batterySoh = effectiveSoh ?: previous.batterySoh,
                    sohEstimated = false,
                    batteryTotalVoltage = totalVoltage ?: previous.batteryTotalVoltage,
                    battery12vVoltage = voltage12v ?: previous.battery12vVoltage,
                    batteryCellTempMax = cellTempMax ?: previous.batteryCellTempMax,
                    batteryCellTempMin = cellTempMin ?: previous.batteryCellTempMin,
                    batteryCellVoltageMax = cellVoltMax ?: previous.batteryCellVoltageMax,
                    batteryCellVoltageMin = cellVoltMin ?: previous.batteryCellVoltageMin,
                )
            }
            publishSnapshot()

            if (
                ENABLE_VERBOSE_SNAPSHOT_LOGS &&
                (state != null || power != null || chargerState != null ||
                    chargerWorkState != null || chargingGunState != null || chargingType != null)
            ) {
                Log.i(
                    TAG,
                    "🔬 charging getter values: chargeState=${state ?: "n/a"} getterPower=${power ?: "n/a"} " +
                        "chargerState=${chargerState ?: "n/a"} workState=${chargerWorkState ?: "n/a"} " +
                        "gunState=${chargingGunState ?: "n/a"} type=${chargingType ?: "n/a"} " +
                        "mode=${chargingMode ?: "n/a"} cap=${chargingCapacity ?: "n/a"}"
                )
            }

            // Persisted charging diagnostic (survives DiLink's ~7-min logcat shred) — records the raw
            // charging getters incl. m33 getChargingPower() so an on-car charge can be validated from
            // diag.log. Throttled to 10 s; fires while any charge signal is live, *including* the
            // silent-callback case (parked + meaningful power into the pack) that the DC-while-on bug
            // exhibited — so the file captures exactly what the SDK reported either way.
            run {
                val snap = _vehicleSnapshot.value
                val parked = snap.gear !in setOf("D", "R")
                val chargingDiagActive = (power?.let { it > 0.1 } == true) ||
                    (chargingGunState ?: 0) != 0 || (chargerWorkState ?: 0) != 0 ||
                    (chargingCapacity?.let { it > 0.0 } == true) ||
                    (parked && (snap.enginePower ?: 0) <= -3)
                val nowMs = SystemClock.elapsedRealtime()
                if (chargingDiagActive && nowMs - lastChargingDiagMs > 10_000L) {
                    lastChargingDiagMs = nowMs
                    runCatching {
                        DiagLog.event(appContext, TAG,
                            "🔌 charge getters: m33Power=${power ?: "-"} gun=${chargingGunState ?: "-"} " +
                                "work=${chargerWorkState ?: "-"} type=${chargingType ?: "-"} mode=${chargingMode ?: "-"} " +
                                "cap=${chargingCapacity ?: "-"} chargeState=${state ?: "-"} " +
                                "gear=${snap.gear} enginePower=${snap.enginePower} " +
                                "carOn=${snap.powerStateRaw ?: snap.powerMcuStatus ?: "-"}")
                    }
                }
            }

            if (
                soh != null || totalVoltage != null || voltage12v != null ||
                cellTempMax != null || cellTempMin != null || cellVoltMax != null || cellVoltMin != null ||
                batteryTemp != null || sohDelta != null || extraValues.isNotEmpty()
            ) {
                logVerboseInfoIfChanged(
                    "chargingDiagnostics",
                ) {
                    "🔬 charging battery values: soh=$soh sohExtra=$sohFromExtra totalVoltage=$totalVoltage 12v=$voltage12v " +
                        "cellTempMax=$cellTempMax cellTempMin=$cellTempMin " +
                        "cellVoltMax=$cellVoltMax cellVoltMin=$cellVoltMin temp=$batteryTemp " +
                        "sohDelta=${describeSohDeltaCandidate(sohDelta)}"
                }
                if (extraValues.isNotEmpty()) {
                    logVerboseInfoIfChanged(
                        "chargingDiagnosticsExtra",
                    ) {
                        "🔬 charging battery extra values: " +
                            extraValues.entries.joinToString(" ") { (key, value) ->
                                "$key=${String.format("%.3f", value)}"
                            }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Charging snapshot failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun logGearboxSnapshot(device: BYDAutoGearboxDevice) {
        try {
            logModeStateSnapshot("gearbox", device)
            val driveMode = invokeIntGetter(device, *runtimeMethodNames("m02"))
            val regenMode = invokeIntGetter(device, *runtimeMethodNames("m03"))

            updateDriveModeCandidate(driveMode, strong = false, source = "gearbox")
            updateRegenModeCandidate(regenMode, strong = false, source = "gearbox")

            val state = m26["gearboxState"]?.takeIf { it.isNotEmpty() }?.let { invokeIntGetter(device, it) }
            val currentGear = m26["currentGear"]?.takeIf { it.isNotEmpty() }?.let { invokeIntGetter(device, it) }
            val brakePedalState = m26["brakeState"]?.takeIf { it.isNotEmpty() }?.let { invokeIntGetter(device, it) }
            val rawGear = m26["gearCode"]?.takeIf { it.isNotEmpty() }?.let { invokeStringGetter(device, it) }
            // Gear-selector position via the auto-mode-type getter (1=P 2=R 3=N 4=D 5=M 6=S).
            // On firmwares where gearCode/currentGear return NULL this is the only working
            // gear source; used as a fallback so gear is correct on app start / after a
            // missed event (the onGearboxAutoModeTypeChanged event handles live shifts).
            val autoModeType = invokeIntGetter(device, "getGearboxAutoModeType")
            val autoModeGear = autoModeType?.let(::mapGearboxAutoModeToGear)
            val normalizedGear = rawGear
                ?.takeUnless { it.equals("NULL", ignoreCase = true) }
                ?: currentGear?.let(::mapGearValue)
                ?: autoModeGear

            state?.let { _gearboxState.value = it }
            _currentGearRaw.value = currentGear
            brakePedalState?.let {
                _gearboxBrakePedalState.value = it
            }
            normalizedGear?.let { _gear.value = it }
            // gear-source diagnostic (release-safe): what the gearbox getter actually returns
            // vs the committed gear, at standstill too — confirms whether the firmware reports
            // gear only while moving. Throttled.
            val nowG = SystemClock.elapsedRealtime()
            if (nowG - lastGearDiagMs > 5_000L) {
                lastGearDiagMs = nowG
                DiagLog.event(appContext, TAG,
                    "⚙️ gear poll: gearCode=${rawGear ?: "null"} currentGear=${currentGear ?: "null"} " +
                        "autoModeType=${autoModeType ?: "null"} " +
                        "→ ${normalizedGear ?: "(no update)"} committed=${_gear.value} " +
                        "spd=${String.format("%.0f", _vehicleSnapshot.value.directSpeedKmh)}")
            }
            publishSnapshot()

        } catch (t: Throwable) {
            Log.w(TAG, "Gearbox snapshot failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun logTyreSnapshot(device: BYDAutoTyreDevice) {
        try {
            val lfDirectRaw = m27["lf"]?.takeIf { it.isNotEmpty() }?.let { invokeDoubleGetter(device, it) }
            val rfDirectRaw = m27["rf"]?.takeIf { it.isNotEmpty() }?.let { invokeDoubleGetter(device, it) }
            val lrDirectRaw = m27["lr"]?.takeIf { it.isNotEmpty() }?.let { invokeDoubleGetter(device, it) }
            val rrDirectRaw = m27["rr"]?.takeIf { it.isNotEmpty() }?.let { invokeDoubleGetter(device, it) }

            // Read all indexed tyre slots; slot 0 is generic/dummy, real tyres are 1-4.
            val pressureValueMethod = m28["pressureValue"].orEmpty()
            val slot1Raw = if (pressureValueMethod.isNotEmpty()) invokeIndexedDoubleGetter(device, 1, pressureValueMethod) else null  // LF
            val slot2Raw = if (pressureValueMethod.isNotEmpty()) invokeIndexedDoubleGetter(device, 2, pressureValueMethod) else null  // RF
            val slot3Raw = if (pressureValueMethod.isNotEmpty()) invokeIndexedDoubleGetter(device, 3, pressureValueMethod) else null  // LR
            val slot4Raw = if (pressureValueMethod.isNotEmpty()) invokeIndexedDoubleGetter(device, 4, pressureValueMethod) else null  // RR
            // Use named getters first; fall back to shifted-index slots (1-4)
            val lfRaw = slot1Raw
            val rfRaw = slot2Raw
            val lrRaw = slot3Raw
            val rrRaw = slot4Raw

            val pressureStateMethod = m28["pressureState"].orEmpty()
            val lfState = if (pressureStateMethod.isNotEmpty()) invokeIndexedIntGetter(device, 1, pressureStateMethod) else null
            val rfState = if (pressureStateMethod.isNotEmpty()) invokeIndexedIntGetter(device, 2, pressureStateMethod) else null
            val lrState = if (pressureStateMethod.isNotEmpty()) invokeIndexedIntGetter(device, 3, pressureStateMethod) else null
            val rrState = if (pressureStateMethod.isNotEmpty()) invokeIndexedIntGetter(device, 4, pressureStateMethod) else null
            val lfSourceRaw = lfDirectRaw ?: lfRaw
            val rfSourceRaw = rfDirectRaw ?: rfRaw
            val lrSourceRaw = lrDirectRaw ?: lrRaw
            val rrSourceRaw = rrDirectRaw ?: rrRaw

            val lfBar = normalizeTyrePressureBar(lfSourceRaw, lfState)
            val rfBar = normalizeTyrePressureBar(rfSourceRaw, rfState)
            val lrBar = normalizeTyrePressureBar(lrSourceRaw, lrState)
            val rrBar = normalizeTyrePressureBar(rrSourceRaw, rrState)

            val lf = barToPsi(lfBar)
            val rf = barToPsi(rfBar)
            val lr = barToPsi(lrBar)
            val rr = barToPsi(rrBar)

            // Only overwrite if we got a valid reading — TPMS may not transmit in ACC/remote-wake
            // mode, in which case the getter returns 0. Keeping the last-known value avoids
            // flipping the indicator back to "no data" when the sensor is simply silent.
            if (lf > 0.0) { _tyrePressureLF.value = lf; _tyrePressureLFBar.value = lfBar }
            if (rf > 0.0) { _tyrePressureRF.value = rf; _tyrePressureRFBar.value = rfBar }
            if (lr > 0.0) { _tyrePressureLR.value = lr; _tyrePressureLRBar.value = lrBar }
            if (rr > 0.0) { _tyrePressureRR.value = rr; _tyrePressureRRBar.value = rrBar }
            _tyrePressureLFState.value = lfState
            _tyrePressureRFState.value = rfState
            _tyrePressureLRState.value = lrState
            _tyrePressureRRState.value = rrState

            // ── Tyre temperature values ─────────────────────────────────────────
            // Tyre battery value: -1.0 indicates voltage sensor not supported on this firmware.
            // Temperature value getter absent on some firmware; use the state getter when present.
            val tempSlots = listOf(1 to _tyreTempLF, 2 to _tyreTempRF,
                                   3 to _tyreTempLR, 4 to _tyreTempRR)
            var anyTempFound = false
            tempSlots.forEach { (slot, flow) ->
                val rawTempC = m28["tempState"]?.takeIf { it.isNotEmpty() }?.let { invokeIndexedIntGetter(device, slot, it) }
                Log.d(TAG, "🛞 TyreTemperatureState slot=$slot returned $rawTempC")
                val tempC = rawTempC?.takeIf { it in -40..125 }
                if (tempC != null && tempC != flow.value) {
                    flow.value = tempC
                    anyTempFound = true
                }
            }
            if (anyTempFound) {
                Log.i(TAG, "🛞 tyreTemp polled: lf=${_tyreTempLF.value} rf=${_tyreTempRF.value} lr=${_tyreTempLR.value} rr=${_tyreTempRR.value}")
            }
            publishSnapshot()
        } catch (t: Throwable) {
            Log.w(TAG, "Tyre snapshot failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun logBodyworkSnapshot(device: Any) {
        try {
            val autoSystemState = m29["autoSystemState"]?.takeIf { it.isNotEmpty() }?.let { invokeIntGetter(device, it) }
            val autoVin = m29["autoVin"]?.takeIf { it.isNotEmpty() }?.let { invokeStringGetter(device, it) }
            // Prefer the private-extension mapping; fall back to the canonical SDK getter name
            // so PHEV/DM-i bodywork capacity (other apps' SoH source) flows even without an s10 map.
            val batteryCapacity = (m29["batteryCapacity"]?.takeIf { it.isNotEmpty() }?.let { invokeIntGetter(device, it) })
                ?: invokeIntGetter(device, "getBatteryCapacity")
            val batteryPowerHev = m29["batteryPowerHEV"]?.takeIf { it.isNotEmpty() }?.let { invokeDoubleGetter(device, it) }
            val batteryPowerValue = m29["batteryPowerValue"]?.takeIf { it.isNotEmpty() }?.let { invokeIntGetter(device, it) }
            val batteryVoltageLevel = m29["batteryVoltageLevel"]?.takeIf { it.isNotEmpty() }?.let { invokeIntGetter(device, it) }
            val battery12vVoltage = batteryPowerValue
                ?.takeIf { it in 90..180 }
                ?.let { it / 10.0 }
            val powerLevel = m29["powerLevel"]?.takeIf { it.isNotEmpty() }?.let { invokeIntGetter(device, it) }
            // Car lock state — try various known getter names
            val lockState = invokeIntGetter(device, *runtimeMethodNames("m30"))
            // Any door open — read door slots 0-5, filter out sentinel values (e.g. -2147482645).
            val doorStateMethod = m29["doorState"].orEmpty()
            val doorStates = if (doorStateMethod.isNotEmpty()) {
                (0..5).mapNotNull { idx ->
                    invokeIndexedIntGetter(device, idx, doorStateMethod)
                        ?.takeIf { it > -1_000_000 }
                }
            } else emptyList()
            val anyDoorOpen = if (doorStates.isNotEmpty()) if (doorStates.any { it != 0 }) 1 else 0 else null
            val washerFluidValues = readNumericGetterMap(device, RuntimeExtensionBridge.methodGroups("m31"))
            val washerFluidLowWarn = runtimeGroupMap("m31")["washerFluidLowWarn"]
                ?.let { invokeIntGetter(device, *it.toTypedArray()) }
                ?.takeIf { it in 0..1 }

            _carLocked.value = lockState
            _anyDoorOpened.value = anyDoorOpen

            _vehicleSnapshot.value = _vehicleSnapshot.value.copy(
                bodyworkAutoSystemState = autoSystemState,
                bodyworkBatteryCapacity = batteryCapacity,
                bodyworkBatteryPowerHEV = batteryPowerHev,
                bodyworkBatteryPowerValue = batteryPowerValue,
                bodyworkBatteryVoltageLevel = batteryVoltageLevel,
                battery12vVoltage = battery12vVoltage,
                bodyworkPowerLevel = powerLevel,
                bodyworkAutoVin = autoVin,
                carLocked = lockState,
                anyDoorOpened = anyDoorOpen,
            )
            publishSnapshot()

            logVerboseInfoIfChanged(
                "bodyworkSnapshot",
            ) {
                "📸 bodywork snapshot: batteryCapacity=$batteryCapacity batteryPowerValue=$batteryPowerValue " +
                    "battery12vVoltage=$battery12vVoltage batteryVoltageLevel=$batteryVoltageLevel " +
                    "batteryPowerHEV=$batteryPowerHev lockState=$lockState anyDoorOpen=$anyDoorOpen"
            }
            if (washerFluidValues.isNotEmpty() || washerFluidLowWarn != null) {
                logWarnIfChanged(
                    "bodyworkWasherSnapshot",
                    "📸 bodywork washer values: ${washerFluidValues.entries.joinToString(" ") { (key, value) -> "$key=${String.format("%.1f", value)}" }.ifBlank { "n/a" }} lowWarn=$washerFluidLowWarn"
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Bodywork snapshot failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun logBatterySnapshot(device: Any) {
        try {

            // State of health
            val soh = invokeIntGetter(device, *runtimeMethodNames("m35"))
            // HV total voltage (typically ~300-400V as Int in 0.1V units or raw V)
            val totalVoltage = invokeIntGetter(device, *runtimeMethodNames("m37"))
            // 12V auxiliary battery voltage
            val voltage12v = invokeDoubleGetter(device, *runtimeMethodNames("m38"))
            // Cell temperatures — decoded via decodePackTempCelsius to handle the
            // direct-°C vs ÷d01-°C encoding ambiguity present on some firmwares.
            // 0 is filtered as the BMS-uninitialized sentinel.
            val cellTempMax = decodePackTempCelsius(invokeIntGetter(device, *runtimeMethodNames("m39"))?.toDouble()?.takeIf { it > 0.0 })?.toInt()
            val cellTempMin = decodePackTempCelsius(invokeIntGetter(device, *runtimeMethodNames("m40"))?.toDouble()?.takeIf { it > 0.0 })?.toInt()
            val packTemp = decodePackTempCelsius(invokeNumericDoubleGetter(device, *runtimeMethodNames("m36")))
            // Cell voltages
            val cellVoltMax = invokeDoubleGetter(device, *runtimeMethodNames("m41"))
            val cellVoltMin = invokeDoubleGetter(device, *runtimeMethodNames("m42"))
            val sohDelta = readSohDeltaValue(device)
            val extraValues = readNumericGetterMap(device, RuntimeExtensionBridge.methodGroups("m44"))
            val sohFromExtra = extraValues["soh"]?.takeIf { it in 0.0..100.0 }?.toInt()
            val effectiveSoh = soh ?: sohFromExtra

            val previous = _vehicleSnapshot.value
            _batterySoh.value = effectiveSoh ?: _batterySoh.value
            _batteryTotalVoltage.value = totalVoltage ?: _batteryTotalVoltage.value
            _battery12vVoltage.value = voltage12v ?: _battery12vVoltage.value
            packTemp?.let { _vehicleSnapshot.value = _vehicleSnapshot.value.copy(batteryPackTemp = it) }
            // cellTempMax/Min are pre-decoded above; null means the BMS gave us nothing usable
            // — preserve the last good value rather than zeroing the displayed range.
            _batteryCellTempMax.value = cellTempMax ?: _batteryCellTempMax.value
            _batteryCellTempMin.value = cellTempMin ?: _batteryCellTempMin.value
            _batteryCellVoltageMax.value = cellVoltMax ?: _batteryCellVoltageMax.value
            _batteryCellVoltageMin.value = cellVoltMin ?: _batteryCellVoltageMin.value

            _vehicleSnapshot.value = previous.copy(
                batterySoh = effectiveSoh ?: previous.batterySoh,
                sohEstimated = false,
                batteryTotalVoltage = totalVoltage ?: previous.batteryTotalVoltage,
                battery12vVoltage = voltage12v ?: previous.battery12vVoltage,
                batteryPackTemp = packTemp ?: previous.batteryPackTemp,
                batteryCellTempMax = cellTempMax ?: previous.batteryCellTempMax,
                batteryCellTempMin = cellTempMin ?: previous.batteryCellTempMin,
                batteryCellVoltageMax = cellVoltMax ?: previous.batteryCellVoltageMax,
                batteryCellVoltageMin = cellVoltMin ?: previous.batteryCellVoltageMin,
            )
            publishSnapshot()

            if (
                soh != null || totalVoltage != null || voltage12v != null || packTemp != null ||
                cellTempMax != null || cellTempMin != null || cellVoltMax != null || cellVoltMin != null
                    || sohDelta != null || extraValues.isNotEmpty()
            ) {
                logInfoIfChanged(
                    "batterySnapshot",
                    "🔬 battery snapshot: soh=$soh sohExtra=$sohFromExtra totalVoltage=$totalVoltage 12v=$voltage12v packTemp=$packTemp " +
                        "cellTempMax=$cellTempMax cellTempMin=$cellTempMin " +
                        "cellVoltMax=$cellVoltMax cellVoltMin=$cellVoltMin " +
                        "sohDelta=${describeSohDeltaCandidate(sohDelta)}"
                )
                if (extraValues.isNotEmpty()) {
                    logInfoIfChanged(
                        "batterySnapshotExtra",
                        "🔬 battery snapshot extra: " +
                            extraValues.entries.joinToString(" ") { (key, value) ->
                                "$key=${String.format("%.3f", value)}"
                            }
                    )
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Battery snapshot failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun logMotorSnapshot(device: Any) {
        try {
            // Probe all plausible traction-motor RPM getters — log all raw values so
            // we can identify which ones exist on this firmware.
            val speedFrontSpecific = invokeIntGetter(device, *runtimeMethodNames("m20"))
            val speedRearSpecific  = invokeIntGetter(device, *runtimeMethodNames("m21"))
            val speedGeneric       = invokeIntGetter(device, *runtimeMethodNames("m22"))
            val motorPower         = invokeDoubleGetter(device, *runtimeMethodNames("m23"))
                ?.takeIf { it > -10000.0 }
            val m24 = runtimeMethodNames("m24")
            val motorAngle     = m24.getOrNull(0)?.let { invokeIntGetter(device, it) }
            val motorPosition  = m24.getOrNull(1)?.let { invokeIntGetter(device, it) }
            val motorDirection = m24.getOrNull(2)?.let { invokeIntGetter(device, it) }

            if (speedFrontSpecific != null || speedRearSpecific != null || speedGeneric != null || motorPower != null) {
                logInfoIfChanged(
                    "motorSnapshot",
                    "🔬 motor snapshot (MotorDevice): frontSpecific=$speedFrontSpecific " +
                        "rearSpecific=$speedRearSpecific generic=$speedGeneric " +
                        "motorPower=$motorPower angle=$motorAngle pos=$motorPosition dir=$motorDirection"
                )
            }

            // Use MotorDevice as an RPM source if Engine/Speed haven't populated it.
            // Only write a non-zero value — zero readings from this supplementary path
            // are spurious SDK poll artefacts and should not overwrite the main path's value.
            val frontRaw = speedFrontSpecific ?: speedGeneric
            val front = frontRaw?.takeIf { it > 0 }
            if (front != null && (_vehicleSnapshot.value.engineSpeedFront == null || _vehicleSnapshot.value.engineSpeedFront == 0)) {
                val rear = speedRearSpecific?.takeIf { it > 0 }
                    ?: if (speedRearSpecific == null) _vehicleSnapshot.value.engineSpeedRear else null
                _vehicleSnapshot.value = _vehicleSnapshot.value.copy(
                    engineSpeedFront = front,
                    engineSpeedRear  = rear ?: _vehicleSnapshot.value.engineSpeedRear ?: 0
                )
                Log.i(TAG, "🔬 RPM (MotorDevice applied): front=$front rear=$rear")
            }

            publishSnapshot()
        } catch (t: Throwable) {
            Log.w(TAG, "Motor snapshot failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun logLocationSnapshot(device: Any) {
        try {
            val locMethods = runtimeGroupMap("m45")
            val combinedLatLon = m45["combinedLatLon"]?.takeIf { it.isNotEmpty() }?.let { invokeGetter(device, it) }
            val latitude = invokeDoubleGetter(device, *locMethods["latitude"].orEmpty().toTypedArray())
            val longitude = invokeDoubleGetter(device, *locMethods["longitude"].orEmpty().toTypedArray())
            val altitude = invokeDoubleGetter(device, *locMethods["altitude"].orEmpty().toTypedArray())
            val gpsSpeed = invokeDoubleGetter(device, *locMethods["gpsSpeed"].orEmpty().toTypedArray())
            val visibleSatellites = invokeIntGetter(device, *locMethods["satellites"].orEmpty().toTypedArray())
            val fixPosition = invokeIntGetter(device, *locMethods["fixPosition"].orEmpty().toTypedArray())
            val orientation = invokeDoubleGetter(device, *locMethods["orientation"].orEmpty().toTypedArray())
            val hasUsefulLocation = latitude != null || longitude != null || altitude != null ||
                gpsSpeed != null || visibleSatellites != null || fixPosition != null || orientation != null
            val snap = _vehicleSnapshot.value
            val nextLatitude = latitude ?: snap.locationLatitude
            val nextLongitude = longitude ?: snap.locationLongitude
            val nextAltitude = altitude ?: snap.locationAltitude
            val nextGpsSpeed = gpsSpeed ?: snap.locationGpsSpeed
            val nextVisibleSatellites = visibleSatellites ?: snap.locationVisibleSatelliteNumber
            val nextFixPosition = fixPosition ?: snap.locationFixPosition
            val nextOrientation = orientation ?: snap.locationOrientation

            if (
                nextLatitude != snap.locationLatitude ||
                nextLongitude != snap.locationLongitude ||
                nextAltitude != snap.locationAltitude ||
                nextGpsSpeed != snap.locationGpsSpeed ||
                nextVisibleSatellites != snap.locationVisibleSatelliteNumber ||
                nextFixPosition != snap.locationFixPosition ||
                nextOrientation != snap.locationOrientation
            ) {
                _vehicleSnapshot.value = snap.copy(
                    locationLatitude = nextLatitude,
                    locationLongitude = nextLongitude,
                    locationAltitude = nextAltitude,
                    locationGpsSpeed = nextGpsSpeed,
                    locationVisibleSatelliteNumber = nextVisibleSatellites,
                    locationFixPosition = nextFixPosition,
                    locationOrientation = nextOrientation,
                )
                publishSnapshot()
            }

            if (hasUsefulLocation) {
                hasDirectLocationSignal = true
                logInfoIfChanged(
                    "locationSnapshot",
                    "🔬 location snapshot: lat=$latitude lon=$longitude alt=$altitude " +
                        "gpsSpeed=$gpsSpeed sats=$visibleSatellites fix=$fixPosition orientation=$orientation " +
                        "combined=${describeAny(combinedLatLon)}"
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Location snapshot failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /** Reads supplemental battery fields when this firmware exposes them. */
    private fun logSignalSnapshot(device: Any) {
        try {
            val signalFeatureValues = RuntimeExtensionBridge.labeledIntGroup("l04")
            val signalResults = mutableMapOf<String, Int?>()
            val getMethod = runCatching {
                device.javaClass.getMethod("get", IntArray::class.java, Class::class.java)
            }.getOrNull()

            if (getMethod != null && signalFeatureValues.isNotEmpty()) {
                signalFeatureValues.forEach { (label, featureId) ->
                    val raw = runCatching {
                        val result = getMethod.invoke(device,
                            intArrayOf(featureId), BYDAutoEventValue::class.java)
                        if (result != null) extractRawIntFromEventValue(result) else null
                    }.getOrNull()
                    signalResults[label] = raw
                }
                val nonNull = signalResults.filter { it.value != null }
                if (nonNull.isNotEmpty()) {
                    logInfoIfChanged("signalFeatureValues",
                        "🔬 SignalDevice get() results: " +
                            nonNull.entries.joinToString { "${it.key}=${it.value}" })
                    // If SOH came through, apply it
                    signalResults.entries.firstOrNull { it.key.startsWith("soh", ignoreCase = true) }?.value?.let { raw ->
                        val decoded = decodeStatisticPercentRaw(raw)?.takeIf { it in 50.0..110.0 }
                        if (decoded != null) {
                            _statisticBatterySoh.value = decoded
                            _vehicleSnapshot.update { it.copy(
                                batterySoh = decoded.toInt(), sohEstimated = false,
                                statisticBatterySoh = decoded
                            ) }
                            publishSnapshot()
                            Log.i(TAG, "✅ SOH from SignalDevice: ${decoded}%")
                        }
                    }
                } else {
                    logInfoIfChanged("signalFeatureValues",
                        "🔬 SignalDevice returned null for all mapped values")
                }
            }

            // ── Getter values — catches named getters if this device exposes them ─────
            val soh = invokeIntGetter(device, *runtimeMethodNames("m35"))
            if (soh != null && soh in 50..110) {
                _batterySoh.value = soh
                _vehicleSnapshot.update { it.copy(batterySoh = soh, sohEstimated = false) }
                publishSnapshot()
                Log.i(TAG, "✅ SOH from SignalDevice getter: $soh%")
            }
            if (soh != null || signalResults.values.any { it != null }) return
            logInfoIfChanged("signalSnapshot", "🔬 signal snapshot: all values returned null")
        } catch (t: Throwable) {
            Log.w(TAG, "Signal snapshot failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun refreshSystemLocationFallback() {
        if (!hasLocationPermission() || hasDirectLocationSignal) return

        // Battery: when parked/idle, drop the 1 Hz GPS fallback entirely (turns the GPS radio off).
        // It re-registers automatically below on the next call once the car is active again — the
        // main loop polls at 1s when the car is on, so GPS is back well before a trip starts.
        if (systemLocationUpdatesRegistered && isParkedIdle()) {
            runCatching { locationManager?.removeUpdates(systemLocationListener) }
            systemLocationUpdatesRegistered = false
            Log.i(TAG, "🔋 GPS fallback paused (parked/idle)")
            return
        }

        val manager = locationManager
            ?: (appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager)?.also {
                locationManager = it
            }
            ?: return

        if (!systemLocationUpdatesRegistered) {
            val providers = listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER,
            ).filter { provider ->
                runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false)
            }

            providers.forEach { provider ->
                runCatching {
                    manager.requestLocationUpdates(
                        provider,
                        1_000L,
                        0f,
                        systemLocationListener,
                        Looper.getMainLooper()
                    )
                }.onFailure { error ->
                    Log.w(TAG, "System location request failed for $provider: ${error.message}")
                }
            }

            systemLocationUpdatesRegistered = providers.isNotEmpty()
        }

        val bestLastKnown = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        ).mapNotNull { provider ->
            runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
        }.maxByOrNull { location -> location.time }

        val candidate = listOfNotNull(latestSystemLocation, bestLastKnown)
            .maxByOrNull { location -> location.time }
            ?: return

        latestSystemLocation = candidate
        applySystemLocationFallback(candidate, "refresh")
    }

    private fun applySystemLocationFallback(location: Location, source: String) {
        if (hasDirectLocationSignal) return

        val latitude = location.latitude.takeIf { it.isFinite() && it in -90.0..90.0 }
        val longitude = location.longitude.takeIf { it.isFinite() && it in -180.0..180.0 }
        val altitude = location.altitude.takeIf {
            it.isFinite() && (location.hasAltitude() || kotlin.math.abs(it) > 0.1)
        }
        val speedKmh = location.speed.toDouble()
            .takeIf { location.hasSpeed() && it.isFinite() && it >= 0.0 }
            ?.times(3.6)
        val heading = location.bearing.toDouble()
            .takeIf { location.hasBearing() && it.isFinite() }
        val accuracy = location.accuracy.toDouble()
            .takeIf { it.isFinite() && it >= 0.0 }
        val ageMs = (System.currentTimeMillis() - location.time).coerceAtLeast(0L)

        val snap = _vehicleSnapshot.value
        if (
            latitude != snap.locationLatitude ||
            longitude != snap.locationLongitude ||
            altitude != snap.locationAltitude ||
            speedKmh != snap.locationGpsSpeed ||
            heading != snap.locationOrientation
        ) {
            _vehicleSnapshot.value = snap.copy(
                locationLatitude = latitude,
                locationLongitude = longitude,
                locationAltitude = altitude,
                locationGpsSpeed = speedKmh,
                locationOrientation = heading,
            )
            publishSnapshot()
        }

        logVerboseInfoIfChanged(
            "systemLocationFallback",
        ) {
            "🔬 system location fallback[$source]: provider=${location.provider} " +
                "lat=$latitude lon=$longitude alt=$altitude speedKmh=$speedKmh " +
                "heading=$heading accuracy=$accuracy ageMs=$ageMs"
        }
    }

    /** Polls BMS fields that the car only pushes on change. */
    private fun pollAndUpdateCellFeatures(device: Any) {
        val spf = statisticPollFields
        val cellV10 = spf["cellV0"]?.let { pollStatisticFeature(device, it) }
        val cellV20 = spf["cellV1"]?.let { pollStatisticFeature(device, it) }
        val cellV30 = spf["cellV2"]?.let { pollStatisticFeature(device, it) }
        val cellV38 = spf["cellV3"]?.let { pollStatisticFeature(device, it) }
        val cellTLowRaw = spf["cellTLow"]?.let { pollStatisticFeature(device, it) }
        val cellTHighRaw = spf["cellTHigh"]?.let { pollStatisticFeature(device, it) }
        val socBmsRaw = spf["socBms"]?.let { pollStatisticFeature(device, it) }
        val cellTCandidate = spf["cellTCandidate"]?.let { pollStatisticFeature(device, it) }
        val cellTAvg = spf["cellTAvg"]?.let { pollStatisticFeature(device, it) }
        val sohRaw   = spf["soh"]?.let { pollStatisticFeature(device, it) }
        val socPanelRaw = spf["socPanel"]?.let { pollStatisticFeature(device, it) }
        val availPowerRaw = spf["availPower"]?.let { pollStatisticFeature(device, it) }
        logInfoIfChanged(
            "overdriveTempProbePoll",
            "🔬 Overdrive temp probe: lowRaw=$cellTLowRaw highRaw=$cellTHighRaw avgRaw=$cellTAvg " +
                "low=${decodeStatisticRawMinus40Temp(cellTLowRaw)} high=${decodeStatisticRawMinus40Temp(cellTHighRaw)} avg=${decodeStatisticRawMinus40Temp(cellTAvg)}"
        )

        // Scale to engineering units (same guards as dispatchStatisticFeatureEvent)
        val v10 = cellV10?.toDouble()?.let { if (it in 1000.0..8000.0) it / 1000.0 else null }
        val v20 = cellV20?.toDouble()?.let { if (it in 1000.0..8000.0) it / 1000.0 else null }
        val v30 = cellV30?.toDouble()?.let { if (it in 1000.0..8000.0) it / 1000.0 else null }
        val v38 = cellV38?.toDouble()?.let { if (it in 1000.0..8000.0) it / 1000.0 else null }
        val voltageCandidates = listOfNotNull(v10, v20, v30, v38)
        val vMin = v10 ?: voltageCandidates.minOrNull()
        val vMax = v30 ?: v38 ?: voltageCandidates.takeIf { it.size >= 2 }?.maxOrNull()
        // BYD statistic cell temperatures use raw - 40 = °C:
        // 0x44700010=low, 0x44700020=high, 0x44700038=average.
        val tMin = decodeStatisticRawMinus40Temp(cellTLowRaw)
        val tMax = decodeStatisticRawMinus40Temp(cellTHighRaw)
        val tAvg = decodeStatisticRawMinus40Temp(cellTAvg)
        val tCandidate = cellTCandidate?.toDouble()?.let { resolveStatisticTempCelsius(it) }

        // Direct named-getter fallback: for firmwares where the statistic device does not
        // surface temperature via feature-ID events or polls (e.g. Seal Excellence), try
        // common reflection-based getter names directly on the device object.
        val tAvgFromGetter = if (tAvg == null && tMax == null && tMin == null) {
            val raw = invokeNumericDoubleGetter(
                device,
                "getBatteryTemperature", "getTemperatureValue", "getBatteryTempValue",
                "getCellTemperatureAvg", "getCellTempAvg", "getBatteryAvgTemperature",
                "getBmsCellTemperature", "getHvBatteryTemperature", "getBmsBatteryTemp",
                "getAvgTemperature", "getTemperature", "getBatCellTempAvg",
                "getBmsTemperature", "getCellAvgTemp", "getHvBatteryTemp"
            )
            raw?.let { r ->
                resolveStatisticTempCelsius(r).also { resolved ->
                    if (resolved != null) {
                        Log.i(TAG, "🌡️ statistic direct-getter temperature: raw=$r → ${String.format("%.1f", resolved)}°C")
                    }
                }
            }
        } else null
        val effectiveTAvg = tAvg ?: tAvgFromGetter
        val soh = sohRaw?.let { decodeStatisticPercentRaw(it) }?.takeIf { it in 50.0..110.0 }
        val socB = socBmsRaw?.let {
            decodeStatisticPercentRaw(it, _vehicleSnapshot.value.statisticElecPercentageValue ?: _instrumentBatteryPercent.value)
        }
        val socPanel = socPanelRaw?.let {
            decodeStatisticPercentRaw(it, _vehicleSnapshot.value.statisticElecPercentageValue ?: _instrumentBatteryPercent.value)
        }
        val availPower = availPowerRaw?.toDouble()?.let { if (it in -1000.0..5000.0) it / 10.0 else null }

        if (vMin != null || vMax != null || tMin != null || effectiveTAvg != null || tMax != null || soh != null || socB != null || socPanel != null || availPower != null) {
            if (vMin != null) _statisticCellVoltageMin.value = vMin
            if (vMax != null) _statisticCellVoltageMax.value = vMax
            if (tMin != null) _statisticCellTempMin.value = tMin
            if (effectiveTAvg != null) _statisticCellTempAvg.value = effectiveTAvg
            if (tMax != null) _statisticCellTempMax.value = tMax
            if (soh != null) _statisticBatterySoh.value = soh
            if (socB != null) _statisticSocBms.value = filterBmsSocGlitch(socB)
            if (socPanel != null && _vehicleSnapshot.value.statisticSocBatteryPct == null) {
                _vehicleSnapshot.value = _vehicleSnapshot.value.copy(statisticSocBatteryPct = socPanel)
            }
            if (availPower != null) _statisticAvailPower.value = availPower
            _vehicleSnapshot.update { snap ->
                snap.copy(
                    batteryCellVoltageMin = vMin ?: snap.batteryCellVoltageMin,
                    batteryCellVoltageMax = vMax ?: snap.batteryCellVoltageMax,
                    batteryCellTempMin = snap.batteryCellTempMin ?: tMin?.toInt(),
                    batteryCellTempMax = snap.batteryCellTempMax ?: tMax?.toInt(),
                    batterySoh = soh?.toInt() ?: snap.batterySoh,
                    sohEstimated = if (soh != null) false else snap.sohEstimated,
                    statisticSocBatteryPct = socPanel ?: snap.statisticSocBatteryPct,
                )
            }
            publishSnapshot()
            logInfoIfChanged(
                "statisticPoll",
                "🔬 poll: cellVMin=$vMin cellVMax=$vMax cellTMin=$tMin cellTAvg=$effectiveTAvg cellTMax=$tMax " +
                    "cellTCandidate=$tCandidate " +
                    "sohRaw=$sohRaw soh=$soh socBms=$socB socPanel=$socPanel availPowerRaw=$availPowerRaw availPower=$availPower"
            )
            logInfoIfChanged(
                "statisticVoltageValues",
                "🔬 statistic voltage values: a=$v10 b=$v20 c=$v30 d=$v38"
            )
            logInfoIfChanged(
                "statisticTempValues",
                "🔬 statistic temp values: min=$tMin max=$tMax soc=$socB value=$tCandidate avg=$effectiveTAvg"
            )
        } else {
            // All mapped values returned null — BMS likely in low-power mode.
            // Log the raw integer values before scaling so we can detect any non-null return.
            val rawLow = cellTLowRaw
            val rawHigh = cellTHighRaw
            val rawAvg = cellTAvg
            logInfoIfChanged(
                "statisticPollNull",
                "🔬 poll: all null — BMS in low-power? rawLow=$rawLow rawHigh=$rawHigh rawTAvg=$rawAvg " +
                    "rawSoh=$sohRaw rawSocBms=$socBmsRaw rawVMin=${cellV10 ?: cellV20}"
            )
        }
    }

    /**
     * Synchronously poll a single Statistic feature.
     * Tries the batch getter first, then the single-value getter as fallback.
     * Returns the raw integer or null on sentinel/error.
     */
    private fun pollStatisticFeature(device: Any, featureId: Int): Int? {
        // Signature 1: get(int[], Class<BYDAutoEventValue>) → BYDAutoEventValue
        val getIntArrayClass = runCatching {
            device.javaClass.getMethod("get", IntArray::class.java, Class::class.java)
        }.getOrNull()
        if (getIntArrayClass != null) {
            // Primary: BYDAutoEventValue wrapper — works for most features
            try {
                val result = getIntArrayClass.invoke(device, intArrayOf(featureId), BYDAutoEventValue::class.java)
                if (result != null) {
                    val extracted = extractRawIntFromEventValue(result)
                    if (extracted != null) return extracted
                }
            } catch (e: Exception) {
                Log.w(TAG, "poll get(int[],BYDAutoEventValue) failed: ${e.message}")
            }
            // Fallback: some firmwares reject BYDAutoEventValue for certain features and expect
            // a primitive wrapper instead (triggers "Param type is invalid" in HAL logs).
            for (typeClass in listOf(Int::class.javaObjectType, Float::class.javaObjectType, Double::class.javaObjectType)) {
                try {
                    val result = getIntArrayClass.invoke(device, intArrayOf(featureId), typeClass) ?: continue
                    val extracted = when (result) {
                        is Int    -> result.takeIf { it > -999_000_000 }
                        is Float  -> result.takeIf { it > -999_000_000f }?.toInt()
                        is Double -> result.takeIf { it > -999_000_000.0 }?.toInt()
                        else      -> extractRawIntFromEventValue(result)
                    }
                    if (extracted != null) return extracted
                } catch (_: Exception) {}
            }
        }
        // Signature 2: get(int) → BYDAutoEventValue
        try {
            val getMethod = device.javaClass.getMethod("get", Int::class.javaPrimitiveType)
            val result = getMethod.invoke(device, featureId)
            if (result != null) {
                val extracted = extractRawIntFromEventValue(result)
                if (extracted != null) return extracted
            }
        } catch (_: NoSuchMethodException) {
        } catch (e: Exception) {
            Log.w(TAG, "poll get(int) failed: ${e.message}")
        }
        return null
    }

    /**
     * Registers a targeted listener using the 2-arg overload exposed by this firmware.
     */
    private fun trySubscribeStatisticFeatures(device: Any) {
        val featureIds = RuntimeExtensionBridge.intGroup("i05")
        if (featureIds.isEmpty()) {
            Log.i(TAG, "ℹ️ No statistic subscription set available — skipping subscription")
            return
        }

        // Get the listener class directly from the 2-arg registerListener method's parameter type.
        // This bypasses Class.forName classloader isolation — it always works if the method exists.
        val registerMethod2 = device.javaClass.methods.firstOrNull { m ->
            m.name == "registerListener" &&
                m.parameterTypes.size == 2 &&
                m.parameterTypes[1] == IntArray::class.java
        }

        if (registerMethod2 != null) {
            val listenerClass = registerMethod2.parameterTypes[0]
            Log.d(TAG, "registerListener 2-arg found, listenerClass=${listenerClass.name}, isInterface=${listenerClass.isInterface}")
            if (listenerClass.isInterface) {
                // Can proxy interfaces at runtime
                try {
                    val seenSubMethodNames = mutableSetOf<String>()
                    val seenDumpedClasses = mutableSetOf<String>()
                    val proxy = Proxy.newProxyInstance(
                        listenerClass.classLoader,
                        arrayOf(listenerClass),
                    ) { _, proxyMethod, args ->
                        val name = proxyMethod.name
                        when {
                            name == "equals"   -> return@newProxyInstance false
                            name == "hashCode" -> return@newProxyInstance 0
                            name == "toString" -> return@newProxyInstance "BmsFeatureListener"
                            name.startsWith("on") -> {
                                // Log the actual callback name on first occurrence so we know what the car uses
                                seenSubMethodNames.add(name)
                                val arg0 = args?.getOrNull(0)
                                val arg1 = args?.getOrNull(1)

                                // Form A: (featureId: Int, value: Any) — e.g. onDataEventChanged
                                val fid = arg0 as? Int
                                if (fid != null && arg1 != null) {
                                    val rawNumber = extractRawEventNumber(arg1)
                                    if (rawNumber != null) {
                                        dispatchStatisticFeatureEvent(fid, rawNumber)
                                        // The raw feature listener already emits throttled, concise summary logs.
                                    } else {
                                        // Log available fields once for diagnostics
                                        val cn1 = arg1.javaClass.name
                                        if (seenDumpedClasses.add(cn1)) {
                                            val methods = arg1.javaClass.methods.joinToString { m ->
                                                val rv = try { m.invoke(arg1) } catch (_: Exception) { "?" }
                                                "${m.name}()=$rv"
                                            }
                                            Log.i(TAG, "🔎 BYDAutoEventValue methods: $methods")
                                        }
                                    }
                                }
                                // Form B: (event: BYDAutoEvent) — e.g. onDataChanged;
                                // extract featureId + value from the event object
                                else if (arg0 != null && arg1 == null) {
                                    val eventFid = extractFeatureIdFromEvent(arg0)
                                    val eventVal = extractRawEventNumber(arg0)
                                    if (eventFid != null && eventVal != null) {
                                        dispatchStatisticFeatureEvent(eventFid, eventVal)
                                        // The raw feature listener already emits throttled, concise summary logs.
                                    } else {
                                        // Log available fields once for diagnostics
                                        val cn0 = arg0.javaClass.name
                                        if (seenDumpedClasses.add(cn0)) {
                                            val methods = arg0.javaClass.methods.joinToString { m ->
                                                val rv = try { m.invoke(arg0) } catch (_: Exception) { "?" }
                                                "${m.name}()=$rv"
                                            }
                                            Log.i(TAG, "🔎 BYDAutoEvent methods: $methods")
                                        }
                                    }
                                }
                            }
                        }
                        null
                    }
                    registerMethod2.invoke(device, proxy, featureIds)
                    listenerReferences += proxy
                    Log.i(TAG, "✅ Statistic feature listener subscribed via registerListener(${listenerClass.simpleName}, int[])")
                    return
                } catch (e: Exception) {
                    Log.w(TAG, "registerListener(${listenerClass.simpleName}, int[]) proxy failed: ${e.message}")
                }
            } else {
                // Abstract class — log methods to find the right abstract callback name
                val methods = listenerClass.declaredMethods.joinToString { it.name }
                Log.i(TAG, "ℹ️ Listener is abstract class ${listenerClass.simpleName}, methods: $methods")
            }
        } else {
            Log.i(TAG, "ℹ️ No 2-arg registerListener found on StatisticDevice")
        }

        Log.i(TAG, "ℹ️ IBYDAutoListener subscribe failed — relying on push-only events from 1-arg listener")
    }

    private fun trySubscribeMqttFeatures(device: Any) {
        val featureIds = RuntimeExtensionBridge.intGroup("i06")
        if (featureIds.isEmpty()) {
            Log.i(TAG, "ℹ️ No MQTT subscription set available — skipping subscription")
            return
        }

        val registerMethods2 = device.javaClass.methods.filter { m ->
            m.name == "registerListener" &&
                m.parameterTypes.size == 2 &&
                m.parameterTypes[1] == IntArray::class.java
        }

        if (registerMethods2.isNotEmpty()) {
            registerMethods2.forEach { registerMethod2 ->
                val listenerClass = registerMethod2.parameterTypes[0]
                Log.d(TAG, "Mqtt registerListener 2-arg found, listenerClass=${listenerClass.name}, isInterface=${listenerClass.isInterface}")
                if (listenerClass.isInterface) {
                    try {
                        val proxy = Proxy.newProxyInstance(
                            listenerClass.classLoader,
                            arrayOf(listenerClass),
                        ) { _, proxyMethod, args ->
                            val name = proxyMethod.name
                            when {
                                name == "equals"   -> return@newProxyInstance false
                                name == "hashCode" -> return@newProxyInstance 0
                                name == "toString" -> return@newProxyInstance "MqttFeatureListener"
                                name.startsWith("on") -> {
                                    val arg0 = args?.getOrNull(0)
                                    val arg1 = args?.getOrNull(1)

                                    val fid = (arg0 as? Int) ?: arg0?.let { extractFeatureIdFromEvent(it) }
                                    val payloadSource = arg1 ?: arg0
                                    val payload = payloadSource?.let { extractEventStructuredPayload(it) }
                                    val rawNumber = payloadSource?.let { extractRawEventNumber(it) }
                                    if (ENABLE_VERBOSE_RAW_EVENT_LOGS && fid != null) {
                                        logInfoIfChanged(
                                            "mqtt-sub-$fid",
                                            "🔬 Mqtt event raw=${rawNumber ?: "n/a"} " +
                                                "payload=${payload?.let { compactDiagnosticDetail(it, 240) } ?: "n/a"} " +
                                                "detail=${payloadSource?.let { compactDiagnosticDetail(describeAny(it), 240) } ?: "null"}"
                                        )
                                    } else if (ENABLE_VERBOSE_RAW_EVENT_LOGS && (payload != null || payloadSource != null)) {
                                        logInfoIfChanged(
                                            "mqtt-sub-generic",
                                            "🔬 Mqtt event [unknown] raw=${rawNumber ?: "n/a"} " +
                                                "payload=${payload?.let { compactDiagnosticDetail(it, 240) } ?: "n/a"} " +
                                                "detail=${compactDiagnosticDetail(describeAny(payloadSource), 240)}"
                                        )
                                    }
                                }
                            }
                            null
                        }
                        registerMethod2.invoke(device, proxy, featureIds)
                        listenerReferences += proxy
                        Log.i(TAG, "✅ Mqtt feature listener subscribed via registerListener(${listenerClass.simpleName}, int[])")
                    } catch (e: Exception) {
                        Log.w(TAG, "Mqtt registerListener(${listenerClass.simpleName}, int[]) proxy failed: ${e.message}")
                    }
                } else {
                    val methods = listenerClass.declaredMethods.joinToString { method ->
                        "${method.name}(${method.parameterTypes.joinToString { it.simpleName }})"
                    }
                    Log.i(TAG, "ℹ️ Mqtt listener is abstract class ${listenerClass.simpleName}, methods: $methods")
                }
            }
        } else {
            Log.i(TAG, "ℹ️ No 2-arg registerListener found on MqttDevice")
        }

        Log.i(TAG, "ℹ️ Mqtt subscribe failed — relying on push-only events from 1-arg listener")
    }

    /** Extract raw integer from BYDAutoEventValue, filtering BYD sentinels. */
    private fun extractRawIntFromEventValue(result: Any): Int? {
        // Try getIntValue first (raw integer — most stat fields are int-encoded)
        for (getter in listOf("getIntValue", "intValue", "getInt")) {
            try {
                val v = result.javaClass.getMethod(getter).invoke(result) as? Int ?: continue
                if (v <= -999_000_000) return null  // BYD sentinel
                return v
            } catch (_: NoSuchMethodException) {} catch (_: Exception) {}
        }
        // Try double (some fields)
        for (getter in listOf("getDoubleValue", "doubleValue", "getDouble")) {
            try {
                val v = result.javaClass.getMethod(getter).invoke(result) as? Double ?: continue
                if (v <= -999_000_000.0) return null
                return v.toInt()
            } catch (_: NoSuchMethodException) {} catch (_: Exception) {}
        }
        return null
    }

    /**
     * Reads BYDAutoDtcDevice — Diagnostic Trouble Codes device.
     * On some firmware versions this also exposes live battery data.
     */
    private fun logDtcSnapshot(device: Any) {
        try {
            val dtcMethods = runtimeGroupMap("m46")
            val dtcFaultCode = invokeGetter(device, *dtcMethods["faultCode"].orEmpty().toTypedArray())
            val hvVoltage    = invokeIntGetter(device, *dtcMethods["hvVoltage"].orEmpty().toTypedArray())
            val soh          = invokeIntGetter(device, *dtcMethods["soh"].orEmpty().toTypedArray())
            if (dtcFaultCode != null || hvVoltage != null || soh != null) {
                logInfoIfChanged(
                    "dtcSnapshot",
                    "📸 dtc snapshot: faultCode=${describeAny(dtcFaultCode)} hvVoltage=$hvVoltage soh=$soh"
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Dtc snapshot failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun logEngineSnapshot(device: Any) {
        try {
            // BYD occasionally emits implausible enginePower values (observed: 3095 kW)
            // that are physically impossible — the highest-power BYD production model (DiLink 3) is
            // under 400 kW combined. These appear to be uninitialized or sentinel Int values
            // from the car (e.g. the device returning cumulative Wh×10 instead of instantaneous
            // kW on a firmware state transition, or reading an uninitialised buffer).
            // Strategy: read the raw value, log full context for diagnosis, and REJECT the
            // reading by falling back to the last known-good value when |raw| > 500 kW.
            // 500 kW provides generous headroom for future high-power variants (Yangwang U9, etc).
            // This is NOT a display cap — it's rejecting obviously corrupt car reads so that
            // TripEntity.maxPower is never poisoned by a single bad packet.
            val enginePowerRaw = invokeIntGetter(device, *runtimeMethodNames("m23"))
            val prev = _vehicleSnapshot.value
            val enginePower: Int? = if (enginePowerRaw != null && kotlin.math.abs(enginePowerRaw) > 500) {
                // Value is implausible — log full drivetrain context and fall back to last known value.
                Log.w(
                    TAG,
                    "⚠️ enginePower raw=$enginePowerRaw REJECTED (>500 kW, likely car sentinel) — " +
                        "falling back to prevEnginePower=${prev.enginePower}. Context: " +
                        "gear=${prev.gear} speed=${prev.directSpeedKmh} " +
                        "engineSpeedFront=${prev.engineSpeedFront} " +
                        "engineSpeedRear=${prev.engineSpeedRear} " +
                        "carOn=${prev.powerStateRaw} totalElecCon=${prev.statisticTotalElecConValue}"
                )
                prev.enginePower   // keep last known-good value
            } else {
                enginePowerRaw     // valid reading — use as-is (may be null, negative for regen, etc.)
            }
            // Prefer dedicated axle getters first. Generic fallback (m22) is kept only
            // because on some AWD firmwares it represents a primary/active motor rather
            // than a clean "front axle" signal.
            val engineSpeedFrontSpecificRaw = invokeIntGetter(device, *runtimeMethodNames("m20"))
            val engineSpeedRearRaw          = invokeIntGetter(device, *runtimeMethodNames("m21"))
            val engineSpeedGenericRaw       = invokeIntGetter(device, *runtimeMethodNames("m22"))
            val engineSpeedFrontRaw = engineSpeedFrontSpecificRaw ?: engineSpeedGenericRaw
            // Rule: if the car is moving and the getter returns 0, that is a spurious SDK
            // poll result (the getter updates slower than the 50 ms poll interval).
            // Preserve the last known value in that case. Only write 0 when the car is
            // genuinely stopped (speed < 1 km/h), which also covers cold-boot / pre-drive.
            // 8191/16383/32767/65535 are CAN "signal unavailable" sentinel values (all-ones
            // patterns at various bit widths); treat them the same as 0 — write 0 when
            // stopped, preserve last known when moving.
            val BYD_RPM_SENTINELS = setOf(8191, 16383, 32767, 65535)
            val directSpeedKmh = _vehicleSnapshot.value.directSpeedKmh
            val prevFront = _vehicleSnapshot.value.engineSpeedFront ?: 0
            val engineSpeedFront = when {
                engineSpeedFrontRaw == null                        -> _vehicleSnapshot.value.engineSpeedFront
                engineSpeedFrontRaw in BYD_RPM_SENTINELS          -> if (directSpeedKmh < 1.0) 0 else prevFront
                engineSpeedFrontRaw > 0                           -> engineSpeedFrontRaw
                directSpeedKmh < 1.0                              -> 0
                else                                              -> prevFront
            }
            val prevRear = _vehicleSnapshot.value.engineSpeedRear ?: 0
            val engineSpeedRear = when {
                engineSpeedRearRaw == null                        -> _vehicleSnapshot.value.engineSpeedRear
                engineSpeedRearRaw in BYD_RPM_SENTINELS           -> if (directSpeedKmh < 1.0) 0 else prevRear
                engineSpeedRearRaw > 0                            -> engineSpeedRearRaw
                directSpeedKmh < 1.0                              -> 0
                else                                              -> prevRear
            }
            val m47 = runtimeGroupMap("m47")
            val engineCoolantTemp = invokeIntGetter(
                device, *m47["coolant"].orEmpty().toTypedArray()
            )?.takeIf { it in -50..200 }
            // oilLevel=255 (0xFF) is also a sentinel meaning "no sensor"
            val oilLevelRaw = invokeIntGetter(device, *m47["oil"].orEmpty().toTypedArray())
            val oilLevel = oilLevelRaw?.takeIf { it != 255 }

            _vehicleSnapshot.value = _vehicleSnapshot.value.copy(
                // Yield to a fresh daemon power push (instant ENGINE_POWER event) — keep its value
                // instead of overwriting with this slower poll getter.
                enginePower = if (SystemClock.elapsedRealtime() - lastPowerPushElapsedMs < SPEED_EVENT_FRESHNESS_MS)
                    _vehicleSnapshot.value.enginePower else enginePower,
                // Yield to fresh daemon RPM pushes (realtime); fall back to this getter poll otherwise.
                engineSpeedFront = if (SystemClock.elapsedRealtime() - lastRpmPushElapsedMs < SPEED_EVENT_FRESHNESS_MS)
                    _vehicleSnapshot.value.engineSpeedFront else engineSpeedFront,
                engineSpeedRear = if (SystemClock.elapsedRealtime() - lastRpmPushElapsedMs < SPEED_EVENT_FRESHNESS_MS)
                    _vehicleSnapshot.value.engineSpeedRear else engineSpeedRear,
            )
            publishSnapshot()

            if (enginePower != null || engineSpeedFront != 0 || engineSpeedRear != null) {
                logVerboseInfoIfChanged("engineSnapshot") {
                    "🔬 engine snapshot: enginePower=$enginePower " +
                        "frontSpecificRaw=$engineSpeedFrontSpecificRaw genericRaw=$engineSpeedGenericRaw " +
                        "frontRaw=$engineSpeedFrontRaw front=$engineSpeedFront " +
                        "rearRaw=$engineSpeedRearRaw rear=$engineSpeedRear " +
                        "coolantTemp=$engineCoolantTemp oilLevel=$oilLevel"
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Engine snapshot failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun logStatisticSnapshot(device: Any) {
        try {

            val m48 = runtimeGroupMap("m48")
            // getElecPercentageValue / getSOCBatteryPercentage are stable, non-obfuscated BYD
            // SDK names. The bridge group can resolve empty (or to a getter that returns 0)
            // on firmwares it was not mapped for — notably DM-i PHEV variants — which nulls
            // out SoC entirely. Fall back to the literal getters, treating a non-positive
            // bridge result as "no reading" so the dashboard SoC stays populated. A genuinely
            // empty battery still reads 0 from the literal getters, so EVs are unaffected.
            val elecPercentage = invokeDoubleGetter(device, *m48["elecPercentage"].orEmpty().toTypedArray())
                ?.takeIf { it > 0.0 }
                ?: invokeDoubleGetter(device, "getElecPercentageValue")?.takeIf { it > 0.0 }
                ?: invokeIntGetter(device, "getSOCBatteryPercentage")?.toDouble()
            val elecRange = invokeIntGetter(device, *m48["elecRange"].orEmpty().toTypedArray())

            // Fuel values: 0xFF (255) = no sensor / EV-only mode; filter it out
            val fuelPercentageRaw = invokeIntGetter(device, *m48["fuelPercentage"].orEmpty().toTypedArray())
            val fuelPercentage = fuelPercentageRaw?.takeIf { it in 0..100 }

            // Fuel range: 0x7FE (2046) and similar edge values are sentinels; >1000 km is unrealistic
            val fuelRangeRaw = invokeIntGetter(device, *m48["fuelRange"].orEmpty().toTypedArray())
            val fuelRange = fuelRangeRaw?.takeIf { it in 0..1000 }

            val totalElecCon = invokeDoubleGetter(device, *m48["totalElecCon"].orEmpty().toTypedArray())
            val totalElecConPhm = invokeDoubleGetter(device, *m48["totalElecConPhm"].orEmpty().toTypedArray())
                ?.takeIf { it in 5.0..50.0 }

            // Total odometer — try multiple getter names; log raw value for debugging.
            val totalMileageRaw = invokeIntGetter(device, *m48["totalMileage"].orEmpty().toTypedArray())
            val totalMileage = totalMileageRaw?.takeIf { it > 0 }   // 0 = not yet populated
            val mileageNumMethod = m48["mileageNumber"]?.firstOrNull().orEmpty()
            val mileageNumber0 = mileageNumMethod.takeIf { it.isNotEmpty() }
                ?.let { invokeIndexedDoubleGetter(device, 0, it) }
                ?.takeIf { it.isFinite() && it > 0.0 && it < 9_999_999.0 }
            val mileageNumber1 = mileageNumMethod.takeIf { it.isNotEmpty() }
                ?.let { invokeIndexedDoubleGetter(device, 1, it) }
                ?.takeIf { it.isFinite() && it > 0.0 && it < 9_999_999.0 }
            val mileageNumber2 = mileageNumMethod.takeIf { it.isNotEmpty() }
                ?.let { invokeIndexedDoubleGetter(device, 2, it) }
                ?.takeIf { it.isFinite() && it > 0.0 && it < 9_999_999.0 }
            val mileageNumber3 = mileageNumMethod.takeIf { it.isNotEmpty() }
                ?.let { invokeIndexedDoubleGetter(device, 3, it) }
                ?.takeIf { it.isFinite() && it > 0.0 && it < 9_999_999.0 }
            val totalMileageDecimal = sequenceOf(
                mileageNumber2?.div(10.0),
                mileageNumber0,
                mileageNumber1,
                mileageNumber3,
            ).firstOrNull { candidate ->
                val total = totalMileage?.toDouble()
                candidate != null && candidate.isFinite() && candidate > 1000.0 &&
                    (total == null || abs(candidate - total) <= 2.0)
            }
            val drivingTime = invokeDoubleGetter(device, *m48["drivingTime"].orEmpty().toTypedArray())

            // Water/coolant temperature: nonsense values like -10011 are sentinels; valid range -50..200°C
            val waterTemperatureRaw = invokeIntGetter(device, *m48["waterTemp"].orEmpty().toTypedArray())
            val waterTemperature = waterTemperatureRaw?.takeIf { it in -50..200 }
            val waterTempMeterNames = m48["waterTempMeter"].orEmpty().toTypedArray()
            val waterTempMeterPercentRaw = invokeDoubleGetter(device, *waterTempMeterNames)
                ?: invokeIntGetter(device, *waterTempMeterNames)?.toDouble()
            val waterTempMeterPercent = decodeMeterPercentRaw(
                waterTempMeterPercentRaw,
                elecPercentage ?: _instrumentBatteryPercent.value
            )

            // ── PHEV-specific getters ────────────────────────────────────────────────
            val avgFuelCon = invokeDoubleGetter(device, *m48["avgFuelCon"].orEmpty().toTypedArray())
            val instantFuelCon = invokeDoubleGetter(device, *m48["instantFuelCon"].orEmpty().toTypedArray())
            // 0xFFFFF (1048575) = BYD sentinel; scaled by 0.1 gives 104857.5 — filter both forms
            val totalFuelCon = invokeDoubleGetter(device, *m48["totalFuelCon"].orEmpty().toTypedArray())
                ?.takeIf { it < 10000.0 }   // >10000 L total fuel is unrealistic; 104857.5 = sentinel
            // 0xFFFFF (1048575) = BYD "not available" sentinel for mileage counters
            val evMileage = invokeIntGetter(device, *m48["evMileage"].orEmpty().toTypedArray())
                ?.takeIf { it in 0..999999 }
            val hevMileage = invokeIntGetter(device, *m48["hevMileage"].orEmpty().toTypedArray())
                ?.takeIf { it in 0..999999 }
            val socBatteryPct = (invokeDoubleGetter(device, *m48["socBattery"].orEmpty().toTypedArray())
                ?.takeIf { it > 0.0 }
                ?: invokeIntGetter(device, "getSOCBatteryPercentage")?.toDouble())
                ?.takeIf { it in 0.0..100.0 }
            val remainingBatteryPowerRaw = invokeIntGetter(device, *m48["remainBattery"].orEmpty().toTypedArray())
            // ── Synchronous poll for slow-changing statistic battery fields ───────
            // The Statistic device only pushes some values when they change.
            // At steady state (constant charge current) it may not fire for minutes.
            // We poll synchronously here so refreshSnapshots() (called periodically)
            // keeps the value fresh even without push events.
            val spf = statisticPollFields
            val cellV10Raw = spf["cellV0"]?.let { pollStatisticFeature(device, it) }
            val cellV20Raw = spf["cellV1"]?.let { pollStatisticFeature(device, it) }
            val cellV30Raw = spf["cellV2"]?.let { pollStatisticFeature(device, it) }
            val cellV38Raw = spf["cellV3"]?.let { pollStatisticFeature(device, it) }
            val cellTLowRaw = spf["cellTLow"]?.let { pollStatisticFeature(device, it) }
            val cellTHighRaw = spf["cellTHigh"]?.let { pollStatisticFeature(device, it) }
            val socBmsRaw = spf["socBms"]?.let { pollStatisticFeature(device, it) }
            val cellT30Raw = spf["cellTCandidate"]?.let { pollStatisticFeature(device, it) }
            val cellTAvgRaw = spf["cellTAvg"]?.let { pollStatisticFeature(device, it) }
            @Suppress("UNUSED_VARIABLE") val cellTMaxRaw: Int? = null
            val sohRaw      = spf["soh"]?.let { pollStatisticFeature(device, it) }
            val socPanelRaw = spf["socPanel"]?.let { pollStatisticFeature(device, it) }
            val availPowerRaw = spf["availPower"]?.let { pollStatisticFeature(device, it) }
            logInfoIfChanged(
                "overdriveTempProbeSnapshot",
                "🔬 Overdrive temp snapshot: lowRaw=$cellTLowRaw highRaw=$cellTHighRaw avgRaw=$cellTAvgRaw " +
                    "low=${decodeStatisticRawMinus40Temp(cellTLowRaw)} high=${decodeStatisticRawMinus40Temp(cellTHighRaw)} avg=${decodeStatisticRawMinus40Temp(cellTAvgRaw)}"
            )
            val extraStatisticCandidates = RuntimeExtensionBridge.intGroup("i07").toList().mapNotNull { fid ->
                describeExtraStatisticValue(pollStatisticFeature(device, fid))
            }
            val gbAlgorithmEstimate = RuntimeExtensionBridge.intGroup("i08")
                .firstOrNull()
                ?.let { invokeFeatureGetter(device, it) }

            val cellV10 = cellV10Raw?.let { v ->
                val d = v.toDouble()
                if (d in 1000.0..8000.0) d / 1000.0 else null
            }
            val cellV20 = cellV20Raw?.let { v ->
                val d = v.toDouble()
                if (d in 1000.0..8000.0) d / 1000.0 else null
            }
            val cellV30 = cellV30Raw?.let { v ->
                val d = v.toDouble()
                if (d in 1000.0..8000.0) d / 1000.0 else null
            }
            val cellV38 = cellV38Raw?.let { v ->
                val d = v.toDouble()
                if (d in 1000.0..8000.0) d / 1000.0 else null
            }
            val voltageCandidates = listOfNotNull(cellV10, cellV20, cellV30, cellV38)
            val cellVMin = cellV10 ?: voltageCandidates.minOrNull()
            val cellVMax = cellV30 ?: cellV38 ?: voltageCandidates.takeIf { it.size >= 2 }?.maxOrNull()
            // BYD statistic cell temperatures use raw - 40 = °C:
            // 0x44700010=low, 0x44700020=high, 0x44700038=average.
            val cellTMin = decodeStatisticRawMinus40Temp(cellTLowRaw)
            val cellTMax = decodeStatisticRawMinus40Temp(cellTHighRaw)
            val cellT30 = cellT30Raw?.let { v -> resolveStatisticTempCelsius(v.toDouble()) }
            val cellTAvg = decodeStatisticRawMinus40Temp(cellTAvgRaw)

            // Direct named-getter fallback when feature-ID polls return no temperature data.
            val cellTAvgFromGetter = if (cellTAvg == null && cellTMax == null && cellTMin == null) {
                val raw = invokeNumericDoubleGetter(
                    device,
                    "getBatteryTemperature", "getTemperatureValue", "getBatteryTempValue",
                    "getCellTemperatureAvg", "getCellTempAvg", "getBatteryAvgTemperature",
                    "getBmsCellTemperature", "getHvBatteryTemperature", "getBmsBatteryTemp",
                    "getAvgTemperature", "getTemperature", "getBatCellTempAvg",
                    "getBmsTemperature", "getCellAvgTemp", "getHvBatteryTemp"
                )
                raw?.let { r ->
                    resolveStatisticTempCelsius(r).also { resolved ->
                        if (resolved != null) {
                            Log.i(TAG, "🌡️ statistic direct-getter temperature: raw=$r → ${String.format("%.1f", resolved)}°C")
                        }
                    }
                }
            } else null
            val effectiveCellTAvg = cellTAvg ?: cellTAvgFromGetter

            val socBmsFrom447 = socBmsRaw?.let { v ->
                decodeStatisticPercentRaw(v, elecPercentage ?: _instrumentBatteryPercent.value)
            }?.takeIf { it in 0.0..100.0 }
            val soh = sohRaw?.let { v ->
                decodeStatisticPercentRaw(v)
            }?.takeIf { it in 50.0..110.0 }
            val panelPercentCandidate = elecPercentage ?: _instrumentBatteryPercent.value
            val socPanelCandidate = socBatteryPct ?: socPanelRaw?.let { v ->
                decodeStatisticPercentRaw(v, panelPercentCandidate)
            }
            val remainingBatteryPowerBms = remainingBatteryPowerRaw
                ?.let { decodeStatisticPercentRaw(it, socBmsFrom447 ?: elecPercentage ?: _instrumentBatteryPercent.value) }
                ?.takeIf { it in 0.0..100.0 }
            val socBms = remainingBatteryPowerBms ?: socBmsFrom447
            val availPower = availPowerRaw?.let { v ->
                val d = v.toDouble()
                if (d in -1000.0..5000.0) d / 10.0 else null
            }

            if (cellVMin != null) _statisticCellVoltageMin.value = cellVMin
            if (cellVMax != null) _statisticCellVoltageMax.value = cellVMax
            if (cellTMin != null) _statisticCellTempMin.value = cellTMin
            if (effectiveCellTAvg != null) _statisticCellTempAvg.value = effectiveCellTAvg
            if (cellTMax != null) _statisticCellTempMax.value = cellTMax
            if (soh != null) _statisticBatterySoh.value = soh
            if (socBms != null) _statisticSocBms.value = filterBmsSocGlitch(socBms)
            socPanelCandidate?.let { _vehicleSnapshot.value = _vehicleSnapshot.value.copy(statisticSocBatteryPct = it) }
            if (availPower != null) _statisticAvailPower.value = availPower

            _vehicleSnapshot.update { snap ->
                // Preserve previous good value on null reads so a transient probe failure
                // doesn't null out the dashboard. Only overwrite when we actually got a value.
                snap.copy(
                    statisticElecPercentageValue = elecPercentage ?: snap.statisticElecPercentageValue,
                    statisticElecDrivingRangeValue = elecRange ?: snap.statisticElecDrivingRangeValue,
                    statisticFuelPercentageValue = fuelPercentage ?: snap.statisticFuelPercentageValue,
                    statisticFuelDrivingRangeValue = fuelRange ?: snap.statisticFuelDrivingRangeValue,
                    statisticTotalElecConValue = totalElecCon,
                    statisticTotalElecConPHMValue = totalElecConPhm ?: snap.statisticTotalElecConPHMValue,
                    statisticTotalMileageValue = totalMileage,
                    statisticTotalMileageDecimal = totalMileageDecimal ?: snap.statisticTotalMileageDecimal,
                    statisticDrivingTimeValue = drivingTime,
                    statisticWaterTemperature = waterTemperature,
                    statisticAvgFuelConsumption = avgFuelCon,
                    statisticInstantFuelCon = instantFuelCon,
                    statisticTotalFuelConValue = totalFuelCon,
                    statisticEvMileageValue = evMileage?.takeIf { it > 0 },
                    statisticHevMileageValue = hevMileage?.takeIf { it > 0 },
                    statisticSocBatteryPct = socPanelCandidate ?: snap.statisticSocBatteryPct,
                    batteryCellVoltageMin = cellVMin ?: snap.batteryCellVoltageMin,
                    batteryCellVoltageMax = cellVMax ?: snap.batteryCellVoltageMax,
                    batteryCellTempMin = snap.batteryCellTempMin ?: cellTMin?.toInt(),
                    batteryCellTempMax = snap.batteryCellTempMax ?: cellTMax?.toInt(),
                    batterySoh = soh?.toInt() ?: snap.batterySoh,
                    sohEstimated = if (soh != null) false else snap.sohEstimated,
                    statisticAvailPower = availPower ?: snap.statisticAvailPower,
                )
            }
            publishSnapshot()
            if (cellT30 != null || cellTMin != null || cellTMax != null || effectiveCellTAvg != null || remainingBatteryPowerBms != null || socBmsFrom447 != null || socPanelCandidate != null || sohRaw != null) {
                logVerboseInfoIfChanged(
                    "statisticSnapshotValues",
                ) {
                    "🔬 statistic snapshot values: " +
                        "cellTMin=${cellTMin?.let { String.format("%.1f", it) }} " +
                        "cellTAvg=${effectiveCellTAvg?.let { String.format("%.1f", it) }} " +
                        "cellTMax=${cellTMax?.let { String.format("%.1f", it) }} " +
                        "socBms=$socBmsFrom447 cellTValue=$cellT30 " +
                        "sohRaw=$sohRaw decodedSoh=$soh socPanel=$socPanelCandidate " +
                        "remainPow=$remainingBatteryPowerBms"
                }
            }
            if (waterTemperatureRaw != null || waterTempMeterPercent != null) {
                logVerboseInfoIfChanged(
                    "statisticWaterValues",
                ) { "🔬 statistic water values: raw=$waterTemperatureRaw tempC=$waterTemperature meterPct=$waterTempMeterPercent rawMeterPct=$waterTempMeterPercentRaw" }
            }
            if (socBatteryPct != null || remainingBatteryPowerRaw != null || remainingBatteryPowerBms != null || waterTempMeterPercentRaw != null) {
                logWarnIfChanged(
                    "hiddenGetterSummaryStatistic",
                    "🔬 hidden getter summary: statSocPanel=$socBatteryPct statSocBmsRaw=$remainingBatteryPowerRaw statSocBms=$remainingBatteryPowerBms statWaterPct=$waterTempMeterPercent statWaterPctRaw=$waterTempMeterPercentRaw"
                )
            }
            if (totalMileage != null || mileageNumber0 != null || mileageNumber1 != null || mileageNumber2 != null || mileageNumber3 != null) {
                logVerboseInfoIfChanged(
                    "odometerValues",
                ) {
                    "🔬 odometer values: totalMileage=$totalMileage " +
                        "mileageNumber[0]=$mileageNumber0 mileageNumber[1]=$mileageNumber1 " +
                        "mileageNumber[2]=$mileageNumber2 mileageNumber[3]=$mileageNumber3 " +
                        "journeyMileage=${_instrumentCurrentJourneyDriveMileage.value} odometerDisplay=${_instrumentOdometerDisplay.value} " +
                        "selectedDecimal=$totalMileageDecimal"
                }
            }
            if (extraStatisticCandidates.isNotEmpty()) {
                logVerboseInfoIfChanged(
                    "statisticExtraValues",
                ) { "🔬 statistic extra values: ${extraStatisticCandidates.joinToString(" | ")}" }
            }
            if (gbAlgorithmEstimate != null) {
                logVerboseInfoIfChanged(
                    "statisticGbValues",
                ) { "🔬 statistic gb values: ${describeAny(gbAlgorithmEstimate)}" }
            }

            // Capture feature-ID-based values for the compat probe so analysts can see
            // which StatisticDevice feature IDs returned data on this firmware.
            // Combines the standard poll fields with the dispatch fields (i05 group).
            if (VehicleCompatibilityProbe.isEnabled.value) {
                val probeFeatureIds = statisticPollFields + statisticDispatchFields
                VehicleCompatibilityProbe.recordFeatureIdGetters("statistic", device, probeFeatureIds)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Statistic snapshot failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun logSpeedSnapshot(device: Any) {
        try {

            val m25 = runtimeMethodNames("m25")
            val speed = m25.getOrNull(0)?.let { invokeDoubleGetter(device, it) }
            val accel = m25.getOrNull(1)?.let { invokeIntGetter(device, it) }
            val brake = m25.getOrNull(2)?.let { invokeIntGetter(device, it) }
            speed?.let { newSpeed ->
                val snap = _vehicleSnapshot.value
                // A fresh typed speed push (onCurrentSpeedChanged) is authoritative — it keeps
                // delivering real speed even when getCurrentSpeed() is wedged at 0 after a
                // mid-session restart. Don't let the poll (or its GPS bridge) override it.
                if (android.os.SystemClock.elapsedRealtime() - lastSpeedPushElapsedMs < SPEED_EVENT_FRESHNESS_MS) return@let
                // Ignore implausible getter spikes outright: don't drive the speedometer or
                // learn a scale from them — just preserve the last known speed by not touching
                // the snapshot for this sample.
                if (newSpeed > MAX_PLAUSIBLE_SPEED_KMH) return@let
                // Learn the event-raw → km/h scale while the getter still gives a trustworthy
                // non-zero reading, so we can drive speed from the live event stream once the
                // getter freezes (see the "Speed" branch of dispatchDynamicRawFeatureEvent).
                val evRaw = lastSpeedEventRaw
                if (newSpeed > 1.0 && evRaw != null && evRaw > 1.0) {
                    val s = newSpeed / evRaw
                    if (s.isFinite() && s in 0.001..10.0) speedEventScale = s
                }

                val prevSpeed = snap.directSpeedKmh
                if (newSpeed > 0.1) {
                    // Valid positive getter reading — the getter is authoritative. Commit it
                    // unconditionally (even if the event stream is fresh), and mark the getter
                    // healthy so the event stream stays subordinate. On firmwares where the
                    // event raw isn't proportional to speed, this keeps the reliable getter in
                    // charge instead of being overridden by a noisy event-derived value.
                    val now = android.os.SystemClock.elapsedRealtime()
                    lastPositiveSpeedElapsedMs = now
                    lastGetterPositiveElapsedMs = now
                    _vehicleSnapshot.value = snap.copy(directSpeedKmh = newSpeed)
                } else if (prevSpeed < 0.1) {
                    // Already showing ~0 — keep it 0.
                    _vehicleSnapshot.value = snap.copy(directSpeedKmh = 0.0)
                } else {
                    // Getter returned a 0 but we were showing a positive speed. getCurrentSpeed
                    // updates slightly slower than the 1 s poll and can return a transient stale
                    // 0 mid-drive. Only commit 0 when independent signals agree we've actually
                    // stopped (traction power < 2 kW AND GPS ~0 AND past a short grace window);
                    // otherwise preserve the last known speed. GPS is used only as a "still
                    // moving" hint here, never as the displayed value. Motor RPM is deliberately
                    // NOT used — the RPM path gates on directSpeedKmh, so coupling them would
                    // deadlock both at a stop.
                    val powerMoving = snap.enginePower?.let { kotlin.math.abs(it) >= 2 } == true
                    val gpsMoving   = snap.locationGpsSpeed?.let { it > 0.1 } == true
                    val withinGrace = android.os.SystemClock.elapsedRealtime() - lastPositiveSpeedElapsedMs < SPEED_STALE_GRACE_MS
                    if (!(powerMoving || gpsMoving || withinGrace)) {
                        _vehicleSnapshot.value = snap.copy(directSpeedKmh = 0.0)
                    }
                }
            }
            // ── Speed-stall diagnostics ──────────────────────────────────────────
            // Fires when the committed speed is ~0 but other (listener-backed) signals
            // say the car is moving — i.e. the "speed stuck at 0 after resume" bug.
            // Captures the RAW getter result so we can distinguish a stale-0 getter from
            // a null/failed getter, and whether GPS could have bridged it.
            run {
                val s = _vehicleSnapshot.value
                val looksMoving = s.gear in setOf("D", "R") ||
                    (s.engineSpeedFront ?: 0) > 0 || (s.engineSpeedRear ?: 0) > 0 ||
                    (s.enginePower?.let { kotlin.math.abs(it) >= 2 } == true)
                if (s.directSpeedKmh < 0.1 && looksMoving) {
                    val evAgoMs = android.os.SystemClock.elapsedRealtime() - lastSpeedEventElapsedMs
                    val msg = "🐌 speed=0 while moving: rawGetter=${speed ?: "null"} " +
                        "gear=${s.gear} rpmF=${s.engineSpeedFront} rpmR=${s.engineSpeedRear} " +
                        "power=${s.enginePower} gps=${s.locationGpsSpeed ?: "n/a"} " +
                        "chargeKw=${_chargingPowerKw.value} gun=${_chargingGunState.value} work=${_chargerWorkState.value} " +
                        "m25=${runtimeMethodNames("m25").getOrNull(0) ?: "none"} " +
                        "spEventAgoMs=$evAgoMs spEventRaw=${lastSpeedEventRaw ?: "none"} scale=${speedEventScale ?: "none"}"
                    logInfoIfChanged("speedStall", msg)
                    // Persist to the in-app diagnostic log (viewable / shareable after
                    // parking — no logcat needed). Throttled so it can't flood the file.
                    val nowMs = android.os.SystemClock.elapsedRealtime()
                    if (nowMs - lastSpeedStallDiagMs > 30_000L) {
                        lastSpeedStallDiagMs = nowMs
                        DiagLog.event(appContext, TAG, msg)
                    }
                }
            }

            // ── Wedge detection (drives the dashboard "restart the car" hint) ─────
            // getCurrentSpeed stuck at 0 while GPS shows clear, sustained motion ⇒ the getter
            // is wedged (post-mid-session-install state that only a car off/on clears). GPS is
            // the independent ground truth, so this never false-fires at a genuine stop.
            run {
                val nowW = android.os.SystemClock.elapsedRealtime()
                val getterDead = (speed ?: 0.0) < 0.1
                val gpsClearMotion = (_vehicleSnapshot.value.locationGpsSpeed ?: 0.0) > WEDGE_GPS_THRESHOLD_KMH
                when {
                    getterDead && gpsClearMotion -> {
                        if (speedWedgeSinceMs == 0L) speedWedgeSinceMs = nowW
                        else if (nowW - speedWedgeSinceMs > WEDGE_CONFIRM_MS && !_vehicleSnapshot.value.speedGetterWedged) {
                            _vehicleSnapshot.value = _vehicleSnapshot.value.copy(speedGetterWedged = true)
                        }
                    }
                    (speed ?: 0.0) > 0.1 -> {
                        // Getter is alive again (only happens after a car off/on) — clear the flag.
                        speedWedgeSinceMs = 0L
                        if (_vehicleSnapshot.value.speedGetterWedged) {
                            _vehicleSnapshot.value = _vehicleSnapshot.value.copy(speedGetterWedged = false)
                        }
                    }
                    else -> speedWedgeSinceMs = 0L  // stopped / no GPS — not enough to judge; keep prior flag
                }
            }

            accel?.let { _speedAccelerateDeepness.value = it }
            brake?.let { _speedBrakeDeepness.value = it }

            // ── Motor RPM from SpeedDevice ────────────────────────────────────────
            run {
                val BYD_RPM_SENTINELS = setOf(8191, 16383, 32767, 65535)
                val enginePower = _vehicleSnapshot.value.enginePower
                val frontSpecific = invokeIntGetter(device, *runtimeMethodNames("m20"))
                val generic       = invokeIntGetter(device, *runtimeMethodNames("m22"))
                val frontRaw      = frontSpecific ?: generic
                val front = frontRaw
                    ?.takeIf { it !in BYD_RPM_SENTINELS }
                    ?.let { if (it <= 5 && (enginePower == null || enginePower == 0)) 0 else it }
                if (_vehicleSnapshot.value.engineSpeedFront == null || _vehicleSnapshot.value.engineSpeedFront == 0) {
                    if (front != null) {
                        val rearSpecific = invokeIntGetter(device, *runtimeMethodNames("m21"))
                        val rear = rearSpecific
                            ?.takeIf { it !in BYD_RPM_SENTINELS }
                            ?.let { if (it <= 5 && (enginePower == null || enginePower == 0)) 0 else it }
                            ?: if (rearSpecific == null) _vehicleSnapshot.value.engineSpeedRear else 0
                        _vehicleSnapshot.value = _vehicleSnapshot.value.copy(
                            engineSpeedFront = front,
                            engineSpeedRear  = rear ?: 0
                        )
                        Log.i(TAG, "🔬 RPM (SpeedDevice applied): front=$front rear=$rear")
                    }
                }
            }

            publishSnapshot()

            if (speed != null || accel != null || brake != null) {
                logVerboseInfoIfChanged(
                    "speedSnapshot",
                ) { "🔬 speed snapshot: currentSpeed=$speed accelerateDeepness=$accel brakeDeepness=$brake" }
            }

        } catch (t: Throwable) {
            Log.w(TAG, "Speed snapshot failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun logLightSnapshot(device: Any) {
        try {

            val m49 = runtimeGroupMap("m49")
            val turnFlashMethod = m49["turnFlash"]?.firstOrNull().orEmpty()
            val lightStatusMethod = m49["lightStatus"]?.firstOrNull().orEmpty()
            val turnFlashState = if (turnFlashMethod.isNotEmpty()) invokeIntGetter(device, turnFlashMethod) else null
            val leftTurnStatus = if (lightStatusMethod.isNotEmpty()) invokeIndexedIntGetter(device, 4, lightStatusMethod) else null
            val rightTurnStatus = if (lightStatusMethod.isNotEmpty()) invokeIndexedIntGetter(device, 5, lightStatusMethod) else null

            _turnSignalFlashState.value = turnFlashState
            _turnSignalLeft.value = leftTurnStatus?.let { it != 0 }
            _turnSignalRight.value = rightTurnStatus?.let { it != 0 }
            publishSnapshot()

        } catch (t: Throwable) {
            Log.w(TAG, "Light snapshot failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun logPowerSnapshot(device: Any) {
        try {
            val remainPowerEv = invokeDoubleGetter(device, *runtimeMethodNames("m18"))
            val mcuStatus = invokeIntGetter(device, *runtimeMethodNames("m17"))
            val m50 = runtimeGroupMap("m50")
            val powerCtlMethod = m50["powerCtl"]?.firstOrNull().orEmpty()
            val shutdownMethod = m50["shutdown"]?.firstOrNull().orEmpty()
            val allStatusMethod = m50["allStatus"]?.firstOrNull().orEmpty()
            val powerCtl0 = if (powerCtlMethod.isNotEmpty()) invokeIndexedIntGetter(device, 0, powerCtlMethod) else null
            val powerCtl1 = if (powerCtlMethod.isNotEmpty()) invokeIndexedIntGetter(device, 1, powerCtlMethod) else null
            val powerCtl2 = if (powerCtlMethod.isNotEmpty()) invokeIndexedIntGetter(device, 2, powerCtlMethod) else null
            val powerCtl3 = if (powerCtlMethod.isNotEmpty()) invokeIndexedIntGetter(device, 3, powerCtlMethod) else null
            val shutdown0 = if (shutdownMethod.isNotEmpty()) invokeIndexedGetter(device, 0, shutdownMethod) else null
            val shutdown1 = if (shutdownMethod.isNotEmpty()) invokeIndexedGetter(device, 1, shutdownMethod) else null
            val shutdown2 = if (shutdownMethod.isNotEmpty()) invokeIndexedGetter(device, 2, shutdownMethod) else null
            val shutdown3 = if (shutdownMethod.isNotEmpty()) invokeIndexedGetter(device, 3, shutdownMethod) else null
            val allStatus = if (allStatusMethod.isNotEmpty()) invokeGetter(device, allStatusMethod) else null
            val livePower = invokeDoubleGetter(device, *m50["livePower"].orEmpty().toTypedArray())
            val availablePower = invokeDoubleGetter(device, *m50["availPower"].orEmpty().toTypedArray())
            val batteryVoltage = invokeDoubleGetter(device, *m50["voltage"].orEmpty().toTypedArray())
            val batteryCurrent = invokeDoubleGetter(device, *m50["current"].orEmpty().toTypedArray())
            val powerUnit = invokeIntGetter(device, *m50["powerUnit"].orEmpty().toTypedArray())
            _vehicleSnapshot.value = _vehicleSnapshot.value.copy(powerMcuStatus = mcuStatus)
            // Range-guard the write: on DiLink-5 the power device's remain-EV getter (m18) returns -1
            // (dead there). Unguarded, that -1 clobbers the correct usable-kWh set by Dilink5Client,
            // collapsing derived BMS SoC to 0. This only bites builds that HAVE private-telemetry
            // supplying the m18 method name (i.e. CI) — a contributor's local build leaves m18 empty,
            // so the read is skipped and it silently "works", which is why it looked CI/JDK-specific.
            // 0.5..200.0 matches the D5 usable-kWh poll guard; real DiLink-3 values are unaffected.
            remainPowerEv?.takeIf { it in 0.5..200.0 }
                ?.let { _vehicleSnapshot.value = _vehicleSnapshot.value.copy(powerBatteryRemainPowerEV = it) }
            publishSnapshot()

            if (
                remainPowerEv != null || livePower != null || availablePower != null ||
                batteryVoltage != null || batteryCurrent != null || powerUnit != null ||
                mcuStatus != null || powerCtl0 != null || powerCtl1 != null || powerCtl2 != null ||
                powerCtl3 != null || shutdown0 != null || shutdown1 != null ||
                shutdown2 != null || shutdown3 != null || allStatus != null
            ) {
                logVerboseInfoIfChanged(
                    "powerSnapshot",
                ) {
                    "🔬 power snapshot: remainPowerEv=$remainPowerEv livePower=$livePower " +
                        "availablePower=$availablePower batteryVoltage=$batteryVoltage " +
                        "batteryCurrent=$batteryCurrent powerUnit=$powerUnit mcuStatus=$mcuStatus " +
                        "powerCtl0=$powerCtl0 powerCtl1=$powerCtl1 powerCtl2=$powerCtl2 powerCtl3=$powerCtl3 " +
                        "shutdown0=${describeDiagnosticValue(shutdown0)} shutdown1=${describeDiagnosticValue(shutdown1)} " +
                        "shutdown2=${describeDiagnosticValue(shutdown2)} shutdown3=${describeDiagnosticValue(shutdown3)} " +
                        "allStatus=${describeDiagnosticValue(allStatus)}"
                }
                if (remainPowerEv != null) {
                    logWarnIfChanged(
                        "hiddenGetterSummaryPower",
                        "🔬 hidden getter summary: powerRemainKwh=$remainPowerEv"
                    )
                }
                if (mcuStatus != null || powerCtl0 != null || powerCtl1 != null || powerCtl2 != null) {
                    logWarnIfChanged(
                        "hiddenGetterSummaryPowerState",
                        "🔬 hidden getter summary: powerMcuStatus=$mcuStatus powerCtl0=$powerCtl0 powerCtl1=$powerCtl1 powerCtl2=$powerCtl2"
                    )
                }
                if (powerCtl3 != null || shutdown0 != null || shutdown1 != null || shutdown2 != null || shutdown3 != null || allStatus != null) {
                    logWarnIfChanged(
                        "hiddenGetterSummaryPowerStateExtended",
                        "🔬 hidden getter summary: powerCtl3=$powerCtl3 shutdown0=${describeDiagnosticValue(shutdown0)} " +
                            "shutdown1=${describeDiagnosticValue(shutdown1)} shutdown2=${describeDiagnosticValue(shutdown2)} " +
                            "shutdown3=${describeDiagnosticValue(shutdown3)} allStatus=${describeDiagnosticValue(allStatus)}"
                    )
                }
            }

        } catch (t: Throwable) {
            Log.w(TAG, "Power snapshot failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun logEnergySnapshot(device: Any) {
        try {
            dumpForCompatProbe("energy", device)
            logModeStateSnapshot("energy", device)
            val energyProbeValues = readNumericGetterMap(device, RuntimeExtensionBridge.methodGroups("m18")) +
                readLabeledFeatureMap(device, energyProbeFields)
            val m54 = runtimeGroupMap("m54")
            val powerGenerationState = invokeIntGetter(device, *runtimeMethodNames("m04"))
            val powerGenerationValue = invokeIntGetter(device, *runtimeMethodNames("m05"))
            val bcmState = invokeIntGetter(device, *m54["bcmState"].orEmpty().toTypedArray())
            val dcWorkMode = invokeIntGetter(device, *runtimeMethodNames("m06"))
            val operationMode = invokeIntGetter(device, *runtimeMethodNames("m07"))
            val roadSurface = invokeIntGetter(device, *runtimeMethodNames("m08"))
            val energyMode = invokeIntGetter(device, *runtimeMethodNames("m09"))
            val lowVoltageSideVoltage = invokeIntGetter(device, *m54["voltage"].orEmpty().toTypedArray())
            val lowVoltageSideCurrent = invokeIntGetter(device, *m54["current"].orEmpty().toTypedArray())
            val energySupplemental = RuntimeExtensionBridge.intGroup("i09").toList()
            val featureDcPortTemp = energySupplemental.getOrNull(0)?.let { invokeFeatureGetter(device, it) }
            val featureHighSideCurrent = energySupplemental.getOrNull(1)?.let { invokeFeatureGetter(device, it) }
            val featureHighSideVoltage = energySupplemental.getOrNull(2)?.let { invokeFeatureGetter(device, it) }
            val featureDcWorkMode = energySupplemental.getOrNull(3)?.let { invokeFeatureGetter(device, it) }
 
            val mOp = invokeIntGetter(device, *runtimeMethodNames("m07"))
            val mDr = invokeIntGetter(device, *runtimeMethodNames("m13"))
            val mEn = invokeIntGetter(device, *runtimeMethodNames("m09"))

            if (ENABLE_LAB_DIAGNOSTICS) {
                Log.i(TAG, "🔍 Mode State (Energy): op=$mOp dr=$mDr en=$mEn")
            }

            updateDriveModeCandidate(mOp, strong = false, source = "energy-op")
            if (mEn != null && mEn in 1..5) {
                _energyMode.value = mEn
            }
            // NOTE: mEn (getEnergyMode: EV=1/FORCE_EV=2/HEV=3/FUEL=4/KEEP=5) is the powertrain
            // source, NOT a regen level. Feeding it to updateRegenModeCandidate made Forced-EV
            // read as "Higher" regen and EV as "Standard". Regen level comes only from the
            // instrument/gearbox getters (m03/m14/getEnergyFeedback).

            // getEnergyState distinguishes RWD-only (3, 6, 19) from AWD (1, 4) and FWD (2, 5).
            // When confirmed RWD-only, zero out the front motor RPM so it doesn't stick at the
            // last event value after the front motor disengages (no Engine/Motor/Speed device
            // exists on some firmwares, so the event path never writes a zero back).
            // On some firmware builds getEnergyState() returns 0 when polled outside a DiLink
            // callback context — skip 0 to preserve any value already written by the event path
            // (dispatchDynamicRawFeatureEvent). Log the raw result once on change for diagnostics.
            val drivetrainRaw = invokeGetter(device, "getEnergyState")
            logInfoIfChanged(
                "drivetrainStateRaw",
                "⚡ getEnergyState raw=${drivetrainRaw?.let { "$it(${it.javaClass.simpleName})" } ?: "null"}"
            )
            val drivetrainState = when (drivetrainRaw) {
                null     -> null
                is Number -> drivetrainRaw.toInt().takeIf { it > 0 }
                else      -> null
            }
            if (drivetrainState != null) {
                val rwdOnly = drivetrainState in setOf(3, 6, 19)
                _vehicleSnapshot.update { snap ->
                    snap.copy(
                        drivetrainState = drivetrainState,
                        engineSpeedFront = if (rwdOnly) 0 else snap.engineSpeedFront
                    )
                }
                if (rwdOnly) publishSnapshot()
                logInfoIfChanged(
                    "drivetrainState",
                    "⚡ drivetrainState=$drivetrainState rwdOnly=$rwdOnly"
                )
            }

            updateDiagnosticProbeValues(source = "energy", values = energyProbeValues)

            if (
                (powerGenerationValue != null && powerGenerationValue != 0) ||
                operationMode != null ||
                energyMode != null ||
                lowVoltageSideVoltage != null ||
                lowVoltageSideCurrent != null ||
                featureDcPortTemp != null ||
                featureHighSideCurrent != null ||
                featureHighSideVoltage != null ||
                featureDcWorkMode != null
            ) {
                logInfoIfChanged(
                    "energySnapshot",
                    "🔬 energy snapshot: powerGenState=$powerGenerationState powerGenValue=$powerGenerationValue " +
                        "bcmState=$bcmState dcWorkMode=$dcWorkMode operationMode=$operationMode " +
                        "roadSurface=$roadSurface energyMode=$energyMode " +
                        "lowVoltageSideVoltage=$lowVoltageSideVoltage lowVoltageSideCurrent=$lowVoltageSideCurrent " +
                        "featureDcPortTemp=${describeAny(featureDcPortTemp)} " +
                        "featureHighSideCurrent=${describeAny(featureHighSideCurrent)} " +
                        "featureHighSideVoltage=${describeAny(featureHighSideVoltage)} " +
                        "featureDcWorkMode=${describeAny(featureDcWorkMode)}"
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Energy snapshot failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun logOtaSnapshot(device: Any) {
        try {
            val batteryPowerVoltage = invokeDoubleGetter(device, *runtimeMethodNames("m52"))
                ?.takeIf { it.isFinite() && it in 9.0..18.0 }

            if (batteryPowerVoltage != null) {
                _battery12vVoltage.value = batteryPowerVoltage
                _vehicleSnapshot.value = _vehicleSnapshot.value.copy(battery12vVoltage = batteryPowerVoltage)
                publishSnapshot()
            }

            if (batteryPowerVoltage != null) {
                logVerboseInfoIfChanged("otaSnapshot") {
                    "🔬 ota snapshot: batteryPowerVoltage=$batteryPowerVoltage"
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Ota snapshot failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun logSensorSnapshot(device: Any) {
        try {

            val m51 = runtimeGroupMap("m51")
            val temperature = invokeNumericDoubleGetter(device, *m51["tempSensor"].orEmpty().toTypedArray())
            temperature?.let { _vehicleSnapshot.value = _vehicleSnapshot.value.copy(sensorTemperatureValue = it) }

            // Read extra sensor values — voltage sensors and cabin temp.
            // m51["batteryTemp"] from the Sensor device is intentionally NOT consumed:
            // observed to behave like a coolant probe (reads several °C below cells under
            // active battery thermal management), causing avg-vs-range inconsistencies.
            // The Charging device cell range is the authoritative source instead.
            val ambientTemp    = invokeNumericDoubleGetter(device, *m51["ambientTemp"].orEmpty().toTypedArray())
                ?.takeIf { it.isFinite() && it in -50.0..80.0 }
            val cabinTemp      = invokeNumericDoubleGetter(device, *m51["cabinTemp"].orEmpty().toTypedArray())
                ?.takeIf { it.isFinite() && it in -30.0..80.0 }
            val hvVoltage      = invokeIntGetter(device, *m51["hvVoltage"].orEmpty().toTypedArray())
            val hvCurrent      = invokeNumericDoubleGetter(device, *m51["hvCurrent"].orEmpty().toTypedArray())
            val auxBatt12v     = invokeNumericDoubleGetter(device, *m51["aux12v"].orEmpty().toTypedArray())
            // sensor.getSlope() confirmed dead on DiLink-5 (constant -1 through a real incline
            // test) — skip the call there so roadSlopeDeg stays null and
            // the dashboard subtitle just doesn't render (see VehicleStats/DashboardCards), instead
            // of showing a bogus "-1.0°". Left as-is on D3 — not confirmed dead there.
            val roadSlope      = if (!DiLink5Platform.isDiLink5) {
                invokeNumericDoubleGetter(device, *m51["roadSlope"].orEmpty().toTypedArray())
            } else null
            _vehicleSnapshot.value = _vehicleSnapshot.value.copy(
                cabinTemperature = cabinTemp   ?: _vehicleSnapshot.value.cabinTemperature,
                roadSlopeDeg     = roadSlope   ?: _vehicleSnapshot.value.roadSlopeDeg,
            )
            publishSnapshot()

            if (ambientTemp != null || cabinTemp != null || hvVoltage != null || hvCurrent != null || auxBatt12v != null) {
                logVerboseInfoIfChanged(
                    "sensorSnapshot",
                ) {
                    "🔬 sensor snapshot: temperature=$temperature " +
                        "ambientTemp=$ambientTemp cabinTemp=$cabinTemp hvVoltage=$hvVoltage hvCurrent=$hvCurrent 12v=$auxBatt12v"
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Sensor snapshot failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private var climateMethodsDumped = false

    private fun logClimateSnapshot(device: Any) {
        try {
            // ── One-shot method enumeration ───────────────────────────────────
            // Runs once per service lifecycle so we can see exactly which power-
            // related getters the current firmware exposes on this Climate device.
            if (!climateMethodsDumped) {
                climateMethodsDumped = true
                val powerKeywords = setOf(
                    "power", "energy", "compressor", "ptc", "heater", "hvac",
                    "consumption", "watt", "kilowatt", "current", "amp", "freq",
                    "refriger", "coolant", "outlet", "inlet", "heat", "cool"
                )
                val methods = device.javaClass.methods
                    .filter { m -> m.parameterCount <= 1 }
                    .filter { m -> powerKeywords.any { kw -> m.name.lowercase().contains(kw) } }
                    .sortedBy { it.name }
                if (methods.isNotEmpty()) {
                    val summary = methods.joinToString(" | ") { m ->
                        "${m.name}(${m.parameterTypes.joinToString(",") { p -> p.simpleName }}):${m.returnType.simpleName}"
                    }
                    Log.i(TAG, "🌡️ Climate power-related methods: $summary")
                } else {
                    Log.i(TAG, "🌡️ Climate power-related methods: none found on ${device.javaClass.simpleName}")
                }
            }

            // ── Indexed power getter probes ───────────────────────────────────
            // Direct single-value variants all return NPE; try index-parameterised forms.
            val indexedHvacPowerProbes = linkedMapOf<String, Double>()
            val hvacPowerNames = RuntimeExtensionBridge.stringList("m_hvac_p").toTypedArray()
            for (idx in 0..3) {
                for (name in hvacPowerNames) {
                    val v = invokeIndexedDoubleGetter(device, idx, name)
                        ?.takeIf { it.isFinite() && it in 0.0..100_000.0 }
                        ?: invokeIndexedIntGetter(device, idx, name)?.toDouble()
                            ?.takeIf { it > 0 && it < 100_000 }
                    if (v != null) {
                        indexedHvacPowerProbes["${name}_idx$idx"] = v
                    }
                }
            }
            if (indexedHvacPowerProbes.isNotEmpty()) {
                Log.i(TAG, "🌡️ Climate indexed power probes: $indexedHvacPowerProbes")
                updateDiagnosticProbeValues(source = "climate-indexed-power", values = indexedHvacPowerProbes)
            }

            // ── Feature ID scan (i11) ─────────────────────────────────────────
            // Polls candidate HVAC power feature IDs; any non-null result lands in
            // probeValues so we can read it from Settings → Trip Data raw JSON.
            val hvacFeatureIds = RuntimeExtensionBridge.intGroup("i11")
            val hvacFeatureResults = hvacFeatureIds.toList().mapNotNull { fid ->
                val raw = (invokeFeatureGetter(device, fid) as? Number)?.toDouble()
                    ?.takeIf { it.isFinite() && it != 0.0 && abs(it) < 1_000_000.0 }
                if (raw != null) "hvac_fid_0x${fid.toLong().and(0xFFFFFFFFL).toString(16)}" to raw
                else null
            }.toMap()
            if (hvacFeatureResults.isNotEmpty()) {
                Log.i(TAG, "🌡️ Climate HVAC feature scan hits: $hvacFeatureResults")
                updateDiagnosticProbeValues(source = "climate-hvac-fid", values = hvacFeatureResults)
            }

            val climateProbeValues = readNumericGetterMap(device, RuntimeExtensionBridge.methodGroups("m17")) +
                readLabeledFeatureMap(device, climateProbeFields)
            val climateCabinValues = readNumericGetterMap(
                device,
                listOf(
                    "inCarTemperature" to listOf("getInCarTemperature"),
                    "insideTemperature" to listOf("getInsideTemperature"),
                    "innerTemperature" to listOf("getInnerTemperature"),
                    "interiorTemperature" to listOf("getInteriorTemperature"),
                    "cabinTemperature" to listOf("getCabinTemperature"),
                    "cockpitTemperature" to listOf("getCockpitTemperature"),
                    "vehicleInsideTemperature" to listOf("getVehicleInsideTemperature"),
                    "carInsideTemperature" to listOf("getCarInsideTemperature"),
                    "insideTemp" to listOf("getInsideTemp"),
                    "interiorTemp" to listOf("getInteriorTemp"),
                    "cabinTemp" to listOf("getCabinTemp"),
                    "cockpitTemp" to listOf("getCockpitTemp"),
                )
            )
            val cabinTemp = invokeNumericDoubleGetter(
                device,
                "getInCarTemperature", "getInsideTemperature", "getInnerTemperature",
                "getInteriorTemperature", "getCabinTemperature", "getCockpitTemperature",
                "getVehicleInsideTemperature", "getCarInsideTemperature",
                "getInsideTemp", "getInteriorTemp", "getCabinTemp", "getCockpitTemp"
            )?.takeIf { it.isFinite() && it in -30.0..80.0 }
            val driverSetTemp = invokeNumericDoubleGetter(
                device,
                "getMainDriverTemperature", "getDriverTemperature", "getLeftTemperature",
                "getTemperatureLeft", "getAcTemperatureDriver", "getCabinSetTemperatureLeft"
            )?.takeIf { it.isFinite() && it in 15.0..35.0 }
            val passengerSetTemp = invokeNumericDoubleGetter(
                device,
                "getCopilotTemperature", "getPassengerTemperature", "getRightTemperature",
                "getTemperatureRight", "getAcTemperaturePassenger", "getCabinSetTemperatureRight"
            )?.takeIf { it.isFinite() && it in 15.0..35.0 }
            // Some firmware builds expose temperature slots here, but not cabin air temperature.
            @Suppress("UNUSED_VARIABLE") val externalAmbientFromClimate = invokeIndexedDoubleGetter(
                device,
                0,
                "getTemprature", "getTemperature"
            )?.takeIf { it.isFinite() && it in -30.0..80.0 }
            // Not used as cabin temp.
            val indexedLeftTemp: Double? = null  // cabin air temp not available via this API
            val indexedRightTemp = invokeIndexedDoubleGetter(
                device,
                1,
                "getTemprature", "getTemperature"
            )?.takeIf { it.isFinite() && it in -30.0..80.0 }
            val indexedTemps = (0..7).mapNotNull { index ->
                invokeIndexedIntGetter(device, index, "getTemprature", "getTemperature")
                    ?.takeIf { it in -30..80 }
                    ?.let { index to it.toDouble() }
            }
            // Log indexed values for diagnosis.
            Log.i(TAG, "🌡️ Climate indexed: [0]=$indexedLeftTemp [1]=$indexedRightTemp " +
                "all=${indexedTemps.map { (i,v) -> "[$i]=$v" }}")

            // ── getTemprature(area) wide sweep ────────────────────────────────
            // The BYD SDK declares getTemprature(int area) with named area constants
            // (e.g. TEMPERATURE_MAIN_DRIVER) whose numeric values are not in our
            // headers. Sweep a wide range and log every non-zero result with raw +
            // candidate scalings (÷1, ÷10, ÷3) so we can spot the cabin sensor by
            // comparing against ambient and the real cabin temperature on a drive.
            // Probe results are surfaced into probeValues as `cabintemp_probe_area_N`
            // so they appear in the JSON snapshot for offline review.
            val cabinProbeRaw = linkedMapOf<String, Double>()
            for (area in 0..31) {
                val rawInt = try {
                    invokeIndexedIntGetter(device, area, "getTemprature", "getTemperature")
                } catch (_: Throwable) { null }
                val rawDouble = try {
                    invokeIndexedDoubleGetter(device, area, "getTemprature", "getTemperature")
                } catch (_: Throwable) { null }
                val raw = rawInt?.toDouble() ?: rawDouble
                if (raw != null && raw != 0.0 && raw.isFinite() && abs(raw) < 100_000.0) {
                    cabinProbeRaw["area_$area"] = raw
                }
            }
            if (cabinProbeRaw.isNotEmpty()) {
                val pretty = cabinProbeRaw.entries.joinToString(" ") { (k, v) ->
                    val rawStr = if (v == v.toLong().toDouble()) v.toLong().toString() else "%.2f".format(v)
                    val d10 = "%.1f".format(v / 10.0)
                    val d3  = "%.1f".format(v / 3.0)
                    "$k=$rawStr(/1=${"%.1f".format(v)} /10=$d10 /3=$d3)"
                }
                Log.i(TAG, "🌡️ getTemprature(area) sweep: $pretty")
                updateDiagnosticProbeValues(
                    source = "climate-cabintemp-area-sweep",
                    values = cabinProbeRaw.mapKeys { (k, _) -> "cabintemp_probe_$k" }
                )
            } else {
                Log.i(TAG, "🌡️ getTemprature(area) sweep: no non-zero results from area 0..31")
            }

            val ambientTemp = invokeNumericDoubleGetter(
                device,
                "getAmbientTemperature", "getOutsideTemp"
            )?.takeIf { it.isFinite() && it in -50.0..80.0 }
            updateDiagnosticProbeValues(source = "climate", values = climateProbeValues)
            val climateFeatureValues = RuntimeExtensionBridge.intGroup("i10").toList().mapNotNull { fid ->
                val raw = pollStatisticFeature(device, fid)
                if (raw != null) fid to raw else null
            }
            if (climateFeatureValues.isNotEmpty()) {
                val climateFeatureStr = climateFeatureValues.joinToString(" ") { (_, raw) ->
                    val decoded = if (raw > 100) raw / 10.0 else raw.toDouble()
                    "raw$raw(${String.format("%.1f", decoded)}°C)"
                }
                logInfoIfChanged("climateFeatureValues", "🌡️ Climate mapped values: $climateFeatureStr " +
                    "indexedLeft=$indexedLeftTemp namedCabin=$cabinTemp")
                // Try to extract cabin temp: look for a value in plausible indoor temp range 15-35°C
                val cabinCandidate = climateFeatureValues.firstNotNullOfOrNull { (_, raw) ->
                    val decoded = if (raw > 100) raw / 10.0 else raw.toDouble()
                    decoded.takeIf { it in 15.0..35.0 }
                }
                if (cabinCandidate != null && cabinTemp == null && indexedLeftTemp == null) {
                    _vehicleSnapshot.value = _vehicleSnapshot.value.copy(cabinTemperature = cabinCandidate)
                    publishSnapshot()
                    logInfoIfChanged("climateFeatureCabinTemp",
                        "🌡️ cabin temp from Climate mapped value: ${String.format("%.1f", cabinCandidate)}°C")
                }
            }

            val subBatteryTemp = decodePackTempCelsius(invokeNumericDoubleGetter(
                device,
                "getAcSubBatteryTemperature", "getSubBatteryTemperature", "getBatterySubTemperature"
            ))

            // cabinTemp = named getters (likely null on Climate device)
            // Keep the firmware spelling for compatibility.
            // Use whichever is non-null; prefer direct named getter, fall back to indexed
            val effectiveCabinTemp = cabinTemp ?: indexedLeftTemp
            effectiveCabinTemp?.let {
                _vehicleSnapshot.value = _vehicleSnapshot.value.copy(cabinTemperature = it)
                publishSnapshot()
                logInfoIfChanged("climateCabinTemp",
                    "🌡️ cabin temp from Climate: ${String.format("%.1f", it)}°C " +
                        "(named=${cabinTemp?.let { v -> String.format("%.1f", v) } ?: "null"} " +
                        "indexed[0]=${indexedLeftTemp?.let { v -> String.format("%.1f", v) } ?: "null"})")
            }
            // This getter returns the HV battery pack temp from the HVAC controller.
            // Apply it to batteryPackTemp when no better source (Sensor/Battery device) has set it.
            subBatteryTemp?.let { temp ->
                val snap = _vehicleSnapshot.value
                if (snap.batteryPackTemp == null || snap.batteryPackTemp == 0.0) {
                    _vehicleSnapshot.value = snap.copy(batteryPackTemp = temp)
                    publishSnapshot()
                    logInfoIfChanged("climateSubBatteryTemp",
                        "🔌 batteryPackTemp from ClimateDevice.getAcSubBatteryTemperature: ${temp}°C")
                }
            }

            if (
                cabinTemp != null || driverSetTemp != null || passengerSetTemp != null ||
                ambientTemp != null || subBatteryTemp != null ||
                climateCabinValues.isNotEmpty() || indexedTemps.isNotEmpty()
            ) {
                val directValueText = climateCabinValues.entries.joinToString(" ") { (key, value) ->
                    "$key=${String.format("%.1f", value)}"
                }.ifBlank { "n/a" }
                val indexedValueText = indexedTemps.joinToString(" ") { (index, value) ->
                    "t[$index]=${String.format("%.1f", value)}"
                }.ifBlank { "n/a" }
                logVerboseInfoIfChanged(
                    "climateSnapshot",
                ) {
                    "🔬 climate snapshot: cabinTemp=$cabinTemp driverSetTemp=$driverSetTemp " +
                        "passengerSetTemp=$passengerSetTemp indexedLeftTemp=$indexedLeftTemp indexedRightTemp=$indexedRightTemp " +
                        "ambientTemp=$ambientTemp subBatteryTemp=$subBatteryTemp " +
                        "directValues=$directValueText indexedValues=$indexedValueText"
                }
            }

            val pm25In = invokeIntGetter(device, *runtimeMethodNames("m15"))?.takeIf { it in 1..999 }
            val pm25Out = invokeIntGetter(device, *runtimeMethodNames("m16"))?.takeIf { it in 1..999 }
            if (pm25In != null || pm25Out != null) {
                updatePm25Snapshot(inCar = pm25In, outCar = pm25Out, source = "climate-device")
            }

            pollHvacState(device)
        } catch (t: Throwable) {
            Log.w(TAG, "Climate snapshot failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /**
     * Polls compressor mode and PTC preheat signal from the Climate device and
     * updates the snapshot. Called both at climate device registration and every
     * 3 s from the polling loop — these getters are not event-driven.
     */
    private fun pollHvacState(device: Any) {
        val compressorMode = invokeIntGetter(device, *runtimeMethodNames("m19a"))
        val ptcPreheat     = invokeIntGetter(device, *runtimeMethodNames("m19b"))
        val tecTempRaw     = _tecControlTempRaw.value
        val ambientTempC   = _vehicleSnapshot.value.instrumentOutCarTemperature?.toDouble()

        val hvacEstimatedKw = estimateHvacKw(compressorMode, ptcPreheat, tecTempRaw, ambientTempC)

        val hvacChanged = compressorMode != _vehicleSnapshot.value.acCompressorMode ||
            ptcPreheat != _vehicleSnapshot.value.acPtcPreheatSignal ||
            tecTempRaw != _vehicleSnapshot.value.tecControlTempRaw ||
            hvacEstimatedKw != _vehicleSnapshot.value.hvacEstimatedKw

        if (hvacChanged) {
            _vehicleSnapshot.value = _vehicleSnapshot.value.copy(
                acCompressorMode   = compressorMode,
                acPtcPreheatSignal = ptcPreheat,
                tecControlTempRaw  = tecTempRaw,
                hvacEstimatedKw    = hvacEstimatedKw,
            )
            publishSnapshot()
            val tecTempC = tecTempRaw?.let { it / 1000.0 }
            val dtC = if (tecTempC != null && ambientTempC != null) tecTempC - ambientTempC else null
            logInfoIfChanged(
                "hvacSnapshot",
                "🌡️ HVAC: compressorMode=$compressorMode ptcPreheat=$ptcPreheat " +
                    "tecC=${tecTempC?.let { String.format("%.1f", it) } ?: "n/a"} " +
                    "ambientC=${ambientTempC?.let { String.format("%.0f", it) } ?: "n/a"} " +
                    "ΔT=${dtC?.let { String.format("%.1f", it) } ?: "n/a"}°C " +
                    "hvacKw=${hvacEstimatedKw?.let { String.format("%.2f", it) } ?: "n/a"}"
            )
        }
    }

    /**
     * Estimates total HVAC draw from the signals available on this firmware.
     *
     * No direct power getter exists. The estimate is built from two components:
     *
     * **Compressor (cooling):** uses ΔT = T_tec − T_ambient as the load signal.
     * `tec-control-temp` is the high-side condenser refrigerant line temperature.
     * At idle with the compressor off it sits ~20–30°C above ambient (residual/solar heat).
     * When the compressor runs, it rises further in proportion to cooling load:
     *   ΔT  <  5°C → idle / just started, 0 kW
     *   ΔT  5–15°C → light load (cabin near setpoint), 0–1.5 kW
     *   ΔT 15–30°C → moderate load, 1.5–3.0 kW
     *   ΔT 30–45°C → heavy load (cabin far from setpoint), 3.0–4.5 kW
     *   ΔT > 45°C  → full blast, 4.5–5.0 kW cap
     * When ambient is unknown the raw T_tec is used with a conservative fixed-offset baseline.
     *
     * **PTC heater (winter heating):** signal level 1 ≈ 1 kW partial, 2+ ≈ 2 kW full load.
     *
     * Calibrated against owner-reported 0–1 kW at setpoint and 4–5 kW full blast.
     */
    private fun estimateHvacKw(
        compressorMode: Int?,
        ptcPreheat: Int?,
        tecTempRaw: Int?,
        ambientTempC: Double? = null,
    ): Double? {
        val compressorOn = compressorMode != null && compressorMode != 0
        val ptcOn        = ptcPreheat != null && ptcPreheat != 0
        if (!compressorOn && !ptcOn) return if (compressorMode != null || ptcPreheat != null) 0.0 else null

        val compressorKw: Double = if (compressorOn && tecTempRaw != null) {
            val tecC = tecTempRaw / 1000.0
            // Prefer ΔT over ambient; fall back to a fixed baseline of 25°C when ambient unknown.
            val baseline = ambientTempC ?: 25.0
            val dt = tecC - baseline
            when {
                dt <  5.0  -> 0.0                                      // compressor on, no real load yet
                dt < 15.0  -> (dt -  5.0) / 10.0 * 1.5                // 0   → 1.5 kW
                dt < 30.0  -> 1.5 + (dt - 15.0) / 15.0 * 1.5         // 1.5 → 3.0 kW
                dt < 45.0  -> 3.0 + (dt - 30.0) / 15.0 * 1.5         // 3.0 → 4.5 kW
                else       -> 4.5 + (dt - 45.0) / 10.0 * 0.5         // 4.5 → 5.0 kW cap
            }.coerceIn(0.0, 5.0)
        } else if (compressorOn) {
            1.5  // compressor on but no TEC signal yet — conservative midpoint
        } else 0.0

        // PTC signal is a level: 1 = partial (~1 kW), 2+ = full load (2 kW).
        val ptcKw = when {
            !ptcOn          -> 0.0
            ptcPreheat == 1 -> 1.0
            else            -> 2.0
        }

        // Total HVAC draw is capped at 5.0 kW — confirmed owner maximum.
        // Compressor and PTC share the HV budget, so combined may not exceed real-world peak.
        return (compressorKw + ptcKw).coerceIn(0.0, 5.0)
    }

    private fun logMqttSnapshot(device: Any) {
        try {
            val mqttIds = RuntimeExtensionBridge.intGroup("i06")
            val dynamicRaw = mqttIds.getOrNull(0)?.let { fid ->
                invokeFeatureBatchGetter(
                    device,
                    intArrayOf(fid),
                    BYDAutoEventValue::class.java,
                    ByteArray::class.java,
                    String::class.java
                )
            } ?: mqttIds.getOrNull(1)?.let { fid ->
                invokeFeatureBatchGetter(
                    device,
                    intArrayOf(fid),
                    BYDAutoEventValue::class.java,
                    ByteArray::class.java,
                    String::class.java
                )
            }
            val dynamicPayload = dynamicRaw?.let { extractEventStructuredPayload(it) }
            val dynamicJson = dynamicPayload
                ?.takeIf { it.startsWith("{") && it.endsWith("}") }
                ?.let { payload -> runCatching { JSONObject(payload) }.getOrNull() }

            if (dynamicRaw != null || dynamicPayload != null) {
                logInfoIfChanged(
                    "mqttSnapshot",
                    buildString {
                        append("🔬 mqtt snapshot: dynamicRaw=")
                        append(compactDiagnosticDetail(describeAny(dynamicRaw), 220))
                        append(" dynamicPayload=")
                        append(dynamicPayload?.let { compactDiagnosticDetail(it, 220) } ?: "null")
                        dynamicJson?.let { json ->
                            append(" parsed=")
                            append(
                                compactDiagnosticDetail(
                                    "battery_cell_temp_min=${json.opt("battery_cell_temp_min")} " +
                                        "battery_cell_temp_max=${json.opt("battery_cell_temp_max")} " +
                                        "battery_total_voltage=${json.opt("battery_total_voltage")} " +
                                        "soc=${json.opt("soc")} soc_panel=${json.opt("soc_panel")}",
                                    220
                                )
                            )
                        }
                    }
                )
            }
            mqttSnapshotLogged = true
        } catch (t: Throwable) {
            Log.w(TAG, "Mqtt snapshot failed: ${t.javaClass.simpleName}: ${t.message}")
            mqttSnapshotLogged = true
        }
    }

    private fun logVemSnapshot(manager: Any) {
        try {
            dumpForCompatProbe("vem", manager)
            val vehicleInfo = manager.javaClass.methods.firstOrNull {
                it.name == "getVehicleInfo" && it.parameterCount == 0
            }?.invoke(manager) ?: return
            dumpForCompatProbe("vem-vehicleInfo", vehicleInfo)

            val batteryTemp = decodePackTempCelsius(invokeNumericDoubleGetter(vehicleInfo, "getBatteryTempCelsius"))
            val externalTemp = invokeNumericDoubleGetter(vehicleInfo, "getExternalTempCelsius")
                ?.takeIf { it.isFinite() && it in -50.0..80.0 }
            val hvacStatus = invokeIntGetter(vehicleInfo, "getHvacStatusValue")
            val peakMotorPowerWatts = invokeIntGetter(vehicleInfo, "getPeakMotorPowerWatts")
            val maxChargingRateWatts = invokeIntGetter(vehicleInfo, "getMaximumChargingRateWatts")

            var changed = false
            if (batteryTemp != null) {
                _vehicleSnapshot.value = _vehicleSnapshot.value.copy(batteryPackTemp = batteryTemp)
                changed = true
            }
            if (externalTemp != null && _vehicleSnapshot.value.instrumentOutCarTemperature == null) {
                _vehicleSnapshot.value = _vehicleSnapshot.value.copy(instrumentOutCarTemperature = externalTemp.toInt())
                changed = true
            }
            if (changed) publishSnapshot()

            if (batteryTemp != null || externalTemp != null || hvacStatus != null || peakMotorPowerWatts != null || maxChargingRateWatts != null) {
                logInfoIfChanged(
                    "vemSnapshot",
                    "🔬 vem snapshot: batteryTemp=$batteryTemp externalTemp=$externalTemp " +
                        "hvacStatus=$hvacStatus peakMotorPowerWatts=$peakMotorPowerWatts " +
                        "maxChargingRateWatts=$maxChargingRateWatts"
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "VEM snapshot failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }


    private fun logInstrumentSnapshot(device: Any) {
        try {
            dumpForCompatProbe("instrument", device)
            logModeStateSnapshot("instrument", device)

            val m53 = runtimeGroupMap("m53")
            val last50Km = invokeDoubleGetter(device, *m53["last50Km"].orEmpty().toTypedArray())
            val outCarTemp = invokeIntGetter(device, *m53["outCarTemp"].orEmpty().toTypedArray())
                ?.takeIf { it in -50..80 }
            val instrumentCoolantTemp = invokeIntGetter(
                device, *runtimeGroupMap("m47")["coolant"].orEmpty().toTypedArray()
            )?.takeIf { it in -50..200 }
            val instrWaterTempMeterNames = runtimeGroupMap("m48")["waterTempMeter"].orEmpty().toTypedArray()
            val instrumentWaterTempMeterPercentRaw = invokeDoubleGetter(device, *instrWaterTempMeterNames)
                ?: invokeIntGetter(device, *instrWaterTempMeterNames)?.toDouble()
            val instrumentWaterTempMeterPercent = decodeMeterPercentRaw(
                instrumentWaterTempMeterPercentRaw,
                _vehicleSnapshot.value.statisticElecPercentageValue
                    ?: _instrumentBatteryPercent.value
                    ?: _vehicleSnapshot.value.statisticSocBatteryPct
            )
            val instrumentCabinValues = readNumericGetterMap(
                device,
                listOf(
                    "inCarTemperature" to listOf("getInCarTemperature"),
                    "insideTemperature" to listOf("getInsideTemperature"),
                    "innerTemperature" to listOf("getInnerTemperature"),
                    "interiorTemperature" to listOf("getInteriorTemperature"),
                    "cabinTemperature" to listOf("getCabinTemperature"),
                    "cockpitTemperature" to listOf("getCockpitTemperature"),
                    "insideTemp" to listOf("getInsideTemp"),
                    "interiorTemp" to listOf("getInteriorTemp"),
                    "cabinTemp" to listOf("getCabinTemp"),
                    "cockpitTemp" to listOf("getCockpitTemp"),
                )
            )
            val cabinTemp = invokeNumericDoubleGetter(
                device,
                "getInCarTemperature", "getInsideTemperature", "getInnerTemperature",
                "getInteriorTemperature", "getCabinTemperature", "getCockpitTemperature",
                "getInsideTemp", "getInteriorTemp", "getCabinTemp", "getCockpitTemp"
            )?.takeIf { it.isFinite() && it in -30.0..80.0 }
            val mileageUnit = invokeIntGetter(device, *m53["mileageUnit"].orEmpty().toTypedArray())
            val safetyBeltMethod = m53["safetyBelt"]?.firstOrNull().orEmpty()
            val safetyBeltDriver = if (safetyBeltMethod.isNotEmpty()) invokeIndexedIntGetter(device, 0, safetyBeltMethod) else null
            val safetyBeltPassenger = if (safetyBeltMethod.isNotEmpty()) invokeIndexedIntGetter(device, 1, safetyBeltMethod) else null
            val averageSpeed = invokeDoubleGetter(device, "getAverageSpeed")
                ?.takeIf { it.isFinite() && it in 0.0..300.0 }
            val currentJourneyDriveMileage = invokeDoubleGetter(device, *m53["journeyMileage"].orEmpty().toTypedArray())
                ?.takeIf { it.isFinite() && it >= 0.0 && it <= 9_999.9 }
            val currentJourneyDriveTime = invokeDoubleGetter(device, *m53["journeyDriveTime"].orEmpty().toTypedArray())
                ?.takeIf { it.isFinite() && it >= 0.0 && it <= 9_999.0 }
            val batteryPercent = invokeDoubleGetter(device, "getBatteryPercent")
                ?.takeIf { it.isFinite() && it in 0.0..100.0 }
            val chargePercent = invokeDoubleGetter(device, "getChargePercent")
                ?.takeIf { it.isFinite() && it in 0.0..100.0 }
            val odometerDisplay = invokeDoubleGetter(device, "getOdometerDisplay")
                ?.takeIf { it.isFinite() && it >= 0.0 && it <= 9_999_999.0 }
            val powerUnit = invokeIntGetter(device, "getPowerUnit")
                ?.takeIf { it in 0..16 }
            val externalChargingPower = invokeNumericDoubleGetter(
                device,
                "getExternalChargingPower"
            )?.takeIf { it.isFinite() && it in 0.0..500.0 }
            val chargingPowerValues = readNumericGetterMap(
                device,
                listOf(
                    "externalChargingPower" to listOf("getExternalChargingPower"),
                    "externalChargePower" to listOf("getExternalChargePower"),
                    "chargingPower" to listOf("getChargingPower"),
                    "chargePower" to listOf("getChargePower"),
                )
            )
            val instrumentSohValues = readNumericGetterMap(
                device,
                listOf(
                    "soh" to listOf("getSOH", "getSoh", "getBatterySOH", "getStateOfHealth"),
                    "batteryHealth" to listOf("getBatteryHealth", "getHealthPercent", "getHealthRate"),
                    "healthyIndex" to listOf("getHealthyIndex", "getBatteryHealthyIndex", "getBatteryHealthIndex"),
                )
            )
            // Only fall back to externalChargingPower when no primary charging power getter
            // exists on this device. On DM-i PHEVs, getChargePower returns a sentinel (359.4)
            // when idle but becomes 0.8 via getExternalChargingPower *after* charging ends,
            // which would keep powerActive=true indefinitely if used as a fallback.
            val primaryChargePowerPresent = chargingPowerValues["chargePower"] != null
                    || chargingPowerValues["chargingPower"] != null
            val instrumentChargingPower = sequenceOf(
                chargingPowerValues["chargingPower"],
                chargingPowerValues["chargePower"],
                if (!primaryChargePowerPresent) externalChargingPower else null
            ).firstOrNull { it != null && it.isFinite() && it in 0.1..50.0 }
            last50Km?.let { _vehicleSnapshot.value = _vehicleSnapshot.value.copy(instrumentLast50KmPowerConsume = it) }
            // DiLink-5: instrument getOutCarTemperature is dead and returns 0, which passes the
            // -50..80 guard above and stamps a false 0 °C over the real ambient the D5 client reads
            // from ac.getTemprature(4) (applyDilink5AmbientTemp, which drops 0). Let the D5 client own
            // ambient on D5 hardware; keep the D3 read unchanged — 0 °C is a legitimate reading there.
            if (!DiLink5Platform.isDiLink5) {
                outCarTemp?.let { _vehicleSnapshot.value = _vehicleSnapshot.value.copy(instrumentOutCarTemperature = it) }
            }
            mileageUnit?.let { _vehicleSnapshot.value = _vehicleSnapshot.value.copy(instrumentMileageUnit = it) }
            safetyBeltDriver?.let { _vehicleSnapshot.value = _vehicleSnapshot.value.copy(instrumentSafetyBeltDriverStatus = it) }
            safetyBeltPassenger?.let { _vehicleSnapshot.value = _vehicleSnapshot.value.copy(instrumentSafetyBeltPassengerStatus = it) }
            cabinTemp?.let { _vehicleSnapshot.value = _vehicleSnapshot.value.copy(cabinTemperature = it) }
            averageSpeed?.let { _instrumentAverageSpeed.value = it }
            currentJourneyDriveMileage?.let { _instrumentCurrentJourneyDriveMileage.value = it }
            currentJourneyDriveTime?.let { _instrumentCurrentJourneyDriveTime.value = it }
            batteryPercent?.let { _instrumentBatteryPercent.value = it }
            chargePercent?.let { _instrumentChargePercent.value = it }
            odometerDisplay?.let { _instrumentOdometerDisplay.value = it }
            powerUnit?.let { _instrumentPowerUnit.value = it }
            // DiLink-5: charging power is owned by the D5 client (chargingDev.getChargingPower +
            // the charging listener), which is decimal-accurate. The instrument device's charge-power
            // getter here would either clobber that decimal with an integer/wrong value, or — when it
            // returns null on D5 — make updateDirectChargingPower clear _chargingPowerRaw every tick,
            // flickering charging detection. Let the D5 client own it; DiLink-3 is unchanged.
            if (!DiLink5Platform.isDiLink5) {
                updateDirectChargingPower(instrumentChargingPower)
            }
            
            // ── Instrument Wheel Temperature Values ──────────────────────────
            // Some firmware routes TPMS wheel temperatures through the instrument device.
            val tempSlots = listOf(1 to _tyreTempRF, 2 to _tyreTempRR,
                                   3 to _tyreTempLF, 4 to _tyreTempLR)
            var wheelTempFound = false
            tempSlots.forEach { (slot, flow) ->
                val wheelTemperatureMethods = runtimeMethodNames("m10")
                val rawTemp = invokeIndexedIntGetter(device, slot, *wheelTemperatureMethods)
                    ?: invokeIndexedDoubleGetter(device, slot, *wheelTemperatureMethods)?.toInt()
                val tempC = rawTemp?.takeIf { it in -40..125 }
                if (tempC != null && tempC != flow.value) {
                    flow.value = tempC
                    wheelTempFound = true
                }
            }
            if (wheelTempFound) {
                Log.i(TAG, "🛞 wheelTemperature (Instrument): lf=${_tyreTempLF.value} rf=${_tyreTempRF.value} lr=${_tyreTempLR.value} rr=${_tyreTempRR.value}")
            }

            // ── Tyre pressure fallback via InstrumentDevice ──────────────────────
            // Some firmware (Seal Excellence AWD, Seal U DM-i PHEV) does not register
            // BYDAutoTyreDevice but still exposes TPMS pressure via InstrumentDevice
            // when getDirectTypePressDisplayState=2 (TPMS active). Attempt indexed
            // getters only when TyreDevice is absent to avoid double-writing.
            if (tyreDevice == null) {
                // Slot mapping from InstrumentDevice POSITION constants (confirmed via probe):
                //   POSITION_RF=1, POSITION_RR=2, POSITION_LF=3, POSITION_LR=4
                val instrPressSlots = listOf(
                    1 to Triple(_tyrePressureRF, _tyrePressureRFBar, _tyrePressureRFState),
                    2 to Triple(_tyrePressureRR, _tyrePressureRRBar, _tyrePressureRRState),
                    3 to Triple(_tyrePressureLF, _tyrePressureLFBar, _tyrePressureLFState),
                    4 to Triple(_tyrePressureLR, _tyrePressureLRBar, _tyrePressureLRState),
                )
                val selectedCar = preferencesManager.getCachedSelectedCarConfig()
                val wheelPressMethod = m53["wheelPressure"]?.firstOrNull().orEmpty()
                val wheelPressRawBySlot = instrPressSlots.associate { (slot, _) ->
                    slot to if (wheelPressMethod.isNotEmpty()) invokeIndexedIntGetter(device, slot, wheelPressMethod) else null
                }
                val decodeResult = decodeInstrumentTyrePressureBars(
                    rawBySlot = wheelPressRawBySlot,
                    selectedCar = selectedCar
                )
                var anyPressFound = false
                instrPressSlots.forEach { (slot, flows) ->
                    val (psiFlow, barFlow, stateFlow) = flows
                    val wheelPressRaw = wheelPressRawBySlot[slot]
                    val bar: Double = if (wheelPressRaw != null && wheelPressRaw > 0) {
                        decodeResult.barBySlot[slot] ?: 0.0
                    } else {
                        // Legacy path for other firmware variants that use centi-bar / deci-bar encoding.
                        val rawPress = invokeIndexedDoubleGetter(
                            device, slot, *m53["directPressValue"].orEmpty().toTypedArray()
                        )
                        val state = invokeIndexedIntGetter(
                            device, slot, *m53["directPressState"].orEmpty().toTypedArray()
                        )
                        state?.let { stateFlow.value = it }
                        normalizeTyrePressureBar(rawPress, state)
                    }
                    val psi = barToPsi(bar)
                    if (psi > 0.0) {
                        psiFlow.value = psi
                        barFlow.value = bar
                        anyPressFound = true
                    }
                }
                if (anyPressFound) {
                    Log.i(
                        TAG,
                        "🛞 tyrePress (instrument fallback ${decodeResult.encoding}): " +
                            "raw=$wheelPressRawBySlot " +
                            "lf=${_tyrePressureLF.value} rf=${_tyrePressureRF.value} " +
                            "lr=${_tyrePressureLR.value} rr=${_tyrePressureRR.value}"
                    )
                }
            }

            // ── Gear fallback via InstrumentDevice ──────────────────────────────
            // When GearboxDevice is absent, InstrumentDevice often exposes the same
            // gear/gearbox getters (m26). Reading here lets us correctly distinguish
            // P / N / R / D without relying on speed inference (which cannot tell
            // R from D since reverse speed is also positive).
            if (gearboxDevice == null) {
                val rawGearStr = m26["gearCode"]?.takeIf { it.isNotEmpty() }
                    ?.let { invokeStringGetter(device, it) }
                    ?.takeUnless { it.equals("NULL", ignoreCase = true) }
                val rawGearInt = m26["currentGear"]?.takeIf { it.isNotEmpty() }
                    ?.let { invokeIntGetter(device, it) }
                val instrGear = rawGearStr ?: rawGearInt?.let(::mapGearValue)
                if (instrGear != null) {
                    _gear.value = instrGear
                    instrumentGearKnown = true
                    Log.d(TAG, "⚙️ gear (instrument fallback): $instrGear (str=$rawGearStr int=$rawGearInt)")
                }
            }

            // Collect all available mode values from the device.
            val mOp = invokeIntGetter(device, *runtimeMethodNames("m07"))
            val mDr = invokeIntGetter(device, *runtimeMethodNames("m13"))
            val mEn = invokeIntGetter(device, *runtimeMethodNames("m09"))
            val mFb = invokeIntGetter(device, *runtimeMethodNames("m03"))
            val mRg = invokeIntGetter(device, *runtimeMethodNames("m14"))

            if (ENABLE_LAB_DIAGNOSTICS) {
                Log.i(TAG, "🔍 Mode State (Instrument): op=$mOp dr=$mDr en=$mEn | fb=$mFb rg=$mRg")
            }

            val driveInfo = invokeIntGetter(device, *runtimeMethodNames("m11"))
            val driveModeFromText = when (driveInfo) {
                363 -> 1 // ECO
                364 -> 3 // NORMAL
                365 -> 2 // SPORT
                else -> null
            }
            val ecoIndicatorState = invokeIntGetter(device, *runtimeMethodNames("m12"))
            val driveModeFromIndicator = when (ecoIndicatorState) {
                8353 -> 1   // Eco
                10306 -> 2  // Sport
                135298 -> 3 // Normal
                else -> null
            }
            val fallbackDriveMode = sequenceOf(mDr, mOp, mEn)
                .filterNotNull()
                .firstOrNull { it in 1..6 }
            val energyFeedback = invokeIntGetter(device, "getEnergyFeedback")?.takeIf { it != 65535 }
            val regenMode = mFb ?: mRg
            val driveModeCandidate = driveModeFromText ?: driveModeFromIndicator ?: fallbackDriveMode
            val driveModeSource = when {
                driveModeFromText != null -> "instrument-text"
                driveModeFromIndicator != null -> "instrument-eco-indicator"
                else -> "instrument-fallback"
            }

            updateDriveModeCandidate(
                mode = driveModeCandidate,
                strong = driveModeFromText != null || driveModeFromIndicator != null,
                source = driveModeSource
            )
            updateRegenModeCandidate(regenMode, strong = true, source = "instrument")
            if (regenMode == null) {
                updateRegenModeCandidate(energyFeedback, strong = true, source = "energy-feedback")
            }

            val pm25In = invokeIntGetter(device, *runtimeMethodNames("m15"))?.takeIf { it in 1..999 }
            val pm25Out = invokeIntGetter(device, *runtimeMethodNames("m16"))?.takeIf { it in 1..999 }
            if (pm25In != null || pm25Out != null) {
                updatePm25Snapshot(inCar = pm25In, outCar = pm25Out, source = "instrument-device")
            }

            publishSnapshot()

            if (
                outCarTemp != null || cabinTemp != null || averageSpeed != null || currentJourneyDriveMileage != null ||
                currentJourneyDriveTime != null || batteryPercent != null || chargePercent != null ||
                odometerDisplay != null || powerUnit != null || externalChargingPower != null ||
                driveModeCandidate != null || regenMode != null || energyFeedback != null
            ) {
                logVerboseInfoIfChanged(
                    "instrumentSnapshot",
                ) {
                    "🔬 instrument snapshot: outCarTemp=$outCarTemp cabinTemp=$cabinTemp averageSpeed=$averageSpeed " +
                        "journeyMileage=$currentJourneyDriveMileage journeyTime=$currentJourneyDriveTime " +
                        "batteryPercent=$batteryPercent chargePercent=$chargePercent " +
                        "odometerDisplay=$odometerDisplay powerUnit=$powerUnit externalChargingPower=$externalChargingPower instrumentChargingPower=$instrumentChargingPower " +
                        "coolantTemp=$instrumentCoolantTemp waterMeterPct=$instrumentWaterTempMeterPercent rawWaterMeterPct=$instrumentWaterTempMeterPercentRaw " +
                        "cabinValues=${instrumentCabinValues.entries.joinToString(" ") { (key, value) -> "$key=${String.format("%.1f", value)}" }.ifBlank { "n/a" }} " +
                        "driveMode=$driveModeCandidate source=$driveModeSource ecoIndicatorState=$ecoIndicatorState regenMode=$regenMode energyFeedback=$energyFeedback"
                }
            }

            if (instrumentWaterTempMeterPercentRaw != null || externalChargingPower != null || instrumentChargingPower != null) {
                logWarnIfChanged(
                    "hiddenGetterSummaryInstrument",
                    "🔬 hidden getter summary: instrumentWaterPct=$instrumentWaterTempMeterPercent instrumentWaterPctRaw=$instrumentWaterTempMeterPercentRaw instrumentChargeKw=$instrumentChargingPower externalChargeKw=$externalChargingPower"
                )
            }

            val shouldLogChargingPowerValues = _chargeState.value != 0 ||
                _chargingGunState.value != 0 ||
                _chargerState.value != 0 ||
                _chargerWorkState.value != 0 ||
                chargingPowerValues.isNotEmpty() ||
                _chargingEventPowerCandidateRaw.value != 0.0 ||
                _chargingEventCapacityRaw.value != 0.0
            if (ENABLE_VERBOSE_SNAPSHOT_LOGS && shouldLogChargingPowerValues) {
                val instrumentValueText = chargingPowerValues.entries.joinToString(" ") { (key, value) ->
                    "$key=${String.format("%.3f", value)}"
                }.ifBlank { "n/a" }
                Log.i(
                    TAG,
                    "🔬 charging power values: instrument=$instrumentValueText " +
                        "state=${_chargeState.value} gun=${_chargingGunState.value} charger=${_chargerState.value} work=${_chargerWorkState.value} " +
                        "candidateRaw=${String.format("%.3f", _chargingEventPowerCandidateRaw.value)} " +
                        "capacityRaw=${String.format("%.3f", _chargingEventCapacityRaw.value)} " +
                        "liveKw=${String.format("%.3f", _vehicleSnapshot.value.chargingPower)}"
                )
            }
            if (instrumentSohValues.isNotEmpty()) {
                logInfoIfChanged(
                    "instrumentSohValues",
                    "🔬 instrument soh values: " + instrumentSohValues.entries.joinToString(" ") { (key, value) ->
                        "$key=${String.format("%.3f", value)}"
                    }
                )
                // Apply the first plausible SoH reading from the instrument device.
                // This is the path used by some firmware variants that expose SoH via
                // InstrumentDevice (e.g. getSOH, getBatterySOH) rather than through the
                // charging or battery device — the most likely reason direct-BMS reads
                // fail (method 1) while the instrument exposes it regardless of charge state.
                val instrumentSoh = instrumentSohValues.values
                    .firstOrNull { it.isFinite() && it in 50.0..110.0 }
                    ?.toInt()
                if (instrumentSoh != null) {
                    val prev = _vehicleSnapshot.value
                    if (prev.batterySoh == null || prev.sohEstimated) {
                        _batterySoh.value = instrumentSoh
                        _vehicleSnapshot.update { it.copy(batterySoh = instrumentSoh, sohEstimated = false) }
                        publishSnapshot()
                        Log.i(TAG, "✅ SOH from InstrumentDevice: $instrumentSoh%")
                    }
                }
            }

        } catch (t: Throwable) {
            Log.w(TAG, "Instrument snapshot failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun handleBodyworkEvent(methodName: String, args: Array<out Any?>?) {
        when (methodName) {
            "onAutoSystemStateChanged" -> {
                val state = args?.firstOrNull().asIntOrNull()
                _vehicleSnapshot.value = _vehicleSnapshot.value.copy(bodyworkAutoSystemState = state)
                publishSnapshot()
                Log.d(TAG, "🚗 bodyworkAutoSystemState=$state")
            }
            "onAutoVINChanged" -> {
                val vin = args?.firstOrNull() as? String
                _vehicleSnapshot.value = _vehicleSnapshot.value.copy(bodyworkAutoVin = vin)
                publishSnapshot()
                Log.d(TAG, "🚗 bodyworkAutoVIN=$vin")
            }
            "onBatteryVoltageLevelChanged" -> {
                val level = args?.firstOrNull().asIntOrNull()
                _vehicleSnapshot.value = _vehicleSnapshot.value.copy(bodyworkBatteryVoltageLevel = level)
                publishSnapshot()
                Log.d(TAG, "🚗 bodyworkBatteryVoltageLevel=$level")
            }
            "onDoorStateChanged" -> {
                Log.d(
                    TAG,
                    "🚗 doorState door=${args?.getOrNull(0)} state=${args?.getOrNull(1)}"
                )
            }
            "onFuelElecLowPowerChanged" -> {
                Log.d(TAG, "🚗 bodyworkFuelElecLowPower=${args?.firstOrNull()}")
            }
            "onPowerLevelChanged" -> {
                val level = args?.firstOrNull().asIntOrNull()
                val prevLevel = _vehicleSnapshot.value.bodyworkPowerLevel
                _vehicleSnapshot.value = _vehicleSnapshot.value.copy(bodyworkPowerLevel = level)
                publishSnapshot()
                Log.d(TAG, "🚗 bodyworkPowerLevel=$level")
                // Capture the off→on power-level transitions to diag.log so the
                // value→state mapping can be learned (this bodywork power level is the
                // car-on signal Electro uses; the power-device MCU status is silent on
                // these firmwares). Once mapped, derive carOn from it for instant on/off.
                // DORMANT (see CLAUDE.md): BodyworkDevice doesn't register in-process on this
                // firmware, so onPowerLevelChanged never fires here (car-on via power level was
                // Electro's/Kinex's approach via a different API). Harmless capture; keep it.
                if (level != prevLevel) {
                    DiagLog.event(appContext, TAG, "🚗 bodyworkPowerLevel: $prevLevel → $level")
                }
            }
        }
    }

    private fun handleEngineEvent(methodName: String, args: Array<out Any?>?) {
        when (methodName) {
            "onEngineSpeedChanged" -> {
                val speed = args?.firstOrNull().asIntOrNull()
                Log.d(TAG, "⚙️ engineSpeed=$speed")
            }
            "onEngineCoolantLevelChanged" -> {
                Log.d(TAG, "⚙️ engineCoolantLevel=${args?.firstOrNull()}")
            }
            "onOilLevelChanged" -> {
                Log.d(TAG, "⚙️ engineOilLevel=${args?.firstOrNull()}")
            }
        }
    }

    private fun handleStatisticEvent(methodName: String, args: Array<out Any?>?) {
        when (methodName) {
            "onElecPercentageChanged" -> {
                // Only accept a finite, in-range value. Without this guard a single bad
                // event (null arg, sentinel, or out-of-range) would overwrite the
                // previously-good polled value, leaving the dashboard SoC at 0.
                val pct = args?.firstOrNull().asDoubleOrNull()
                    ?.takeIf { it.isFinite() && it in 0.0..100.0 }
                if (pct != null) {
                    _vehicleSnapshot.value = _vehicleSnapshot.value.copy(statisticElecPercentageValue = pct)
                    publishSnapshot()
                }
                Log.d(TAG, "📈 elecPercentage=$pct")
            }
            "onElecDrivingRangeChanged" -> {
                val range = args?.firstOrNull().asIntOrNull()
                _vehicleSnapshot.value = _vehicleSnapshot.value.copy(statisticElecDrivingRangeValue = range)
                publishSnapshot()
                Log.d(TAG, "📈 elecDrivingRange=$range")
            }
            "onFuelPercentageChanged" -> {
                val pct = args?.firstOrNull().asIntOrNull()
                _vehicleSnapshot.value = _vehicleSnapshot.value.copy(statisticFuelPercentageValue = pct)
                publishSnapshot()
                Log.d(TAG, "📈 fuelPercentage=$pct")
            }
            "onFuelDrivingRangeChanged" -> {
                val range = args?.firstOrNull().asIntOrNull()
                _vehicleSnapshot.value = _vehicleSnapshot.value.copy(statisticFuelDrivingRangeValue = range)
                publishSnapshot()
                Log.d(TAG, "📈 fuelDrivingRange=$range")
            }
            "onTotalElecConChanged" -> {
                val total = args?.firstOrNull().asDoubleOrNull()
                _vehicleSnapshot.value = _vehicleSnapshot.value.copy(statisticTotalElecConValue = total)
                publishSnapshot()
                Log.d(TAG, "📈 totalElecCon=$total")
            }
            "onTotalElecConPHMChanged" -> {
                val rate = args?.firstOrNull().asDoubleOrNull()?.takeIf { it in 5.0..50.0 }
                if (rate != null) {
                    _vehicleSnapshot.value = _vehicleSnapshot.value.copy(statisticTotalElecConPHMValue = rate)
                    publishSnapshot()
                    Log.d(TAG, "📈 totalElecConPHM=$rate")
                }
            }
            "onTotalMileageChanged" -> {
                val total = args?.firstOrNull().asIntOrNull()
                _vehicleSnapshot.value = _vehicleSnapshot.value.copy(statisticTotalMileageValue = total)
                publishSnapshot()
                Log.d(TAG, "📈 totalMileage=$total")
            }
            "onDrivingTimeChanged" -> {
                val duration = args?.firstOrNull().asDoubleOrNull()
                _vehicleSnapshot.value = _vehicleSnapshot.value.copy(statisticDrivingTimeValue = duration)
                publishSnapshot()
                Log.d(TAG, "📈 drivingTime=$duration")
            }
            "onKeyBatteryLevelChanged" -> {
                Log.d(TAG, "📈 keyBatteryLevel=${args?.firstOrNull()}")
            }
        }
    }

    private fun handleInstrumentEvent(methodName: String, args: Array<out Any?>?) {
        when (methodName) {
            "onSafetyBeltStatusChanged" -> {
                val seat = args?.getOrNull(0).asIntOrNull()
                val status = args?.getOrNull(1).asIntOrNull()
                _vehicleSnapshot.value = when (seat) {
                    0 -> _vehicleSnapshot.value.copy(instrumentSafetyBeltDriverStatus = status)
                    1 -> _vehicleSnapshot.value.copy(instrumentSafetyBeltPassengerStatus = status)
                    else -> _vehicleSnapshot.value
                }
                publishSnapshot()
                Log.d(TAG, "🎗 safetyBelt seat=$seat status=$status")
            }
        }
    }

    private inline fun runSnapshotBatch(block: () -> Unit) {
        synchronized(snapshotPublishLock) {
            snapshotBatchDepth += 1
        }
        try {
            block()
        } finally {
            val shouldFlush = synchronized(snapshotPublishLock) {
                snapshotBatchDepth -= 1
                if (snapshotBatchDepth == 0 && snapshotPublishPending) {
                    snapshotPublishPending = false
                    true
                } else {
                    false
                }
            }
            if (shouldFlush) {
                publishSnapshotNow()
            }
        }
    }

    /**
     * Apply instant speed/gear pushed by the privileged telemetry daemon (TelemetryDaemonClient).
     * Drives the live UI snapshot immediately; the 1 s poll yields to it via lastSpeedPushElapsedMs
     * (see logSpeedSnapshot) and daemonGearKnown (see toTelemetry). DB recording stays on the poll.
     */
    fun applyDaemonTelemetry(
        speedKmh: Double?, gear: String?, powerKw: Double?,
        frontRpm: Int? = null, rearRpm: Int? = null,
    ) {
        val now = SystemClock.elapsedRealtime()
        var changed = false
        if (speedKmh != null && speedKmh in 0.0..MAX_PLAUSIBLE_SPEED_KMH) {
            lastSpeedPushElapsedMs = now
            if (speedKmh > 0.1) lastPositiveSpeedElapsedMs = now
            _vehicleSnapshot.value = _vehicleSnapshot.value.copy(directSpeedKmh = speedKmh)
            changed = true
        }
        if (gear != null && gear.isNotEmpty()) {
            daemonGearKnown = true
            if (_gear.value != gear) { _gear.value = gear; changed = true }
        }
        if (powerKw != null && kotlin.math.abs(powerKw) <= 500.0) {
            lastPowerPushElapsedMs = now
            val kwInt = Math.round(powerKw).toInt()
            if (_vehicleSnapshot.value.enginePower != kwInt) {
                _vehicleSnapshot.value = _vehicleSnapshot.value.copy(enginePower = kwInt)
                changed = true
            }
        }
        if (frontRpm != null || rearRpm != null) {
            lastRpmPushElapsedMs = now
            val snap = _vehicleSnapshot.value
            val newFront = frontRpm ?: snap.engineSpeedFront
            val newRear = rearRpm ?: snap.engineSpeedRear
            if (newFront != snap.engineSpeedFront || newRear != snap.engineSpeedRear) {
                _vehicleSnapshot.value = snap.copy(engineSpeedFront = newFront, engineSpeedRear = newRear)
                changed = true
            }
        }
        if (changed) {
            publishSnapshot()
            if (now - lastDaemonDiagMs > 5_000L) {
                lastDaemonDiagMs = now
                DiagLog.event(appContext, TAG, "📡 daemon telemetry: speed=${speedKmh ?: "-"} gear=${gear ?: "-"} power=${powerKw ?: "-"} rpmF=${frontRpm ?: "-"} rpmR=${rearRpm ?: "-"}")
            }
        }
    }
    private var lastDaemonDiagMs = 0L

    /**
     * Apply DiLink-5 statistic/charging telemetry pushed by the DiLink-5 client (dilink5 flavor
     * only; started reflectively from start()). Mirrors applyDaemonTelemetry: writes the raw
     * snapshot fields the existing toTelemetry()/ABRP path already reads, then publishes once.
     * Speed and derived driving-power go through applyDaemonTelemetry; this handles
     * soc / total-mileage / elec-range / usable-kWh / SOH / charge-power. Pure Kotlin (no bydauto
     * types) so it compiles in both flavors. All inputs nullable + range-guarded.
     */
    fun applyDilink5Telemetry(
        socPanelPct: Int? = null,
        totalMileageKm: Double? = null,
        elecRangeKm: Int? = null,
        usableKwh: Double? = null,
        sohPct: Double? = null,
        chargingPowerKw: Double? = null,
    ) {
        var changed = false
        var snap = _vehicleSnapshot.value
        // Confirmed integer-only on D5 — the dash/panel reading, not decimal BMS. Floored at 1 to
        // reject the car's occasional transient-0 glitch. "BMS" mode gets its decimal precision
        // from toTelemetry()'s derivedBmsSoc (usableKwh/batteryKwh*100) instead — no extra wiring
        // needed, it already falls through once this bucket stays unfed.
        if (socPanelPct != null && socPanelPct in 1..100) {
            snap = snap.copy(statisticElecPercentageValue = socPanelPct.toDouble()); changed = true
        }
        if (totalMileageKm != null && totalMileageKm in 1.0..9_999_999.0) {
            snap = snap.copy(statisticTotalMileageDecimal = totalMileageKm,
                             statisticTotalMileageValue = totalMileageKm.toInt()); changed = true
        }
        if (elecRangeKm != null && elecRangeKm in 0..2000) {
            snap = snap.copy(statisticElecDrivingRangeValue = elecRangeKm); changed = true
        }
        if (usableKwh != null && usableKwh in 0.0..200.0) {
            snap = snap.copy(powerBatteryRemainPowerEV = usableKwh); changed = true
        }
        if (changed) _vehicleSnapshot.value = snap
        if (sohPct != null && sohPct in 50.0..110.0) { _statisticBatterySoh.value = sohPct; changed = true }
        if (chargingPowerKw != null && chargingPowerKw in 0.0..250.0) {
            _chargingPowerKw.value = chargingPowerKw; _chargingPowerRaw.value = chargingPowerKw; changed = true
        }
        if (changed) publishSnapshot()
    }

    /**
     * DiLink-5 tyre pressure STATE only (0=normal/1=over/2=under) — a discrete enum, unit-independent,
     * safe to poll via getTyrePressureState(area). The pressure VALUE is deliberately NOT polled here
     * anymore: getTyrePressureValueByType(area) re-scales its raw output to whatever pressure unit the
     * dash cluster currently has selected (confirmed on-car: raw ~27 in Bar mode, ~277 in kPa mode,
     * ~401 in PSI mode, same physical tyre — this is why the old "raw is always kPa" fix still showed
     * wrong numbers in Bar/PSI mode). The value now arrives only via the tyre listener's
     * onTyrePressureValueByTypeChanged event, decoded using the live unit state from
     * applyDilink5PressureUnit — see that function and onTyrePressureValueByTypeChanged below.
     */
    fun applyDilink5TyreState(
        lfState: Int?, rfState: Int?, lrState: Int?, rrState: Int?,
    ) {
        fun state(s: Int?): Int? = s?.takeIf { it in 0..3 }
        state(lfState)?.let { _tyrePressureLFState.value = it }
        state(rfState)?.let { _tyrePressureRFState.value = it }
        state(lrState)?.let { _tyrePressureLRState.value = it }
        state(rrState)?.let { _tyrePressureRRState.value = it }
        publishSnapshot()
    }

    // Current tyre-pressure display unit (1=Bar, 2=PSI, 3=kPa), from instrument fid=4208
    // (INSTRUMENT_UNIT_PRESSURE). Null until the first poll/event arrives — decode is skipped rather
    // than guessed while unknown. See registerInstrumentListener (Dilink5Client) for how this is set.
    @Volatile private var dilink5PressureUnit: Int? = null

    fun applyDilink5PressureUnit(unit: Int) {
        if (unit !in 1..3) return
        dilink5PressureUnit = unit
    }

    /**
     * Decodes a raw DiLink-5 tyre-pressure-ByType value into bar, using the live unit state from
     * [applyDilink5PressureUnit]. Returns null while the unit is still unknown (never guesses).
     *   1 (Bar): raw/10 = bar        (confirmed on-car: raw ~27 → 2.7 bar)
     *   2 (PSI): raw/10 = psi        (confirmed on-car: raw ~401 → 40.1 psi → 2.76 bar)
     *   3 (kPa): raw/100 = bar       (confirmed on-car: raw ~277 → 2.77 bar)
     */
    private fun decodeDilink5PressureByTypeBar(raw: Float): Double? {
        val v = raw.toDouble()
        return when (dilink5PressureUnit) {
            1 -> v / 10.0
            2 -> (v / 10.0) * 0.0689476
            3 -> v / 100.0
            else -> null
        }?.takeIf { it in 1.0..5.0 }  // sane tyre-pressure range guard
    }

    /**
     * Per-wheel tyre temperature from the DiLink-5 tyre listener (onTyreTemperatureValueChanged).
     * Wheel index is 0-based: 0=LF, 1=RF, 2=LR, 3=RR (= getter area − 1). The polling getter
     * getTyreTemperatureValue is area-blind (uniform value on all wheels) — real per-wheel temp is
     * event-only. See applyDilink5TyreTemp for the on-car index confirmation.
     */
    /**
     * DiLink-5 HV pack voltage from the collectdata event (onMotorMCUGeneratrixVolt). Getters return
     * -1/dead; this is the real measured pack voltage (≈450–480 V).
     */
    fun applyDilink5HvVoltage(volts: Int) {
        if (volts !in 100..1000) return
        // Write the snapshot field directly: publishSnapshot preserves batteryTotalVoltage from the
        // snapshot (not the _batteryTotalVoltage StateFlow), so a StateFlow write wouldn't surface.
        _vehicleSnapshot.value = _vehicleSnapshot.value.copy(batteryTotalVoltage = volts)
        _batteryTotalVoltage.value = volts   // keep the flow in sync for other readers
        publishSnapshot()
    }

    /**
     * DiLink-5 license device id = the T-Box serial (ota.getTBoxSerialNumber), used raw exactly like
     * DiLink-3 uses the raw VIN: fed to EntitlementManager, which normalize()s it (lowercase/trim);
     * the HMAC lives only in ProLicenseBridge for the code, not the id itself. No app-side hashing.
     */
    fun applyDilink5TboxSerial(serial: String) {
        val s = serial.trim()
        if (s.length !in 4..64 || s.equals(_vehicleSnapshot.value.tboxSerialNumber, ignoreCase = true)) return
        _vehicleSnapshot.value = _vehicleSnapshot.value.copy(tboxSerialNumber = s)
        publishSnapshot()
    }

    /**
     * DiLink-5 HV pack current from the collectdata event (onMotorMCUGeneratrixCurrent). Signed amps:
     * positive = discharge, negative = regen/charging. Written to the snapshot directly (publishSnapshot
     * preserves unlisted snapshot fields).
     */
    fun applyDilink5HvCurrent(amps: Int) {
        if (amps !in -2000..2000) return
        _vehicleSnapshot.value = _vehicleSnapshot.value.copy(batteryTotalCurrent = amps.toDouble())
        publishSnapshot()
    }

    // DiLink-5 drive mode from instrument.getSportModeState. Its raw value matches the
    // app's canonical driveMode encoding directly (1=Eco 2=Sport 3=Normal 4=Snow; 5=Mud 6=Sand),
    // confirmed on-car 2026-07-12 — no remap needed. Treated as a strong/confirmed source.
    fun applyDilink5DriveMode(raw: Int) {
        if (raw !in 1..6) return
        updateDriveModeCandidate(raw, strong = true, source = "d5-sportmode")
        publishSnapshot()
    }

    // DiLink-5 regen (energy feedback) strength from setting.getEnergyFeedback() / its event
    // onEnergyFeedbackStrengthChanged. Confirmed on-car: raw 2=Standard,
    // 3=High — remapped to the app's canonical 1=Standard/2=High encoding used everywhere else
    // (UI, trip JSON, MQTT). The parallel energy.getEnergyFeedback() (CarBodyManager path) is
    // dead (constant 0 through a parked toggle test) — not used.
    fun applyDilink5RegenMode(raw: Int) {
        val normalized = when (raw) {
            2 -> 1  // Standard
            3 -> 2  // High
            else -> return
        }
        updateRegenModeCandidate(normalized, strong = true, source = "d5-setting")
        publishSnapshot()
    }

    // Follow-up: ambient / outside-air temperature from instrument.getOutCarTemperature
    // (or its event). Written to the snapshot directly because publishSnapshot preserves
    // instrumentOutCarTemperature from the snapshot itself (not a StateFlow).
    // 12V aux battery voltage from ota.getBatteryVoltage(0) (confirmed on-car = 13 V).
    // publishSnapshot reads _battery12vVoltage first, so write the StateFlow (+ snapshot for parity).
    fun applyDilink5AuxVoltage(volts: Int) {
        if (volts !in 6..17) return   // SDK BATTERY_VOLTAGE range; drops the -1 sentinel
        _battery12vVoltage.value = volts.toDouble()
        _vehicleSnapshot.value = _vehicleSnapshot.value.copy(battery12vVoltage = volts.toDouble())
        publishSnapshot()
    }

    fun applyDilink5AmbientTemp(tempC: Int) {
        // 0 = the AC getter's no-data sentinel (ac.getTemprature(4) reads real outside temp only while
        // the climate system is powered; returns 0 otherwise). Drop it so the UI shows "—" not "0°C".
        // Real 0 °C is indistinguishable here, but a false 0 in warm weather is the worse failure.
        if (tempC == 0 || tempC !in -50..60) return   // range guard also drops the 255 sentinel
        _vehicleSnapshot.value = _vehicleSnapshot.value.copy(instrumentOutCarTemperature = tempC)
        publishSnapshot()
    }

    fun applyDilink5TyreTemp(wheel: Int, tempC: Int) {
        // Drop 0/out-of-range: 0 is the sleep/no-data sentinel (TPMS temp sensors sleep when parked;
        // getWheelTemperature and the events both return 0 then). Dropping it keeps the last real
        // temp on screen instead of blanking each wheel back to "no data".
        if (tempC <= 0 || tempC > 120) return
        // Tyre EVENT wheel index is 0-based (= getter area − 1): 0=LF, 1=RF, 2=LR, 3=RR. Confirmed
        // on-car 2026-07-12 by matching press-event kPa per index against the per-area byType getter
        // (event 0≈LF pressure, 1≈RF, 2≈LR, 3≈RR).
        when (wheel) {
            0 -> _tyreTempLF.value = tempC
            1 -> _tyreTempRF.value = tempC
            2 -> _tyreTempLR.value = tempC
            3 -> _tyreTempRR.value = tempC
            else -> return   // out of range
        }
        publishSnapshot()
    }

    // DiLink-5 client (dilink5 flavor only) — loaded reflectively so src/main stays flavor-agnostic.
    private var dilink5Client: Any? = null
    private fun startDilink5Client() {
        try {
            val cls = Class.forName("com.byd.tripstats.sdk.Dilink5Client")
            val client = cls.getDeclaredConstructor().newInstance()
            cls.getMethod("start", Context::class.java, BydVehicleDataSource::class.java)
                .invoke(client, ctx, this)
            dilink5Client = client
            Log.i(TAG, "✅ DiLink-5 client started")
        } catch (e: ClassNotFoundException) {
            Log.w(TAG, "DiLink-5 client not present in this build")
        } catch (t: Throwable) {
            Log.w(TAG, "DiLink-5 client start failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }
    private fun stopDilink5Client() {
        dilink5Client?.let { c -> try { c.javaClass.getMethod("stop").invoke(c) } catch (_: Throwable) {} }
        dilink5Client = null
    }

    /**
     * Recover a wedged SDK event-callback channel. On this firmware the BYD SDK occasionally stops
     * delivering pushed callbacks (speed/gear/etc.) while synchronous getters keep working — events
     * silently stall and only resume after the listeners are re-registered (empirically, a full
     * service restart fixed it). This does the lightweight in-process equivalent: unregister the
     * current listeners and register fresh ones, so we don't have to bounce the whole service.
     * Throttled so a persistent stall can't spin. Returns true if a recovery pass ran.
     */
    fun recoverEventDelivery(force: Boolean = false): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastWedgeRecoveryMs < 30_000L) return false
        lastWedgeRecoveryMs = now
        DiagLog.event(appContext, TAG, "♻️ SDK event-delivery wedge — re-registering listeners (force=$force)")
        // Separate runCatching per step so a failed unregister never leaves us without the listener.
        runCatching { gearboxDevice?.unregisterListener(gearboxListener) }
        runCatching { gearboxDevice?.registerListener(gearboxListener) }
        runCatching { chargingDevice?.unregisterListener(chargingListener) }
        runCatching { chargingDevice?.registerListener(chargingListener) }
        runCatching { tyreDevice?.unregisterListener(tyreListener) }
        runCatching { tyreDevice?.registerListener(tyreListener) }
        // Generic mirror listeners: drop the stale proxy, register a fresh one (registerEventMirrorListener
        // de-dupes its own tracking by device).
        for (reg in mirrorRegs.toList()) {
            runCatching {
                reg.device.javaClass.methods.firstOrNull {
                    it.name == "unregisterListener" && it.parameterTypes.size == 1 &&
                        it.parameterTypes[0].isAssignableFrom(reg.proxy.javaClass)
                }?.invoke(reg.device, reg.proxy)
            }
            runCatching { registerEventMirrorListener(reg.device, reg.label) }
        }
        return true
    }

    private fun publishSnapshot() {
        val publishImmediately = synchronized(snapshotPublishLock) {
            if (snapshotBatchDepth > 0) {
                snapshotPublishPending = true
                false
            } else {
                true
            }
        }
        if (publishImmediately) {
            publishSnapshotNow()
        }
    }

    private fun publishSnapshotNow() {
        val nowElapsedMs = SystemClock.elapsedRealtime()
        val recentPackPower = hasFreshPackChargingPower(nowElapsedMs)
        val recentDirectPower = hasFreshDirectChargingPower(nowElapsedMs)
        val recentCapacityActivity = lastChargingActivityElapsedMs != 0L &&
            nowElapsedMs - lastChargingActivityElapsedMs <= 8_000L
        // Charging-power source priority. NOTE the last tier measures a DIFFERENT physical quantity:
        //   recentPackPower (m33) and recentDirectPower are GROSS charge power
        //     (what the charger pushes), matching the BYD app's figure. Available on e.g. the Seal.
        //   recentCapacityActivity falls back to _chargingPowerKw, derived from the chargingCapacity
        //     counter's rate. That counter accrues NET energy reaching the cells, i.e. gross minus
        //     whatever accessories divert. So on cars where m33 returns 0 the whole charge (verified
        //     on an Atto 3: m33Power=0.0 throughout, enginePower=-4 ≈ 4 kW gross, cap-rate ≈ 2.2 kW),
        //     charging_power reads LOWER than engine_power and visibly dips with load — e.g. the owner
        //     saw 4.1→2.1 kW with aircon on, 4.1→3.5 kW with the car powered on without aircon.
        //   This is intentional/known, not a bug. If a future change should report gross on those
        //   cars instead, add a tier here sourcing |enginePower| (m23) while charging.
        val effectiveChargingPowerKw = when {
            recentPackPower -> _packChargingPowerKw.value     // m33 pack power — authoritative, sees DC (to 500 kW)
            recentDirectPower -> _chargingPowerRaw.value
            recentCapacityActivity -> _chargingPowerKw.value  // capacity-rate = NET into battery (see note above)
            else -> 0.0
        }
        // Only the capacity-rate tier is NET (under-reports); the pack/direct getters are gross and
        // decimal-accurate. toTelemetry uses this to know when the whole-kW enginePower fallback is
        // allowed to override chargingPower (net) vs. must defer to it (gross getter).
        val chargingPowerFromNet = !recentPackPower && !recentDirectPower && recentCapacityActivity
        _vehicleSnapshot.value = VehicleTelemetrySnapshot(
            wifiSsid = currentWifiSsid(),
            isChargingActive = computeChargingActive(),
            chargingPower = effectiveChargingPowerKw,
            chargingPowerIsNet = chargingPowerFromNet,
            chargingPowerRaw = _chargingPowerRaw.value,
            chargingCapacity = _chargingCapacity.value,
            chargingCapState = _chargingCapState.value,
            chargingCapValue = _chargingCapValue.value,
            chargingEventPowerCandidateRaw = _chargingEventPowerCandidateRaw.value,
            chargingEventCapacityRaw = _chargingEventCapacityRaw.value,
            chargingEventUnknownInt27Raw = _chargingEventUnknownInt27Raw.value,
            chargingEventUnknownCounterRaw = _chargingEventUnknownCounterRaw.value,
            gear = run {
                val resolved = when {
                    // Privileged telemetry daemon supplied a real gear (P/R/N/D) — authoritative.
                    // Correctly handles R while reversing, where the speed-inference fallback below
                    // would otherwise show "D".
                    daemonGearKnown -> _gear.value
                    gearboxDevice != null -> _gear.value
                    // InstrumentDevice successfully read a gear value — use it directly.
                    // This correctly handles R, N, P as well as D and avoids the speed
                    // ambiguity (reverse speed is also positive).
                    instrumentGearKnown -> _gear.value
                    // Last resort: no gear device and instrument gave nothing.
                    // Speed > 1 km/h means the car is moving; the only reasonable guess is D.
                    // R is indistinguishable from D here, but this is better than showing P
                    // while clearly driving forward in HEV/EV mode.
                    _vehicleSnapshot.value.directSpeedKmh > 1.0 -> "D"
                    else -> _gear.value
                }
                // Physical-impossibility guard: you cannot be in Park at speed — the
                // parking pawl would lock the driveline. Some firmwares leave the gearbox
                // gear stuck at its default "P" when the gear getter/event never reports,
                // and the gearboxDevice!=null branch above trusts that stale "P" forever
                // (which also makes the gear=D/R car-on proxy read false while driving).
                // Override to "D" only when clearly moving forward; leave R alone (low-speed
                // reverse is legitimate) and stay above creep speed to avoid false flips.
                if (resolved == "P" && _vehicleSnapshot.value.directSpeedKmh > 5.0) "D" else resolved
            },
            chargeState = _chargeState.value,
            remainMinutes = _remainMinutes.value,
            remainHours = _remainHours.value,
            chargerState = _chargerState.value,
            chargerWorkState = _chargerWorkState.value,
            chargingType = _chargingType.value,
            chargingGunState = _chargingGunState.value,
            chargingMode = _chargingMode.value,
            gearboxState = _gearboxState.value,
            currentGearRaw = _currentGearRaw.value,
            tyrePressureLFPsi = _tyrePressureLF.value,
            tyrePressureRFPsi = _tyrePressureRF.value,
            tyrePressureLRPsi = _tyrePressureLR.value,
            tyrePressureRRPsi = _tyrePressureRR.value,
            tyrePressureLFBar = _tyrePressureLFBar.value,
            tyrePressureRFBar = _tyrePressureRFBar.value,
            tyrePressureLRBar = _tyrePressureLRBar.value,
            tyrePressureRRBar = _tyrePressureRRBar.value,
            tyrePressureLFState = _tyrePressureLFState.value,
            tyrePressureRFState = _tyrePressureRFState.value,
            tyrePressureLRState = _tyrePressureLRState.value,
            tyrePressureRRState = _tyrePressureRRState.value,
            tyreTempLF = _tyreTempLF.value,
            tyreTempRF = _tyreTempRF.value,
            tyreTempLR = _tyreTempLR.value,
            tyreTempRR = _tyreTempRR.value,
            directSpeedKmh = _vehicleSnapshot.value.directSpeedKmh,
            speedAccelerateDeepness = _speedAccelerateDeepness.value,
            speedBrakeDeepness = _speedBrakeDeepness.value,
            gearboxBrakePedalState = _gearboxBrakePedalState.value,
            instrumentAverageSpeed = _instrumentAverageSpeed.value,
            instrumentCurrentJourneyDriveMileage = _instrumentCurrentJourneyDriveMileage.value,
            driveMode = _driveMode.value,
            energyMode = _energyMode.value,
            regenMode = _regenMode.value,
            instrumentCurrentJourneyDriveTime = _instrumentCurrentJourneyDriveTime.value,
            instrumentBatteryPercent = _instrumentBatteryPercent.value,
            instrumentChargePercent = _instrumentChargePercent.value,
            instrumentOdometerDisplay = _instrumentOdometerDisplay.value,
            instrumentPowerUnit = _instrumentPowerUnit.value,
            turnSignalFlashState = _turnSignalFlashState.value,
            turnSignalLeft = _turnSignalLeft.value,
            turnSignalRight = _turnSignalRight.value,
            bodyworkAutoSystemState = _vehicleSnapshot.value.bodyworkAutoSystemState,
            bodyworkBatteryCapacity = _vehicleSnapshot.value.bodyworkBatteryCapacity,
            bodyworkBatteryPowerHEV = _vehicleSnapshot.value.bodyworkBatteryPowerHEV,
            bodyworkBatteryPowerValue = _vehicleSnapshot.value.bodyworkBatteryPowerValue,
            bodyworkBatteryVoltageLevel = _vehicleSnapshot.value.bodyworkBatteryVoltageLevel,
            bodyworkPowerLevel = _vehicleSnapshot.value.bodyworkPowerLevel,
            bodyworkAutoVin = _vehicleSnapshot.value.bodyworkAutoVin,
            tboxSerialNumber = _vehicleSnapshot.value.tboxSerialNumber,
            powerBatteryRemainPowerEV = _vehicleSnapshot.value.powerBatteryRemainPowerEV,
            sensorTemperatureValue = _vehicleSnapshot.value.sensorTemperatureValue,
            cabinTemperature = _vehicleSnapshot.value.cabinTemperature,
            instrumentLast50KmPowerConsume = _vehicleSnapshot.value.instrumentLast50KmPowerConsume,
            instrumentOutCarTemperature = _vehicleSnapshot.value.instrumentOutCarTemperature,
            pm25InCar = _vehicleSnapshot.value.pm25InCar,
            pm25OutCar = _vehicleSnapshot.value.pm25OutCar,
            instrumentMileageUnit = _vehicleSnapshot.value.instrumentMileageUnit,
            instrumentSafetyBeltDriverStatus = _vehicleSnapshot.value.instrumentSafetyBeltDriverStatus,
            instrumentSafetyBeltPassengerStatus = _vehicleSnapshot.value.instrumentSafetyBeltPassengerStatus,
            locationLatitude = _vehicleSnapshot.value.locationLatitude,
            locationLongitude = _vehicleSnapshot.value.locationLongitude,
            locationAltitude = _vehicleSnapshot.value.locationAltitude,
            locationGpsSpeed = _vehicleSnapshot.value.locationGpsSpeed,
            locationVisibleSatelliteNumber = _vehicleSnapshot.value.locationVisibleSatelliteNumber,
            locationFixPosition = _vehicleSnapshot.value.locationFixPosition,
            locationOrientation = _vehicleSnapshot.value.locationOrientation,
            enginePower = _vehicleSnapshot.value.enginePower,
            engineSpeedFront = _vehicleSnapshot.value.engineSpeedFront,
            engineSpeedRear = _vehicleSnapshot.value.engineSpeedRear,
            batterySoh = _vehicleSnapshot.value.batterySoh,
            batteryTotalVoltage = _vehicleSnapshot.value.batteryTotalVoltage,
            batteryTotalCurrent = _vehicleSnapshot.value.batteryTotalCurrent,
            battery12vVoltage = _battery12vVoltage.value
                ?: _vehicleSnapshot.value.battery12vVoltage,
            batteryPackTemp = validatePackTemp(_vehicleSnapshot.value.batteryPackTemp),
            // Cell temps are already decoded (via decodePackTempCelsius) and sentinel-filtered
            // at the write sites in logChargingSnapshot / logBatterySnapshot — pass through.
            batteryCellTempMax = _vehicleSnapshot.value.batteryCellTempMax,
            batteryCellTempMin = _vehicleSnapshot.value.batteryCellTempMin,
            batteryCellVoltageMax = _vehicleSnapshot.value.batteryCellVoltageMax,
            batteryCellVoltageMin = _vehicleSnapshot.value.batteryCellVoltageMin,
            carLocked = _vehicleSnapshot.value.carLocked,
            anyDoorOpened = _vehicleSnapshot.value.anyDoorOpened,
            statisticElecPercentageValue = _vehicleSnapshot.value.statisticElecPercentageValue,
            statisticElecDrivingRangeValue = _vehicleSnapshot.value.statisticElecDrivingRangeValue,
            statisticFuelPercentageValue = _vehicleSnapshot.value.statisticFuelPercentageValue,
            statisticFuelDrivingRangeValue = _vehicleSnapshot.value.statisticFuelDrivingRangeValue,
            statisticTotalElecConValue = _vehicleSnapshot.value.statisticTotalElecConValue,
            statisticTotalMileageValue = _vehicleSnapshot.value.statisticTotalMileageValue,
            statisticTotalMileageDecimal = _vehicleSnapshot.value.statisticTotalMileageDecimal,
            statisticDrivingTimeValue = _vehicleSnapshot.value.statisticDrivingTimeValue,
            statisticWaterTemperature = _vehicleSnapshot.value.statisticWaterTemperature,
            statisticAvgFuelConsumption = _vehicleSnapshot.value.statisticAvgFuelConsumption,
            statisticInstantFuelCon = _vehicleSnapshot.value.statisticInstantFuelCon,
            statisticTotalFuelConValue = _vehicleSnapshot.value.statisticTotalFuelConValue,
            statisticEvMileageValue = _vehicleSnapshot.value.statisticEvMileageValue,
            statisticHevMileageValue = _vehicleSnapshot.value.statisticHevMileageValue,
            statisticSocBatteryPct = _vehicleSnapshot.value.statisticSocBatteryPct,
            statisticBatteryCurrent = _statisticBatteryCurrent.value,
            statisticCellVoltageMin = _statisticCellVoltageMin.value,
            statisticCellVoltageMax = _statisticCellVoltageMax.value,
            statisticCellTempMin    = validatePackTemp(_statisticCellTempMin.value),
            statisticCellTempMax    = validatePackTemp(_statisticCellTempMax.value),
            statisticCellTempAvg    = validatePackTemp(_statisticCellTempAvg.value),
            statisticBatterySoh     = _statisticBatterySoh.value,
            statisticSocBms         = _statisticSocBms.value,
            statisticAvailPower     = _statisticAvailPower.value,
            probeValues             = _vehicleSnapshot.value.probeValues,
            roadSlopeDeg            = _vehicleSnapshot.value.roadSlopeDeg,
        )
        persistStatisticState(_vehicleSnapshot.value)
        logPrimaryTelemetrySummaryIfChanged(_vehicleSnapshot.value)
        if (ENABLE_VERBOSE_SNAPSHOT_LOGS) {
            logBatterySummaryIfChanged(_vehicleSnapshot.value)
            logStatisticMapIfChanged(_vehicleSnapshot.value)
            logDrivetrainSummaryIfChanged(_vehicleSnapshot.value)
            logDriveCaptureIfChanged(_vehicleSnapshot.value)
        }
    }

    private fun updateDriveModeCandidate(mode: Int?, strong: Boolean, source: String) {
        val normalized = mode?.takeIf { it in 1..6 } ?: return
        val current = _driveMode.value
        val shouldApply = when {
            strong -> true
            !hasConfirmedDriveMode -> true
            current == DRIVE_MODE_UNKNOWN -> true
            normalized == 1 -> false  // Don't overwrite a confirmed mode with a weak eco signal
            normalized == current -> true
            else -> true
        }
        if (!shouldApply) {
            if (ENABLE_LAB_DIAGNOSTICS) {
                Log.i(TAG, "🔍 Drive mode preserved: current=$current incoming=$normalized source=$source")
            }
            return
        }
        _driveMode.value = normalized
        if (strong) {
            hasConfirmedDriveMode = true
        }
    }

    private fun writeRegenDiag(key: String?, message: String) {
        if (key != null) {
            val previous = lastRegenDiagMessages.put(key, message)
            if (previous == message) return
        }
        try {
            val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
            val line = "$ts $message\n"
            val resolver = appContext.contentResolver
            val collection = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val name = "regen_diag.txt"
            val relativePath = "Download/BydTripStats/"
            val existingUri = resolver.query(
                collection,
                arrayOf(android.provider.MediaStore.Downloads._ID),
                "${android.provider.MediaStore.Downloads.DISPLAY_NAME} = ? AND ${android.provider.MediaStore.Downloads.RELATIVE_PATH} = ?",
                arrayOf(name, relativePath), null
            )?.use { c ->
                if (c.moveToFirst()) android.content.ContentUris.withAppendedId(
                    collection, c.getLong(c.getColumnIndexOrThrow(android.provider.MediaStore.Downloads._ID))
                ) else null
            }
            if (existingUri != null) {
                resolver.openOutputStream(existingUri, "wa")?.use { it.write(line.toByteArray()) }
            } else {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                    put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(android.provider.MediaStore.Downloads.RELATIVE_PATH, relativePath)
                }
                val uri = resolver.insert(collection, values) ?: return
                resolver.openOutputStream(uri)?.use { it.write(line.toByteArray()) }
            }
        } catch (_: Exception) { }
    }

    private fun updateRegenModeCandidate(mode: Int?, strong: Boolean, source: String) {
        if (mode == null) {
            Log.e(TAG, "regenMode null source=$source (getter not resolved)")
            writeRegenDiag("null-$source", "regenMode null source=$source (getter not resolved)")
            return
        }
        if (mode !in 1..2) {
            Log.e(TAG, "regenMode out of range: raw=$mode source=$source (expected 1..2)")
            writeRegenDiag("range-$source", "regenMode out of range: raw=$mode source=$source (expected 1..2)")
        }
        val normalized = mode.takeIf { it in 1..2 } ?: return
        val current = _regenMode.value
        val shouldApply = when {
            strong -> true
            !hasConfirmedRegenMode -> true
            current == 0 -> true
            normalized == current -> true
            else -> true
        }
        if (!shouldApply) {
            if (ENABLE_LAB_DIAGNOSTICS) {
                Log.i(TAG, "🔍 Regen mode preserved: current=$current incoming=$normalized source=$source")
            }
            return
        }
        if (normalized != current) {
            Log.e(TAG, "regenMode changed: $current -> $normalized source=$source strong=$strong confirmed=$hasConfirmedRegenMode")
            writeRegenDiag(null, "regenMode changed: $current -> $normalized source=$source strong=$strong confirmed=$hasConfirmedRegenMode")
        }
        _regenMode.value = normalized
        if (strong) {
            hasConfirmedRegenMode = true
        }
    }

    private fun restorePersistedStatisticState() {
        // Version check: if cache was written before STAT_CACHE_VERSION, clear it.
        // This handles stale cell temps from an older mapping where a housing sensor
        // was wrongly used as cell temp min.
        val cachedVersion = statCache.getInt("stat_cache_version", 0)
        if (cachedVersion < STAT_CACHE_VERSION) {
            Log.i(TAG, "🧹 Clearing stat cache v$cachedVersion → v$STAT_CACHE_VERSION (cache mapping changed)")
            statCache.edit().clear().putInt("stat_cache_version", STAT_CACHE_VERSION).apply()
            return  // fresh data arrives within seconds; nothing to restore
        }

        fun getDouble(key: String): Double? = statCache.getString(key, null)?.toDoubleOrNull()

        _statisticCellVoltageMin.value = getDouble("stat_cell_v_min")
        _statisticCellVoltageMax.value = getDouble("stat_cell_v_max")
        _statisticCellTempMin.value = getDouble("stat_cell_t_min")
        _statisticCellTempAvg.value = getDouble("stat_cell_t_avg")
        _statisticCellTempMax.value = null
        _statisticBatterySoh.value = getDouble("stat_soh")
        // D5 never writes this StateFlow live — restoring a cached value would freeze the BMS
        // bucket forever (directBmsSoc wins over derivedBmsSoc). Only D3 feeds this from a live decode.
        if (!DiLink5Platform.isDiLink5) {
            _statisticSocBms.value = getDouble("stat_soc_bms")
            // Seed the glitch filter's baseline from the restored value so the first live reading
            // isn't mistaken for a jump.
            lastSocBmsFiltered = _statisticSocBms.value
        }
        socBmsHeldOnce = false
        _statisticAvailPower.value = getDouble("stat_avail_power")
        _statisticBatteryCurrent.value = getDouble("stat_current")
        statCache.getInt("drive_mode", DRIVE_MODE_UNKNOWN)
            .takeIf { it in 1..6 }
            ?.let {
                _driveMode.value = it
                hasConfirmedDriveMode = statCache.getBoolean("drive_mode_confirmed", false)
            }
        statCache.getInt("regen_mode", 0)
            .takeIf { it in 1..2 }
            ?.let {
                _regenMode.value = it
                hasConfirmedRegenMode = statCache.getBoolean("regen_mode_confirmed", false)
            }
        // Restore last-known tyre pressures so indicators show during ACC/remote-wake mode
        // when TPMS sensors are not actively transmitting.
        getDouble("tyre_lf_psi")?.takeIf { it > 0 }?.let { _tyrePressureLF.value = it }
        getDouble("tyre_rf_psi")?.takeIf { it > 0 }?.let { _tyrePressureRF.value = it }
        getDouble("tyre_lr_psi")?.takeIf { it > 0 }?.let { _tyrePressureLR.value = it }
        getDouble("tyre_rr_psi")?.takeIf { it > 0 }?.let { _tyrePressureRR.value = it }
        getDouble("tyre_lf_bar")?.takeIf { it > 0 }?.let { _tyrePressureLFBar.value = it }
        getDouble("tyre_rf_bar")?.takeIf { it > 0 }?.let { _tyrePressureRFBar.value = it }
        getDouble("tyre_lr_bar")?.takeIf { it > 0 }?.let { _tyrePressureLRBar.value = it }
        getDouble("tyre_rr_bar")?.takeIf { it > 0 }?.let { _tyrePressureRRBar.value = it }
        Log.i(TAG, "🔌 restored stat cache v$STAT_CACHE_VERSION: cellTMin=${_statisticCellTempMin.value} cellTAvg=${_statisticCellTempAvg.value}")
    }

    private fun persistStatisticState(snapshot: VehicleTelemetrySnapshot) {
        val editor = statCache.edit()
        editor.putInt("stat_cache_version", STAT_CACHE_VERSION)
        var changed = false

        fun putDouble(key: String, value: Double?) {
            if (value != null) {
                editor.putString(key, value.toString())
                changed = true
            }
        }

        putDouble("stat_cell_v_min", snapshot.statisticCellVoltageMin)
        putDouble("stat_cell_v_max", snapshot.statisticCellVoltageMax)
        putDouble("stat_cell_t_min", snapshot.statisticCellTempMin)
        putDouble("stat_cell_t_avg", snapshot.statisticCellTempAvg)
        putDouble("stat_soh", snapshot.statisticBatterySoh)
        putDouble("stat_soc_bms", snapshot.statisticSocBms)
        putDouble("stat_avail_power", snapshot.statisticAvailPower)
        putDouble("stat_current", snapshot.statisticBatteryCurrent)
        putDouble("tyre_lf_psi", snapshot.tyrePressureLFPsi.takeIf { it > 0 })
        putDouble("tyre_rf_psi", snapshot.tyrePressureRFPsi.takeIf { it > 0 })
        putDouble("tyre_lr_psi", snapshot.tyrePressureLRPsi.takeIf { it > 0 })
        putDouble("tyre_rr_psi", snapshot.tyrePressureRRPsi.takeIf { it > 0 })
        putDouble("tyre_lf_bar", snapshot.tyrePressureLFBar.takeIf { it > 0 })
        putDouble("tyre_rf_bar", snapshot.tyrePressureRFBar.takeIf { it > 0 })
        putDouble("tyre_lr_bar", snapshot.tyrePressureLRBar.takeIf { it > 0 })
        putDouble("tyre_rr_bar", snapshot.tyrePressureRRBar.takeIf { it > 0 })
        if (snapshot.driveMode in 1..6 && hasConfirmedDriveMode) {
            editor.putInt("drive_mode", snapshot.driveMode)
            editor.putBoolean("drive_mode_confirmed", true)
            changed = true
        }
        if (snapshot.regenMode in 1..2 && hasConfirmedRegenMode) {
            editor.putInt("regen_mode", snapshot.regenMode)
            editor.putBoolean("regen_mode_confirmed", true)
            changed = true
        }

        if (changed) editor.apply()
    }

    private fun persistStatisticFlows() {
        val snapshot = VehicleTelemetrySnapshot(
            statisticCellVoltageMin = _statisticCellVoltageMin.value,
            statisticCellVoltageMax = _statisticCellVoltageMax.value,
            statisticCellTempMin = _statisticCellTempMin.value,
            statisticCellTempAvg = _statisticCellTempAvg.value,
            statisticCellTempMax = _statisticCellTempMax.value,
            statisticBatteryCurrent = _statisticBatteryCurrent.value,
            statisticBatterySoh = _statisticBatterySoh.value,
            statisticSocBms = _statisticSocBms.value,
            statisticAvailPower = _statisticAvailPower.value,
            driveMode = _driveMode.value,
            regenMode = _regenMode.value,
        )
        persistStatisticState(snapshot)
    }

    private fun logInfoIfChanged(key: String, message: String) {
        val previous = lastDiagnosticLogs.put(key, message)
        if (previous != message) {
            Log.i(TAG, message)
        }
    }

    /**
     * Forwards a device object into [VehicleCompatibilityProbe] when probing
     * is enabled. This is called at the start of every log*Snapshot() function
     * so users on non-Seal vehicles capture a full snapshot of every device.
     * Zero overhead when probing is disabled (first check is a StateFlow read).
     */
    private fun dumpForCompatProbe(label: String, device: Any) {
        if (VehicleCompatibilityProbe.isEnabled.value) {
            VehicleCompatibilityProbe.recordDevice(label, device)
        }
    }

    private fun logWarnIfChanged(key: String, message: String) {
        val previous = lastDiagnosticLogs.put(key, message)
        if (previous != message) {
            Log.w(TAG, message)
        }
    }

    private fun logPrimaryTelemetrySummaryIfChanged(snapshot: VehicleTelemetrySnapshot) {
        val socPanel = snapshot.statisticElecPercentageValue
            ?: snapshot.instrumentBatteryPercent
            ?: snapshot.statisticSocBatteryPct
        val socBms = snapshot.statisticSocBms
        val remainKwh = snapshot.powerBatteryRemainPowerEV
        val voltage12v = snapshot.battery12vVoltage
        val cellVMin = snapshot.statisticCellVoltageMin ?: snapshot.batteryCellVoltageMin
        val cellVMax = snapshot.statisticCellVoltageMax ?: snapshot.batteryCellVoltageMax

        val hasSignal = listOf(socPanel, socBms, remainKwh, voltage12v, cellVMin, cellVMax).any { it != null }
        if (!hasSignal) return

        fun fmt1(value: Double?) = value?.let { String.format("%.1f", it) } ?: "n/a"
        fun fmt3(value: Double?) = value?.let { String.format("%.3f", it) } ?: "n/a"

        logWarnIfChanged(
            "primaryTelemetrySummary",
            "🔬 telemetry summary: socPanel=${fmt1(socPanel)}% socBms=${fmt1(socBms)}% " +
                "remain=${fmt1(remainKwh)}kWh 12v=${fmt1(voltage12v)}V " +
                "cellV=${fmt3(cellVMin)}-${fmt3(cellVMax)}V"
        )
    }

    private inline fun logVerboseInfoIfChanged(key: String, message: () -> String) {
        if (ENABLE_VERBOSE_SNAPSHOT_LOGS) {
            logInfoIfChanged(key, message())
        }
    }

    private fun startReadLogsMonitor() {
        if (readLogsMonitorJob != null) return
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_LOGS) != PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "ℹ️ readlogs monitor: READ_LOGS not granted")
            return
        }
        val vendorFilters = RuntimeExtensionBridge.vendorLogcatFilters()
        if (vendorFilters.isEmpty()) {
            Log.i(TAG, "ℹ️ readlogs monitor: no vendor filters available")
            return
        }
        val scope = pollingScope ?: CoroutineScope(Dispatchers.IO)
        readLogsMonitorJob = scope.launch {
            try {
                seedVendorLogSignals(vendorFilters)
                monitorVendorLogSignals(vendorFilters)
            } catch (t: Throwable) {
                Log.i(TAG, "ℹ️ readlogs monitor failed: ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                readLogsMonitorJob = null
            }
        }
    }

    private fun seedVendorLogSignals(vendorFilters: List<String>) {
        val process = ProcessBuilder(
            "logcat", "-v", "brief", "-d",
            *vendorFilters.toTypedArray(),
            "*:S"
        ).redirectErrorStream(true).start()

        var matched = 0
        process.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { rawLine ->
                val line = rawLine.trim()
                if (applyVendorLogSignal(line)) matched += 1
            }
        }

        if (ENABLE_LAB_DIAGNOSTICS) {
            Log.i(TAG, "ℹ️ readlogs seed matched=$matched filters=${vendorFilters.joinToString()}")
        }
    }

    private fun monitorVendorLogSignals(vendorFilters: List<String>) {
        val process = ProcessBuilder(
            "logcat", "-v", "brief",
            *vendorFilters.toTypedArray(),
            "*:S"
        ).redirectErrorStream(true).start()

        if (ENABLE_LAB_DIAGNOSTICS) {
            Log.i(TAG, "ℹ️ readlogs live monitor started filters=${vendorFilters.joinToString()}")
        }

        process.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { rawLine ->
                val line = rawLine.trim()
                applyVendorLogSignal(line)
            }
        }
    }

    private fun applyVendorLogSignal(line: String): Boolean {
        val signal = RuntimeExtensionBridge.parseVendorLogLine(line) ?: return false
        when (signal.kind) {
            "pm25" -> updatePm25Snapshot(inCar = signal.inCar, outCar = signal.outCar, source = signal.source)
            "tec-control-temp" -> {
                signal.raw?.let { raw ->
                    _tecControlTempRaw.value = raw
                    updateDiagnosticProbeValues(
                        source = "vendor-tec-control",
                        values = mapOf("tec_control_temp_raw" to raw.toDouble())
                    )
                }
                signal.summary?.let {
                    logInfoIfChanged("vendor-log-${signal.kind}", "🔬 optional log value[$it]")
                }
            }
            else -> {
                signal.raw?.toDouble()?.let { value ->
                    updateDiagnosticProbeValues(
                        source = "vendor-${signal.source}",
                        values = mapOf(signal.kind to value)
                    )
                }
                signal.summary?.let {
                    logInfoIfChanged("vendor-log-${signal.kind}", "🔬 optional log value[$it]")
                }
            }
        }
        return true
    }

    private fun updatePm25Snapshot(inCar: Int?, outCar: Int?, source: String) {
        val snap = _vehicleSnapshot.value
        val candidateIn  = inCar  ?: snap.pm25InCar
        val candidateOut = outCar ?: snap.pm25OutCar

        // Debounce: require the same value on two consecutive polls to suppress
        // 1-cycle sensor oscillation (e.g. alternating 18/22 every second).
        // Exception: skip debounce on the very first reading (snap values are both
        // null) — there is nothing to debounce against and the extra poll cycle
        // delay is user-visible as "PM2.5 not showing until I reopen the app".
        val firstReading = snap.pm25InCar == null && snap.pm25OutCar == null
        val stableIn: Int?
        val stableOut: Int?
        if (firstReading) {
            stableIn       = candidateIn
            stableOut      = candidateOut
            pendingPm25In  = candidateIn
            pendingPm25Out = candidateOut
        } else {
            stableIn  = if (candidateIn  == pendingPm25In)  candidateIn  else { pendingPm25In  = candidateIn;  null }
            stableOut = if (candidateOut == pendingPm25Out) candidateOut else { pendingPm25Out = candidateOut; null }
        }

        val nextIn  = stableIn  ?: snap.pm25InCar
        val nextOut = stableOut ?: snap.pm25OutCar
        if (nextIn == snap.pm25InCar && nextOut == snap.pm25OutCar) return

        _vehicleSnapshot.value = snap.copy(
            pm25InCar = nextIn,
            pm25OutCar = nextOut
        )
        publishSnapshot()
        logInfoIfChanged(
            "pm25Snapshot",
            "🌫️ PM2.5 snapshot[$source]: in=${nextIn ?: "n/a"} out=${nextOut ?: "n/a"}"
        )
    }

    private fun isVerboseEventLabel(label: String): Boolean = ENABLE_VERBOSE_RAW_EVENT_LOGS && label in setOf(
        "Charging",
        "Battery",
        "BMS",
        "Instrument",
        "Mqtt",
        "Sensor",
        "Power",
        "Statistic",
        "Bodywork",
        "Energy",
    )

    private fun compactDiagnosticDetail(detail: String, maxLen: Int = 280): String {
        val singleLine = detail.replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
        return if (singleLine.length <= maxLen) singleLine else singleLine.take(maxLen) + "..."
    }

    private fun describeDiagnosticValue(value: Any?): String = when (value) {
        null -> "null"
        is Number -> value.toString()
        is Boolean -> value.toString()
        else -> compactDiagnosticDetail(value.toString(), 120)
    }

    private fun describeGeneralEventValue(rawNumber: Number?): String? {
        val value = rawNumber?.toDouble() ?: return null
        if (!value.isFinite() || abs(value) > 1_000_000.0) return null

        val hints = buildList {
            if (value in -30.0..120.0) add("raw≈${String.format("%.1f", value)}")
            if (value in 0.0..100.0) add("pct≈${String.format("%.1f", value)}%")
            if (abs(value) >= 20.0 && (value / 3.0) in -30.0..120.0) {
                add("div3≈${String.format("%.1f", value / 3.0)}")
            }
            if (abs(value) >= 50.0 && (value / 10.0) in -30.0..120.0) {
                add("div10≈${String.format("%.1f", value / 10.0)}")
            }
            if (abs(value) >= 100.0 && (value / 100.0) in -30.0..120.0) {
                add("div100≈${String.format("%.1f", value / 100.0)}")
            }
            if (value in 1000.0..8000.0) {
                add("mv≈${String.format("%.3f", value / 1000.0)}V")
            }
        }.distinct()

        return hints.takeIf { it.isNotEmpty() }?.joinToString(prefix = "hint=", separator = " ")
    }

    private fun readNumericGetterMap(
        target: Any,
        specs: List<Pair<String, List<String>>>
    ): Map<String, Double> {
        val result = linkedMapOf<String, Double>()
        for ((label, methodNames) in specs) {
            val value = invokeNumericDoubleGetter(target, *methodNames.toTypedArray())
                ?.takeIf { it.isFinite() && abs(it) < 1_000_000.0 }
            if (value != null) result[label] = value
        }
        return result
    }

    private fun readLabeledFeatureMap(
        target: Any,
        specs: Map<String, Int>
    ): Map<String, Double> {
        val result = linkedMapOf<String, Double>()
        for ((label, featureId) in specs) {
            val value = (invokeFeatureGetter(target, featureId) as? Number)
                ?.toDouble()
                ?.takeIf { it.isFinite() && abs(it) < 10_000.0 }
            if (value != null) {
                result[label] = value
            }
        }
        return result
    }

    private fun normalizeProbeKey(label: String): String {
        return label.lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
    }

    private fun updateDiagnosticProbeValues(source: String, values: Map<String, Double>) {
        if (values.isEmpty()) return

        val normalized = values.entries.mapNotNull { (label, rawValue) ->
            rawValue.takeIf { it.isFinite() && abs(it) < 10_000.0 }
                ?.let { normalizeProbeKey(label) }
                ?.takeIf { it.isNotBlank() }
                ?.let { normalizedKey -> normalizedKey to rawValue }
        }.toMap()
        if (normalized.isEmpty()) return

        val previous = _vehicleSnapshot.value
        val merged = previous.probeValues.toMutableMap().apply { putAll(normalized) }.toSortedMap()
        if (merged == previous.probeValues) return

        _vehicleSnapshot.value = previous.copy(probeValues = merged)
        publishSnapshot()

        if (ENABLE_LAB_DIAGNOSTICS) {
            val summary = merged.entries.joinToString(" ") { (key, value) ->
                "$key=${String.format("%.2f", value)}"
            }
            logInfoIfChanged("probeValues-$source", "🧪 $source probes: $summary")
        }
    }

    private fun logModeStateSnapshot(label: String, target: Any) {
        if (!ENABLE_MODE_STATE_LOGS) return

        val methods = target.javaClass.methods
            .asSequence()
            .filter { it.parameterTypes.isEmpty() }
            .map { it.name }
            .filter { name ->
                val lower = name.lowercase()
                lower.contains("mode") ||
                    lower.contains("energy") ||
                    lower.contains("feedback") ||
                    lower.contains("text") ||
                    lower.contains("eco") ||
                    lower.contains("sport") ||
                    lower.contains("road")
            }
            .distinct()
            .sorted()
            .toList()
        logInfoIfChanged(
            "modeStateMethods-$label",
            "🔎 DriveModeState methods $label: ${methods.joinToString().ifBlank { "none" }}"
        )

        val values = readNumericGetterMap(target, RuntimeExtensionBridge.methodGroups("m01"))
        if (values.isEmpty()) {
            logInfoIfChanged("modeState-$label", "🔎 DriveModeState $label: no numeric mode values")
            return
        }

        val currentLabel = when (_driveMode.value) {
            1 -> "Eco"
            2 -> "Sport"
            3 -> "Normal"
            DRIVE_MODE_UNKNOWN -> "Unknown"
            else -> "Mode ${_driveMode.value}"
        }
        val valueText = values.entries.joinToString(" ") { (key, value) ->
            "$key=${if (value % 1.0 == 0.0) value.toInt().toString() else String.format("%.2f", value)}"
        }
        logInfoIfChanged(
            "modeState-$label",
            "🔎 DriveModeState $label: $valueText | current=${_driveMode.value}($currentLabel) confirmed=$hasConfirmedDriveMode regen=${_regenMode.value} regenConfirmed=$hasConfirmedRegenMode"
        )
    }

    private fun logBatterySummaryIfChanged(snapshot: VehicleTelemetrySnapshot) {
        val cellVMin = snapshot.statisticCellVoltageMin ?: snapshot.batteryCellVoltageMin
        val cellVMax = snapshot.statisticCellVoltageMax ?: snapshot.batteryCellVoltageMax
        val cellTMin = snapshot.batteryCellTempMin?.toDouble() ?: snapshot.statisticCellTempMin
        val cellTMax = snapshot.batteryCellTempMax?.toDouble() ?: snapshot.statisticCellTempMax
        val hasDirectCellTemps = snapshot.batteryCellTempMin != null || snapshot.batteryCellTempMax != null
        val cellTAvg = snapshot.batteryPackTemp
            ?.takeIf { it > 0.0 }
            ?: if (hasDirectCellTemps && cellTMin != null && cellTMax != null) {
                (cellTMin + cellTMax) / 2.0
            } else {
                snapshot.statisticCellTempAvg
                    ?: if (cellTMin != null && cellTMax != null) (cellTMin + cellTMax) / 2.0 else null
            }
        val voltage12v = snapshot.battery12vVoltage
        val packTemp = snapshot.batteryPackTemp
        val socBms = snapshot.statisticSocBms
        val socPanel = snapshot.statisticElecPercentageValue
            ?: snapshot.instrumentBatteryPercent
            ?: snapshot.statisticSocBatteryPct
        val batteryCapacity = snapshot.bodyworkBatteryCapacity
        val remainKwh = snapshot.powerBatteryRemainPowerEV
        val hasSignal = listOf(
            cellVMin, cellVMax, cellTMin, cellTAvg, cellTMax, voltage12v, packTemp, socBms, socPanel, remainKwh
        ).any { it != null }
        if (!hasSignal) return

        fun fmt1(v: Double?) = v?.let { String.format("%.1f", it) } ?: "n/a"
        fun fmt3(v: Double?) = v?.let { String.format("%.3f", it) } ?: "n/a"
        val currentA = snapshot.statisticBatteryCurrent
        val availKw = snapshot.statisticAvailPower

        logInfoIfChanged(
            "batterySummary",
                "🔬 battery summary: 12v=${fmt1(voltage12v)}V capAh=${batteryCapacity ?: "n/a"} " +
                "socBms=${fmt1(socBms)}% socPanel=${fmt1(socPanel)}% " +
                "cellV=${fmt3(cellVMin)}-${fmt3(cellVMax)}V " +
                "packT=${fmt1(packTemp)}°C cellT=${fmt1(cellTMin)}/${fmt1(cellTAvg)}/${fmt1(cellTMax)}°C " +
                "current=${fmt1(currentA)}A avail=${fmt1(availKw)}kW remain=${fmt1(remainKwh)}kWh"
        )
    }

    private fun logStatisticMapIfChanged(snapshot: VehicleTelemetrySnapshot) {
        val cellVMin = snapshot.statisticCellVoltageMin
        val cellVMax = snapshot.statisticCellVoltageMax
        val cellTMin = snapshot.statisticCellTempMin
        val cellTAvg = snapshot.statisticCellTempAvg
        val cellTMax = snapshot.statisticCellTempMax
        val soh = snapshot.statisticBatterySoh
        val socBms = snapshot.statisticSocBms

        val hasSignal = listOf(cellVMin, cellVMax, cellTMin, cellTAvg, cellTMax, soh, socBms).any { it != null }
        if (!hasSignal) return

        fun fmt1(v: Double?) = v?.let { String.format("%.1f", it) } ?: "n/a"
        fun fmt3(v: Double?) = v?.let { String.format("%.3f", it) } ?: "n/a"

        logInfoIfChanged(
            "statisticMap",
            "🔬 statistic map: " +
                "cellV[min=${fmt3(cellVMin)}V max=${fmt3(cellVMax)}V] " +
                "cellT[min=${fmt1(cellTMin)}C avg=${fmt1(cellTAvg)}C max=${fmt1(cellTMax)}C] " +
                "battery[soc=${fmt1(socBms)}% soh=${fmt1(soh)}% panel=${fmt1(snapshot.statisticSocBatteryPct)}% avail=${fmt1(snapshot.statisticAvailPower)}kW]"
        )
    }

    private fun logDrivetrainSummaryIfChanged(snapshot: VehicleTelemetrySnapshot) {
        val speed = snapshot.directSpeedKmh
        val powerKw = snapshot.enginePower?.toDouble()
        val frontRpm = snapshot.engineSpeedFront?.takeIf { it > 0 }
        val rearRpm = snapshot.engineSpeedRear?.takeIf { it > 0 }
        val availKw = snapshot.statisticAvailPower
        val remainKwh = snapshot.powerBatteryRemainPowerEV
        val hasSignal = listOf(speed, powerKw, frontRpm?.toDouble(), rearRpm?.toDouble(), availKw, remainKwh).any { it != null }
        if (!hasSignal) return

        fun fmt0(v: Int?) = v?.toString() ?: "n/a"
        fun fmt1(v: Double?) = v?.let { String.format("%.1f", it) } ?: "n/a"

        logInfoIfChanged(
            "drivetrainSummary",
            "🔬 drivetrain summary: speed=${fmt1(speed)}km/h power=${fmt1(powerKw)}kW " +
                "front=${fmt0(frontRpm)}rpm rear=${fmt0(rearRpm)}rpm " +
                "avail=${fmt1(availKw)}kW remain=${fmt1(remainKwh)}kWh"
        )
    }

    private fun logDriveCaptureIfChanged(snapshot: VehicleTelemetrySnapshot) {
        val speed = snapshot.directSpeedKmh
        val lat = snapshot.locationLatitude
        val lon = snapshot.locationLongitude
        val alt = snapshot.locationAltitude
        val frontRpm = snapshot.engineSpeedFront?.takeIf { it > 0 }
        val rearRpm = snapshot.engineSpeedRear?.takeIf { it > 0 }
        val powerKw = snapshot.enginePower?.toDouble()
        val currentA = snapshot.statisticBatteryCurrent
        val remainKwh = snapshot.powerBatteryRemainPowerEV
        val hasSignal = listOf(
            speed,
            lat,
            lon,
            alt,
            frontRpm?.toDouble(),
            rearRpm?.toDouble(),
            powerKw,
            currentA,
            remainKwh
        ).any { it != null }
        if (!hasSignal) return

        fun fmt0(v: Int?) = v?.toString() ?: "n/a"
        fun fmt1(v: Double?) = v?.let { String.format("%.1f", it) } ?: "n/a"
        fun fmt5(v: Double?) = v?.let { String.format("%.5f", it) } ?: "n/a"

        logInfoIfChanged(
            "driveCapture",
            "🔬 drive capture: gear=${snapshot.gear} speed=${fmt1(speed)}km/h " +
                "power=${fmt1(powerKw)}kW front=${fmt0(frontRpm)}rpm rear=${fmt0(rearRpm)}rpm " +
                "current=${fmt1(currentA)}A remain=${fmt1(remainKwh)}kWh " +
                "lat=${fmt5(lat)} lon=${fmt5(lon)} alt=${fmt1(alt)}m"
        )
    }

    private fun Any?.asIntOrNull(): Int? = when (this) {
        is Int -> this
        is Number -> this.toInt()
        else -> null
    }

    private fun Any?.asDoubleOrNull(): Double? = when (this) {
        is Double -> this
        is Number -> this.toDouble()
        else -> null
    }

    private fun noteChargingCapacityActivity(capacity: Double) {
        val nowElapsedMs = SystemClock.elapsedRealtime()
        val previous = lastChargingCapacityRaw
        if (previous == null || capacity > previous + 0.0005) {
            lastChargingActivityElapsedMs = nowElapsedMs
        }
        lastChargingCapacityRaw = capacity

        if (chargingCapacityHistory.isEmpty() || capacity > chargingCapacityHistory.last().second + 0.0001) {
            chargingCapacityHistory.addLast(nowElapsedMs to capacity)
        }
        while (chargingCapacityHistory.isNotEmpty() && nowElapsedMs - chargingCapacityHistory.first().first > 30_000L) {
            chargingCapacityHistory.removeFirst()
        }

        val intervalKw = chargingCapacityHistory
            .zipWithNext()
            .mapNotNull { (older, newer) ->
                val deltaMs = newer.first - older.first
                val deltaKwh = newer.second - older.second
                if (deltaMs <= 0L || deltaKwh <= 0.0002) {
                    null
                } else {
                    (deltaKwh * 3_600_000.0 / deltaMs.toDouble()).takeIf { it.isFinite() && it in 0.1..350.0 }
                }
            }

        if (intervalKw.isNotEmpty() && !hasFreshDirectChargingPower(nowElapsedMs)) {
            val sorted = intervalKw.sorted()
            val medianKw = if (sorted.size % 2 == 1) {
                sorted[sorted.lastIndex / 2]
            } else {
                val upper = sorted.size / 2
                (sorted[upper - 1] + sorted[upper]) / 2.0
            }
            val smoothedKw = if (_chargingPowerKw.value > 0.1) {
                _chargingPowerKw.value * 0.7 + medianKw * 0.3
            } else {
                medianKw
            }
            _chargingPowerKw.value = smoothedKw
        }
    }

    private fun computeChargingActive(nowElapsedMs: Long = SystemClock.elapsedRealtime()): Boolean {
        // Driving overrides everything: if the car is moving in a drive gear, it is
        // physically impossible to be charging. Defense-in-depth against either of the
        // BMS signals below getting stuck non-zero (chargingGunState in particular has
        // been observed to miss onChargingGunStateChanged(0) when the unplug happens
        // while the car is locked and the app process isn't observing).
        val snap = _vehicleSnapshot.value
        // A drive gear (D/R) makes charging physically impossible — you can't be plugged in and
        // in gear. Gate on gear ALONE, not speed: on this firmware directSpeedKmh frequently reads
        // 0 even while moving (daemon gaps / wedged getter), so the old speed>5 gate let a stuck
        // chargerWorkState/gunState survive a slow back-and-forth and then show a phantom charging
        // animation once parked. The daemon gear (or speed-inference fallback) is the reliable signal.
        val driving = snap.gear in setOf("D", "R")
        if (driving) {
            // Self-calibration: gun/work reading non-zero WHILE DRIVING is physically impossible for a
            // real charge, so this car's BMS reports them spuriously (observed on Seal: gun=1 work=1
            // mid-drive). Once we've seen that, those flags can't be trusted alone — a real charge must
            // be backed by actual power or capacity activity, otherwise a stuck work=1 lights a phantom
            // charging animation once parked (where the gear gate below can no longer clear it).
            if (!chargerSignalsUnreliable && (_chargingGunState.value != 0 || _chargerWorkState.value != 0)) {
                chargerSignalsUnreliable = true
                // Persist so the guard is already armed right after an app update/restart — otherwise a
                // stuck work=1 shows a phantom charge while parked until the next drive re-latches it.
                runCatching { statCache.edit().putBoolean(KEY_CHARGER_SIGNALS_UNRELIABLE, true).apply() }
                Log.i(TAG, "🔌 BMS charger gun/work seen while driving — marking them unreliable (require power/capacity)")
                runCatching { DiagLog.event(appContext, TAG, "🔌 charger gun/work unreliable on this car (seen while driving) — phantom-charge guard on") }
            }
            if (_chargingGunState.value != 0) _chargingGunState.value = 0
            if (_chargerWorkState.value != 0) { _chargerWorkState.value = 0; chargerWorkStateSetElapsedMs = 0L }
            lastChargingActivityElapsedMs = 0L
            updateDirectChargingPower(null)
            lastPackChargingPowerElapsedMs = 0L
            return false
        }
        // Pack power (m33) is the authoritative DC source; OR it in so a DC charge started while the
        // car is on trips powerActive even when the instrument/event power paths are silent. Purely
        // additive — it only contributes when getChargingPower() actually reports > 0.1 kW.
        val powerActive = hasFreshDirectChargingPower(nowElapsedMs) ||
            hasFreshPackChargingPower(nowElapsedMs)
        val recentCapacityActivity = lastChargingActivityElapsedMs != 0L &&
            nowElapsedMs - lastChargingActivityElapsedMs <= 3_000L
        // chargerWorkState is set by AbsBYDAutoChargingListener.onChargerWorkStateChanged,
        // which fires from BMS-level middleware independently of ignition state. This catches
        // AC off-state charging where direct power readings are absent but the charger daemon
        // is still actively reporting its work state. This matches the 0efedd1 behaviour the
        // dev confirmed as reliable for both AC overnight charging and live DC charging.
        val chargerWorking = _chargerWorkState.value > 0
        // PHEV safety net. On some DM-i firmwares the BMS never returns chargerWorkState
        // to 0 after charging completes — the session would otherwise stay "in progress"
        // until the user manually cleared charging-data in the car UI. If we've seen
        // capacity activity at some point in this session AND nothing fresh has arrived
        // for the relevant timeout, treat the work state as stale and force it back to 0.
        // Doesn't affect Seal/EV cars where the BMS resets reliably — they always have
        // fresh power or capacity activity during real charging.
        // When capacity was actually observed (lastChargingActivityElapsedMs != 0L), 2 min
        // of silence reliably means charging has finished — use the shorter window so the
        // icon clears promptly after a PHEV reaches 100%. The conservative 10-min window
        // is kept for the fresh-install / no-prior-charge path (chargerWorkStateSetElapsedMs
        // only) where we've never actually seen capacity move.
        val hasCapacityEvidence = lastChargingActivityElapsedMs != 0L
        // Root-cause fix for DM-i PHEVs whose BMS firmware never fires
        // onChargerWorkStateChanged(0) when charging finishes.
        // chargeState (from getChargeState / onChargingStateChanged) is a separate
        // BMS signal that represents the state machine rather than the daemon work
        // state. If it reads 0 ("idle") while chargerWorkState is still non-zero,
        // the daemon flag is definitively stale — clear it immediately without any
        // timeout. Guard: only apply once we've seen actual capacity activity so
        // we don't misfire at session start before chargeState has been reported.
        if (chargerWorking && !powerActive && !recentCapacityActivity &&
            hasCapacityEvidence && _chargeState.value == 0) {
            Log.i(TAG, "🔋 chargerWorkState stuck but chargeState=0 after capacity activity — clearing immediately")
            _chargerWorkState.value = 0
            chargerWorkStateSetElapsedMs = 0L
            return false
        }
        // Fallback stale-state safety net for firmwares where chargeState also
        // doesn't reset reliably. When capacity was observed: 2-min silence;
        // fresh-install / no-prior-charge path: conservative 10-min.
        val staleBaseline = if (hasCapacityEvidence) lastChargingActivityElapsedMs
                            else chargerWorkStateSetElapsedMs
        val staleTimeoutMs = if (hasCapacityEvidence) 2 * 60_000L
                             else STALE_CHARGER_WORK_STATE_TIMEOUT_MS
        if (chargerWorking && !powerActive && !recentCapacityActivity &&
            staleBaseline != 0L &&
            nowElapsedMs - staleBaseline > staleTimeoutMs) {
            Log.i(TAG, "🔋 chargerWorkState stale (${(nowElapsedMs - staleBaseline) / 60_000L}m of silence) — forcing 0")
            // A work=1 that timed out with NO capacity ever seen was a phantom (no real charge
            // happened) — same conclusion as seeing gun/work while driving. Latch + persist the guard
            // so a parked user who never drove after an update still stops seeing it recur, without
            // waiting out the timeout each time.
            if (!hasCapacityEvidence && !chargerSignalsUnreliable) {
                chargerSignalsUnreliable = true
                runCatching { statCache.edit().putBoolean(KEY_CHARGER_SIGNALS_UNRELIABLE, true).apply() }
                runCatching { DiagLog.event(appContext, TAG, "🔌 charger work=1 timed out with no real charge — marking signals unreliable") }
            }
            _chargerWorkState.value = 0
            chargerWorkStateSetElapsedMs = 0L
            return false
        }
        // On cars whose gun/work flags are spurious (chargerSignalsUnreliable), don't let a bare
        // work=1 assert charging — require corroboration by real power now or capacity activity seen
        // this session. Real charging still trips powerActive/recentCapacityActivity (and capacity
        // evidence keeps work trustworthy through a genuine charge); a phantom stuck work=1 after a
        // drive has neither, so it no longer shows a charging animation while parked.
        val workTrustworthy = !chargerSignalsUnreliable || hasCapacityEvidence
        return powerActive || recentCapacityActivity || (chargerWorking && workTrustworthy)
    }

    private fun hasFreshDirectChargingPower(nowElapsedMs: Long = SystemClock.elapsedRealtime()): Boolean {
        return lastChargingPowerRawElapsedMs != 0L &&
            nowElapsedMs - lastChargingPowerRawElapsedMs <= DIRECT_CHARGING_POWER_TIMEOUT_MS &&
            _chargingPowerRaw.value > 0.1
    }

    private fun updateDirectChargingPower(power: Double?) {
        val sanitized = power?.takeIf { it.isFinite() && it > 0.1 }
        if (sanitized != null) {
            _chargingPowerRaw.value = sanitized
            _chargingPowerKw.value = sanitized
            lastChargingPowerRawElapsedMs = SystemClock.elapsedRealtime()
        } else {
            _chargingPowerRaw.value = 0.0
            lastChargingPowerRawElapsedMs = 0L
        }
    }

    private fun hasFreshPackChargingPower(nowElapsedMs: Long = SystemClock.elapsedRealtime()): Boolean {
        return lastPackChargingPowerElapsedMs != 0L &&
            nowElapsedMs - lastPackChargingPowerElapsedMs <= DIRECT_CHARGING_POWER_TIMEOUT_MS &&
            _packChargingPowerKw.value > 0.1
    }

    // Charging device getChargingPower() (m33), pack V×I up to 500 kW. Unlike updateDirectChargingPower
    // this never clears on a missing read — it self-expires via the 4 s freshness window — so a brief
    // poll gap during a DC charge can't drop the session. Cleared explicitly only by the driving gate.
    private fun updatePackChargingPower(power: Double?) {
        val sanitized = power?.takeIf { it.isFinite() && it in 0.1..500.0 } ?: return
        _packChargingPowerKw.value = sanitized
        lastPackChargingPowerElapsedMs = SystemClock.elapsedRealtime()
    }

    private fun handleChargingEvent(eventId: Int, value: BYDAutoEventValue) {
        val decoded = extractEventDouble(value) ?: return
        val cids = chargingEventIds
        when (eventId) {
            cids["capacity"] -> {
                noteChargingCapacityActivity(decoded)
                _chargingEventCapacityRaw.value = decoded
                _chargingCapacity.value = decoded
                publishSnapshot()
            }
            cids["powerCandidate"] -> {
                _chargingEventPowerCandidateRaw.value = decoded
                publishSnapshot()
                if (ENABLE_VERBOSE_RAW_EVENT_LOGS) {
                    Log.d(TAG, "🔋 chargingPowerCandidate raw=$decoded")
                }
            }
            cids["unknownInt27"] -> {
                _chargingEventUnknownInt27Raw.value = extractEventInt(value) ?: 0
                publishSnapshot()
                if (ENABLE_VERBOSE_RAW_EVENT_LOGS) {
                    Log.d(TAG, "🔋 chargingUnknownEvent int=${_chargingEventUnknownInt27Raw.value} rawDouble=$decoded")
                }
            }
            cids["packVoltage"] -> {
                val rawVoltage = extractEventInt(value) ?: 0
                _chargingEventUnknownCounterRaw.value = rawVoltage
                if (rawVoltage in 300..800) {
                    _batteryTotalVoltage.value = rawVoltage
                }
                publishSnapshot()
                if (ENABLE_VERBOSE_RAW_EVENT_LOGS) {
                    Log.d(
                        TAG,
                        "🔋 chargingPackVoltage=$rawVoltage V rawDouble=$decoded"
                    )
                }
            }
        }
    }

    private fun extractEventDouble(value: BYDAutoEventValue): Double? {
        return try {
            val field = value.javaClass.getDeclaredField("doubleValue")
            field.isAccessible = true
            (field.get(value) as? Number)?.toDouble()
        } catch (_: Throwable) {
            null
        }
    }

    private fun extractEventInt(value: BYDAutoEventValue): Int? {
        return try {
            val field = value.javaClass.getDeclaredField("intValue")
            field.isAccessible = true
            (field.get(value) as? Number)?.toInt()
        } catch (_: Throwable) {
            null
        }
    }

    private fun extractRestTime(raw: Any?): Pair<Int, Int>? {
        return when (raw) {
            is IntArray -> {
                val hours = raw.getOrNull(0) ?: return null
                val minutes = raw.getOrNull(1) ?: 0
                hours to minutes
            }
            is Array<*> -> {
                val hours = (raw.getOrNull(0) as? Number)?.toInt() ?: return null
                val minutes = (raw.getOrNull(1) as? Number)?.toInt() ?: 0
                hours to minutes
            }
            is Int -> 0 to raw
            is Number -> 0 to raw.toInt()
            else -> null
        }
    }

    private fun describeEventValue(value: BYDAutoEventValue): String {
        return try {
            val cls = value.javaClass
            val getters = cls.methods
                .filter { it.parameterCount == 0 && (it.name.startsWith("get") || it.name.startsWith("is")) }
                .filterNot { it.name == "getClass" }
                .sortedBy { it.name }
                .mapNotNull { method ->
                    try {
                        "${method.name}=${method.invoke(value)}"
                    } catch (_: Throwable) {
                        null
                    }
                }

            val fields = cls.declaredFields
                .sortedBy { it.name }
                .mapNotNull { field ->
                    try {
                        field.isAccessible = true
                        "${field.name}=${field.get(value)}"
                    } catch (_: Throwable) {
                        null
                    }
                }

            val methods = cls.declaredMethods
                .filter { it.parameterCount == 0 }
                .sortedBy { it.name }
                .mapNotNull { method ->
                    try {
                        method.isAccessible = true
                        "${method.name}=${method.invoke(value)}"
                    } catch (_: Throwable) {
                        null
                    }
                }

            val parts = buildList {
                addAll(getters)
                addAll(fields)
                addAll(methods)
            }.distinct()

            if (parts.isNotEmpty()) {
                parts.joinToString(prefix = "${cls.simpleName}(", postfix = ")")
            } else {
                "${cls.simpleName}(toString=$value)"
            }
        } catch (t: Throwable) {
            "${value.javaClass.simpleName}(describeFailed=${t.javaClass.simpleName}:${t.message})"
        }
    }

    private fun describeAny(value: Any?): String {
        if (value == null) return "null"
        return try {
            when (value) {
                is ByteArray -> value.decodeToString().ifBlank {
                    value.joinToString(prefix = "ByteArray(", postfix = ")")
                }
                is IntArray -> value.joinToString(prefix = "IntArray(", postfix = ")")
                is FloatArray -> value.joinToString(prefix = "FloatArray(", postfix = ")")
                is DoubleArray -> value.joinToString(prefix = "DoubleArray(", postfix = ")")
                is Array<*> -> value.joinToString(prefix = "Array(", postfix = ")")
                else -> {
                    val cls = value.javaClass
                    val fields = cls.declaredFields
                        .sortedBy { it.name }
                        .mapNotNull { field ->
                            try {
                                field.isAccessible = true
                                "${field.name}=${field.get(value)}"
                            } catch (_: Throwable) {
                                null
                            }
                        }
                    if (fields.isEmpty()) "${cls.simpleName}($value)"
                    else fields.joinToString(prefix = "${cls.simpleName}(", postfix = ")")
                }
            }
        } catch (t: Throwable) {
            "${value.javaClass.simpleName}(describeFailed=${t.javaClass.simpleName}:${t.message})"
        }
    }

    private fun extractEventStructuredPayload(raw: Any): String? {
        if (raw is String) return raw
        if (raw is ByteArray) return raw.decodeToString().takeIf { it.isNotBlank() }

        val accessorNames = listOf(
            "getBufferDataValue",
            "bufferDataValue",
            "getByteArrayValue",
            "byteArrayValue",
            "getStringValue",
            "stringValue",
        )

        accessorNames.forEach { name ->
            runCatching {
                val method = raw.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }
                    ?: raw.javaClass.declaredMethods.firstOrNull { it.name == name && it.parameterCount == 0 }
                val value = method?.let {
                    it.isAccessible = true
                    it.invoke(raw)
                }
                when (value) {
                    is ByteArray -> return value.decodeToString().takeIf { it.isNotBlank() }
                    is String -> return value.takeIf { it.isNotBlank() }
                    else -> Unit
                }
            }
        }

        listOf("bufferDataValue", "byteArrayValue", "stringValue").forEach { name ->
            runCatching {
                val field = raw.javaClass.declaredFields.firstOrNull { it.name == name } ?: return@runCatching
                field.isAccessible = true
                val value = field.get(raw)
                when (value) {
                    is ByteArray -> return value.decodeToString().takeIf { it.isNotBlank() }
                    is String -> return value.takeIf { it.isNotBlank() }
                    else -> Unit
                }
            }
        }

        return null
    }

    private fun invokeCapState(target: Any): Pair<Int, Int>? {
        return try {
            val method = target.javaClass.getMethod("getChargingCapState")
            when (val value = method.invoke(target)) {
                is IntArray -> {
                    val state = value.getOrNull(0) ?: return null
                    val capValue = value.getOrNull(1) ?: 0
                    state to capValue
                }
                is Array<*> -> {
                    val state = (value.getOrNull(0) as? Number)?.toInt() ?: return null
                    val capValue = (value.getOrNull(1) as? Number)?.toInt() ?: 0
                    state to capValue
                }
                else -> null
            }
        } catch (_: NoSuchMethodException) {
            null
        } catch (t: Throwable) {
            Log.w(TAG, "Getter getChargingCapState failed: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    private fun invokeIntGetter(target: Any, vararg methodNames: String): Int? {
        val value = invokeGetter(target, *methodNames) ?: return null
        return when (value) {
            is Int -> value
            is Number -> value.toInt()
            else -> null
        }
    }

    private fun invokeDoubleGetter(target: Any, vararg methodNames: String): Double? {
        val value = invokeGetter(target, *methodNames) ?: return null
        return when (value) {
            is Double -> value
            is Number -> value.toDouble()
            else -> null
        }
    }

    private fun invokeNumericDoubleGetter(target: Any, vararg methodNames: String): Double? {
        return invokeDoubleGetter(target, *methodNames)
            ?: invokeIntGetter(target, *methodNames)?.toDouble()
    }

    private fun invokeStringGetter(target: Any, vararg methodNames: String): String? {
        val value = invokeGetter(target, *methodNames) ?: return null
        return value as? String
    }

    private fun invokeBooleanGetter(target: Any, vararg methodNames: String): Boolean? {
        val value = invokeGetter(target, *methodNames) ?: return null
        return value as? Boolean
    }

    private fun invokeIndexedDoubleGetter(target: Any, index: Int, vararg methodNames: String): Double? {
        val value = invokeIndexedGetter(target, index, *methodNames) ?: return null
        return when (value) {
            is Double -> value
            is Number -> value.toDouble()
            else -> null
        }
    }

    private fun invokeIndexedIntGetter(target: Any, index: Int, vararg methodNames: String): Int? {
        val value = invokeIndexedGetter(target, index, *methodNames) ?: return null
        return when (value) {
            is Int -> value
            is Number -> value.toInt()
            else -> null
        }
    }

    private fun invokeRemainMinutesGetter(target: Any): Int? {
        val value = invokeGetter(target, "getRemainChargingTime", "getChargingRestTime") ?: return null
        return when (value) {
            is Int -> value
            is Number -> value.toInt()
            is IntArray -> value.firstOrNull()
            is Array<*> -> (value.firstOrNull() as? Number)?.toInt()
            else -> null
        }
    }

    private fun invokeGetter(target: Any, vararg methodNames: String): Any? {
        methodNames.forEach { methodName ->
            try {
                val method = methodCache.getOrPut(target.javaClass to methodName) {
                    try { target.javaClass.getMethod(methodName) }
                    catch (_: NoSuchMethodException) { null }
                } ?: return@forEach
                return method.invoke(target)
            } catch (t: Throwable) {
                // NullPointerException from within the BYD SDK means the getter exists
                // but the underlying data source is unavailable on this firmware — skip silently.
                if (t is NullPointerException) return@forEach
                Log.w(TAG, "Getter $methodName failed: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
        return null
    }

    private fun readFieldOrGetter(target: Any, vararg names: String): Any? {
        for (name in names) {
            val getterName = if (name.startsWith("get")) {
                name
            } else {
                "get" + name.replaceFirstChar { it.uppercase() }
            }
            runCatching {
                val getter = target.javaClass.methods.firstOrNull {
                    it.name == getterName && it.parameterCount == 0
                }
                if (getter != null) {
                    return getter.invoke(target)
                }
            }
            runCatching {
                val field = target.javaClass.getDeclaredField(name)
                field.isAccessible = true
                return field.get(target)
            }
        }
        return null
    }

    /**
     * Call BYDAutoDevice.get(int featureId) — the primary device read mechanism.
     * Feature IDs sourced from com.byd.feature.statistics.Statistics in DiCarServer.apk.
     *
     * get(int) returns a BYDAutoEventValue, NOT a primitive.
     * We try to extract doubleValue/intValue/floatValue from it dynamically.
     * On the first call we log the raw type+toString so we can verify.
     */
    private fun invokeFeatureGetter(target: Any, featureId: Int): Any? {
        return try {
            val method = target.javaClass.getMethod("get", Int::class.javaPrimitiveType)
            val raw = method.invoke(target, featureId) ?: return null

            // Log type on first non-null result so we can see what BYDAutoEventValue looks like
            if (ENABLE_VERBOSE_SNAPSHOT_LOGS && featureId == statisticDispatchFields["soh"]) {
                Log.i(TAG, "🔬 feature getter raw type=${raw.javaClass.name} toString=$raw")
                // Also log all accessible values on the result
                val resultMethods = raw.javaClass.methods
                    .filter { it.parameterCount == 0 && it.name != "getClass" }
                    .sortedBy { it.name }
                    .joinToString { it.name }
                Log.i(TAG, "🔬 BYDAutoEventValue methods: $resultMethods")
            }

            // Try to extract a numeric value from the returned BYDAutoEventValue
            // DiCarServer uses PropertyUtils.getBYDAutoEventValue() which calls doubleValue()
            extractEventValue(raw)
        } catch (_: NoSuchMethodException) {
            null
        } catch (t: Throwable) {
            Log.w(TAG, "invokeFeatureGetter failed: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    private fun invokeFeatureRawGetter(target: Any, featureId: Int): Any? {
        return try {
            val method = target.javaClass.getMethod("get", Int::class.javaPrimitiveType)
            method.invoke(target, featureId)
        } catch (_: NoSuchMethodException) {
            null
        } catch (t: Throwable) {
            Log.w(TAG, "invokeFeatureRawGetter failed: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    private fun invokeFeatureBatchGetter(target: Any, featureIds: IntArray, vararg returnClassCandidates: Class<*>): Any? {
        for (returnClass in returnClassCandidates) {
            try {
                val method = target.javaClass.getMethod("get", IntArray::class.java, Class::class.java)
                val raw = method.invoke(target, featureIds, returnClass) ?: continue
                return raw
            } catch (_: NoSuchMethodException) {
                return null
            } catch (t: Throwable) {
                Log.w(
                    TAG,
                    "invokeFeatureBatchGetter(${returnClass.simpleName}) failed: ${t.javaClass.simpleName}: ${t.message}"
                )
            }
        }
        return null
    }

    /**
     * Extract the numeric payload from whatever BYDAutoDevice.get() returns.
     * Priority: doubleValue → floatValue → intValue → longValue → toString
     * Sentinel: -9.99999999E8 (BYD's "no data" sentinel) maps to null.
     */
    private fun extractEventValue(raw: Any): Any? {
        // Try doubleValue first (most stat fields are type=22 = double)
        for (getter in listOf("doubleValue", "getDoubleValue", "getDouble")) {
            try {
                val m = raw.javaClass.getMethod(getter)
                val v = m.invoke(raw) as? Double ?: continue
                // -9.99999999E8 = BYD no-data sentinel
                if (v <= -999_000_000.0) return null
                return v
            } catch (_: NoSuchMethodException) {} catch (_: Exception) {}
        }
        // Try int
        for (getter in listOf("intValue", "getIntValue", "getInt")) {
            try {
                val m = raw.javaClass.getMethod(getter)
                val v = m.invoke(raw)
                if (v != null) return v
            } catch (_: NoSuchMethodException) {} catch (_: Exception) {}
        }
        // Fallback: return raw to let the log show whatever it is
        return raw
    }

    /**
     * Raw event wrappers can expose the useful payload through several numeric accessors.
     */
    private fun extractRawEventNumber(raw: Any): Number? {
        for (getter in listOf("value", "getValue")) {
            try {
                val m = raw.javaClass.getMethod(getter)
                val v = m.invoke(raw)
                if (v is Number) return v
            } catch (_: NoSuchMethodException) {
            } catch (_: Exception) {
            }
        }
        for (getter in listOf("intValue", "getIntValue", "getInt")) {
            try {
                val m = raw.javaClass.getMethod(getter)
                val v = m.invoke(raw)
                if (v is Number) return v
            } catch (_: NoSuchMethodException) {
            } catch (_: Exception) {
            }
        }
        for (getter in listOf("doubleValue", "getDoubleValue", "getDouble")) {
            try {
                val m = raw.javaClass.getMethod(getter)
                val v = m.invoke(raw)
                if (v is Number) return v
            } catch (_: NoSuchMethodException) {
            } catch (_: Exception) {
            }
        }
        return null
    }

    /**
     * Extracts featureId from a BYDAutoEvent object passed to the single-arg onDataChanged callback.
     * Tries common getter names used in BYD event objects.
     */
    private fun extractFeatureIdFromEvent(event: Any): Int? {
        for (getter in listOf("getEventType", "getFeatureId", "getType", "getFeature", "getId", "featureId", "type")) {
            try {
                val v = event.javaClass.getMethod(getter).invoke(event)
                if (v is Int) return v
                if (v is Number) return v.toInt()
            } catch (_: NoSuchMethodException) {
            } catch (_: Exception) {
            }
        }
        // Try public fields as fallback
        for (fieldName in listOf("featureId", "type", "id")) {
            try {
                val f = event.javaClass.getField(fieldName)
                val v = f.get(event)
                if (v is Int) return v
                if (v is Number) return v.toInt()
            } catch (_: NoSuchFieldException) {
            } catch (_: Exception) {
            }
        }
        return null
    }

    @Suppress("UNUSED_PARAMETER")
    private fun describeRawValueHint(featureId: Int, rawNumber: Number?): String? = null

    @Suppress("UNUSED_PARAMETER")
    private fun describeRawValueFallbackHint(featureId: Int, rawNumber: Number?): String? = null

    private fun dispatchDynamicRawFeatureEvent(label: String, featureId: Int, rawNumber: Number) {
        lastFeatureEventElapsedMs = SystemClock.elapsedRealtime()
        when (label) {
            "Speed" -> {
                val spIds = speedAuxFeatureIds
                if (spIds.isNotEmpty() && (featureId == spIds[0] || (spIds.size > 1 && featureId == spIds[1]))) {
                    val raw = rawNumber.toDouble().takeIf { it in 0.0..5000.0 } ?: return
                    lastSpeedEventRaw = raw
                    lastSpeedEventElapsedMs = SystemClock.elapsedRealtime()
                    // Drive speed from this live event ONLY as a fallback for a frozen getter.
                    // The polled getCurrentSpeed getter is authoritative whenever it's healthy;
                    // on firmwares where the event raw isn't proportional to speed, letting
                    // events override a working getter produced wildly wrong / zero speeds.
                    // So commit an event-derived value only when BOTH:
                    //   • the getter has gone stale (no fresh positive reading), AND
                    //   • independent signals (GPS or traction power) confirm we're moving —
                    //     this prevents the noisy event raw from inventing speed at a standstill.
                    val scale = speedEventScale
                    val now = SystemClock.elapsedRealtime()
                    val getterHealthy = now - lastGetterPositiveElapsedMs < SPEED_EVENT_FRESHNESS_MS
                    val snap = _vehicleSnapshot.value
                    // GPS is the preferred bridge for a dead getter (handled in the poll path);
                    // only let the event stream drive speed when GPS is NOT giving a clear
                    // reading (e.g. tunnel / no fix) but traction power still says we're moving.
                    // This keeps the noisy event raw from overriding good GPS on firmwares where
                    // the event value isn't proportional to km/h.
                    val gpsClear = snap.locationGpsSpeed?.let { it > GPS_BRIDGE_MIN_KMH } == true
                    val powerMoving = snap.enginePower?.let { kotlin.math.abs(it) >= 2 } == true
                    // Defer to a fresh typed onSpeedChanged push — it's the clean km/h source
                    // and must not be overridden by this noisy raw-event fallback.
                    val pushFresh = now - lastSpeedPushElapsedMs < SPEED_EVENT_FRESHNESS_MS
                    if (scale != null && !getterHealthy && powerMoving && !gpsClear && !pushFresh) {
                        val kmh = (raw * scale).takeIf { it.isFinite() && it in 0.0..MAX_PLAUSIBLE_SPEED_KMH }
                        if (kmh != null) {
                            if (kmh > 0.1) lastPositiveSpeedElapsedMs = now
                            _vehicleSnapshot.value = snap.copy(directSpeedKmh = kmh)
                            publishSnapshot()
                            // Confirm in the in-app log that events are bridging a stale getter,
                            // so the fix can be verified after parking.
                            if (kmh > 0.1 && now - lastSpeedEventDiagMs > 30_000L) {
                                lastSpeedEventDiagMs = now
                                DiagLog.event(appContext, TAG,
                                    "✅ speed from event stream (getter stale): ${String.format("%.0f", kmh)} km/h " +
                                        "(raw=${String.format("%.0f", raw)} scale=${String.format("%.4f", scale)})")
                            }
                        }
                    }
                    logInfoIfChanged(
                        "speed-value-$featureId",
                        "🔬 speed event raw=${String.format("%.0f", raw)} scale=${speedEventScale ?: "learning"}"
                    )
                }
            }
            "Power" -> {
                val raw = rawNumber.toDouble()
                if (!raw.isFinite() || kotlin.math.abs(raw) > 1_000_000.0) return
                val psIds = powerStateFeatureIds
                if (psIds.isNotEmpty() && featureId == psIds[0]) {
                    val rawState = raw.toInt().takeIf { it in 0..2 }
                    rawState?.let {
                        _vehicleSnapshot.value = _vehicleSnapshot.value.copy(powerStateRaw = it)
                    }
                    logInfoIfChanged(
                        "power-state-$featureId",
                        "🔬 power state value raw=${String.format("%.0f", raw)} " +
                            "state=${rawState ?: "n/a"}"
                    )
                }
                logInfoIfChanged(
                    "power-value-$featureId",
                    "🔬 power value raw=${String.format("%.0f", raw)} " +
                        "asKw=${String.format("%.1f", raw)} div10=${String.format("%.1f", raw / 10.0)} " +
                        "div100=${String.format("%.2f", raw / 100.0)}"
                )
            }
            "Engine" -> {
                val decodedRpm = kotlin.math.abs(rawNumber.toInt()).takeIf { it in 1..20000 } ?: return
                val rpmIds = engineRpmFeatureIds
                when {
                    rpmIds.isNotEmpty() && featureId == rpmIds[0] -> {
                        _vehicleSnapshot.update { snap -> snap.copy(engineSpeedFront = decodedRpm) }
                        publishSnapshot()
                        logInfoIfChanged("engine-front-rpm", "🔬 applied Engine frontRpm=$decodedRpm")
                    }
                    rpmIds.size > 1 && featureId == rpmIds[1] -> {
                        _vehicleSnapshot.update { snap -> snap.copy(engineSpeedRear = decodedRpm) }
                        publishSnapshot()
                        logInfoIfChanged("engine-rear-rpm", "🔬 applied Engine rearRpm=$decodedRpm")
                    }
                }
            }
            "Energy" -> {
                // getEnergyState fires an event every time the drivetrain engagement changes
                // (AWD ↔ RWD ↔ FWD). We don't have the feature ID mapping, so re-read the
                // getter by name immediately so the front-RPM zero-out fires within the same
                // callback rather than waiting up to 1 s for the next poll tick.
                // If getEnergyState() returns 0 (SDK not in active event context), fall back
                // to the raw event value when it falls in the valid drivetrain-state range.
                energyDevice?.let { dev ->
                    val DRIVETRAIN_STATES = setOf(1, 2, 3, 4, 5, 6, 19)
                    val fromGetter = invokeIntGetter(dev, "getEnergyState")?.takeIf { it > 0 }
                    val state = fromGetter
                        ?: rawNumber.toInt().takeIf { it in DRIVETRAIN_STATES }
                        ?: return@let
                    val rwdOnly = state in setOf(3, 6, 19)
                    _vehicleSnapshot.update { snap ->
                        snap.copy(
                            drivetrainState = state,
                            engineSpeedFront = if (rwdOnly) 0 else snap.engineSpeedFront
                        )
                    }
                    if (rwdOnly) publishSnapshot()
                    logInfoIfChanged(
                        "drivetrainState-event",
                        "⚡ drivetrainState(event)=$state (getter=${fromGetter ?: "fallback"}) rwdOnly=$rwdOnly"
                    )
                }
            }
            "Tyre" -> {
                val raw = rawNumber.toDouble()
                Log.d(TAG, "🛞 TyreEvent raw=$raw")
            }
            "Climate" -> {
                // Log arriving AC events so we can map feature IDs to compressor
                // mode, set temperature, fan speed, etc.
                val fid = "0x${featureId.toLong().and(0xFFFFFFFFL).toString(16)}"
                logInfoIfChanged(
                    "climate-event-$featureId",
                    "🌡️ Climate event fid=$fid raw=$rawNumber"
                )
            }
        }
    }

    /**
     * Called by the registered listener proxy whenever a subscribed statistic event arrives.
     * Updates the backing field and rebuilds the snapshot before persistence.
     */
    private fun dispatchStatisticFeatureEvent(featureId: Int, rawNumber: Number) {
        // Capture the pushed event channel for the compat probe — this is where SoH actually
        // arrives (synchronous polls return null on these firmwares). Records EVERY id incl.
        // ones we don't map, so an unmapped real-SoH register (e.g. other apps' 100%) shows up.
        if (VehicleCompatibilityProbe.isEnabled.value) {
            VehicleCompatibilityProbe.recordDispatchedFeature("statistic", featureId, rawNumber)
        }
        val v = rawNumber.toDouble()
        val sdf = statisticDispatchFields
        var matched = true
        when (featureId) {
            sdf["cellVMin"] -> if (v in 1000.0..8000.0) _statisticCellVoltageMin.value = v / 1000.0 else matched = false
            sdf["cellVMax"] -> if (v in 1000.0..8000.0) _statisticCellVoltageMax.value = v / 1000.0 else matched = false
            sdf["cellVCandidateA"], sdf["cellVCandidateB"] -> {
                if (v in 1000.0..8000.0) {
                    val decoded = v / 1000.0
                    val currentMin = _statisticCellVoltageMin.value
                    val currentMax = _statisticCellVoltageMax.value
                    if (currentMin == null || decoded < currentMin) _statisticCellVoltageMin.value = decoded
                    if (currentMax == null || decoded > currentMax) _statisticCellVoltageMax.value = decoded
                } else matched = false
            }
            sdf["batteryCurrent"] -> if (v in 10.0..200000.0) _statisticBatteryCurrent.value  = v / 100.0  else matched = false
            sdf["cellTLow"] -> {
                val resolved = decodeStatisticRawMinus40Temp(v.toInt())
                if (resolved != null) {
                    _statisticCellTempMin.value = resolved
                } else matched = false
            }
            sdf["cellTHigh"] -> {
                val resolved = decodeStatisticRawMinus40Temp(v.toInt())
                if (resolved != null) _statisticCellTempMax.value = resolved else matched = false
            }
            sdf["socBms"] -> {
                val decoded = decodeStatisticPercentRaw(v.toInt(), _vehicleSnapshot.value.statisticElecPercentageValue ?: _instrumentBatteryPercent.value)
                if (decoded != null) _statisticSocBms.value = filterBmsSocGlitch(decoded) else matched = false
            }
            sdf["cellTAvg"] -> {
                val resolved = decodeStatisticRawMinus40Temp(v.toInt())
                if (resolved != null) {
                    _statisticCellTempAvg.value = resolved
                } else matched = false
            }
            sdf["soh"] -> {
                val decoded = decodeStatisticPercentRaw(v.toInt())?.takeIf { it in 50.0..110.0 }
                if (decoded != null) _statisticBatterySoh.value = decoded else matched = false
            }
            sdf["socPanel"] -> {
                val decoded = decodeStatisticPercentRaw(v.toInt(), _vehicleSnapshot.value.statisticElecPercentageValue ?: _instrumentBatteryPercent.value)
                if (decoded != null) {
                    _vehicleSnapshot.value = _vehicleSnapshot.value.copy(statisticSocBatteryPct = decoded)
                } else matched = false
            }
            sdf["availPower"] -> {
                if (v in -1000.0..5000.0) _statisticAvailPower.value = v / 10.0 else matched = false
            }
            else -> matched = false
        }
        if (matched) {
            _vehicleSnapshot.update { snap ->
                snap.copy(
                    batteryCellVoltageMin = _statisticCellVoltageMin.value ?: snap.batteryCellVoltageMin,
                    batteryCellVoltageMax = _statisticCellVoltageMax.value ?: snap.batteryCellVoltageMax,
                    batteryCellTempMin = snap.batteryCellTempMin ?: _statisticCellTempMin.value?.toInt(),
                    batteryCellTempMax = snap.batteryCellTempMax ?: _statisticCellTempMax.value?.toInt(),
                    batterySoh = _statisticBatterySoh.value?.toInt() ?: snap.batterySoh,
                    statisticCellVoltageMin = _statisticCellVoltageMin.value,
                    statisticCellVoltageMax = _statisticCellVoltageMax.value,
                    statisticCellTempMin = _statisticCellTempMin.value,
                    statisticCellTempAvg = _statisticCellTempAvg.value,
                    statisticCellTempMax = _statisticCellTempMax.value,
                    statisticBatteryCurrent = _statisticBatteryCurrent.value,
                    statisticBatterySoh = _statisticBatterySoh.value,
                    statisticSocBms = _statisticSocBms.value,
                    statisticAvailPower = _statisticAvailPower.value,
                    statisticSocBatteryPct = snap.statisticSocBatteryPct,
                )
            }
            publishSnapshot()
            if (
                ENABLE_VERBOSE_RAW_EVENT_LOGS &&
                statisticDispatchFields.values.contains(featureId)
            ) {
                logInfoIfChanged(
                    "dispatch-$featureId",
                    "🔬 applied statistic event -> " +
                        "flowV=${_statisticCellVoltageMin.value}/${_statisticCellVoltageMax.value} " +
                        "flowT=${_statisticCellTempMin.value}/${_statisticCellTempAvg.value}/${_statisticCellTempMax.value} " +
                        "socBms=${_statisticSocBms.value} socPanel=${_vehicleSnapshot.value.statisticSocBatteryPct} " +
                        "snapV=${_vehicleSnapshot.value.statisticCellVoltageMin}/${_vehicleSnapshot.value.statisticCellVoltageMax} " +
                        "snapT=${_vehicleSnapshot.value.statisticCellTempMin}/${_vehicleSnapshot.value.statisticCellTempAvg}/${_vehicleSnapshot.value.statisticCellTempMax}"
                )
            }
            persistStatisticFlows()
        }
    }



    private fun invokeIndexedGetter(target: Any, index: Int, vararg methodNames: String): Any? {
        methodNames.forEach { methodName ->
            try {
                val cacheKey = target.javaClass to "$methodName(Int)"
                val method = methodCache.getOrPut(cacheKey) {
                    try { target.javaClass.getMethod(methodName, Int::class.javaPrimitiveType) }
                    catch (_: NoSuchMethodException) { null }
                } ?: return@forEach
                return method.invoke(target, index)
            } catch (t: Throwable) {
                // NullPointerException from getTyreBatteryValue is expected when
                // TPMS sensor hasn't transmitted yet — log at debug level only
                if (t is NullPointerException) {
                    Log.d(TAG, "Getter $methodName($index) returned null (TPMS not yet received)")
                } else {
                    Log.w(TAG, "Getter $methodName($index) failed: ${t.javaClass.simpleName}: ${t.message}")
                }
            }
        }
        return null
    }

    private fun mapGearValue(gear: Int): String = when (gear) {
        0 -> "N"
        1 -> "R"
        2 -> "D"
        3 -> "P"
        else -> gear.toString()
    }

    /**
     * Maps the gearbox "auto mode type" (the gear-selector position) to a gear label.
     * Values are the platform BYDAutoGearboxDevice constants:
     *   1=P  2=R  3=N  4=D  5=M(manual)  6=S(sport).
     * On firmwares where onCurrentGearChanged never fires and the gearCode/currentGear
     * getters return NULL, this is the channel that actually reports the selector — it
     * fires instantly on a physical shift, even while stationary (this is what the stock
     * cluster and third-party apps read). M and S are forward-driving selector-gate
     * positions, so they map to D for gear/trip purposes (DRIVE_GEARS = {D, R}).
     */
    private fun mapGearboxAutoModeToGear(type: Int): String? = when (type) {
        1 -> "P"
        2 -> "R"
        3 -> "N"
        4 -> "D"
        5, 6 -> "D"
        else -> null
    }

    private fun normalizeTyrePressureBar(raw: Double?, @Suppress("UNUSED_PARAMETER") state: Int? = 0): Double {
        // Do NOT gate on state here — non-zero states (low battery, sensor warning) are
        // informational and should not suppress a valid pressure reading. The raw value range
        // check below is the only validity guard. State is preserved separately for display
        // (formatTyrePsiOrNA) and MQTT schema (tyrePressureSchemaValue).
        if (raw == null) return 0.0
        if (!raw.isFinite()) return 0.0
        if (raw <= -1_000_000_000) return 0.0
        if (raw <= 0.0) return 0.0

        // BYD exposes tyre pressure in one of several encodings depending on the device/firmware:
        //   centi-bar: raw=250 → 2.50 bar  (most common)
        //   deci-bar:   raw=35 → 3.5  bar
        //   bar:        raw=2.5 → 2.5 bar
        val bar = when {
            raw in 100.0..500.0 -> raw / 100.0   // centi-bar
            raw in  10.0..100.0 -> raw / 10.0    // deci-bar
            raw in   1.0..  5.0 -> raw            // already in bar
            else -> 0.0
        }

        return if (bar.isFinite()) bar else 0.0
    }

    private fun barToPsi(bar: Double): Double = if (bar > 0.0) bar * 14.5037738 else 0.0

    private data class InstrumentTyrePressureDecodeResult(
        val encoding: InstrumentTyrePressureEncoding,
        val barBySlot: Map<Int, Double>
    )

    private fun decodeInstrumentTyrePressureBars(
        rawBySlot: Map<Int, Int?>,
        selectedCar: CarConfig?
    ): InstrumentTyrePressureDecodeResult {
        val positiveRaw = rawBySlot.mapNotNull { (slot, raw) ->
            raw?.takeIf { it > 0 }?.let { slot to it }
        }.toMap()
        if (positiveRaw.isEmpty()) {
            return InstrumentTyrePressureDecodeResult(InstrumentTyrePressureEncoding.LEGACY, emptyMap())
        }

        val allAreAmbiguousInts = positiveRaw.values.all { it in 100..500 }
        if (!allAreAmbiguousInts) {
            return InstrumentTyrePressureDecodeResult(
                encoding = InstrumentTyrePressureEncoding.LEGACY,
                barBySlot = positiveRaw.mapValues { (_, raw) -> normalizeTyrePressureBar(raw.toDouble(), null) }
            )
        }

        val centiBarBySlot = positiveRaw.mapValues { (_, raw) -> raw / 100.0 }
        val deciPsiBarBySlot = positiveRaw.mapValues { (_, raw) -> raw / 10.0 / 14.5037738 }

        fun averageForSlots(values: Map<Int, Double>, slots: Set<Int>): Double? {
            val picked = slots.mapNotNull { values[it] }
            return picked.takeIf { it.isNotEmpty() }?.average()
        }

        fun plausibilityPenalty(values: Map<Int, Double>): Double =
            values.values.sumOf { bar ->
                when {
                    bar !in 1.8..3.6 -> 10.0 + abs(bar - 2.7)
                    bar < 2.1 -> 1.0 + (2.1 - bar) * 6.0
                    bar > 3.2 -> (bar - 3.2) * 2.0
                    else -> 0.0
                }
            }

        fun targetPenalty(values: Map<Int, Double>): Double {
            val car = selectedCar ?: return 0.0
            val frontAvg = averageForSlots(values, setOf(1, 3))
            val rearAvg = averageForSlots(values, setOf(2, 4))
            var penalty = 0.0
            frontAvg?.let { penalty += abs(it - car.frontTyrePressureBar) * 4.0 }
            rearAvg?.let { penalty += abs(it - car.rearTyrePressureBar) * 4.0 }
            return penalty
        }

        fun score(values: Map<Int, Double>): Double =
            plausibilityPenalty(values) + targetPenalty(values)

        val centiScore = score(centiBarBySlot)
        val deciPsiScore = score(deciPsiBarBySlot)
        val chosenEncoding = when {
            centiScore + 0.05 < deciPsiScore -> InstrumentTyrePressureEncoding.CENTI_BAR
            deciPsiScore + 0.05 < centiScore -> InstrumentTyrePressureEncoding.DECI_PSI
            positiveRaw.values.maxOrNull() ?: 0 <= 340 -> InstrumentTyrePressureEncoding.CENTI_BAR
            else -> InstrumentTyrePressureEncoding.DECI_PSI
        }
        val chosenBars = when (chosenEncoding) {
            InstrumentTyrePressureEncoding.CENTI_BAR -> centiBarBySlot
            InstrumentTyrePressureEncoding.DECI_PSI -> deciPsiBarBySlot
            InstrumentTyrePressureEncoding.LEGACY -> positiveRaw.mapValues { (_, raw) ->
                normalizeTyrePressureBar(raw.toDouble(), null)
            }
        }

        return InstrumentTyrePressureDecodeResult(chosenEncoding, chosenBars)
    }

    private fun formatTyrePsiOrNA(psi: Double, state: Int?): String {
        return if (state != null && state != 0) "n/a" else if (psi > 0.0) "%.1f".format(psi) else "n/a"
    }

    private fun wheelName(wheel: Int): String = when (wheel) {
        0 -> "lf"
        1 -> "rf"
        2 -> "lr"
        3 -> "rr"
        else -> "wheel=$wheel"
    }

    // Per-feature-ID tracking: key = "CLASS:featureId" or "SNAP:className", value = last dump string
    private val seenFeatureIds = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val lastFeatureLogTimes = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * Tries every plausible numeric accessor on a BYDAutoEventValue object.
     * Returns a descriptive string of all non-exception results.
     */
    /**
     * Reads every declared field (including private) from an object and returns a compact
     * name=value string. Used to decode opaque BYD value/snapshot objects.
     */
    private fun dumpObjectFields(obj: Any): String {
        val cls = obj.javaClass
        val sb = StringBuilder()
        // Walk up the class hierarchy collecting declared fields
        var c: Class<*>? = cls
        while (c != null && c != Any::class.java) {
            for (f in c.declaredFields) {
                try {
                    f.isAccessible = true
                    val v = f.get(obj)
                    if (sb.isNotEmpty()) sb.append(" ")
                    sb.append("${f.name}=$v")
                } catch (_: Exception) {}
            }
            c = c.superclass
        }
        // Also try all declared methods including non-public
        for (m in cls.declaredMethods) {
            if (m.parameterTypes.isNotEmpty()) continue
            if (m.name in setOf("toString", "hashCode", "getClass")) continue
            try {
                m.isAccessible = true
                val v = m.invoke(obj)
                if (v != null) {
                    if (sb.isNotEmpty()) sb.append(" ")
                    sb.append("${m.name}()=$v")
                }
            } catch (_: Exception) {}
        }
        return if (sb.isEmpty()) "<empty>" else sb.toString()
    }

    /**
     * Inspect the device's registerListener overloads at runtime and mirror
     * incoming callback values into the public snapshot.
     */
    private fun registerEventMirrorListener(device: Any, label: String) {
        try {
            val deviceClass = device.javaClass
            val registerMethods = deviceClass.methods.filter { it.name == "registerListener" }
            val paramSummary = registerMethods.joinToString { m ->
                m.parameterTypes.joinToString(",") { it.simpleName }
            }
            if (ENABLE_LAB_DIAGNOSTICS) {
                Log.i(TAG, "🔌 $label registerListener params: $paramSummary")
            }

            for (method in registerMethods) {
                val params = method.parameterTypes
                if (params.size != 1) continue
                val listenerType = params[0]
                if (!listenerType.isInterface) {
                    val abstractMethods = listenerType.methods.filter {
                        java.lang.reflect.Modifier.isAbstract(it.modifiers)
                    }
                    if (ENABLE_LAB_DIAGNOSTICS) {
                        Log.i(TAG, "🔌 $label ${listenerType.simpleName} is abstract: ${abstractMethods.joinToString { it.name }}")
                    }
                    continue
                }
                val holderRef = arrayOfNulls<Any>(1)
                val proxy = Proxy.newProxyInstance(
                    listenerType.classLoader,
                    arrayOf(listenerType),
                ) { _, proxyMethod, args ->
                    when (proxyMethod.name) {
                        "onSpeedChanged", "onCurrentSpeedChanged" -> {
                            // DORMANT (see CLAUDE.md): the bydauto SpeedDevice does NOT deliver this
                            // typed callback to a normal in-process app on this firmware (only the
                            // noisy raw onDataEventChanged). Other apps get instant speed from a different
                            // API (com.ts.lib.caradapter / CarSensorAdapterManager), not bydauto. This
                            // handler is correct and harmless and would activate if the callback is ever
                            // delivered (privileged daemon or other firmware) — keep it.
                            //
                            // Typed speed push from the SpeedDevice — the cluster's actual
                            // km/h, delivered the instant it changes. It is clean and already in km/h,
                            // unlike the raw feature event (noisy, non-proportional). Apply it
                            // straight to the snapshot for an instant UI update; setting
                            // lastSpeedPushElapsedMs makes the 1 s poll getter yield to it
                            // (see logSpeedSnapshot). DB recording stays on the poll cadence.
                            if (label == "Speed") {
                                val kmh = (args?.getOrNull(0) as? Number)?.toDouble()
                                if (kmh != null && kmh in 0.0..MAX_PLAUSIBLE_SPEED_KMH) {
                                    val now = SystemClock.elapsedRealtime()
                                    if (now - lastSpeedPushElapsedMs >= SPEED_PUSH_MIN_INTERVAL_MS) {
                                        val firstPush = lastSpeedPushElapsedMs == 0L
                                        lastSpeedPushElapsedMs = now
                                        if (kmh > 0.1) lastPositiveSpeedElapsedMs = now
                                        _vehicleSnapshot.value = _vehicleSnapshot.value.copy(directSpeedKmh = kmh)
                                        publishSnapshot()
                                        // Confirm the typed push is live (throttled ~5 s). If these
                                        // appear, speed is now event-driven/instant on this firmware.
                                        if (firstPush || now - lastSpeedPushDiagMs > 5_000L) {
                                            lastSpeedPushDiagMs = now
                                            DiagLog.event(appContext, TAG,
                                                "✅ speed PUSH (onSpeedChanged): ${String.format("%.0f", kmh)} km/h")
                                        }
                                    }
                                }
                            }
                        }
                        "onDataEventChanged", "onEventChanged", "onFeatureChanged", "onDataChanged" -> {
                            val arg0 = args?.getOrNull(0)
                            val arg1 = args?.getOrNull(1)
                            val fid = arg0 as? Int
                            if (fid != null && arg1 != null) {
                                val ev = arg1
                                val structKey = "CLASS:$fid"
                                if (!seenFeatureIds.containsKey(structKey)) {
                                    seenFeatureIds[structKey] = ""
                                }
                                val dump = dumpObjectFields(ev)
                                val rawNumber = extractRawEventNumber(ev)
                                val candidate = describeRawValueHint(fid, rawNumber)
                                val tempLikeCandidate = if (label == "Statistic") {
                                    describeRawValueFallbackHint(fid, rawNumber)
                                } else null
                                // Apply arriving values to the public snapshot.
                                if (rawNumber != null) dispatchStatisticFeatureEvent(fid, rawNumber)
                                if (rawNumber != null && label != "Statistic") {
                                    dispatchDynamicRawFeatureEvent(label, fid, rawNumber)
                                    if (label == "Tyre") {
                                        Log.i(TAG, "🛞 rawFeature Tyre raw=$rawNumber")
                                    }
                                }
                                val valueKey = "VAL:$fid"
                                val lastLogTime = lastFeatureLogTimes[valueKey] ?: 0L
                                val now = android.os.SystemClock.elapsedRealtime()
                                // Throttle: max 1 log per second per key if changing, 1 per 10s if not changing
                                val changed = seenFeatureIds[valueKey] != dump
                                if (changed) seenFeatureIds[valueKey] = dump

                                if (
                                    ENABLE_VERBOSE_RAW_EVENT_LOGS &&
                                    candidate != null &&
                                    ((changed && now - lastLogTime > 1000) || (!changed && now - lastLogTime > 10000))
                                ) {
                                    lastFeatureLogTimes[valueKey] = now
                                    Log.i(TAG, "🔬 $label $candidate")
                                } else if (
                                    ENABLE_VERBOSE_RAW_EVENT_LOGS &&
                                    tempLikeCandidate != null &&
                                    ((changed && now - lastLogTime > 1000) || (!changed && now - lastLogTime > 10000))
                                ) {
                                    lastFeatureLogTimes[valueKey] = now
                                    Log.i(TAG, "🔬 $label temp-like raw=$rawNumber $tempLikeCandidate")
                                } else if (
                                    ENABLE_VERBOSE_RAW_EVENT_LOGS &&
                                    label != "Statistic" &&
                                    rawNumber != null &&
                                    ((changed && now - lastLogTime > 1000) || (!changed && now - lastLogTime > 10000))
                                ) {
                                    lastFeatureLogTimes[valueKey] = now
                                    Log.i(TAG, "🔬 $label raw value=$rawNumber")
                                }

                                val detailKey = "DETAIL:$valueKey"
                                val lastDetailTime = lastFeatureLogTimes[detailKey] ?: 0L
                                if (
                                    isVerboseEventLabel(label) &&
                                    ((changed && now - lastDetailTime > 1000) || (!changed && now - lastDetailTime > 10000))
                                ) {
                                    lastFeatureLogTimes[detailKey] = now
                                    val rawHint = describeGeneralEventValue(rawNumber)
                                    val detail = compactDiagnosticDetail(
                                        if (ev is BYDAutoEventValue) describeEventValue(ev) else dump
                                    )
                                    Log.i(
                                        TAG,
                                        "🔬 $label event raw=${rawNumber ?: "n/a"}" +
                                            (rawHint?.let { " $it" } ?: "") +
                                            " detail=$detail"
                                    )
                                }
                            } else if (arg0 != null && arg0 !is Int) {
                                // onDataChanged(BYDAutoEvent snapshot) — no featureId arg
                                val snapKey = "SNAP:${arg0.javaClass.simpleName}"
                                if (!seenFeatureIds.containsKey(snapKey)) {
                                    seenFeatureIds[snapKey] = ""
                                }
                                // Extract the event subtype when present.
                                val eventType = runCatching {
                                    arg0.javaClass.getDeclaredMethod("getEventType").also { it.isAccessible = true }.invoke(arg0)
                                }.getOrNull()
                                val eventFeatureId = (eventType as? Int) ?: 0
                                val subTypeKey = if (eventFeatureId != 0) {
                                    "SNAP_VAL:$eventFeatureId"
                                } else {
                                    "SNAP_VAL:${arg0.javaClass.simpleName}"
                                }
                                val dump = dumpObjectFields(arg0)
                                val rawNumber = extractRawEventNumber(arg0)
                                if (eventFeatureId != 0 && rawNumber != null) {
                                    dispatchStatisticFeatureEvent(eventFeatureId, rawNumber)
                                    if (label != "Statistic") dispatchDynamicRawFeatureEvent(label, eventFeatureId, rawNumber)
                                }
                                val candidate = describeRawValueHint(eventFeatureId, rawNumber)
                                val tempLikeCandidate = if (label == "Statistic") {
                                    describeRawValueFallbackHint(eventFeatureId, rawNumber)
                                } else null
                                val lastLogTime = lastFeatureLogTimes[subTypeKey] ?: 0L
                                val now = android.os.SystemClock.elapsedRealtime()
                                val changed = seenFeatureIds[subTypeKey] != dump
                                if (changed) seenFeatureIds[subTypeKey] = dump

                                if (
                                    ENABLE_VERBOSE_RAW_EVENT_LOGS &&
                                    candidate != null &&
                                    ((changed && now - lastLogTime > 1000) || (!changed && now - lastLogTime > 10000))
                                ) {
                                    lastFeatureLogTimes[subTypeKey] = now
                                    Log.i(TAG, "🔬 $label $candidate")
                                } else if (
                                    ENABLE_VERBOSE_RAW_EVENT_LOGS &&
                                    tempLikeCandidate != null &&
                                    ((changed && now - lastLogTime > 1000) || (!changed && now - lastLogTime > 10000))
                                ) {
                                    lastFeatureLogTimes[subTypeKey] = now
                                    Log.i(TAG, "🔬 $label temp-like raw=$rawNumber $tempLikeCandidate")
                                } else if (
                                    ENABLE_VERBOSE_RAW_EVENT_LOGS &&
                                    label != "Statistic" &&
                                    rawNumber != null &&
                                    ((changed && now - lastLogTime > 1000) || (!changed && now - lastLogTime > 10000))
                                ) {
                                    lastFeatureLogTimes[subTypeKey] = now
                                    Log.i(TAG, "🔬 $label raw value=$rawNumber")
                                }

                                val detailKey = "DETAIL:$subTypeKey"
                                val lastDetailTime = lastFeatureLogTimes[detailKey] ?: 0L
                                if (
                                    isVerboseEventLabel(label) &&
                                    ((changed && now - lastDetailTime > 1000) || (!changed && now - lastDetailTime > 10000))
                                ) {
                                    lastFeatureLogTimes[detailKey] = now
                                    val rawHint = describeGeneralEventValue(rawNumber)
                                    Log.i(
                                        TAG,
                                        "🔬 $label event raw=${rawNumber ?: "n/a"}" +
                                            (rawHint?.let { " $it" } ?: "") +
                                            " detail=${compactDiagnosticDetail(dump)}"
                                    )
                                }
                            }
                        }
                        "equals" -> return@newProxyInstance (args?.get(0) === holderRef[0])
                        "hashCode" -> return@newProxyInstance System.identityHashCode(holderRef[0])
                        "toString" -> return@newProxyInstance "RawFeatureListener($label)"
                        else -> if (ENABLE_VERBOSE_RAW_EVENT_LOGS && proxyMethod.name.startsWith("on")) {
                            Log.d(TAG, "📡 $label.${proxyMethod.name}")
                        }
                    }
                    null
                }
                holderRef[0] = proxy
                try {
                    method.invoke(device, proxy)
                    listenerReferences += proxy
                    // Track for wedge recovery (re-register on a stalled SDK callback channel).
                    mirrorRegs.removeAll { it.device === device }
                    mirrorRegs += MirrorReg(device, proxy, label, listenerType)
                    if (ENABLE_LAB_DIAGNOSTICS) {
                        Log.i(TAG, "\u2705 $label raw listener registered via ${listenerType.simpleName}")
                    }
                    return
                } catch (e: Exception) {
                    Log.w(TAG, "🔌 $label registerListener(${listenerType.simpleName}) failed: ${e.message}")
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "registerEventMirrorListener($label): ${t.javaClass.simpleName}: ${t.message}")
        }
    }
}
