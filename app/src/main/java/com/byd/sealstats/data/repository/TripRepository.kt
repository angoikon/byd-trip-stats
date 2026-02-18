package com.byd.sealstats.data.repository

import android.content.Context
import android.util.Log
import com.byd.sealstats.data.local.BydStatsDatabase
import com.byd.sealstats.data.local.entity.TripDataPointEntity
import com.byd.sealstats.data.local.entity.TripEntity
import com.byd.sealstats.data.local.entity.TripStatsEntity
import com.byd.sealstats.data.model.VehicleTelemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import kotlin.math.abs

class TripRepository private constructor(context: Context) {
    private val TAG = "TripRepository"
    private val database = BydStatsDatabase.getDatabase(context)
    private val tripDao = database.tripDao()
    private val dataPointDao = database.tripDataPointDao()
    private val statsDao = database.tripStatsDao()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Latest telemetry for UI
    private val _latestTelemetry = MutableStateFlow<VehicleTelemetry?>(null)
    val latestTelemetry: StateFlow<VehicleTelemetry?> = _latestTelemetry.asStateFlow()

    private var currentTripId: Long? = null
    private var lastTelemetry: VehicleTelemetry? = null
    private var tripStarted = false

    // Configuration
    private var autoTripDetection = true
    private val tripEndDelayMs = 2 * 60 * 1000L // 2 minutes of inactivity to end trip
    private var lastActiveTime = 0L

    init {
        // Check for active trip on initialization
        scope.launch {
            val activeTrip = tripDao.getActiveTrip()
            if (activeTrip != null) {
                currentTripId = activeTrip.id
                tripStarted = true
                Log.i(TAG, "Resumed active trip: ${activeTrip.id}")
            }
        }
    }

    suspend fun processTelemetry(telemetry: VehicleTelemetry) {
        // Broadcast latest telemetry to UI
        _latestTelemetry.value = telemetry
        Log.d(TAG, "Broadcasting telemetry: SOC=${telemetry.soc}, Speed=${telemetry.speed}")

        val currentTime = System.currentTimeMillis()

        // Auto trip detection
        if (autoTripDetection) {
            handleAutoTripDetection(telemetry, currentTime)
        }

        // If there's an active trip, record data point
        currentTripId?.let { tripId ->
            recordDataPoint(tripId, telemetry)
            updateTripMetrics(tripId, telemetry)
        }

        lastTelemetry = telemetry

        // Check for trip timeout
        if (tripStarted && !telemetry.isDriving) {
            if (currentTime - lastActiveTime > tripEndDelayMs) {
                Log.i(TAG, "Trip timeout - ending trip")
                endCurrentTrip()
            }
        } else if (telemetry.isDriving) {
            lastActiveTime = currentTime
        }
    }

    private suspend fun handleAutoTripDetection(telemetry: VehicleTelemetry, currentTime: Long) {
        val last = lastTelemetry

        // Trip start conditions
        if (!tripStarted && shouldStartTrip(last, telemetry)) {
            Log.i(TAG, "Auto-starting trip")
            startTrip(telemetry, isManual = false)
        }

        // Trip end conditions
        if (tripStarted && shouldEndTrip(telemetry, currentTime)) {
            Log.i(TAG, "Auto-ending trip")
            endCurrentTrip()
        }
    }

    private fun shouldStartTrip(last: VehicleTelemetry?, current: VehicleTelemetry): Boolean {
        // Start trip when:
        // 1. Gear changes from P to D/R
        // 2. Vehicle starts moving from stopped

        if (last == null) return false

        val gearChange = last.gear == "P" && current.gear in listOf("D", "R")
        val startedMoving = last.speed < 0.5 && current.speed > 0.5

        return gearChange || (startedMoving && current.gear in listOf("D", "R"))
    }

