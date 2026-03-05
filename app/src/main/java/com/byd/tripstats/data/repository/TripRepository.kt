package com.byd.tripstats.data.repository

import android.content.Context
import android.location.Location
import android.util.Log
import com.byd.tripstats.data.local.BydStatsDatabase
import com.byd.tripstats.data.local.entity.TripDataPointEntity
import com.byd.tripstats.data.local.entity.TripEntity
import com.byd.tripstats.data.local.entity.TripStatsEntity
import com.byd.tripstats.data.model.VehicleTelemetry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
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

    // In-memory cache of the current trip row — avoids a DB read on every telemetry point
    private var cachedCurrentTrip: TripEntity? = null
    private var lastTelemetry: VehicleTelemetry? = null
    private var lastRecordedTelemetry: VehicleTelemetry? = null

    private var tripStarted = false
    private var lastTelemetryTime = 0L
    private var lastWriteTime = 0L

    // Configuration — persisted; default false so first-run is manual mode
    private var autoTripDetection = prefs.getBoolean(PREF_AUTO_TRIP, false)

    private val TELEMETRY_TIMEOUT_MS = 3 * 60 * 1000L
    private val WRITE_INTERVAL_MS = 5_000L

    private val DRIVE_GEARS = setOf("D", "R")

    init {
        scope.launch {
            recoverActiveTrip()
        }

        startTripWatchdog()
    }

    /**
     * Recover active trip after process restart
     */
    private suspend fun recoverActiveTrip() {
        val activeTrip = tripDao.getActiveTrip() ?: return
        val lastPoint = dataPointDao.getLastDataPointForTrip(activeTrip.id)
        
        val now = System.currentTimeMillis()
        val gap = if (lastPoint != null) {
            now - lastPoint.timestamp
        } else {
            now - activeTrip.startTime
        }
        
        val STALE_TRIP_THRESHOLD = 10 * 60 * 1000L

        if (gap > STALE_TRIP_THRESHOLD) {
            Log.w(TAG, "Closing stale trip ${activeTrip.id}")
            
            // CRITICAL FIX: We must assign the ID to the StateFlow so endCurrentTrip() 
            // knows which trip to close, since it reads from _currentTripId.value
            _currentTripId.value = activeTrip.id 
            endCurrentTrip(lastPoint?.timestamp)
            
        } else {
            Log.i(TAG, "Resuming active trip ${activeTrip.id}")
            
            tripStarted = true
            cachedCurrentTrip = activeTrip
            _currentTripId.value = activeTrip.id
            _isInTrip.value = true
            
            lastTelemetryTime = lastPoint?.timestamp ?: activeTrip.startTime
        }
    }

    /**
     * MAIN TELEMETRY ENTRY
     */
    suspend fun processTelemetry(telemetry: VehicleTelemetry) {

        val now = System.currentTimeMillis()

        _latestTelemetry.value = telemetry

        // Detect telemetry silence
        if (tripStarted && lastTelemetryTime > 0) {

            val gap = now - lastTelemetryTime

            if (gap > TELEMETRY_TIMEOUT_MS) {

                Log.w(TAG, "Telemetry gap detected → ending trip")

                endCurrentTrip()
            }
        }

        lastTelemetryTime = now

        // Engine turned OFF
        if (tripStarted && !telemetry.isCarOn) {

            Log.i(TAG, "Engine OFF → ending trip")

            endCurrentTrip()
            lastTelemetry = telemetry
            return
        }
        // AUTO TRIP DETECTION
        if (autoTripDetection) {
            handleAutoTripDetection(telemetry)
        }

        // RECORD DATA POINTS
        if (tripStarted) {

            _currentTripId.value?.let { tripId ->

                if (shouldRecordDataPoint(telemetry, now)) {

                    recordDataPoint(tripId, telemetry)
                    updateTripMetrics(tripId, telemetry)

                    lastRecordedTelemetry = telemetry
                    lastWriteTime = now
                }
            }
        }

        lastTelemetry = telemetry
    }

    /**
     * Reduce database writes dramatically
     */
    private fun shouldRecordDataPoint(
        telemetry: VehicleTelemetry,
        now: Long
    ): Boolean {

        val last = lastRecordedTelemetry ?: return true

        if (now - lastWriteTime > WRITE_INTERVAL_MS) return true

        if (abs(telemetry.speed - last.speed) >= 2) return true

        if (abs(telemetry.soc - last.soc) >= 0.5) return true

        if (abs(telemetry.odometer - last.odometer) >= 0.02) return true

        if (telemetry.gear != last.gear) return true

        if (locationChanged(telemetry, last)) return true

        return false
    }

    private fun locationChanged(
        a: VehicleTelemetry,
        b: VehicleTelemetry
    ): Boolean {

        if (a.locationLatitude == null || b.locationLatitude == null) return false

        val results = FloatArray(1)

        Location.distanceBetween(
            a.locationLatitude,
            a.locationLongitude,
            b.locationLatitude,
            b.locationLongitude,
            results
        )

        return results[0] > 15
    }

    /**
     * AUTO TRIP DETECTION
     */
    private suspend fun handleAutoTripDetection(telemetry: VehicleTelemetry) {

        val last = lastTelemetry

        if (tripStarted) return
        if (!telemetry.isCarOn) return
        if (telemetry.gear !in DRIVE_GEARS) return

        val movementDetected =
            telemetry.speed > 5 ||
            telemetry.enginePower > 5 ||
            (last != null && telemetry.odometer > last.odometer)

        if (!movementDetected) {
            Log.i(TAG, "Gear engaged but no movement yet")
            return
        }
    
        Log.i(TAG, "Movement detected → starting trip")

        startTrip(telemetry, false)
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

        tripStarted = true
        _currentTripId.value = tripId
        // Broadcast state change so UI updates
        _isInTrip.value = true

        cachedCurrentTrip = trip.copy(id = tripId)

        Log.i(TAG, "Trip started with ID: $tripId, broadcasting state change")

        return tripId
    }

    suspend fun endCurrentTrip(overrideEndTime: Long? = null) {
                val tripId = _currentTripId.value ?: run {
            Log.w(TAG, "endCurrentTrip called but no active trip")
            return
        }
        val trip = tripDao.getTripById(tripId) ?: return
        // Handle null telemetry gracefully
        val telemetry = lastTelemetry

        val endTime = overrideEndTime ?: System.currentTimeMillis()


        val updated = trip.copy(
            // Use the override time if provided, otherwise use current time
            endTime = endTime, 
            endOdometer = telemetry?.odometer ?: trip.startOdometer,
            endSoc = telemetry?.soc ?: trip.startSoc,
            endTotalDischarge = telemetry?.totalDischarge ?: trip.startTotalDischarge,
            isActive = false
        )

        tripDao.updateTrip(updated)

        // Calculate stats (wrap in try-catch to prevent failures)
        try {
            calculateTripStats(tripId)
        } catch (e: Exception) {
            Log.e(TAG, "Stats calculation failed", e)
        }
        // Still update state to prevent stuck trips
        cachedCurrentTrip = null
        lastRecordedTelemetry = null
        lastWriteTime = 0L
        tripStarted = false
        _currentTripId.value = null
        _isInTrip.value = false

        Log.i(TAG, "Trip ended $tripId")
    }

    private fun startTripWatchdog() {
    scope.launch {
        while (true) {
            delay(60_000)
            if (!tripStarted) continue

            val elapsed = System.currentTimeMillis() - lastTelemetryTime

            if (elapsed > TELEMETRY_TIMEOUT_MS) {
                Log.w(TAG, "Watchdog ending trip")
                // Backdate the end time to the last time we actually received data
                endCurrentTrip(overrideEndTime = lastTelemetryTime)
            }
        }
    }
}

    private suspend fun recordDataPoint(
        tripId: Long,
        telemetry: VehicleTelemetry
    ) {

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

    private suspend fun updateTripMetrics(
        tripId: Long,
        telemetry: VehicleTelemetry
    ) {
        // Use in-memory cache to avoid a DB read on every ~1 Hz telemetry point.
        // The cache is seeded at trip start and kept in sync here on every write.
        val trip = cachedCurrentTrip ?: return

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
        // Calculate regeneration energy across ALL consecutive points
        val totalRegenEnergy = if (dataPoints.size < 2) 0.0 else {
            dataPoints.zipWithNext { a, b ->
                // Only calculate energy if 'a' was actually regenerating
                if (a.isRegenerating) {
                    val dtHours = (b.timestamp - a.timestamp) / 3_600_000.0
                    abs(a.power) * dtHours
                } else {
                    0.0
                }
            }.sum()
        }

        // Calculate averages
        val avgSpeed = if (dataPoints.isNotEmpty()) {
            dataPoints.filter { it.speed > 0 }.map { it.speed }.average()
        } else 0.0

        val avgEfficiency = trip.efficiency ?: 0.0

        // Power distribution (histogram)
        // Half-open ranges [low, high] so boundary values are counted exactly once.
        val powerRanges = mapOf(
            "regen_strong"     to dataPoints.count { it.power < -30.0 }.toDouble(),
            "regen_medium"     to dataPoints.count { it.power >= -30.0 && it.power < -10.0 }.toDouble(),
            "regen_light"      to dataPoints.count { it.power >= -10.0 && it.power < 0.0 }.toDouble(),
            "cruising"         to dataPoints.count { it.power >= 0.0 && it.power < 20.0 }.toDouble(),
            "acceleration"     to dataPoints.count { it.power >= 20.0 && it.power < 50.0 }.toDouble(),
            "hard_acceleration" to dataPoints.count { it.power >= 50.0 }.toDouble()
        )

        // Speed distribution (histogram)
        // Half-open ranges [low, high] so boundary values are counted exactly once.
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

                INSTANCE ?: TripRepository(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
    }
}