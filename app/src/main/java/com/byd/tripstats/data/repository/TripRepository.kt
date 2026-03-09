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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlin.math.abs
import kotlin.math.sqrt

// ── Event bus ─────────────────────────────────────────────────────────────────

/**
 * Every operation that touches trip state enters the repository through one of
 * these events. The processor coroutine drains the channel sequentially, so no
 * mutex, no @Volatile, no concurrency reasoning anywhere in the business logic.
 */
sealed class TripEvent {
    /** Live telemetry packet arriving from MQTT. */
    data class Telemetry(val data: VehicleTelemetry) : TripEvent()

    /** User tapped "Start trip" in the UI. */
    object ManualStart : TripEvent()

    /** User tapped "Stop trip" in the UI. */
    object ManualStop : TripEvent()

    /**
     * Sent by the watchdog every 60 s. The handler decides whether silence
     * has been long enough to close the active trip.
     */
    object WatchdogTick : TripEvent()

    /**
     * Injected as the very first event on cold start. The handler checks for
     * an open trip in the DB and either resumes or closes it.
     */
    object Recover : TripEvent()
}

// ── Trip state machine ────────────────────────────────────────────────────────

private enum class TripState { IDLE, ACTIVE }

// ── Repository ────────────────────────────────────────────────────────────────

class TripRepository private constructor(context: Context) {

    private val TAG = "TripRepository"

    private val database     = BydStatsDatabase.getDatabase(context)
    private val tripDao      = database.tripDao()
    private val dataPointDao = database.tripDataPointDao()
    private val statsDao     = database.tripStatsDao()
    private val segmentDao   = database.tripSegmentDao()

    private val prefs = context.getSharedPreferences("trip_prefs", Context.MODE_PRIVATE)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── Public state (read by UI) ─────────────────────────────────────────────

    private val _latestTelemetry = MutableStateFlow<VehicleTelemetry?>(null)
    val latestTelemetry: StateFlow<VehicleTelemetry?> = _latestTelemetry.asStateFlow()

    private val _isInTrip = MutableStateFlow(false)
    val isInTrip: StateFlow<Boolean> = _isInTrip.asStateFlow()

    private val _currentTripId = MutableStateFlow<Long?>(null)
    val currentTripId: StateFlow<Long?> = _currentTripId.asStateFlow()

    // ── Event channel ─────────────────────────────────────────────────────────

    /**
     * UNLIMITED so producers never block. At 1 Hz MQTT the queue depth will
     * never exceed a handful of items — memory is not a concern.
     * Control events (ManualStart, ManualStop, WatchdogTick, Recover) are rare,
     * and the processor drains them as fast as DB I/O allows.
     */
    private val tripEvents = Channel<TripEvent>(Channel.UNLIMITED)

    // ── Private state — ONLY touched from the processor coroutine ─────────────
    // No mutex, no @Volatile: serialisation is guaranteed by the single consumer.

    private var tripState             = TripState.IDLE
    private var cachedCurrentTrip: TripEntity? = null
    private var lastTelemetry:        VehicleTelemetry? = null
    private var lastRecordedTelemetry: VehicleTelemetry? = null
    private var lastTelemetryTime     = 0L
    private var lastWriteTime         = 0L
    private var batteryTempSum        = 0.0
    private var batteryTempSamples    = 0

    private var autoTripDetection = prefs.getBoolean(PREF_AUTO_TRIP, false)

    // ── Timing constants ──────────────────────────────────────────────────────

    private val TELEMETRY_TIMEOUT_MS = 3 * 60 * 1000L

    // Flush a segment every 30 s. At 50 km/h ≈ 417 m per endpoint pair —
    // accurate enough for route display; RDP compression smooths it at end.
    private val SEGMENT_FLUSH_MS = 30_000L

    // Data-point heartbeat raised to 10 s because GPS is owned by segments now.
    private val WRITE_INTERVAL_MS = 10_000L

    private val DRIVE_GEARS = setOf("D", "R")

    // ── Segment builder ───────────────────────────────────────────────────────

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

    // ── Initialisation ────────────────────────────────────────────────────────

