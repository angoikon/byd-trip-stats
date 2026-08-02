package com.byd.tripstats.mock

import com.byd.tripstats.data.model.VehicleTelemetry
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.time.Instant
import kotlin.math.sin
import kotlin.random.Random

/**
 * Mock telemetry generator used by both the in-app mock drive button and the
 * unit / integration test suite.
 *
 * Simulates a realistic BYD Seal AWD Excellence drive cycle:
 *   0–15 %  → acceleration to 80 km/h
 *   15–70 % → motorway cruise at ~80 km/h
 *   70–85 % → regen deceleration
 *   85–100% → final stop and park
 *
 * All VehicleTelemetry fields are populated, including v2 fields
 * (tyrePressures, tyreTempLF/RF/LR/RR, socPanel, carOn, etc.).
 *
 * With [phev] = true the same cycle is generated as a DM-i style plug-in hybrid
 * drive (small pack via [batteryKwh]): the trip starts electric (energyMode=EV),
 * the middle of the cruise runs charge-sustaining on petrol (energyMode=HEV —
 * fuel burns, iceMileageKm accrues, coolant heats to ~90 °C, SoC holds roughly
 * flat), and deceleration/stop return to EV. All PHEV telemetry fields
 * (fuelPercentage, fuelDrivingRangeKm, instant/avg/totalFuelConsumption,
 * evMileageKm, iceMileageKm, engineCoolantTemp, energyMode) are populated so
 * PHEV features can be developed without access to a real PHEV.
 */
