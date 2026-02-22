package com.byd.tripstats.data.repository

import android.content.Context
import android.util.Log
import com.byd.tripstats.data.local.BydStatsDatabase
import com.byd.tripstats.data.local.entity.TripDataPointEntity
import com.byd.tripstats.data.local.entity.TripEntity
import com.byd.tripstats.data.local.entity.TripStatsEntity
import com.byd.tripstats.data.model.VehicleTelemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
    private val briefStopDelayMs = 15 * 1000L          // 15 seconds in P to end trip
    private val engineOffIntervalMs = 11 * 1000L       // 11 seconds between messages = engine OFF
    private var lastTelemetryTime = 0L                // When we last received ANY telemetry
    private var lastActiveTime = 0L                   // When car was last actively driving

    init {
        // Check for active trip on initialization
        scope.launch {
            val activeTrip = tripDao.getActiveTrip()
            if (activeTrip != null) {
                currentTripId = activeTrip.id
                tripStarted = true

                // Restore lastActiveTime and lastTelemetryTime from last data point
                val lastPoint = dataPointDao.getDataPointsForTripSync(activeTrip.id).lastOrNull()
                if (lastPoint != null) {
                    lastActiveTime = lastPoint.timestamp
                    lastTelemetryTime = lastPoint.timestamp
                    Log.i(TAG, "Resumed active trip: ${activeTrip.id}, lastActive: ${java.text.SimpleDateFormat("HH:mm:ss").format(lastActiveTime)}")
                } else {
                    // No data points yet, use trip start time
                    lastActiveTime = activeTrip.startTime
                    lastTelemetryTime = activeTrip.startTime
                    Log.i(TAG, "Resumed active trip: ${activeTrip.id} (no data points yet)")
                }

                // Check if engine was turned off by examining message interval
                // If last message was more than 11 seconds ago, engine is OFF
                val timeSinceLastMessage = System.currentTimeMillis() - lastTelemetryTime
                if (timeSinceLastMessage > engineOffIntervalMs) {
                    Log.i(TAG, "Engine was turned off (${timeSinceLastMessage / 1000}s since last message) - ending trip")
                    endCurrentTrip()
                }
            }
        }
    }

    suspend fun processTelemetry(telemetry: VehicleTelemetry) {
        // Broadcast latest telemetry to UI
        _latestTelemetry.value = telemetry

        val currentTime = System.currentTimeMillis()

        // Check message interval to detect engine state
        // Car ON: MQTT every 1 second
        // Car OFF: MQTT every 10 minutes
        if (lastTelemetryTime > 0) {
            val messageInterval = currentTime - lastTelemetryTime
            
            // Only check engine-off interval when parked (not while actively driving)
            if (tripStarted && telemetry.isParked && messageInterval > engineOffIntervalMs) {
                Log.i(TAG, "Engine turned OFF detected (${messageInterval / 1000}s interval) - ending trip")
                endCurrentTrip()
                // Don't return - still process this telemetry for UI display
            }
        }

        lastTelemetryTime = currentTime  // Update telemetry timestamp

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
        
        // Update last active time if driving
        if (telemetry.isDriving) {
            lastActiveTime = currentTime
        }
        
        // Check for brief stop timeout (parked with engine ON for 15 seconds)
        // EXCEPTION: Don't end trip if charging (DC charging can be 40+ min with engine ON)
        if (tripStarted && telemetry.isParked && !telemetry.isDriving && !telemetry.isCharging) {
            if (currentTime - lastActiveTime > briefStopDelayMs) {
                Log.i(TAG, "Brief stop timeout (15s in P, not charging) - ending trip")
                endCurrentTrip()
            }
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
        // 1. Parked for 15 seconds
        // 2. Charging started
        
        val parkedTooLong = telemetry.isParked && (currentTime - lastActiveTime > briefStopDelayMs)
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

        Log.i(TAG, "Trip ended: $tripId, distance: ${updatedTrip.distance} km, efficiency: ${updatedTrip.efficiency} kWh/100km")
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
            "30-70" to dataPoints.count { it.speed in 30.0..70.0 }.toDouble(),
            "70-100" to dataPoints.count { it.speed in 70.0..100.0 }.toDouble(),
            "100-130" to dataPoints.count { it.speed in 100.0..130.0 }.toDouble(),
            "130+" to dataPoints.count { it.speed > 130 }.toDouble()
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

    // Merge trips functionality
    suspend fun mergeTrips(tripIds: List<Long>): Long? {
        if (tripIds.size < 2) return null

        val trips = tripIds.mapNotNull { tripDao.getTripById(it) }
        if (trips.size != tripIds.size) return null

        // Sort by start time
        val sortedTrips = trips.sortedBy { it.startTime }
        val firstTrip = sortedTrips.first()
        val lastTrip = sortedTrips.last()

        // Get all data points from all trips and sort by timestamp
        val allDataPoints = mutableListOf<TripDataPointEntity>()
        for (tripId in tripIds) {
            val points = dataPointDao.getDataPointsForTripSync(tripId)
            allDataPoints.addAll(points)
        }
        allDataPoints.sortBy { it.timestamp }

        // Calculate actual trip times from data points (excluding gaps)
        // Use the earliest and latest data point timestamps for start/end
        val actualStartTime = allDataPoints.firstOrNull()?.timestamp ?: firstTrip.startTime
        val actualEndTime = allDataPoints.lastOrNull()?.timestamp ?: lastTrip.endTime

        // Create merged trip
        val mergedTrip = TripEntity(
            startTime = actualStartTime,
            endTime = actualEndTime,
            startOdometer = firstTrip.startOdometer,
            endOdometer = lastTrip.endOdometer,
            startSoc = firstTrip.startSoc,
            endSoc = lastTrip.endSoc,
            startTotalDischarge = firstTrip.startTotalDischarge,
            endTotalDischarge = lastTrip.endTotalDischarge,
            isActive = false,
            isManual = true, // Merged trips are considered manual
            maxSpeed = sortedTrips.maxOf { it.maxSpeed },
            maxPower = sortedTrips.maxOf { it.maxPower },
            maxRegenPower = sortedTrips.minOf { it.maxRegenPower },
            avgBatteryTemp = sortedTrips.map { it.avgBatteryTemp }.average(),
            minSoc = sortedTrips.minOf { it.minSoc },
            maxBatteryCellTemp = sortedTrips.maxOf { it.maxBatteryCellTemp },
            minBatteryCellTemp = sortedTrips.minOf { it.minBatteryCellTemp }
        )
        
        val mergedTripId = tripDao.insertTrip(mergedTrip)
        
        // Copy all data points to merged trip (already sorted)
        val updatedPoints = allDataPoints.map { it.copy(id = 0, tripId = mergedTripId) }
        dataPointDao.insertDataPoints(updatedPoints)
        
        // Calculate stats for merged trip
        calculateTripStats(mergedTripId)
        
        // Delete original trips
        for (tripId in tripIds) {
            deleteTrip(tripId)
        }
        
        Log.i(TAG, "Merged ${tripIds.size} trips into trip $mergedTripId")
        return mergedTripId
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