    init {
        // Start the single processor coroutine — this is the only place that
        // ever reads or writes trip state.
        scope.launch {
            for (event in tripEvents) {
                try {
                    handleEvent(event)
                } catch (e: Exception) {
                    Log.e(TAG, "Unhandled error processing $event", e)
                    // Continue processing — a bad event should never kill the loop.
                }
            }
        }

        // Inject recovery as the first event so it runs before any telemetry.
        tripEvents.trySend(TripEvent.Recover)

        startWatchdog()
    }

    // ── Event dispatcher ──────────────────────────────────────────────────────

    private suspend fun handleEvent(event: TripEvent) {
        when (event) {
            is TripEvent.Telemetry   -> handleTelemetry(event.data)
            TripEvent.ManualStart    -> handleManualStart()
            TripEvent.ManualStop     -> handleManualStop()
            TripEvent.WatchdogTick   -> handleWatchdogTick()
            TripEvent.Recover        -> recoverActiveTrip()
        }
    }

    // ── Handlers ──────────────────────────────────────────────────────────────

    private suspend fun handleTelemetry(t: VehicleTelemetry) {
        val now = System.currentTimeMillis()

        // Always broadcast for UI, regardless of trip state
        _latestTelemetry.value = t

        when (tripState) {

            TripState.IDLE -> {
                lastTelemetry     = t
                lastTelemetryTime = now

                if (autoTripDetection) {
                    if (shouldAutoStart(t)) {
                        Log.i(TAG, "Auto-detect: movement → starting trip")
                        doStartTrip(t, isManual = false)
                    }
                }
            }

            TripState.ACTIVE -> {
                // Engine off → close immediately
                if (!t.isCarOn) {
                    Log.i(TAG, "Engine OFF → ending trip")
                    doEndTrip()
                    lastTelemetry     = t
                    lastTelemetryTime = now
                    return
                }

                // Inline gap check — if the processor was somehow stalled and a
                // burst of packets arrives at once, close before recording more.
                if (lastTelemetryTime > 0 && now - lastTelemetryTime > TELEMETRY_TIMEOUT_MS) {
                    Log.w(TAG, "Gap detected inside telemetry handler → ending trip")
                    doEndTrip(overrideEndTime = lastTelemetryTime)
                    lastTelemetry     = t
                    lastTelemetryTime = now
                    return
                }

                // Feed into segment accumulator on every packet
                updateSegment(t)

                // Flush segment on gear change or time boundary
                val gearChanged = lastTelemetry?.gear != null && t.gear != lastTelemetry?.gear
                val segmentAge  = now - (currentSegment?.startTime ?: now)
                if (gearChanged || segmentAge >= SEGMENT_FLUSH_MS) {
                    flushSegment(endTelemetry = t)
                }

                // Throttled data-point write
                if (shouldRecordDataPoint(t, now)) {
                    _currentTripId.value?.let { tripId ->
                        recordDataPoint(tripId, t)
                        updateTripMetrics(t)
                        lastRecordedTelemetry = t
                        lastWriteTime = now
                    }
                }

                lastTelemetry     = t
                lastTelemetryTime = now
            }
        }
    }

    private suspend fun handleManualStart() {
        if (tripState == TripState.ACTIVE) {
            Log.w(TAG, "ManualStart ignored — trip already active")
            return
        }
        val t = lastTelemetry ?: run {
            Log.w(TAG, "ManualStart ignored — no telemetry yet")
            return
        }
        Log.i(TAG, "Manual start requested")
        doStartTrip(t, isManual = true)
    }

    private suspend fun handleManualStop() {
        if (tripState == TripState.IDLE) {
            Log.w(TAG, "ManualStop ignored — no active trip")
            return
        }
        Log.i(TAG, "Manual stop requested")
        doEndTrip()
    }

    private suspend fun handleWatchdogTick() {
        if (tripState != TripState.ACTIVE) return
        val silence = System.currentTimeMillis() - lastTelemetryTime
        if (silence > TELEMETRY_TIMEOUT_MS) {
            Log.w(TAG, "Watchdog: ${silence / 1000}s silence → ending trip")
            doEndTrip(overrideEndTime = lastTelemetryTime)
        }
    }

