package com.byd.tripstats.mock

import com.byd.tripstats.data.model.VehicleTelemetry
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.time.Instant
import kotlin.math.sin
import kotlin.random.Random

/**
 * Mock Data Generator for testing the app without real MQTT connection
 * Simulates a realistic drive with acceleration, regeneration, and varying conditions
 */
class MockDataGenerator {
    
    private var currentOdometer = 23366.3
    private var currentTotalDischarge = 4762.6
    private var currentSoc = 97.6
    private var currentSpeed = 0.0
    private var currentPower = 0.0
    
    /**
     * Generates mock telemetry data simulating a drive
     * @param durationSeconds Total duration of the simulated drive
     * @param updateIntervalMs Interval between updates (simulates MQTT message frequency)
     */
    fun generateMockDrive(
        durationSeconds: Int = 120, // 2-minute drive
        updateIntervalMs: Long = 1000 // 1 second updates
    ): Flow<VehicleTelemetry> = flow {
        val totalUpdates = (durationSeconds * 1000 / updateIntervalMs).toInt()
        
        for (i in 0..totalUpdates) {
            val progress = i.toFloat() / totalUpdates
            
            // Simulate realistic driving pattern
            val telemetry = generateTelemetryForProgress(progress)
            emit(telemetry)
            
            delay(updateIntervalMs)
        }
    }
    
    private fun generateTelemetryForProgress(progress: Float): VehicleTelemetry {
        // Simulate driving phases: acceleration, cruising, deceleration
        val phase = when {
            progress < 0.15f -> "acceleration" // 0-15%: accelerating
            progress < 0.7f -> "cruising"      // 15-70%: steady cruising  
            progress < 0.85f -> "deceleration" // 70-85%: slowing down with regen
            else -> "stopping"                  // 85-100%: coming to stop
        }
        
        // Update speed based on phase
        currentSpeed = when (phase) {
            "acceleration" -> (progress / 0.15f) * 80.0 // 0 to 80 km/h
            "cruising" -> 80.0 + sin(progress * 10) * 5 // 75-85 km/h with variation
            "deceleration" -> 80.0 * (1 - ((progress - 0.7f) / 0.15f)) // 80 to 0 km/h
            "stopping" -> maxOf(0.0, 20.0 * (1 - ((progress - 0.85f) / 0.15f)))
            else -> 0.0
        }
        
        // Update power based on phase
        currentPower = when (phase) {
            "acceleration" -> 30.0 + Random.nextDouble(-5.0, 10.0) // Positive power
            "cruising" -> 15.0 + Random.nextDouble(-3.0, 3.0) // Moderate power
            "deceleration" -> -25.0 + Random.nextDouble(-10.0, 5.0) // Negative (regen)
            "stopping" -> -15.0 + Random.nextDouble(-5.0, 2.0)
            else -> 0.0
        }
        
        // Update odometer (distance = speed * time)
        val distanceIncrement = (currentSpeed / 3600.0) // km per second
        currentOdometer += distanceIncrement
        
        // Update total discharge (energy = power * time)
        if (currentPower > 0) {
            val energyIncrement = (currentPower / 3600.0) // kWh per second
            currentTotalDischarge += energyIncrement
            currentSoc -= energyIncrement / 82.5 * 100 // Assuming 82.5 kWh battery
        } else {
            // Regeneration - add energy back
            val energyRecovered = (-currentPower * 0.7 / 3600.0) // 70% efficiency
            currentSoc += energyRecovered / 82.5 * 100
        }
        
        // Clamp SOC between 0 and 100
        currentSoc = currentSoc.coerceIn(0.0, 100.0)
        
        // Determine gear
        val gear = when {
            progress > 0.95 -> "P"  // Go to Park at 95% complete
            currentSpeed < 0.5 -> "D"
            else -> "D"
        }
        
        // Simulated battery temperature (varies slightly)
        val baseTemp = 20
        val tempVariation = (sin(progress * 20) * 3).toInt()
        
        // Simulate GPS coordinates (San Francisco area for demo)
        val startLat = 37.7749
        val startLon = -122.4194
        val latOffset = progress * 0.02 // Move north
        val lonOffset = progress * 0.015 // Move east
        
        return VehicleTelemetry(
            battery12vVoltage = 13.0 + Random.nextDouble(-0.5, 0.5),
            batteryCellTempMax = baseTemp + tempVariation + 2,
            batteryCellVoltageMax = 3.331 + Random.nextDouble(-0.01, 0.01),
            batteryCellTempMin = baseTemp + tempVariation - 2,
            batteryCellVoltageMin = 3.328 + Random.nextDouble(-0.01, 0.01),
            currentDatetime = Instant.now().toString(),
            odometer = currentOdometer,
            soc = currentSoc,
            soh = 98,
            locationAltitude = 50.0 + Random.nextDouble(-10.0, 10.0),
            chargingPower = if (progress > 0.95) 10.0 else 0.0, // Start "charging" at 95%
            enginePower = currentPower,
            engineSpeedFront = (currentSpeed * 90).toInt(),
            gear = gear,
            locationLatitude = startLat + latOffset,
            locationLongitude = startLon + lonOffset,
            engineSpeedRear = (currentSpeed * 100).toInt(),
            speed = currentSpeed,
            wifiSsid = "",
            batteryTotalVoltage = 573,
            electricDrivingRangeKm = (currentSoc * 5.15).toInt(), // ~515km at 100%
            totalDischarge = currentTotalDischarge
        )
    }
    
    /**
     * Generate a single static telemetry reading (parked car)
     */
    fun generateParkedTelemetry(): VehicleTelemetry {
        return VehicleTelemetry(
            battery12vVoltage = 13.0,
            batteryCellTempMax = 18,
            batteryCellVoltageMax = 3.331,
            batteryCellTempMin = 16,
            batteryCellVoltageMin = 3.328,
            currentDatetime = Instant.now().toString(),
            odometer = currentOdometer,
            soc = currentSoc,
            soh = 98,
            locationAltitude = 50.0,
            chargingPower = 0.0,
            enginePower = 0.0,
            engineSpeedFront = 0,
            gear = "P",
            locationLatitude = 37.7749,
            locationLongitude = -122.4194,
            engineSpeedRear = 0,
            speed = 0.0,
            wifiSsid = "Home_WiFi",
            batteryTotalVoltage = 573,
            electricDrivingRangeKm = (currentSoc * 5.15).toInt(),
            totalDischarge = currentTotalDischarge
        )
    }
}
