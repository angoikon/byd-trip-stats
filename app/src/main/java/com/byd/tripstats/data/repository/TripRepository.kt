package com.byd.tripstats.data.repository

import android.content.Context
import android.util.Log
import com.byd.tripstats.data.local.BydStatsDatabase
import com.byd.tripstats.data.local.entity.LatLng
import com.byd.tripstats.data.local.entity.TripDataPointEntity
import com.byd.tripstats.data.local.entity.TripEntity
import com.byd.tripstats.data.local.entity.TripSegmentEntity
import com.byd.tripstats.data.local.entity.TripStatsEntity
import com.byd.tripstats.data.model.VehicleTelemetry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs
import kotlin.math.sqrt

class TripRepository private constructor(context: Context) {

    private val TAG = "TripRepository"

    private val database     = BydStatsDatabase.getDatabase(context)
    private val tripDao      = database.tripDao()
    private val dataPointDao = database.tripDataPointDao()
    private val statsDao     = database.tripStatsDao()
    private val segmentDao   = database.tripSegmentDao()

    // Persistent settings — survives reboots / process death
    private val prefs = context.getSharedPreferences("trip_prefs", Context.MODE_PRIVATE)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── Public state ──────────────────────────────────────────────────────────

    private val _latestTelemetry = MutableStateFlow<VehicleTelemetry?>(null)
    val latestTelemetry: StateFlow<VehicleTelemetry?> = _latestTelemetry.asStateFlow()

    private val _isInTrip = MutableStateFlow(false)
    val isInTrip: StateFlow<Boolean> = _isInTrip.asStateFlow()

    private val _currentTripId = MutableStateFlow<Long?>(null)
    val currentTripId: StateFlow<Long?> = _currentTripId.asStateFlow()

    // ── In-memory trip state ──────────────────────────────────────────────────

    // Mutex guards tripStarted so the watchdog coroutine and
    // processTelemetry can never both enter endCurrentTrip for the same trip.
    // The flag is flipped to false atomically as the very first thing inside
    // endCurrentTrip; any concurrent caller will see false and return early.
    private val tripMutex = Mutex()

    private var cachedCurrentTrip: TripEntity? = null
    private var lastTelemetry: VehicleTelemetry? = null
    private var lastRecordedTelemetry: VehicleTelemetry? = null

    // Must only be written inside tripMutex. @Volatile lets the watchdog read
    // it cheaply as a fast-path bail-out before acquiring the lock.
    @Volatile private var tripStarted = false

    private var lastTelemetryTime = 0L
    private var lastWriteTime     = 0L

    // Running average for avgBatteryTemp — reset at trip start and end.
    // Accumulated on every data-point write.
    private var batteryTempSum     = 0.0
    private var batteryTempSamples = 0

    // ── Configuration ─────────────────────────────────────────────────────────

    private var autoTripDetection = prefs.getBoolean(PREF_AUTO_TRIP, false)

    // ── Timing constants ──────────────────────────────────────────────────────

    private val TELEMETRY_TIMEOUT_MS = 3 * 60 * 1000L

    // Segments flush every 30 s. At 50 km/h that is ~417 m per segment
    // endpoint pair — accurate enough for route display. Route compression
    // (RDP) runs at trip end so the final map path is still smooth.
    private val SEGMENT_FLUSH_MS = 30_000L

    // Data-point write interval is raised because GPS movement no longer
    // triggers individual writes — spatial data lives in segments instead.
    // This cuts peak write rate from ~1 Hz down to ~0.1 Hz on a steady cruise.
    private val WRITE_INTERVAL_MS = 10_000L

    private val DRIVE_GEARS = setOf("D", "R")

    // ── Segment builder ───────────────────────────────────────────────────────

    /**
     * Accumulates telemetry between flushes. Only start/end GPS coordinates
     * and aggregated averages are persisted, so DB writes for GPS drop from
     * 1 per second to 1 per 30 s — roughly a 97 % reduction on a motorway leg.
     *
     * Segments flush on:
     *   • SEGMENT_FLUSH_MS elapsed
     *   • gear change (driving style boundary)
     *   • trip end
     */
    private data class SegmentBuilder(
        val tripId:              Long,
        val startTime:           Long,
        val startLat:            Double?,
        val startLon:            Double?,
        val startOdometer:       Double,
        val startTotalDischarge: Double,
        var endTime:             Long,
        var endLat:              Double?,
        var endLon:              Double?,
        var speedSum:            Double = 0.0,
        var powerSum:            Double = 0.0,
        var samples:             Int    = 0
    )