    /**
     * Runs as the first event on cold start. If a trip row is still marked
     * active, either resume it (last data point is recent) or close it using
     * the last DB-persisted values so distance / energy are not zeroed out.
     */
    private suspend fun recoverActiveTrip() {
        val activeTrip = tripDao.getActiveTrip() ?: return
        val lastPoint  = dataPointDao.getLastDataPointForTrip(activeTrip.id)

        val now  = System.currentTimeMillis()
        val gap  = if (lastPoint != null) now - lastPoint.timestamp
                   else                   now - activeTrip.startTime
        val STALE_THRESHOLD = 10 * 60 * 1000L

        if (gap > STALE_THRESHOLD) {
            Log.w(TAG, "Stale trip ${activeTrip.id} — closing with last DB values")
            // Seed state so doEndTrip can find the trip
            _currentTripId.value = activeTrip.id
            cachedCurrentTrip    = activeTrip
            tripState            = TripState.ACTIVE

            doEndTrip(
                overrideEndTime        = lastPoint?.timestamp,
                overrideOdometer       = lastPoint?.odometer,
                overrideSoc            = lastPoint?.soc,
                overrideTotalDischarge = lastPoint?.totalDischarge
            )
        } else {
            Log.i(TAG, "Resuming active trip ${activeTrip.id}")
            _currentTripId.value = activeTrip.id
            _isInTrip.value      = true
            cachedCurrentTrip    = activeTrip
            tripState            = TripState.ACTIVE
            lastTelemetryTime    = lastPoint?.timestamp ?: activeTrip.startTime
        }
    }

    // ── Core trip operations ──────────────────────────────────────────────────

    private suspend fun doStartTrip(telemetry: VehicleTelemetry, isManual: Boolean) {
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
        tripState            = TripState.ACTIVE

        batteryTempSum     = telemetry.batteryTempAvg
        batteryTempSamples = 1

        currentSegment = openSegment(tripId, telemetry)

        Log.i(TAG, "Trip started id=$tripId (manual=$isManual)")
    }

    /**
     * Closes the active trip. Override params are only used by recoverActiveTrip
     * to supply DB-sourced values when lastTelemetry is null on cold start.
     */
    private suspend fun doEndTrip(
        overrideEndTime:        Long?   = null,
        overrideOdometer:       Double? = null,
        overrideSoc:            Double? = null,
        overrideTotalDischarge: Double? = null
    ) {
        val tripId = _currentTripId.value ?: run {
            Log.w(TAG, "doEndTrip called with no currentTripId")
            tripState = TripState.IDLE
            return
        }

        val trip      = tripDao.getTripById(tripId) ?: run {
            Log.w(TAG, "doEndTrip: trip $tripId not found in DB")
            resetTripState()
            return
        }

        val endTime      = overrideEndTime ?: System.currentTimeMillis()
        val endTelemetry = lastTelemetry

        flushSegment(endTelemetry = endTelemetry)

        val finalAvgTemp = if (batteryTempSamples > 0)
            batteryTempSum / batteryTempSamples
        else
            trip.avgBatteryTemp

        tripDao.updateTrip(
            trip.copy(
                endTime           = endTime,
                endOdometer       = overrideOdometer       ?: endTelemetry?.odometer       ?: trip.startOdometer,
                endSoc            = overrideSoc            ?: endTelemetry?.soc            ?: trip.startSoc,
                endTotalDischarge = overrideTotalDischarge ?: endTelemetry?.totalDischarge ?: trip.startTotalDischarge,
                isActive          = false,
                avgBatteryTemp    = finalAvgTemp
            )
        )

        try {
            calculateTripStats(tripId)
        } catch (e: Exception) {
            Log.e(TAG, "Stats calculation failed for trip $tripId", e)
        }

        resetTripState()
        Log.i(TAG, "Trip ended id=$tripId")
    }

    private fun resetTripState() {
        cachedCurrentTrip     = null
        lastRecordedTelemetry = null
        currentSegment        = null
        lastWriteTime         = 0L
        batteryTempSum        = 0.0
        batteryTempSamples    = 0
        tripState             = TripState.IDLE
        _currentTripId.value  = null
        _isInTrip.value       = false
    }