    private fun shouldEndTrip(telemetry: VehicleTelemetry, currentTime: Long): Boolean {
        // End trip when:
        // 1. Parked for extended period
        // 2. Charging started

        val parkedTooLong = telemetry.isParked && (currentTime - lastActiveTime > tripEndDelayMs)
        val startedCharging = telemetry.isCharging

        return parkedTooLong || startedCharging
    }

    suspend fun startTrip(telemetry: VehicleTelemetry, isManual: Boolean = false): Long {
        if (tripStarted) {
            Log.w(TAG, "Trip already active")
            return currentTripId ?: -1
        }

        val trip = TripEntity(
            startTime = System.currentTimeMillis(),
            startOdometer = telemetry.odometer,
            startSoc = telemetry.soc,
            startTotalDischarge = telemetry.totalDischarge,
            isActive = true,
            isManual = isManual,
            minSoc = telemetry.soc,
            avgBatteryTemp = telemetry.batteryTempAvg,
            maxBatteryCellTemp = telemetry.batteryCellTempMax,
            minBatteryCellTemp = telemetry.batteryCellTempMin
        )

        currentTripId = tripDao.insertTrip(trip)
        tripStarted = true
        lastActiveTime = System.currentTimeMillis()

        Log.i(TAG, "Trip started: $currentTripId (manual: $isManual)")
        return currentTripId!!
    }

    suspend fun endCurrentTrip() {
        val tripId = currentTripId ?: return
        val trip = tripDao.getTripById(tripId) ?: return
        val telemetry = lastTelemetry ?: return

        val updatedTrip = trip.copy(
            endTime = System.currentTimeMillis(),
            endOdometer = telemetry.odometer,
            endSoc = telemetry.soc,
            endTotalDischarge = telemetry.totalDischarge,
            isActive = false
        )

        tripDao.updateTrip(updatedTrip)
        calculateTripStats(tripId)

        currentTripId = null
        tripStarted = false

        Log.i(TAG, "Trip ended: $tripId, distance: ${updatedTrip.distance} km, consumption: ${updatedTrip.efficiency} kWh / 100km")
    }

    private suspend fun recordDataPoint(tripId: Long, telemetry: VehicleTelemetry) {
        val dataPoint = TripDataPointEntity(
            tripId = tripId,
            timestamp = System.currentTimeMillis(),
            latitude = telemetry.locationLatitude,
            longitude = telemetry.locationLongitude,
            altitude = telemetry.locationAltitude,
            speed = telemetry.speed,
            power = telemetry.enginePower,
            soc = telemetry.soc,
            odometer = telemetry.odometer,
            batteryTemp = telemetry.batteryTempAvg,
            totalDischarge = telemetry.totalDischarge,
            gear = telemetry.gear,
            isRegenerating = telemetry.isRegenerating,
            engineSpeedFront = telemetry.engineSpeedFront,
            engineSpeedRear = telemetry.engineSpeedRear
        )

        dataPointDao.insertDataPoint(dataPoint)
    }

    private suspend fun updateTripMetrics(tripId: Long, telemetry: VehicleTelemetry) {
        val trip = tripDao.getTripById(tripId) ?: return

        val updated = trip.copy(
            maxSpeed = maxOf(trip.maxSpeed, telemetry.speed),
            maxPower = maxOf(trip.maxPower, telemetry.enginePower),
            maxRegenPower = if (telemetry.isRegenerating) {
                minOf(trip.maxRegenPower, telemetry.enginePower)
            } else {
                trip.maxRegenPower
            },
            minSoc = minOf(trip.minSoc, telemetry.soc),
            maxBatteryCellTemp = maxOf(trip.maxBatteryCellTemp, telemetry.batteryCellTempMax),
            minBatteryCellTemp = minOf(trip.minBatteryCellTemp, telemetry.batteryCellTempMin)
        )

        tripDao.updateTrip(updated)
    }