    private var currentSegment: SegmentBuilder? = null

    // ── Init ──────────────────────────────────────────────────────────────────

    init {
        scope.launch { recoverActiveTrip() }
        startTripWatchdog()
    }

    // ── Trip recovery ─────────────────────────────────────────────────────────

    /**
     * Called once on cold start. If a trip was left open by a previous process,
     * either resume it (recent last point) or close it cleanly.
     *
     * The stale-trip path now passes the last DB values for
     * odometer / soc / totalDischarge to endCurrentTrip so distance and energy
     * are not zeroed out
     */
    private suspend fun recoverActiveTrip() {
        val activeTrip = tripDao.getActiveTrip() ?: return
        val lastPoint  = dataPointDao.getLastDataPointForTrip(activeTrip.id)

        val now = System.currentTimeMillis()
        val gap = if (lastPoint != null) now - lastPoint.timestamp
                  else                   now - activeTrip.startTime

        val STALE_THRESHOLD = 10 * 60 * 1000L

        if (gap > STALE_THRESHOLD) {
            Log.w(TAG, "Closing stale trip ${activeTrip.id}")
            _currentTripId.value = activeTrip.id
            tripMutex.withLock { tripStarted = true }   // so endCurrentTrip guard passes

            endCurrentTrip(
                overrideEndTime         = lastPoint?.timestamp,
                overrideOdometer        = lastPoint?.odometer,
                overrideSoc             = lastPoint?.soc,
                overrideTotalDischarge  = lastPoint?.totalDischarge
            )
        } else {
            Log.i(TAG, "Resuming active trip ${activeTrip.id}")
            tripMutex.withLock { tripStarted = true }
            cachedCurrentTrip    = activeTrip
            _currentTripId.value = activeTrip.id
            _isInTrip.value      = true
            lastTelemetryTime    = lastPoint?.timestamp ?: activeTrip.startTime
        }
    }

    // ── Main telemetry entry point ────────────────────────────────────────────

    suspend fun processTelemetry(telemetry: VehicleTelemetry) {
        val now = System.currentTimeMillis()
        _latestTelemetry.value = telemetry

        // Inline gap detection — backdate end time to the last real packet
        if (tripStarted && lastTelemetryTime > 0) {
            if (now - lastTelemetryTime > TELEMETRY_TIMEOUT_MS) {
                Log.w(TAG, "Telemetry gap detected → ending trip")
                endCurrentTrip(overrideEndTime = lastTelemetryTime)
            }
        }
        lastTelemetryTime = now

        // Engine off
        if (tripStarted && !telemetry.isCarOn) {
            Log.i(TAG, "Engine OFF → ending trip")
            endCurrentTrip()
            lastTelemetry = telemetry
            return
        }

        if (autoTripDetection) handleAutoTripDetection(telemetry)

        if (tripStarted) {
            _currentTripId.value?.let { _ ->

                // Feed every packet into the segment accumulator
                updateSegment(telemetry)

                // Flush segment on gear change or time interval
                val gearChanged = lastTelemetry?.gear != null &&
                                  telemetry.gear != lastTelemetry?.gear
                val segmentAge  = now - (currentSegment?.startTime ?: now)
                if (gearChanged || segmentAge >= SEGMENT_FLUSH_MS) {
                    flushSegment(telemetry)
                }

                // Write telemetry data point (GPS excluded — handled by segments)
                if (shouldRecordDataPoint(telemetry, now)) {
                    _currentTripId.value?.let { tripId ->
                        recordDataPoint(tripId, telemetry)
                        updateTripMetrics(telemetry)
                        lastRecordedTelemetry = telemetry
                        lastWriteTime = now
                    }
                }
            }
        }

        lastTelemetry = telemetry
    }

    // ── Data-point throttle ───────────────────────────────────────────────────

    /**
     * GPS movement is no longer a write trigger — segments own spatial data.
     * Only telemetry state changes (speed jump, SOC drop, gear shift, power
     * spike, or the periodic heartbeat) cause a data-point write.
     */
    private fun shouldRecordDataPoint(telemetry: VehicleTelemetry, now: Long): Boolean {
        val last = lastRecordedTelemetry ?: return true
        if (now - lastWriteTime >= WRITE_INTERVAL_MS)               return true
        if (abs(telemetry.speed - last.speed) >= 5)                 return true
        if (abs(telemetry.soc - last.soc) >= 0.5)                   return true
        if (abs(telemetry.enginePower - last.enginePower) >= 10)    return true
        if (telemetry.gear != last.gear)                            return true
        return false
    }