    // ── Watchdog ──────────────────────────────────────────────────────────────

    private fun startWatchdog() {
        scope.launch {
            while (true) {
                delay(60_000)
                tripEvents.trySend(TripEvent.WatchdogTick)
            }
        }
    }

    // ── Auto-start detection ──────────────────────────────────────────────────

    private fun shouldAutoStart(t: VehicleTelemetry): Boolean {
        if (!t.isCarOn) return false
        if (t.gear !in DRIVE_GEARS) return false
        return t.speed > 5 ||
               t.enginePower > 5 ||
               (lastTelemetry != null && t.odometer > lastTelemetry!!.odometer)
    }

    // ── Data-point throttle ───────────────────────────────────────────────────

    private fun shouldRecordDataPoint(t: VehicleTelemetry, now: Long): Boolean {
        val last = lastRecordedTelemetry ?: return true
        if (now - lastWriteTime >= WRITE_INTERVAL_MS)            return true
        if (abs(t.speed        - last.speed)        >= 5)        return true
        if (abs(t.soc          - last.soc)          >= 0.5)      return true
        if (abs(t.enginePower  - last.enginePower)  >= 10)       return true
        if (t.gear != last.gear)                                  return true
        return false
    }

    // ── Segment helpers ───────────────────────────────────────────────────────

    private fun hasValidGps(lat: Double?, lon: Double?) =
        lat != null && lat != 0.0 && lon != null && lon != 0.0

    private fun openSegment(tripId: Long, t: VehicleTelemetry): SegmentBuilder {
        val now = System.currentTimeMillis()
        val lat = t.locationLatitude.takeIf  { it != 0.0 }
        val lon = t.locationLongitude.takeIf { it != 0.0 }
        return SegmentBuilder(
            tripId              = tripId,
            startTime           = now,
            startLat            = lat,
            startLon            = lon,
            startOdometer       = t.odometer,
            startTotalDischarge = t.totalDischarge,
            endTime             = now,
            endLat              = lat,
            endLon              = lon
        )
    }

    private fun updateSegment(t: VehicleTelemetry) {
        val seg = currentSegment ?: return
        seg.speedSum += t.speed
        seg.powerSum += t.enginePower
        seg.samples++
        seg.endTime = System.currentTimeMillis()
        val lat = t.locationLatitude.takeIf  { it != 0.0 }
        val lon = t.locationLongitude.takeIf { it != 0.0 }
        if (lat != null) { seg.endLat = lat; seg.endLon = lon }
    }

    private suspend fun flushSegment(endTelemetry: VehicleTelemetry?) {
        val seg    = currentSegment ?: return
        val tripId = _currentTripId.value ?: return

        if (seg.samples == 0) {
            currentSegment = endTelemetry?.let { openSegment(tripId, it) }
            return
        }

        segmentDao.insertSegment(
            TripSegmentEntity(
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
                                 maxOf(0.0, endTelemetry.odometer       - seg.startOdometer)
                             else 0.0,
                energyUsed = if (endTelemetry != null)
                                 maxOf(0.0, endTelemetry.totalDischarge - seg.startTotalDischarge)
                             else 0.0
            )
        )

        currentSegment = endTelemetry?.let { openSegment(tripId, it) }
    }

    // ── Data point recording ──────────────────────────────────────────────────

    private suspend fun recordDataPoint(tripId: Long, t: VehicleTelemetry) {
        dataPointDao.insertDataPoint(
            TripDataPointEntity(
                tripId                 = tripId,
                timestamp              = System.currentTimeMillis(),
                latitude               = t.locationLatitude,
                longitude              = t.locationLongitude,
                altitude               = t.locationAltitude,
                speed                  = t.speed,
                power                  = t.enginePower,
                soc                    = t.soc,
                odometer               = t.odometer,
                batteryTemp            = t.batteryTempAvg,
                totalDischarge         = t.totalDischarge,
                gear                   = t.gear,
                isRegenerating         = t.isRegenerating,
                engineSpeedFront       = t.engineSpeedFront,
                engineSpeedRear        = t.engineSpeedRear,
                electricDrivingRangeKm = t.electricDrivingRangeKm,
                tyrePressureLF         = t.tyrePressureLF,
                tyrePressureRF         = t.tyrePressureRF,
                tyrePressureLR         = t.tyrePressureLR,
                tyrePressureRR         = t.tyrePressureRR,
                soh                    = t.soh,
                batteryTotalVoltage    = t.batteryTotalVoltage,
                battery12vVoltage      = t.battery12vVoltage,
                batteryCellVoltageMax  = t.batteryCellVoltageMax,
                batteryCellVoltageMin  = t.batteryCellVoltageMin,
                rawJson                = t.toRawJson()
            )
        )
    }