class MockDataGenerator(
    private val phev: Boolean = false,
    private val batteryKwh: Double = 82.5,
) {

    private companion object {
        const val PHEV_TANK_LITERS       = 60.0   // Seal U DM-i style tank
        const val PHEV_ICE_L_PER_100KM   = 5.8    // charge-sustain cruise burn
        const val AMBIENT_TEMP_C         = 22
        const val COOLANT_OPERATING_C    = 90
    }

    // Starting state — realistic values for a Seal Excellence mid-trip
    // (PHEV profile starts at a typical part-charged pack instead of near-full)
    private var currentOdometer      = 23_366.3
    private var currentTotalDischarge = 4_762.6
    private var currentSoc           = if (phev) 44.0 else 97.6
    private var currentSpeed         = 0.0
    private var currentPower         = 0.0
    private var currentSocPanel      = if (phev) 44 else 97   // instrument cluster SoC (integer %)

    // PHEV state — lifetime-style counters like the real statistic device reports
    private var currentFuelPct       = 62.0
    private var currentTotalFuelL    = 823.4   // lifetime litres burned
    private var currentIceKm         = 5_210.0 // lifetime km propelled by ICE
    private var currentEvKm          = 18_100.0
    private var currentCoolantC      = AMBIENT_TEMP_C.toDouble()
    private var currentEnergyMode    = 1       // 1=EV, 3=HEV

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Emits a sequence of telemetry packets simulating a complete drive.
     *
     * @param durationSeconds  Total simulated drive time
     * @param updateIntervalMs Interval between packets (mirrors Electro publish rate)
     */
    fun generateMockDrive(
        durationSeconds:  Int  = 120,
        updateIntervalMs: Long = 1_000
    ): Flow<VehicleTelemetry> = flow {
        val totalUpdates = (durationSeconds * 1_000 / updateIntervalMs).toInt()
        for (i in 0..totalUpdates) {
            emit(generateTelemetryForProgress(i.toFloat() / totalUpdates))
            delay(updateIntervalMs)
        }
    }

    /**
     * Generates a [count] list of telemetry packets without any delay.
     * Useful in tests where you need deterministic data without coroutine timing.
     */
    fun generateDriveSequence(count: Int = 60): List<VehicleTelemetry> {
        reset()
        return (0 until count).map { i ->
            generateTelemetryForProgress(i.toFloat() / count)
        }
    }

    /** Returns a single parked-car telemetry snapshot. */
    fun generateParkedTelemetry(): VehicleTelemetry = VehicleTelemetry(
        battery12vVoltage    = 12.8,
        batteryCellTempMax   = 18,
        batteryCellVoltageMax = 3.331,
        batteryCellTempMin   = 16,
        batteryCellVoltageMin = 3.328,
        currentDatetime      = Instant.now().toString(),
        odometer             = currentOdometer,
        soc                  = currentSoc,
        soh                  = 98,
        locationAltitude     = 50.0,
        chargingPower        = 0.0,
        enginePower          = 0,
        engineSpeedFront     = 0,
        gear                 = "P",
        locationLatitude     = 37.9838,
        locationLongitude    = 23.7275,
        engineSpeedRear      = 0,
        speed                = 0.0,
        wifiSsid             = "",
        batteryTotalVoltage  = 573,
        electricDrivingRangeKm = mockElectricRangeKm(),
        totalDischarge       = currentTotalDischarge,
        carOn                = 0,
        tyrePressureLF       = 38.5,
        tyrePressureRF       = 38.5,
        tyrePressureLR       = 42.0,
        tyrePressureRR       = 42.0,
        tyreTempLF           = 22,
        tyreTempRF           = 22,
        tyreTempLR           = 22,
        tyreTempRR           = 22,
        socPanel             = currentSocPanel,
        carLocked            = 1,
        anyDoorOpened        = 0,
        fuelPercentage       = if (phev) currentFuelPct.toInt() else 0,
        fuelDrivingRangeKm   = if (phev) mockFuelRangeKm() else 0,
        totalFuelConsumption = if (phev) currentTotalFuelL else null,
        evMileageKm          = if (phev) currentEvKm.toInt() else null,
        iceMileageKm         = if (phev) currentIceKm.toInt() else null,
        engineCoolantTemp    = if (phev) AMBIENT_TEMP_C else null,
    )

    /** Generates a charging telemetry packet (AC, ~7 kW). */
    fun generateAcChargingTelemetry(
        chargingPower: Double = 7.2,
        soc: Double = currentSoc,
        carOn: Int = 0
    ): VehicleTelemetry = generateParkedTelemetry().copy(
        chargingPower = chargingPower,
        soc           = soc,
        carOn         = carOn,
        gear          = "P"
    )

    /** Generates a DC fast-charging packet (50 kW). */
    fun generateDcChargingTelemetry(
        chargingPower: Double = 50.0,
        soc: Double = currentSoc,
        carOn: Int = 1
    ): VehicleTelemetry = generateParkedTelemetry().copy(
        chargingPower = chargingPower,
        soc           = soc,
        carOn         = carOn,
        gear          = "P"
    )

    /** Resets internal state back to initial values. */
    fun reset() {
        currentOdometer       = 23_366.3
        currentTotalDischarge = 4_762.6
        currentSoc            = if (phev) 44.0 else 97.6
        currentSpeed          = 0.0
        currentPower          = 0.0
        currentSocPanel       = if (phev) 44 else 97
        currentFuelPct        = 62.0
        currentTotalFuelL     = 823.4
        currentIceKm          = 5_210.0
        currentEvKm           = 18_100.0
        currentCoolantC       = AMBIENT_TEMP_C.toDouble()
        currentEnergyMode     = 1
    }

    // ── Internal generation ───────────────────────────────────────────────────

    private fun generateTelemetryForProgress(progress: Float): VehicleTelemetry {
        val phase = when {
            progress < 0.15f -> "acceleration"
            progress < 0.70f -> "cruising"
            progress < 0.85f -> "deceleration"
            else             -> "stopping"
        }

        currentSpeed = when (phase) {
            "acceleration" -> (progress / 0.15f) * 80.0
            "cruising"     -> 80.0 + sin(progress * 10.0) * 5.0
            "deceleration" -> 80.0 * (1.0 - (progress - 0.70f) / 0.15f)
            else           -> maxOf(0.0, 20.0 * (1.0 - (progress - 0.85f) / 0.15f))
        }

        // PHEV: the middle of the cruise runs charge-sustaining on petrol (HEV);
        // everything else is electric. BEV: never.
        val iceActive = phev && progress >= 0.40f && progress < 0.70f
        currentEnergyMode = if (iceActive) 3 else 1

        currentPower = when {
            // Charge-sustain: the ICE propels, the pack sees only a small +/- trickle.
            iceActive              -> 1.0 + Random.nextDouble(-3.0, 3.0)
            phase == "acceleration" -> 30.0 + Random.nextDouble(-5.0, 10.0)
            phase == "cruising"     -> 15.0 + Random.nextDouble(-3.0,  3.0)
            phase == "deceleration" -> -25.0 + Random.nextDouble(-10.0, 5.0)
            else                    -> -15.0 + Random.nextDouble(-5.0,  2.0)
        }

        // Odometer — km per second at current speed
        val tickDistanceKm = currentSpeed / 3_600.0
        currentOdometer += tickDistanceKm

        // SoC — discharge or recover (regen)
        if (currentPower > 0) {
            val energyKwh = currentPower / 3_600.0
            currentTotalDischarge += energyKwh
            currentSoc -= energyKwh / batteryKwh * 100.0
        } else {
            val recovered = -currentPower * 0.7 / 3_600.0
            currentSoc   += recovered / batteryKwh * 100.0
        }
        currentSoc = currentSoc.coerceIn(0.0, 100.0)
        currentSocPanel = currentSoc.toInt()  // simplified — usually ±1 of soc

        // PHEV counters: fuel burn + ICE/EV mileage split + coolant thermal model
        var instantFuelLPer100 = 0.0
        if (phev) {
            if (iceActive) {
                instantFuelLPer100 = PHEV_ICE_L_PER_100KM + Random.nextDouble(-1.2, 1.8)
                currentTotalFuelL += instantFuelLPer100 * tickDistanceKm / 100.0
                currentFuelPct -= instantFuelLPer100 * tickDistanceKm / 100.0 / PHEV_TANK_LITERS * 100.0
                currentIceKm += tickDistanceKm
                // Warm-up ramp toward operating temperature while burning fuel
                currentCoolantC = minOf(COOLANT_OPERATING_C.toDouble(), currentCoolantC + 2.5)
            } else {
                currentEvKm += tickDistanceKm
                // Slow cool-down toward ambient with the ICE off
                currentCoolantC = maxOf(AMBIENT_TEMP_C.toDouble(), currentCoolantC - 0.3)
            }
            currentFuelPct = currentFuelPct.coerceIn(0.0, 100.0)
        }

        val gear  = if (progress > 0.95f) "P" else "D"
        val carOn = if (progress > 0.97f) 0 else 2

        val baseTemp   = 22
        val tempOffset = (sin(progress * 20.0) * 3.0).toInt()

        // Tyre pressures rise slightly as tyres warm (PSI)
        val tyrePressureBase = 38.5 + progress * 1.5

        return VehicleTelemetry(
            battery12vVoltage     = 13.4 + Random.nextDouble(-0.3, 0.3),
            batteryCellTempMax    = baseTemp + tempOffset + 2,
            batteryCellVoltageMax = 3.331 + Random.nextDouble(-0.005, 0.005),
            batteryCellTempMin    = baseTemp + tempOffset - 2,
            batteryCellVoltageMin = 3.328 + Random.nextDouble(-0.005, 0.005),
            currentDatetime       = Instant.now().toString(),
            odometer              = currentOdometer,
            soc                   = currentSoc,
            soh                   = 98,
            locationAltitude      = 50.0 + Random.nextDouble(-5.0, 5.0),
            chargingPower         = 0.0,
            enginePower           = currentPower.toInt(),
            engineSpeedFront      = if (gear == "D") (currentSpeed * 85).toInt() else 0,
            gear                  = gear,
            locationLatitude      = 37.9838 + progress * 0.05,
            locationLongitude     = 23.7275 + progress * 0.04,
            engineSpeedRear       = if (gear == "D") (currentSpeed * 100).toInt() else 0,
            speed                 = currentSpeed,
            wifiSsid              = "",
            batteryTotalVoltage   = (560 + currentSoc * 0.15).toInt(),
            electricDrivingRangeKm = mockElectricRangeKm(),
            totalDischarge        = currentTotalDischarge,
            carOn                 = carOn,
            tyrePressureLF        = tyrePressureBase + Random.nextDouble(-0.3, 0.3),
            tyrePressureRF        = tyrePressureBase + Random.nextDouble(-0.3, 0.3),
            tyrePressureLR        = tyrePressureBase + 3.5 + Random.nextDouble(-0.3, 0.3),
            tyrePressureRR        = tyrePressureBase + 3.5 + Random.nextDouble(-0.3, 0.3),
            tyreTempLF            = 22 + (progress * 15).toInt(),
            tyreTempRF            = 22 + (progress * 15).toInt(),
            tyreTempLR            = 21 + (progress * 14).toInt(),
            tyreTempRR            = 21 + (progress * 14).toInt(),
            socPanel              = currentSocPanel,
            carLocked             = 0,
            anyDoorOpened         = 0,
            fuelPercentage        = if (phev) currentFuelPct.toInt() else 0,
            fuelDrivingRangeKm    = if (phev) mockFuelRangeKm() else 0,
            avgFuelConsumption    = if (phev) PHEV_ICE_L_PER_100KM - 0.2 else null,
            instantFuelConsumption = if (phev) instantFuelLPer100 else null,
            totalFuelConsumption  = if (phev) currentTotalFuelL else null,
            evMileageKm           = if (phev) currentEvKm.toInt() else null,
            iceMileageKm          = if (phev) currentIceKm.toInt() else null,
            engineCoolantTemp     = if (phev) currentCoolantC.toInt() else null,
            energyMode            = if (phev) currentEnergyMode else 0,
        )
    }

    /**
     * Projected electric range for the current SoC. Assumes ~16 kWh/100km, which
     * reproduces the historical `soc * 5.2` figure for the default 82.5 kWh pack
     * and scales honestly for the small PHEV pack.
     */
    private fun mockElectricRangeKm(): Int =
        if (phev) (currentSoc / 100.0 * batteryKwh / 0.16).toInt()
        else (currentSoc * 5.2).toInt()

    /** Fuel range like the cluster reports it: remaining litres / cruise burn rate. */
    private fun mockFuelRangeKm(): Int =
        (currentFuelPct / 100.0 * PHEV_TANK_LITERS / PHEV_ICE_L_PER_100KM * 100.0).toInt()
}