    // ── Auto trip detection ───────────────────────────────────────────────────

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
        startTrip(telemetry, isManual = false)
    }

    // ── Trip lifecycle ────────────────────────────────────────────────────────

    suspend fun startTrip(telemetry: VehicleTelemetry, isManual: Boolean = false): Long {
        // Atomically claim the start — only the first caller proceeds
        val alreadyStarted = tripMutex.withLock {
            if (tripStarted) true else { tripStarted = true; false }
        }
        if (alreadyStarted) {
            Log.w(TAG, "Trip already active")
            return _currentTripId.value ?: -1
        }

        Log.i(TAG, "*** Starting new trip *** (manual=$isManual)")

        val trip = TripEntity(
            startTime           = System.currentTimeMillis(),
            startOdometer       = telemetry.odometer,
            startSoc            = telemetry.soc,
            startTotalDischarge = telemetry.totalDischarge,
            isActive            = true,
            isManual            = isManual,
            minSoc              = telemetry.soc,
            avgBatteryTemp      = telemetry.batteryTempAvg,
            maxBatteryCellTemp  = telemetry.batteryCellTempMax,
            minBatteryCellTemp  = telemetry.batteryCellTempMin
        )

        val tripId = tripDao.insertTrip(trip)

        _currentTripId.value = tripId
        _isInTrip.value      = true
        cachedCurrentTrip    = trip.copy(id = tripId)

        // Seed running battery-temp average with the first reading
        batteryTempSum     = telemetry.batteryTempAvg
        batteryTempSamples = 1

        // Open the first segment immediately
        currentSegment = openSegment(tripId, telemetry)

        Log.i(TAG, "Trip started id=$tripId")
        return tripId
    }

    /**
     * Ends the current trip.
     *
     * The override parameters are used only by recoverActiveTrip() so that a
     * stale trip closed on cold-start uses the last *DB-persisted* values
     * rather than null telemetry.
     *
     * Concurrency: tripStarted is flipped to false inside
     * tripMutex as the very first action. Any second caller (watchdog racing
     * processTelemetry, or double manual tap) will read false and return before
     * doing any work, making double-close impossible.
     */
    suspend fun endCurrentTrip(
        overrideEndTime:        Long?   = null,
        overrideOdometer:       Double? = null,
        overrideSoc:            Double? = null,
        overrideTotalDischarge: Double? = null
    ) {
        // Atomically claim the end — only the first caller proceeds
        val claimed = tripMutex.withLock {
            if (!tripStarted) return   // already ended or never started
            tripStarted = false
            true
        }
        @Suppress("UNUSED_VARIABLE") val _consumed = claimed

        val tripId = _currentTripId.value ?: run {
            Log.w(TAG, "endCurrentTrip: no currentTripId after claiming end")
            return
        }

        val trip      = tripDao.getTripById(tripId) ?: return
        val telemetry = lastTelemetry
        val endTime   = overrideEndTime ?: System.currentTimeMillis()

        // Flush the last open segment before closing
        flushSegment(endTelemetry = telemetry)

        val finalAvgTemp = if (batteryTempSamples > 0)
            batteryTempSum / batteryTempSamples
        else
            trip.avgBatteryTemp

        val updated = trip.copy(
            endTime             = endTime,
            endOdometer         = overrideOdometer       ?: telemetry?.odometer        ?: trip.startOdometer,
            endSoc              = overrideSoc            ?: telemetry?.soc             ?: trip.startSoc,
            endTotalDischarge   = overrideTotalDischarge ?: telemetry?.totalDischarge  ?: trip.startTotalDischarge,
            isActive            = false,
            avgBatteryTemp      = finalAvgTemp
        )

        tripDao.updateTrip(updated)

        try {
            calculateTripStats(tripId)
        } catch (e: Exception) {
            Log.e(TAG, "Stats calculation failed", e)
        }

        // Reset all in-memory state
        cachedCurrentTrip     = null
        lastRecordedTelemetry = null
        currentSegment        = null
        lastWriteTime         = 0L
        batteryTempSum        = 0.0
        batteryTempSamples    = 0
        _currentTripId.value  = null
        _isInTrip.value       = false

        Log.i(TAG, "Trip ended id=$tripId")
    }

    // ── Watchdog ──────────────────────────────────────────────────────────────

    private fun startTripWatchdog() {
        scope.launch {
            while (true) {
                delay(60_000)
                if (!tripStarted) continue   // fast-path read; mutex inside endCurrentTrip handles races
                val elapsed = System.currentTimeMillis() - lastTelemetryTime
                if (elapsed > TELEMETRY_TIMEOUT_MS) {
                    Log.w(TAG, "Watchdog: ending trip after ${elapsed / 1000}s silence")
                    endCurrentTrip(overrideEndTime = lastTelemetryTime)
                }
            }
        }
    }

    // ── Segment helpers ───────────────────────────────────────────────────────

    /** Returns true only if both coordinates look like a real GPS fix. */
    private fun hasValidGps(lat: Double?, lon: Double?) =
        lat != null && lat != 0.0 && lon != null && lon != 0.0

    private fun openSegment(tripId: Long, telemetry: VehicleTelemetry): SegmentBuilder {
        val now = System.currentTimeMillis()
        val lat = telemetry.locationLatitude?.takeIf  { it != 0.0 }
        val lon = telemetry.locationLongitude?.takeIf { it != 0.0 }
        return SegmentBuilder(
            tripId              = tripId,
            startTime           = now,
            startLat            = lat,
            startLon            = lon,
            startOdometer       = telemetry.odometer,
            startTotalDischarge = telemetry.totalDischarge,
            endTime             = now,
            endLat              = lat,
            endLon              = lon
        )
    }

    /** Accumulates every incoming packet into the open segment. */
    private fun updateSegment(telemetry: VehicleTelemetry) {
        val seg = currentSegment ?: return
        seg.speedSum += telemetry.speed
        seg.powerSum += telemetry.enginePower
        seg.samples++
        seg.endTime = System.currentTimeMillis()
        // Update end GPS only when we have a real fix so 0,0 values never
        // overwrite a previously good coordinate
        val lat = telemetry.locationLatitude?.takeIf  { it != 0.0 }
        val lon = telemetry.locationLongitude?.takeIf { it != 0.0 }
        if (lat != null) { seg.endLat = lat; seg.endLon = lon }
    }

    /**
     * Writes the accumulated segment to the DB and opens a new one.
     * If the segment has zero samples (e.g. flush called right after open)
     * it is discarded and the start pointer is simply advanced.
     *
     * [endTelemetry] null only on cold-start recovery where the repo has no
     * live telemetry — distance/energy default to 0 in that case, which is
     * acceptable since the trip is already being closed.
     */
    private suspend fun flushSegment(endTelemetry: VehicleTelemetry?) {
        val seg    = currentSegment ?: return
        val tripId = _currentTripId.value ?: return

        if (seg.samples == 0) {
            currentSegment = endTelemetry?.let { openSegment(tripId, it) }
            return
        }

        val entity = TripSegmentEntity(
            tripId     = seg.tripId,
            startTime  = seg.startTime,
            endTime    = seg.endTime,
            startLat   = seg.startLat,
            startLon   = seg.startLon,
            endLat     = seg.endLat,
            endLon     = seg.endLon,
            avgSpeed   = seg.speedSum  / seg.samples,
            avgPower   = seg.powerSum  / seg.samples,
            distance   = if (endTelemetry != null)
                             maxOf(0.0, endTelemetry.odometer        - seg.startOdometer)
                         else 0.0,
            energyUsed = if (endTelemetry != null)
                             maxOf(0.0, endTelemetry.totalDischarge  - seg.startTotalDischarge)
                         else 0.0
        )

        segmentDao.insertSegment(entity)
        currentSegment = endTelemetry?.let { openSegment(tripId, it) }
    }

    // ── Data point recording ──────────────────────────────────────────────────

    private suspend fun recordDataPoint(tripId: Long, telemetry: VehicleTelemetry) {
        val dataPoint = TripDataPointEntity(
            tripId                 = tripId,
            timestamp              = System.currentTimeMillis(),
            // 0.0 stored when GPS has no fix — filtered out during stats
            latitude               = telemetry.locationLatitude  ?: 0.0,
            longitude              = telemetry.locationLongitude ?: 0.0,
            altitude               = telemetry.locationAltitude,
            speed                  = telemetry.speed,
            power                  = telemetry.enginePower,
            soc                    = telemetry.soc,
            odometer               = telemetry.odometer,
            batteryTemp            = telemetry.batteryTempAvg,
            totalDischarge         = telemetry.totalDischarge,
            gear                   = telemetry.gear,
            isRegenerating         = telemetry.isRegenerating,
            engineSpeedFront       = telemetry.engineSpeedFront,
            engineSpeedRear        = telemetry.engineSpeedRear,
            electricDrivingRangeKm = telemetry.electricDrivingRangeKm,
            tyrePressureLF         = telemetry.tyrePressureLF,
            tyrePressureRF         = telemetry.tyrePressureRF,
            tyrePressureLR         = telemetry.tyrePressureLR,
            tyrePressureRR         = telemetry.tyrePressureRR,
            soh                    = telemetry.soh,
            batteryTotalVoltage    = telemetry.batteryTotalVoltage,
            battery12vVoltage      = telemetry.battery12vVoltage,
            batteryCellVoltageMax  = telemetry.batteryCellVoltageMax,
            batteryCellVoltageMin  = telemetry.batteryCellVoltageMin,
            rawJson                = telemetry.toRawJson()
        )
        dataPointDao.insertDataPoint(dataPoint)
    }

    // ── Trip metrics (in-memory cache, written per data-point flush) ──────────

    private suspend fun updateTripMetrics(telemetry: VehicleTelemetry) {
        val trip = cachedCurrentTrip ?: return

        // Accumulate running battery-temp average (BUG 3 FIX)
        batteryTempSum += telemetry.batteryTempAvg
        batteryTempSamples++

        val updated = trip.copy(
            maxSpeed           = maxOf(trip.maxSpeed, telemetry.speed),
            maxPower           = maxOf(trip.maxPower, telemetry.enginePower),
            maxRegenPower      = if (telemetry.isRegenerating)
                                     minOf(trip.maxRegenPower, telemetry.enginePower)
                                 else trip.maxRegenPower,
            minSoc             = minOf(trip.minSoc, telemetry.soc),
            avgBatteryTemp     = batteryTempSum / batteryTempSamples,
            maxBatteryCellTemp = maxOf(trip.maxBatteryCellTemp, telemetry.batteryCellTempMax),
            minBatteryCellTemp = minOf(trip.minBatteryCellTemp, telemetry.batteryCellTempMin)
        )

        cachedCurrentTrip = updated   // keep cache in sync before the DB write
        tripDao.updateTrip(updated)
    }

    // ── Stats calculation ─────────────────────────────────────────────────────

    private fun compressRoute(points: List<LatLng>, epsilon: Double): List<LatLng> {
        if (points.size < 3) return points
        var maxDist = 0.0
        var index   = 0
        val start   = points.first()
        val end     = points.last()
        for (i in 1 until points.lastIndex) {
            val d = perpendicularDistance(points[i], start, end)
            if (d > maxDist) { index = i; maxDist = d }
        }
        return if (maxDist > epsilon) {
            val left  = compressRoute(points.subList(0, index + 1), epsilon)
            val right = compressRoute(points.subList(index, points.size), epsilon)
            left.dropLast(1) + right
        } else {
            listOf(start, end)
        }
    }

    private fun perpendicularDistance(p: LatLng, a: LatLng, b: LatLng): Double {
        val dx    = b.lon - a.lon
        val dy    = b.lat - a.lat
        val t     = ((p.lon - a.lon) * dx + (p.lat - a.lat) * dy) / (dx * dx + dy * dy)
        val diffX = p.lon - (a.lon + t * dx)
        val diffY = p.lat - (a.lat + t * dy)
        return sqrt(diffX * diffX + diffY * diffY)
    }

    private suspend fun calculateTripStats(tripId: Long) {
        val dataPoints = dataPointDao.getDataPointsForTripSync(tripId)
        if (dataPoints.isEmpty()) return

        val trip = tripDao.getTripById(tripId) ?: return

        val totalDistance       = trip.distance       ?: 0.0
        val totalDuration       = trip.duration       ?: 0
        val totalEnergyConsumed = trip.energyConsumed ?: 0.0

        // Time-weighted regen energy
        val totalRegenEnergy = if (dataPoints.size < 2) 0.0 else {
            dataPoints.zipWithNext { a, b ->
                if (a.isRegenerating) abs(a.power) * (b.timestamp - a.timestamp) / 3_600_000.0
                else 0.0
            }.sum()
        }

        val avgSpeed = dataPoints.filter { it.speed > 0 }
            .takeIf { it.isNotEmpty() }?.map { it.speed }?.average() ?: 0.0

        val avgEfficiency = trip.efficiency ?: 0.0

        val energyBySpeed   = mutableMapOf<String, Double>()
        val distanceBySpeed = mutableMapOf<String, Double>()
        var regenEnergy      = 0.0
        var mechanicalEnergy = 0.0

        for (i in 1 until dataPoints.size) {
            val a  = dataPoints[i - 1]
            val b  = dataPoints[i]
            val distance = b.odometer      - a.odometer
            val energy   = b.totalDischarge - a.totalDischarge
            val dt       = (b.timestamp     - a.timestamp) / 3_600_000.0

            if (a.power > 0) mechanicalEnergy += a.power * dt
            if (a.power < 0) regenEnergy      += abs(a.power) * dt

            val midSpeed = (a.speed + b.speed) / 2
            val speedBin = speedBin(midSpeed)

            energyBySpeed[speedBin]   = energyBySpeed.getOrDefault(speedBin, 0.0)   + energy
            distanceBySpeed[speedBin] = distanceBySpeed.getOrDefault(speedBin, 0.0) + distance
        }

        val efficiencyBySpeed = mutableMapOf<String, Double>()
        energyBySpeed.forEach { (bin, e) ->
            val d = distanceBySpeed[bin] ?: return@forEach
            if (d > 0) efficiencyBySpeed[bin] = (e / d) * 100
        }

        // ── Route — built from segment endpoints; fallback to data points ─────
        // 0.0 coordinates (no GPS fix) are filtered out here.
        val segments = segmentDao.getSegmentsForTripSync(tripId)
        val routePoints: List<LatLng> = if (segments.isNotEmpty()) {
            segments.flatMap { seg ->
                listOfNotNull(
                    if (hasValidGps(seg.startLat, seg.startLon)) LatLng(seg.startLat!!, seg.startLon!!) else null,
                    if (hasValidGps(seg.endLat,   seg.endLon))   LatLng(seg.endLat!!,   seg.endLon!!)   else null
                )
            }.distinct()
        } else {
            // Backward-compat: old trips stored GPS in data points.
            // latitude is non-nullable Double so we check for 0.0 instead of null.
            dataPoints.filter { it.latitude != 0.0 && it.longitude != 0.0 }
                .map { LatLng(it.latitude, it.longitude) }
        }
        val compressedRoute = compressRoute(routePoints, 0.0001)

        // Start / end coordinates — prefer segment endpoints
        val startLat = segments.firstOrNull()?.startLat?.takeIf { it != 0.0 }
            ?: dataPoints.firstOrNull { it.latitude != 0.0 }?.latitude  ?: 0.0
        val startLon = segments.firstOrNull()?.startLon?.takeIf { it != 0.0 }
            ?: dataPoints.firstOrNull { it.latitude != 0.0 }?.longitude ?: 0.0
        val endLat   = segments.lastOrNull()?.endLat?.takeIf { it != 0.0 }
            ?: dataPoints.lastOrNull  { it.latitude != 0.0 }?.latitude  ?: 0.0
        val endLon   = segments.lastOrNull()?.endLon?.takeIf { it != 0.0 }
            ?: dataPoints.lastOrNull  { it.latitude != 0.0 }?.longitude ?: 0.0

        // ── Speed × power heatmap ─────────────────────────────────────────────
        val matrixDistribution = mutableMapOf<String, Int>()
        dataPoints.forEach { dp ->
            val powerBin = when {
                dp.power < -30 -> "regen-high"
                dp.power <  -5 -> "regen"
                dp.power <  10 -> "idle"
                dp.power <  30 -> "low"
                dp.power <  60 -> "medium"
                else           -> "high"
            }
            val key = "${speedBin(dp.speed)}|$powerBin"
            matrixDistribution[key] = matrixDistribution.getOrDefault(key, 0) + 1
        }

        // ── Power distribution histogram ──────────────────────────────────────
        val powerRanges = mapOf(
            "regen_strong"      to dataPoints.count { it.power <  -30.0 }.toDouble(),
            "regen_medium"      to dataPoints.count { it.power >= -30.0 && it.power <  -10.0 }.toDouble(),
            "regen_light"       to dataPoints.count { it.power >= -10.0 && it.power <    0.0 }.toDouble(),
            "cruising"          to dataPoints.count { it.power >=   0.0 && it.power <   20.0 }.toDouble(),
            "acceleration"      to dataPoints.count { it.power >=  20.0 && it.power <   50.0 }.toDouble(),
            "hard_acceleration" to dataPoints.count { it.power >=  50.0 }.toDouble()
        )

        // ── Speed distribution histogram ──────────────────────────────────────
        val speedRanges = mapOf(
            "0-30"    to dataPoints.count { it.speed >=   0.0 && it.speed <  30.0 }.toDouble(),
            "30-70"   to dataPoints.count { it.speed >=  30.0 && it.speed <  70.0 }.toDouble(),
            "70-100"  to dataPoints.count { it.speed >=  70.0 && it.speed < 100.0 }.toDouble(),
            "100-130" to dataPoints.count { it.speed >= 100.0 && it.speed < 130.0 }.toDouble(),
            "130+"    to dataPoints.count { it.speed >= 130.0 }.toDouble()
        )

        statsDao.insertStats(
            TripStatsEntity(
                tripId                   = tripId,
                totalDistance            = totalDistance,
                totalDuration            = totalDuration,
                totalEnergyConsumed      = totalEnergyConsumed,
                totalRegenEnergy         = totalRegenEnergy,
                avgSpeed                 = avgSpeed,
                avgEfficiency            = avgEfficiency,
                maxSpeed                 = trip.maxSpeed,
                maxPower                 = trip.maxPower,
                maxRegenPower            = trip.maxRegenPower,
                powerDistribution        = powerRanges,
                speedDistribution        = speedRanges,
                startLatitude            = startLat,
                startLongitude           = startLon,
                endLatitude              = endLat,
                endLongitude             = endLon,
                matrixDistribution       = matrixDistribution,
                energyConsumptionBySpeed = efficiencyBySpeed,
                regenEnergy              = regenEnergy,
                mechanicalEnergy         = mechanicalEnergy,
                compressedRoute          = compressedRoute
            )
        )
    }

    /** Shared speed-bin label — single source of truth for all histograms. */
    private fun speedBin(speed: Double) = when {
        speed <  20 -> "0-20"
        speed <  40 -> "20-40"
        speed <  60 -> "40-60"
        speed <  80 -> "60-80"
        speed < 100 -> "80-100"
        else        -> "100+"
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun getAllTrips(): Flow<List<TripEntity>> = tripDao.getAllTrips()

    fun getTripById(tripId: Long): Flow<TripEntity?> = tripDao.getTripByIdFlow(tripId)

    fun getDataPointsForTrip(tripId: Long): Flow<List<TripDataPointEntity>> =
        dataPointDao.getDataPointsForTrip(tripId)

    fun getStatsForTrip(tripId: Long): Flow<TripStatsEntity?> =
        statsDao.getStatsForTripFlow(tripId)

    fun getAllTripStats(): Flow<List<TripStatsEntity>> = statsDao.getAllTripStats()

    fun getSegmentsForTrip(tripId: Long) = segmentDao.getSegmentsForTrip(tripId)

    suspend fun deleteTrips(tripIds: List<Long>) = tripIds.forEach { deleteTrip(it) }

    suspend fun deleteTrip(tripId: Long) {
        tripDao.deleteTripById(tripId)
        dataPointDao.deleteDataPointsForTrip(tripId)
        statsDao.deleteStatsForTrip(tripId)
        segmentDao.deleteSegmentsForTrip(tripId)   // BUG 5 FIX
    }

    fun setAutoTripDetection(enabled: Boolean) {
        autoTripDetection = enabled
        prefs.edit().putBoolean(PREF_AUTO_TRIP, enabled).apply()
    }

    fun isAutoTripDetectionEnabled(): Boolean = autoTripDetection

    // ── Singleton ─────────────────────────────────────────────────────────────

    companion object {
        private const val PREF_AUTO_TRIP = "auto_trip_detection"

        @Volatile private var INSTANCE: TripRepository? = null

        fun getInstance(context: Context): TripRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: TripRepository(context.applicationContext).also { INSTANCE = it }
            }
    }
}