    // ── Trip metrics ──────────────────────────────────────────────────────────

    private suspend fun updateTripMetrics(t: VehicleTelemetry) {
        val trip = cachedCurrentTrip ?: return

        batteryTempSum += t.batteryTempAvg
        batteryTempSamples++

        val updated = trip.copy(
            maxSpeed           = maxOf(trip.maxSpeed, t.speed),
            maxPower           = maxOf(trip.maxPower, t.enginePower),
            maxRegenPower      = if (t.isRegenerating)
                                     minOf(trip.maxRegenPower, t.enginePower)
                                 else trip.maxRegenPower,
            minSoc             = minOf(trip.minSoc, t.soc),
            avgBatteryTemp     = batteryTempSum / batteryTempSamples,
            maxBatteryCellTemp = maxOf(trip.maxBatteryCellTemp, t.batteryCellTempMax),
            minBatteryCellTemp = minOf(trip.minBatteryCellTemp, t.batteryCellTempMin)
        )

        cachedCurrentTrip = updated
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
            compressRoute(points.subList(0, index + 1), epsilon).dropLast(1) +
            compressRoute(points.subList(index, points.size), epsilon)
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

    private fun speedBin(speed: Double) = when {
        speed <  20 -> "0-20"
        speed <  40 -> "20-40"
        speed <  60 -> "40-60"
        speed <  80 -> "60-80"
        speed < 100 -> "80-100"
        else        -> "100+"
    }

    private suspend fun calculateTripStats(tripId: Long) {
        val dataPoints = dataPointDao.getDataPointsForTripSync(tripId)
        if (dataPoints.isEmpty()) return

        val trip = tripDao.getTripById(tripId) ?: return

        val totalDistance       = trip.distance       ?: 0.0
        val totalDuration       = trip.duration       ?: 0L
        val totalEnergyConsumed = trip.energyConsumed ?: 0.0

        val totalRegenEnergy = if (dataPoints.size < 2) 0.0 else {
            dataPoints.zipWithNext { a, b ->
                if (a.isRegenerating) abs(a.power) * (b.timestamp - a.timestamp) / 3_600_000.0
                else 0.0
            }.sum()
        }

        val avgSpeed = dataPoints.filter { it.speed > 0 }
            .takeIf { it.isNotEmpty() }?.map { it.speed }?.average() ?: 0.0

        val energyBySpeed   = mutableMapOf<String, Double>()
        val distanceBySpeed = mutableMapOf<String, Double>()
        var regenEnergy      = 0.0
        var mechanicalEnergy = 0.0

        for (i in 1 until dataPoints.size) {
            val a  = dataPoints[i - 1]
            val b  = dataPoints[i]
            val dt = (b.timestamp - a.timestamp) / 3_600_000.0

            if (a.power > 0) mechanicalEnergy += a.power * dt
            if (a.power < 0) regenEnergy      += abs(a.power) * dt

            val bin = speedBin((a.speed + b.speed) / 2)
            energyBySpeed[bin]   = energyBySpeed.getOrDefault(bin, 0.0)   + (b.totalDischarge - a.totalDischarge)
            distanceBySpeed[bin] = distanceBySpeed.getOrDefault(bin, 0.0) + (b.odometer       - a.odometer)
        }

        val efficiencyBySpeed = mutableMapOf<String, Double>()
        energyBySpeed.forEach { (bin, e) ->
            val d = distanceBySpeed[bin] ?: return@forEach
            if (d > 0) efficiencyBySpeed[bin] = (e / d) * 100
        }

        // Route from segment endpoints — fallback to data points for old trips
        val segments = segmentDao.getSegmentsForTripSync(tripId)
        val routePoints: List<LatLng> = if (segments.isNotEmpty()) {
            segments.flatMap { seg ->
                listOfNotNull(
                    if (hasValidGps(seg.startLat, seg.startLon)) LatLng(seg.startLat!!, seg.startLon!!) else null,
                    if (hasValidGps(seg.endLat,   seg.endLon))   LatLng(seg.endLat!!,   seg.endLon!!)   else null
                )
            }.distinct()
        } else {
            dataPoints.filter { it.latitude != 0.0 && it.longitude != 0.0 }
                .map { LatLng(it.latitude, it.longitude) }
        }
        val compressedRoute = compressRoute(routePoints, 0.0001)

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
                avgEfficiency            = trip.efficiency ?: 0.0,
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

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Called by the MQTT service on every incoming packet.
     * Non-suspend: just enqueues — never blocks the MQTT thread.
     */
    fun processTelemetry(telemetry: VehicleTelemetry) {
        _latestTelemetry.value = telemetry   // immediate UI update, no wait for queue
        tripEvents.trySend(TripEvent.Telemetry(telemetry))
    }

    /** Called by the UI "Start trip" button. Non-suspend. */
    fun requestManualStart() {
        tripEvents.trySend(TripEvent.ManualStart)
    }

    /** Called by the UI "Stop trip" button. Non-suspend. */
    fun requestManualStop() {
        tripEvents.trySend(TripEvent.ManualStop)
    }

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
        segmentDao.deleteSegmentsForTrip(tripId)
    }

    fun setAutoTripDetection(enabled: Boolean) {
        autoTripDetection = enabled
        prefs.edit().putBoolean(PREF_AUTO_TRIP, enabled).apply()
    }

    fun isAutoTripDetectionEnabled(): Boolean = autoTripDetection

    /**
     * Reduces storage for old trips by removing redundant data points.
     *
     * For every completed trip that started more than [olderThanMonths] months ago,
     * only one data point per [keepEverySeconds] seconds is retained. The first and
     * last points of each trip are always kept so the route endpoints are preserved.
     *
     * This is a lossy but safe operation: trip-level stats (distance, energy,
     * efficiency) come from TripEntity and TripStatsEntity and are NOT affected.
     * Only the raw point density used by charts and heatmaps is reduced.
     *
     * Called by DatabaseMaintenanceWorker on a monthly schedule.
     */
    suspend fun thinOldDataPoints(olderThanMonths: Int = 3, keepEverySeconds: Int = 30) {
        val cutoffMs = System.currentTimeMillis() -
            olderThanMonths.toLong() * 30L * 24L * 3_600_000L

        val oldTrips = tripDao.getCompletedTripsBefore(cutoffMs)
        if (oldTrips.isEmpty()) {
            Log.i(TAG, "thinOldDataPoints: no trips older than $olderThanMonths months")
            return
        }

        val keepIntervalMs = keepEverySeconds * 1000L
        var totalDeleted = 0

        oldTrips.forEach { trip ->
            val points = dataPointDao.getDataPointsForTripSync(trip.id)
            if (points.size < 3) return@forEach     // nothing meaningful to thin

            val toDelete = mutableListOf<Long>()
            var lastKeptTimestamp = points.first().timestamp

            // Always keep index 0 (trip start) and last index (trip end).
            // For everything in between, keep only if far enough from last kept.
            for (i in 1 until points.lastIndex) {
                val pt = points[i]
                if (pt.timestamp - lastKeptTimestamp >= keepIntervalMs) {
                    lastKeptTimestamp = pt.timestamp
                } else {
                    toDelete.add(pt.id)
                }
            }

            if (toDelete.isNotEmpty()) {
                dataPointDao.deleteDataPointsByIds(toDelete)
                totalDeleted += toDelete.size
            }
        }

        Log.i(TAG, "thinOldDataPoints: removed $totalDeleted points across ${oldTrips.size} trips")
    }

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