    private suspend fun calculateTripStats(tripId: Long) {
        val dataPoints = dataPointDao.getDataPointsForTripSync(tripId)
        if (dataPoints.isEmpty()) return

        val trip = tripDao.getTripById(tripId) ?: return

        // Calculate statistics
        val totalDistance = trip.distance ?: 0.0
        val totalDuration = trip.duration ?: 0
        val totalEnergyConsumed = trip.energyConsumed ?: 0.0

        // Calculate regeneration energy
        val regenPoints = dataPoints.filter { it.isRegenerating }
        val totalRegenEnergy = regenPoints.sumOf { abs(it.power) } / 3600.0 // Approximate kWh

        // Calculate averages
        val avgSpeed = if (dataPoints.isNotEmpty()) {
            dataPoints.filter { it.speed > 0 }.map { it.speed }.average()
        } else 0.0

        val avgEfficiency = trip.efficiency ?: 0.0

        // Power distribution (histogram)
        val powerRanges = mapOf(
            "regen_strong" to dataPoints.count { it.power < -30 }.toDouble(),
            "regen_medium" to dataPoints.count { it.power in -30.0..-10.0 }.toDouble(),
            "regen_light" to dataPoints.count { it.power in -10.0..0.0 }.toDouble(),
            "cruising" to dataPoints.count { it.power in 0.0..20.0 }.toDouble(),
            "acceleration" to dataPoints.count { it.power in 20.0..50.0 }.toDouble(),
            "hard_acceleration" to dataPoints.count { it.power > 50 }.toDouble()
        )

        // Speed distribution
        val speedRanges = mapOf(
            "0-30" to dataPoints.count { it.speed in 0.0..30.0 }.toDouble(),
            "30-50" to dataPoints.count { it.speed in 30.0..50.0 }.toDouble(),
            "50-80" to dataPoints.count { it.speed in 50.0..80.0 }.toDouble(),
            "80-100" to dataPoints.count { it.speed in 80.0..100.0 }.toDouble(),
            "100+" to dataPoints.count { it.speed > 100 }.toDouble()
        )

        val firstPoint = dataPoints.first()
        val lastPoint = dataPoints.last()

        val stats = TripStatsEntity(
            tripId = tripId,
            totalDistance = totalDistance,
            totalDuration = totalDuration,
            totalEnergyConsumed = totalEnergyConsumed,
            totalRegenEnergy = totalRegenEnergy,
            avgSpeed = avgSpeed,
            avgEfficiency = avgEfficiency,
            maxSpeed = trip.maxSpeed,
            maxPower = trip.maxPower,
            maxRegenPower = trip.maxRegenPower,
            powerDistribution = powerRanges,
            speedDistribution = speedRanges,
            startLatitude = firstPoint.latitude,
            startLongitude = firstPoint.longitude,
            endLatitude = lastPoint.latitude,
            endLongitude = lastPoint.longitude
        )

        statsDao.insertStats(stats)
    }

    // Public API
    fun getAllTrips(): Flow<List<TripEntity>> = tripDao.getAllTrips()

    fun getTripById(tripId: Long): Flow<TripEntity?> = tripDao.getTripByIdFlow(tripId)

    fun getDataPointsForTrip(tripId: Long): Flow<List<TripDataPointEntity>> =
        dataPointDao.getDataPointsForTrip(tripId)

    fun getStatsForTrip(tripId: Long): Flow<TripStatsEntity?> =
        statsDao.getStatsForTripFlow(tripId)

    suspend fun deleteTrip(tripId: Long) {
        tripDao.deleteTripById(tripId)
        dataPointDao.deleteDataPointsForTrip(tripId)
        statsDao.deleteStatsForTrip(tripId)
    }

    fun setAutoTripDetection(enabled: Boolean) {
        autoTripDetection = enabled
    }

    fun isAutoTripDetectionEnabled(): Boolean = autoTripDetection

    fun isCurrentlyInTrip(): Boolean = tripStarted

    fun getCurrentTripId(): Long? = currentTripId

    companion object {
        @Volatile
        private var INSTANCE: TripRepository? = null

        fun getInstance(context: Context): TripRepository {
            return INSTANCE ?: synchronized(this) {
                val instance = TripRepository(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }
    }
}