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

    // Persistent settings — survives reboots/process death
    private val prefs = context.getSharedPreferences("trip_prefs", Context.MODE_PRIVATE)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Latest telemetry for UI
    private val _latestTelemetry = MutableStateFlow<VehicleTelemetry?>(null)
    val latestTelemetry: StateFlow<VehicleTelemetry?> = _latestTelemetry.asStateFlow()

    // Add public StateFlows for trip state
    private val _isInTrip = MutableStateFlow(false)
    val isInTrip: StateFlow<Boolean> = _isInTrip.asStateFlow()

    private val _currentTripId = MutableStateFlow<Long?>(null)
    val currentTripId: StateFlow<Long?> = _currentTripId.asStateFlow()

    private var lastTelemetry: VehicleTelemetry? = null
    private var tripStarted = false
    private var firstTelemetryReceived = false

    // In-memory cache of the current trip row — avoids a DB read on every telemetry point
    private var cachedCurrentTrip: TripEntity? = null

    // Configuration — persisted; default false so first-run is manual mode
    private var autoTripDetection = prefs.getBoolean(PREF_AUTO_TRIP, false)
    private var lastTelemetryTime = 0L
    
    // Stale trip timeout: If last data point is older than this, trip is stale
    private val STALE_TRIP_TIMEOUT_MS = 10 * 60 * 1000L  // 10 minutes

    init {
        scope.launch {
            val activeTrip = tripDao.getActiveTrip()
            if (activeTrip != null) {
                Log.w(TAG, "Found active trip ${activeTrip.id} from previous session")
                
                // Store the ID but don't set tripStarted yet
                _currentTripId.value = activeTrip.id
                tripStarted = false
                firstTelemetryReceived = false
                
                Log.i(TAG, "Will verify trip ${activeTrip.id} status on first telemetry")
            }
        }
    }

    suspend fun processTelemetry(telemetry: VehicleTelemetry) {
        // Broadcast latest telemetry to UI
        _latestTelemetry.value = telemetry

        val currentTime = System.currentTimeMillis()

        // HANDLE STALE TRIPS FROM PREVIOUS SESSION
        if (!firstTelemetryReceived) {
            firstTelemetryReceived = true
            
            if (_currentTripId.value != null) {
                handleStaleTrip(telemetry)
            }
        }

        // NORMAL TRIP ENDING: Car turned off
        if (tripStarted && !telemetry.isCarOn) {
            Log.i(TAG, "Car turned OFF - ending trip")
            endCurrentTrip()
            lastTelemetry = telemetry
            lastTelemetryTime = currentTime
            return
        }

        lastTelemetryTime = currentTime

        // AUTO TRIP DETECTION
        if (autoTripDetection) {
            handleAutoTripDetection(telemetry)
        }
        
        // RECORD DATA POINTS
        if (tripStarted) {
            _currentTripId.value?.let { tripId ->
                recordDataPoint(tripId, telemetry)
                updateTripMetrics(tripId, telemetry)
            }
        }
        
        lastTelemetry = telemetry
    }

    private suspend fun handleStaleTrip(telemetry: VehicleTelemetry) {
        val tripId = _currentTripId.value ?: return
        val trip = tripDao.getTripById(tripId) ?: return
        
        Log.i(TAG, "=== Handling stale trip $tripId ===")
        
        // Get last data point to check age
        val dataPoints = dataPointDao.getDataPointsForTripSync(tripId)
        
        if (dataPoints.isEmpty()) {
            Log.w(TAG, "Stale trip has no data points - deleting it")
            tripDao.deleteTripById(tripId)
            _currentTripId.value = null
            _isInTrip.value = false
            tripStarted = false
            return
        }
        
        val lastPoint = dataPoints.last()
        val timeSinceLastPoint = System.currentTimeMillis() - lastPoint.timestamp
        val isStale = timeSinceLastPoint > STALE_TRIP_TIMEOUT_MS
        
        Log.i(TAG, "Last data point was ${timeSinceLastPoint / 1000}s ago")
        Log.i(TAG, "Current state: car_on=${telemetry.carOn}, gear=${telemetry.gear}")
        
        // DECISION LOGIC FOR STALE TRIPS
        when {
            // Car is OFF → definitely end the trip
            !telemetry.isCarOn -> {
                Log.i(TAG, "Car is OFF → ending stale trip")
                endStaleTrip(trip, lastPoint)
            }
            
            // Car is ON but in Park and trip is old → end it
            telemetry.gear == "P" && isStale -> {
                Log.i(TAG, "Car in Park and trip is stale → ending it")
                endStaleTrip(trip, lastPoint)
            }
            
            // Car is ON in D/R but trip is very old → end old, start new
            telemetry.gear in listOf("D", "R") && isStale -> {
                Log.i(TAG, "Car in D/R but trip is stale → ending old trip, will start new one")
                endStaleTrip(trip, lastPoint)
                // New trip will be started by auto-detection on next telemetry
            }
            
            // Car is ON in D/R and trip is recent → RESUME IT
            telemetry.gear in listOf("D", "R") && !isStale -> {
                Log.i(TAG, "Car in D/R and trip is recent → RESUMING trip")
                tripStarted = true
                _isInTrip.value = true  // Update state
                // Trip continues!
            }
            
            // Car is ON in Park and trip is recent → wait for gear change
            telemetry.gear == "P" && !isStale -> {
                Log.i(TAG, "Car in Park but trip is recent → ending it, will restart on D")
                endStaleTrip(trip, lastPoint)
            }
            
            // Default: end the trip to be safe
            else -> {
                Log.i(TAG, "Unknown state → ending stale trip")
                endStaleTrip(trip, lastPoint)
            }
        }
    }

    private suspend fun handleAutoTripDetection(telemetry: VehicleTelemetry) {
        val last = lastTelemetry

        // Don't start if already in trip
        if (tripStarted) return
        
        // Don't start if car is off
        if (!telemetry.isCarOn) return
        
        // Don't start if in Park or Neutral
        if (telemetry.gear !in listOf("D", "R")) return
        
        // SMART DETECTION LOGIC
        val shouldStart = when {
            // No previous telemetry (app just opened while driving)
            last == null -> {
                Log.i(TAG, "First telemetry: car ON, gear ${telemetry.gear} → START TRIP")
                true  // SCENARIO 2: Start immediately!
            }
            
            // Gear changed from P to D/R
            last.gear == "P" && telemetry.gear in listOf("D", "R") -> {
                Log.i(TAG, "Gear changed P → ${telemetry.gear} → START TRIP")
                true  // SCENARIO 1: Normal start
            }
            
            // Car just turned on while in D/R (edge case)
            !last.isCarOn && telemetry.isCarOn && telemetry.gear in listOf("D", "R") -> {
                Log.i(TAG, "Car turned ON in gear ${telemetry.gear} → START TRIP")
                true
            }
            
            // Already in D/R, don't spam trip starts
            else -> false
        }
        
        if (shouldStart) {
            startTrip(telemetry, isManual = false)
        }
    }
    
    suspend fun startTrip(telemetry: VehicleTelemetry, isManual: Boolean = false): Long {
        if (tripStarted) {
            Log.w(TAG, "Trip already active")
            return _currentTripId.value ?: -1
        }

        Log.i(TAG, "*** Starting new trip *** (manual: $isManual)")

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

        val tripId = tripDao.insertTrip(trip)
        _currentTripId.value = tripId
        tripStarted = true
        cachedCurrentTrip = trip.copy(id = tripId)  // seed cache immediately

        // Broadcast state change so UI updates
        _isInTrip.value = true

        Log.i(TAG, "Trip started with ID: $tripId, broadcasting state change")
        
        return tripId
    }

    suspend fun endCurrentTrip() {
        val tripId = _currentTripId.value ?: run {
            Log.w(TAG, "endCurrentTrip called but no active trip")
            return
        }
        
        val trip = tripDao.getTripById(tripId) ?: run {
            Log.e(TAG, "Trip $tripId not found in database!")
            // Clean up state anyway
            _currentTripId.value = null
            tripStarted = false
            _isInTrip.value = false
            return
        }

        Log.i(TAG, "*** Ending trip $tripId ***")
        
        // Handle null telemetry gracefully
        val telemetry = lastTelemetry
        
        if (telemetry == null) {
            Log.w(TAG, "No current telemetry available - using last data point")
            
            // Fall back to last data point from database
            try {
                val dataPoints = dataPointDao.getDataPointsForTripSync(tripId)
                
                if (dataPoints.isEmpty()) {
                    Log.e(TAG, "Cannot end trip - no data points recorded!")
                    
                    // Mark trip as ended anyway with current time
                    val updatedTrip = trip.copy(
                        endTime = System.currentTimeMillis(),
                        endOdometer = trip.startOdometer,  // No change
                        endSoc = trip.startSoc,
                        endTotalDischarge = trip.startTotalDischarge,
                        isActive = false
                    )
                    
                    tripDao.updateTrip(updatedTrip)
                    
                    // Update state
                    _currentTripId.value = null
                    tripStarted = false
                    _isInTrip.value = false
                    
                    Log.i(TAG, "Trip ended with no data points (emergency end)")
                    return
                }
                
                // Use last data point
                val lastPoint = dataPoints.last()
                endStaleTrip(trip, lastPoint)
                return
                
            } catch (e: Exception) {
                Log.e(TAG, "Error getting last data point", e)
                
                // Emergency end - just mark as inactive
                try {
                    val updatedTrip = trip.copy(
                        endTime = System.currentTimeMillis(),
                        isActive = false
                    )
                    tripDao.updateTrip(updatedTrip)
                } catch (dbError: Exception) {
                    Log.e(TAG, "Failed to update trip in database", dbError)
                }
                
                // Always update state to prevent stuck trips
                _currentTripId.value = null
                tripStarted = false
                _isInTrip.value = false
                return
            }
        }

        // Normal end with current telemetry
        try {
            val updatedTrip = trip.copy(
                endTime = System.currentTimeMillis(),
                endOdometer = telemetry.odometer,
                endSoc = telemetry.soc,
                endTotalDischarge = telemetry.totalDischarge,
                isActive = false
            )

            tripDao.updateTrip(updatedTrip)
            
            // Calculate stats (wrap in try-catch to prevent failures)
            try {
                calculateTripStats(tripId)
            } catch (statsError: Exception) {
                Log.e(TAG, "Error calculating trip stats (non-fatal)", statsError)
            }

            // Update state AFTER successful database update
            cachedCurrentTrip = null
            _currentTripId.value = null
            tripStarted = false
            _isInTrip.value = false

            Log.i(TAG, "Trip ended successfully: distance ${updatedTrip.distance} km, efficiency ${updatedTrip.efficiency} kWh/100km")
            
        } catch (e: Exception) {
            Log.e(TAG, "CRITICAL ERROR ending trip", e)
            e.printStackTrace()
            
            // Still update state to prevent stuck trips
            cachedCurrentTrip = null
            _currentTripId.value = null
            tripStarted = false
            _isInTrip.value = false

            Log.e(TAG, "Emergency state reset after error")
        }
    }

    private suspend fun endStaleTrip(trip: TripEntity, lastPoint: TripDataPointEntity) {
        Log.i(TAG, "*** Ending stale trip ${trip.id} ***")
        
        // Use last data point as the end state
        val updatedTrip = trip.copy(
            endTime = lastPoint.timestamp,  // Use timestamp of last data point!
            endOdometer = lastPoint.odometer,
            endSoc = lastPoint.soc,
            endTotalDischarge = lastPoint.totalDischarge,
            isActive = false
        )

        tripDao.updateTrip(updatedTrip)
        calculateTripStats(trip.id)

        cachedCurrentTrip = null
        _currentTripId.value = null
        tripStarted = false

        // Broadcast state change
        _isInTrip.value = false

        Log.i(TAG, "Stale trip ended with last point timestamp: ${lastPoint.timestamp}")
    }

    private suspend fun recordDataPoint(tripId: Long, telemetry: VehicleTelemetry) {
        val dataPoint = TripDataPointEntity(
            tripId = tripId,
            timestamp = System.currentTimeMillis(),
            electricDrivingRangeKm = telemetry.electricDrivingRangeKm,
            tyrePressureLF = telemetry.tyrePressureLF,
            tyrePressureRF = telemetry.tyrePressureRF,
            tyrePressureLR = telemetry.tyrePressureLR,
            tyrePressureRR = telemetry.tyrePressureRR,
            soh = telemetry.soh,
            batteryTotalVoltage = telemetry.batteryTotalVoltage,
            battery12vVoltage = telemetry.battery12vVoltage,
            batteryCellVoltageMax = telemetry.batteryCellVoltageMax,
            batteryCellVoltageMin = telemetry.batteryCellVoltageMin,
            rawJson = telemetry.toRawJson(),
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
        // Use in-memory cache to avoid a DB read on every ~1 Hz telemetry point.
        // The cache is seeded at trip start and kept in sync here on every write.
        val trip = cachedCurrentTrip ?: tripDao.getTripById(tripId) ?: return

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

        cachedCurrentTrip = updated   // keep cache in sync before the DB write
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
        // Time-weighted regen energy: kWh = Σ(kW × Δt_seconds / 3600)
        // Zip consecutive regen points and use the time gap between them.
        // This is correct regardless of MQTT sampling rate.
        val totalRegenEnergy = if (regenPoints.size < 2) 0.0 else {
            regenPoints.zipWithNext { a, b ->
                val dtHours = (b.timestamp - a.timestamp) / 3_600_000.0
                abs(a.power) * dtHours
            }.sum()
        }

        // Calculate averages
        val avgSpeed = if (dataPoints.isNotEmpty()) {
            dataPoints.filter { it.speed > 0 }.map { it.speed }.average()
        } else 0.0

        val avgEfficiency = trip.efficiency ?: 0.0

        // Power distribution (histogram)
        // Half-open ranges [low, high) so boundary values are counted exactly once.
        val powerRanges = mapOf(
            "regen_strong"     to dataPoints.count { it.power < -30.0 }.toDouble(),
            "regen_medium"     to dataPoints.count { it.power >= -30.0 && it.power < -10.0 }.toDouble(),
            "regen_light"      to dataPoints.count { it.power >= -10.0 && it.power < 0.0 }.toDouble(),
            "cruising"         to dataPoints.count { it.power >= 0.0 && it.power < 20.0 }.toDouble(),
            "acceleration"     to dataPoints.count { it.power >= 20.0 && it.power < 50.0 }.toDouble(),
            "hard_acceleration" to dataPoints.count { it.power >= 50.0 }.toDouble()
        )

        // Speed distribution (histogram)
        // Half-open ranges [low, high) so boundary values are counted exactly once.
        val speedRanges = mapOf(
            "0-30"   to dataPoints.count { it.speed >= 0.0 && it.speed < 30.0 }.toDouble(),
            "30-70"  to dataPoints.count { it.speed >= 30.0 && it.speed < 70.0 }.toDouble(),
            "70-100" to dataPoints.count { it.speed >= 70.0 && it.speed < 100.0 }.toDouble(),
            "100-130" to dataPoints.count { it.speed >= 100.0 && it.speed < 130.0 }.toDouble(),
            "130+"   to dataPoints.count { it.speed >= 130.0 }.toDouble()
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
            isManual = true,
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

    fun getAllTripStats(): Flow<List<TripStatsEntity>> = statsDao.getAllTripStats()

    suspend fun deleteTrips(tripIds: List<Long>) {
        tripIds.forEach { deleteTrip(it) }
    }

    suspend fun deleteTrip(tripId: Long) {
        tripDao.deleteTripById(tripId)
        dataPointDao.deleteDataPointsForTrip(tripId)
        statsDao.deleteStatsForTrip(tripId)
    }

    fun setAutoTripDetection(enabled: Boolean) {
        autoTripDetection = enabled
        prefs.edit().putBoolean(PREF_AUTO_TRIP, enabled).apply()
    }

    fun isAutoTripDetectionEnabled(): Boolean = autoTripDetection

    companion object {
        private const val PREF_AUTO_TRIP = "auto_trip_detection"

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