package com.byd.tripstats.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.byd.tripstats.data.local.BydStatsDatabase
import com.byd.tripstats.data.local.entity.ChargingDataPointEntity
import com.byd.tripstats.data.local.entity.ChargingSessionEntity
import com.byd.tripstats.data.local.entity.TripDataPointEntity
import com.byd.tripstats.data.local.entity.TripEntity
import com.byd.tripstats.data.local.entity.TripStatsEntity
import com.byd.tripstats.data.model.VehicleTelemetry
import com.byd.tripstats.data.preferences.PreferencesManager
import com.byd.tripstats.data.repository.ChargingRepository
import com.byd.tripstats.data.repository.TripRepository
import com.byd.tripstats.service.MqttBrokerService
import com.byd.tripstats.service.MqttService
import com.byd.tripstats.ui.components.RangeDataPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.content.SharedPreferences
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class DashboardViewModel(application: Application) : AndroidViewModel(application) {

    private val TAG = "DashboardViewModel"

    private val tripRepository      = TripRepository.getInstance(application)
    private val chargingRepository  = ChargingRepository.getInstance(application)
    private val preferencesManager  = PreferencesManager(application)

    // ── MQTT state ────────────────────────────────────────────────────────────

    private val _mqttConnected = MutableStateFlow(false)
    val mqttConnected: StateFlow<Boolean> = _mqttConnected.asStateFlow()

    private val _mqttConnectionError = MutableStateFlow<String?>(null)
    val mqttConnectionError: StateFlow<String?> = _mqttConnectionError.asStateFlow()

    // ── Car config ────────────────────────────────────────────────────────────

    val selectedCarConfig = preferencesManager.selectedCarConfig
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // ── Telemetry & trip state (from repository) ──────────────────────────────

    val currentTelemetry: StateFlow<VehicleTelemetry?> = tripRepository.latestTelemetry
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val isInTrip: StateFlow<Boolean> = tripRepository.isInTrip
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val currentTripId: StateFlow<Long?> = tripRepository.currentTripId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // ── Charging session state ────────────────────────────────────────────────

    val isChargingSession: StateFlow<Boolean> = chargingRepository.isCharging
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val allChargingSessions: StateFlow<List<ChargingSessionEntity>> =
        chargingRepository.getAllSessions()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _selectedSessionId = MutableStateFlow<Long?>(null)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val selectedSession: StateFlow<ChargingSessionEntity?> =
        _selectedSessionId.flatMapLatest { id ->
            if (id == null) flowOf(null)
            else chargingRepository.getDataPointsForSession(id).map { _ ->
                chargingRepository.getSessionById(id)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val selectedSessionDataPoints: StateFlow<List<ChargingDataPointEntity>> =
        _selectedSessionId.flatMapLatest { id ->
            if (id == null) flowOf(emptyList())
            else chargingRepository.getDataPointsForSession(id)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun selectSession(sessionId: Long) { _selectedSessionId.value = sessionId }
    fun clearSelectedSession()         { _selectedSessionId.value = null }

    // ── Trip list & stats ─────────────────────────────────────────────────────

    val allTrips: StateFlow<List<TripEntity>> = tripRepository.getAllTrips()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Eagerly kept alive — drives multiple derived StateFlows simultaneously.
    private val allTripStats: StateFlow<List<TripStatsEntity>> = tripRepository.getAllTripStats()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // ── Selected trip — single source of truth for detail screens ────────────

    /**
     * Set by the UI when navigating into a trip detail screen.
     * All detail-screen StateFlows derive from this so there is exactly one DB
     * subscription per ViewModel lifetime instead of one per recomposition.
     */
    private val _selectedTripId = MutableStateFlow<Long?>(null)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val selectedTrip: StateFlow<TripEntity?> =
        _selectedTripId.flatMapLatest { id ->
            if (id == null) flowOf(null) else tripRepository.getTripById(id)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val selectedTripDataPoints: StateFlow<List<TripDataPointEntity>> =
        _selectedTripId.flatMapLatest { id ->
            if (id == null) flowOf(emptyList()) else tripRepository.getDataPointsForTrip(id)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val selectedTripStats: StateFlow<TripStatsEntity?> =
        _selectedTripId.flatMapLatest { id ->
            if (id == null) flowOf(null) else tripRepository.getStatsForTrip(id)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Call this when navigating to a trip detail screen. */
    fun selectTrip(tripId: Long) {
        _selectedTripId.value = tripId
    }

    /** Call this when navigating away from the detail screen. */
    fun clearSelectedTrip() {
        _selectedTripId.value = null
    }

    // ── Backward-compat helpers (used by screens that still call getTripXxx) ──
    // These now just delegate to the shared selected-trip flows rather than
    // creating new subscriptions, so they're safe to call from composables.

    fun getTripDetails(tripId: Long): StateFlow<TripEntity?> {
        selectTrip(tripId)
        return selectedTrip
    }

    fun getTripDataPoints(tripId: Long): StateFlow<List<TripDataPointEntity>> {
        selectTrip(tripId)
        return selectedTripDataPoints
    }

    fun getTripStats(tripId: Long): StateFlow<TripStatsEntity?> {
        selectTrip(tripId)
        return selectedTripStats
    }

    // ── Efficiency charts ─────────────────────────────────────────────────────

    /** One entry per time bucket that has at least one completed trip with ≥ 0.5 km. */
    data class DailyEfficiency(val dateLabel: String, val avgKwhPer100km: Double)

    /**
     * Builds a list of [DailyEfficiency] for [bucketCount] daily buckets ending today,
     * or for monthly buckets when [monthly] is true.
     *
     * Single implementation used by all three chart StateFlows — previously
     * the same date-windowing logic was copy-pasted three times.
     */
    private fun List<TripEntity>.toEfficiencyBuckets(
        bucketCount: Int,
        fmt: String,
        monthly: Boolean = false
    ): List<DailyEfficiency> {
        val formatter = SimpleDateFormat(fmt, Locale.getDefault())
        val cal = Calendar.getInstance().apply {
            if (monthly) set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val anchor = cal.timeInMillis

        return (bucketCount - 1 downTo 0).mapNotNull { bucketAgo ->
            val bucketCal = cal.clone() as Calendar
            if (monthly) {
                bucketCal.add(Calendar.MONTH, -bucketAgo)
            } else {
                bucketCal.timeInMillis = anchor - bucketAgo * 86_400_000L
            }
            val bucketStart = bucketCal.timeInMillis
            val bucketEnd = if (monthly) {
                (bucketCal.clone() as Calendar).also { it.add(Calendar.MONTH, 1) }.timeInMillis - 1L
            } else {
                bucketStart + 86_400_000L - 1L
            }
            val label = formatter.format(java.util.Date(bucketStart))
            val efficiencies = filter {
                it.startTime in bucketStart..bucketEnd &&
                it.efficiency != null &&
                (it.distance ?: 0.0) >= 0.5
            }.mapNotNull { it.efficiency }
            if (efficiencies.isEmpty()) null
            else DailyEfficiency(label, efficiencies.average())
        }
    }

    /** Past 7 days, one point per day. */
    val weeklyEfficiency: StateFlow<List<DailyEfficiency>> = allTrips
        .map { it.toEfficiencyBuckets(7, "dd/MM") }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Past 30 days, one point per day. */
    val monthlyEfficiency: StateFlow<List<DailyEfficiency>> = allTrips
        .map { it.toEfficiencyBuckets(30, "dd/MM") }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Past 12 calendar months, one point per month. */
    val yearlyEfficiency: StateFlow<List<DailyEfficiency>> = allTrips
        .map { it.toEfficiencyBuckets(12, "MMM", monthly = true) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // ── Per-trip display metrics ──────────────────────────────────────────────

    /**
     * Pre-computed display metrics keyed by trip ID. Derived once and cached —
     * the LazyColumn in TripHistoryScreen does a map lookup, never arithmetic.
     */
    data class TripDisplayMetrics(
        val avgSpeedKmh:       Int?,
        val tripScore:         Int?,
        val regenEfficiencyPct: Double?
    )

    val tripDisplayMetrics: StateFlow<Map<Long, TripDisplayMetrics>> =
        combine(allTrips, allTripStats) { trips, stats ->
            val statsById = stats.associateBy { it.tripId }
            trips.associate { trip ->
                val dist = trip.distance
                val dur  = trip.duration

                val avgSpeed = if (dist != null && dur != null && dur > 0 && dist > 0)
                    (dist / (dur / 3_600_000.0)).toInt() else null
                // ─────────────────────────────────────────────────────────────
                // TRIP SCORE  (0–100)
                // Composed of three independent components, each contributing
                // a portion of the total score:
                //
                //   1. EFFICIENCY SCORE  (0–40 pts)
                //      Based on energy consumption (Wh/km).
                //      ≤ 17 Wh/km  → full 40 pts  (very efficient)
                //      ≥ 25 Wh/km  → 0 pts         (very inefficient)
                //      Between 17–25: linear interpolation using the wider
                //      range of 15–25 as the scale denominator:
                //        (25 - eff) / (25 - 15) * 40
                //
                //   2. REGEN SCORE  (0–30 pts)
                //      Measures how much of the total power demand was
                //      recovered via regenerative braking:
                //        |maxRegenPower| / (maxPower + |maxRegenPower|) * 30
                //      Higher regen share → higher score.
                //
                //   3. SMOOTHNESS SCORE  (0–30 pts)
                //      Ratio of average speed to max speed:
                //        (avgSpeed / maxSpeed) * 30
                //      A ratio close to 1 means steady, consistent driving
                //      with few hard accelerations or sudden stops.
                //      A low ratio means lots of speed variation (stop/go).
                //
                // Minimum requirements to compute a score:
                //   - efficiency must be available
                //   - distance ≥ 0.5 km  (filters out micro/accidental trips)
                //   - duration > 0
                // ─────────────────────────────────────────────────────────────
                val score = run {
                    val eff = trip.efficiency ?: return@run null
                    if (dist == null || dur == null || dist < 0.5 || dur <= 0) return@run null
                    val effScore = when {
                        eff <= 17.0 -> 40
                        eff >= 25.0 -> 0
                        else        -> ((25.0 - eff) / (25.0 - 15.0) * 40).toInt()
                    }
                    val maxRegen = kotlin.math.abs(trip.maxRegenPower)
                    val maxPower = trip.maxPower
                    val regenScore = if (maxPower + maxRegen > 0)
                        ((maxRegen / (maxPower + maxRegen)) * 30).toInt().coerceIn(0, 30) else 0
                    val avg = dist / (dur / 3_600_000.0)
                    val smoothScore = if (trip.maxSpeed > 0)
                        ((avg / trip.maxSpeed) * 30).toInt().coerceIn(0, 30) else 0
                    (effScore + regenScore + smoothScore).coerceIn(0, 100)
                }

                val tripStat = statsById[trip.id]
                val regenPct = if (
                    tripStat != null &&
                    trip.energyConsumed != null &&
                    trip.energyConsumed!! > 0
                ) {
                    val regen = tripStat.totalRegenEnergy
                    (regen / (trip.energyConsumed!! + regen)) * 100.0
                } else null

                trip.id to TripDisplayMetrics(avgSpeed, score, regenPct)
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    // ── Auto trip detection ───────────────────────────────────────────────────

    private val _autoTripDetection = MutableStateFlow(tripRepository.isAutoTripDetectionEnabled())
    val autoTripDetection: StateFlow<Boolean> = _autoTripDetection.asStateFlow()

    // ── Live range projection ─────────────────────────────────────────────────
    //
    // Four-level tiered model with dynamic fallback.
    // The active model is exposed via activeRangeModel StateFlow so the UI can
    // show the user which level is currently driving the projection.
    //
    // LEVEL 1 — LIVE_TRIP (most accurate)
    //   Rolling window (last ROLLING_WINDOW_KM) + EMA smoothing.
    //   Activated once distKm >= STABILISATION_KM and the rolling window has
    //   produced a valid Wh/km estimate. This is the primary operating mode.
    //   Equivalent to "last 30 km" Trip Range model.
    //
    // LEVEL 2 — HISTORICAL_BINS (speed-bin model)
    //   Live speed bins are accumulated on every telemetry tick (not just 100 m
    //   samples) so they build up quickly within the same trip.
    //   If the current speed bin has ≥ BIN_MIN_DIST_KM of observed distance,
    //   its Wh/km is used as the projection rate. This captures driving-style
    //   variation (city vs highway) from actual data without relying on past trips.
    //   Activated only when Level 1 is not yet available (pre-stabilisation).
    //
    // LEVEL 3 — LIFETIME_AVERAGE  ← TODO Phase 2
    //   Aggregate past-trip consumption: sum(energyConsumed) / sum(distance)
    //   across all completed TripEntity records.
    //   Use when: no Level 2 bin data and lifetime distance > LIFETIME_MIN_KM.
    //   Data is available via allTrips StateFlow (TripEntity.energyConsumed / .distance).
    //
    // LEVEL 4 — BASELINE (static fallback)
    //   BYD Seal AWD Excellence WLTP-based static rate: BASELINE_WH_PER_KM = 185 Wh/km.
    //   Always available. Only used when all other levels fail.
    //
    // ── TODO Phase 2 ──────────────────────────────────────────────────────────
    // [ ] Level 3 — Lifetime average
    //     val lifetimeWhPerKm = allTrips.value
    //         .filter { it.distance != null && it.energyConsumed != null }
    //         .takeIf { trips -> trips.sumOf { it.distance!! } > LIFETIME_MIN_KM }
    //         ?.let { trips ->
    //             trips.sumOf { it.energyConsumed!! * 1000.0 } /
    //             trips.sumOf { it.distance!! }
    //         }
    //     Condition: lifetimeWhPerKm != null && distKm < STABILISATION_KM
    //
    // [ ] Merge past-trip speed bins with live bins (Bayesian prior)
    //     Each bin: mergedWhPerKm = (historicalSamples × historicalRate +
    //                                liveSamples × liveRate) /
    //               (historicalSamples + liveSamples)
    //     historicalRate comes from allTripStats.value
    //         .flatMap { it.energyConsumptionBySpeed.entries }
    //         grouped and averaged per bin key.
    //     As live data accumulates it dominates the prior automatically.
    // ─────────────────────────────────────────────────────────────────────────

    enum class RangeModel { LIVE_TRIP, HISTORICAL_BINS, LIFETIME_AVERAGE, BASELINE }

    companion object {
        const val BATTERY_CAPACITY_KWH  = 82.56  // TODO: Import from config
        const val STABILISATION_KM      = 2.0    // km before Level 1 is trusted
        const val SAMPLE_INTERVAL_KM    = 0.1    // record a chart point every 100 m
        const val ROLLING_WINDOW_KM     = 10.0   // Level 1: rolling window length
        const val EMA_ALPHA             = 0.15   // Level 1: EMA smoothing factor
        const val MAX_DELTA_SECONDS     = 10.0   // discard Δt > this (reconnect / wake)
        const val BIN_MIN_DIST_KM       = 0.5    // Level 2: min km in a bin before trusting it
        const val BASELINE_WH_PER_KM    = 185.0  // Level 4: BYD Seal AWD Excellence static fallback // TODO: Import from config
        // TODO Phase 2:
        // const val LIFETIME_MIN_KM    = 50.0   // Level 3: min lifetime km before using average
    }

    // Rolling buffer entry: cumulative values at a given distance milestone
    private data class EnergySample(val distanceKm: Double, val cumulativeEnergyWh: Double)

    // Live speed-bin accumulator — updated on every telemetry tick for fine granularity
    private data class BinAccumulator(var energyWh: Double = 0.0, var distanceKm: Double = 0.0)

    private val _tripDataPoints  = MutableStateFlow<List<RangeDataPoint>>(emptyList())
    val tripDataPoints: StateFlow<List<RangeDataPoint>> = _tripDataPoints.asStateFlow()

    private val _activeRangeModel = MutableStateFlow(RangeModel.BASELINE)
    val activeRangeModel: StateFlow<RangeModel> = _activeRangeModel.asStateFlow()

    private var tripStartOdometer:   Double?  = null
    private var lastTelemetryTimeMs: Long?    = null
    private var accumulatedEnergyWh: Double   = 0.0
    private var smoothedWhPerKm:     Double?  = null  // Level 1 EMA state
    private val energySamples      = mutableListOf<EnergySample>()
    private val liveSpeedBins      = mutableMapOf<String, BinAccumulator>()

    /** Mirror of TripRepository.speedBin — kept in sync manually. */
    private fun speedBin(speed: Double) = when {
        speed <  20 -> "0-20"
        speed <  40 -> "20-40"
        speed <  60 -> "40-60"
        speed <  80 -> "60-80"
        speed < 100 -> "80-100"
        else        -> "100+"
    }

    // ── Init ──────────────────────────────────────────────────────────────────

    init {
        viewModelScope.launch {
            var wasInTrip       = false
            var lastBinOdo:     Double? = null

            combine(isInTrip, currentTelemetry) { inTrip, telemetry ->
                inTrip to telemetry
            }.collect { (inTrip, telemetry) ->
                if (telemetry == null) return@collect

                // Parse telemetry timestamp — more accurate than system clock
                val telemetryMs = runCatching {
                    java.time.Instant.parse(telemetry.currentDatetime).toEpochMilli()
                }.getOrNull() ?: System.currentTimeMillis()

                // Feed every packet to the charging repository regardless of trip state
                chargingRepository.onTelemetry(telemetry, selectedCarConfig.value)

                when {
                    inTrip && !wasInTrip -> {
                        // ── Trip start ────────────────────────────────────────
                        tripStartOdometer    = telemetry.odometer
                        lastTelemetryTimeMs  = telemetryMs
                        lastBinOdo           = telemetry.odometer
                        accumulatedEnergyWh  = 0.0
                        smoothedWhPerKm      = null
                        energySamples.clear()
                        liveSpeedBins.clear()
                        _activeRangeModel.value = RangeModel.BASELINE
                        _tripDataPoints.value = listOf(
                            RangeDataPoint(
                                distanceKm             = 0.0,
                                soc                    = telemetry.soc,
                                electricDrivingRangeKm = telemetry.electricDrivingRangeKm,
                                projectedRangeKm       = null,
                                isStabilised           = false
                            )
                        )
                    }

                    !inTrip && wasInTrip -> {
                        // ── Trip end — keep points for post-trip review ────────
                        tripStartOdometer    = null
                        lastTelemetryTimeMs  = null
                        lastBinOdo           = null
                        accumulatedEnergyWh  = 0.0
                        smoothedWhPerKm      = null
                        energySamples.clear()
                        liveSpeedBins.clear()
                    }

                    inTrip -> {
                        // ── 1. Integrate energy using telemetry Δt ────────────
                        val prevMs = lastTelemetryTimeMs
                        var deltaEnergyWh = 0.0
                        if (prevMs != null) {
                            val deltaSeconds = (telemetryMs - prevMs) / 1000.0
                            if (deltaSeconds in 0.0..MAX_DELTA_SECONDS) {
                                deltaEnergyWh        = telemetry.enginePower * 1000.0 * (deltaSeconds / 3600.0)
                                accumulatedEnergyWh += deltaEnergyWh
                            }
                        }
                        lastTelemetryTimeMs = telemetryMs

                        // ── 2. Update live speed bins (every tick, not per 100 m) ──
                        // Fine-grained accumulation means bins are useful within the
                        // first few km even before Level 1 stabilises.
                        val prevOdo = lastBinOdo
                        if (prevOdo != null && deltaEnergyWh > 0.0) {
                            val binDistKm = (telemetry.odometer - prevOdo).coerceAtLeast(0.0)
                            val bin       = speedBin(telemetry.speed)
                            val acc       = liveSpeedBins.getOrPut(bin) { BinAccumulator() }
                            acc.energyWh   += deltaEnergyWh
                            acc.distanceKm += binDistKm
                        }
                        lastBinOdo    = telemetry.odometer

                        // ── 3. Sample every 100 m for chart point ─────────────
                        val anchor = tripStartOdometer ?: run {
                            android.util.Log.w("RangeProjection", "tripStartOdometer null mid-trip — skipping")
                            return@collect
                        }
                        val distKm   = (telemetry.odometer - anchor).coerceAtLeast(0.0)
                        val lastDist = _tripDataPoints.value.lastOrNull()?.distanceKm ?: 0.0
                        if (distKm - lastDist < SAMPLE_INTERVAL_KM) return@collect

                        // ── 4. Rolling window buffer ──────────────────────────
                        energySamples.add(EnergySample(distKm, accumulatedEnergyWh))
                        val windowFloor = distKm - ROLLING_WINDOW_KM
                        while (energySamples.size > 1 && energySamples[0].distanceKm < windowFloor) {
                            energySamples.removeAt(0)
                        }

                        // ── 5. Level 1 — rolling window + EMA ─────────────────
                        val rawWhPerKm: Double? = if (energySamples.size >= 2) {
                            val wEnergyWh = energySamples.last().cumulativeEnergyWh -
                                            energySamples.first().cumulativeEnergyWh
                            val wDistKm   = energySamples.last().distanceKm -
                                            energySamples.first().distanceKm
                            if (wDistKm > 0 && wEnergyWh > 0) wEnergyWh / wDistKm else null
                        } else null

                        if (rawWhPerKm != null) {
                            smoothedWhPerKm = smoothedWhPerKm
                                ?.let { EMA_ALPHA * rawWhPerKm + (1.0 - EMA_ALPHA) * it }
                                ?: rawWhPerKm
                        }

                        // ── 6. Level 2 — current speed bin ────────────────────
                        val currentBinAcc = liveSpeedBins[speedBin(telemetry.speed)]
                        val binWhPerKm: Double? = currentBinAcc
                            ?.takeIf { it.distanceKm >= BIN_MIN_DIST_KM && it.energyWh > 0 }
                            ?.let { it.energyWh / it.distanceKm }

                        // ── 7. Fallback chain ─────────────────────────────────
                        val isStabilised = distKm >= STABILISATION_KM
                        val batteryKwh = selectedCarConfig.value?.batteryKwh ?: BATTERY_CAPACITY_KWH
                        val remainingEnergyWh = batteryKwh * 1000.0 * (telemetry.soc / 100.0)

                        val (projectedRange, model) = when {
                            // Level 1: rolling window ready and past stabilisation window
                            isStabilised && smoothedWhPerKm != null && smoothedWhPerKm!! > 0.0 ->
                                (remainingEnergyWh / smoothedWhPerKm!!).coerceAtLeast(0.0) to
                                RangeModel.LIVE_TRIP

                            // Level 2: current speed bin has enough live data
                            binWhPerKm != null ->
                                (remainingEnergyWh / binWhPerKm).coerceAtLeast(0.0) to
                                RangeModel.HISTORICAL_BINS

                            // TODO Phase 2 — Level 3: lifetime average
                            // Insert here: check allTrips for lifetime Wh/km
                            // and return X to RangeModel.LIFETIME_AVERAGE

                            // Level 4: static baseline — always available
                            else ->
                                (remainingEnergyWh / BASELINE_WH_PER_KM).coerceAtLeast(0.0) to
                                RangeModel.BASELINE
                        }

                        _activeRangeModel.value = model

                        _tripDataPoints.value = _tripDataPoints.value + RangeDataPoint(
                            distanceKm             = distKm,
                            soc                    = telemetry.soc,
                            electricDrivingRangeKm = telemetry.electricDrivingRangeKm,
                            projectedRangeKm       = projectedRange,
                            isStabilised           = isStabilised || model != RangeModel.BASELINE
                        )
                    }
                }
                wasInTrip = inTrip
            }
        }
    }

    // ── Trip controls ─────────────────────────────────────────────────────────

    /**
     * Both functions are now plain fun — the repository's public API is
     * fire-and-forget (enqueues a TripEvent), so no coroutine is needed here.
     */
    fun startManualTrip() {
        _tripDataPoints.value = emptyList()   // reset before repo broadcasts isInTrip = true
        tripRepository.requestManualStart()
    }

    fun endManualTrip() {
        tripRepository.requestManualStop()
    }

    fun toggleAutoTripDetection() {
        val newValue = !_autoTripDetection.value
        tripRepository.setAutoTripDetection(newValue)
        _autoTripDetection.value = newValue
    }

    // ── Mock drive ────────────────────────────────────────────────────────────

    fun startMockDrive() {
        viewModelScope.launch {
            val mockGenerator = com.byd.tripstats.mock.MockDataGenerator()
            mockGenerator.generateMockDrive().collect { telemetry ->
                // processTelemetry is now a plain fun — just call directly
                tripRepository.processTelemetry(telemetry)
            }
        }
    }

    // ── MQTT service ──────────────────────────────────────────────────────────

    /** Called from MainActivity when service binding is established. */
    fun observeMqttServiceState(service: MqttService) {
        viewModelScope.launch {
            service.connectionState.collect { state ->
                when (state) {
                    is MqttService.ConnectionState.Connected    -> {
                        _mqttConnected.value      = true
                        _mqttConnectionError.value = null
                    }
                    is MqttService.ConnectionState.Error        -> {
                        _mqttConnected.value      = false
                        _mqttConnectionError.value = state.message
                    }
                    is MqttService.ConnectionState.Connecting,
                    is MqttService.ConnectionState.Disconnected -> {
                        _mqttConnected.value      = false
                        _mqttConnectionError.value = null
                    }
                }
            }
        }
    }

    fun stopMqttService() {
        MqttService.stop(getApplication())
        _mqttConnected.value       = false
        _mqttConnectionError.value = null
    }

    /** Restarts the MQTT client, optionally starting the embedded broker first. */
    fun restartMqttService(
        brokerUrl: String,
        brokerPort: Int,
        username: String?,
        password: String?,
        topic: String
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "=== Restarting MQTT Service ===")
                MqttService.stop(getApplication())
                delay(2_000)

                val isLocal = brokerUrl.trim().let {
                    it == "127.0.0.1" || it == "localhost" || it == "::1"
                }

                if (isLocal) {
                    try {
                        MqttBrokerService.start(getApplication())
                        delay(6_000)   // wait for embedded broker init
                    } catch (e: Exception) {
                        Log.e(TAG, "Error starting embedded broker", e)
                    }
                }

                MqttService.start(
                    context   = getApplication(),
                    brokerUrl = brokerUrl,
                    brokerPort = brokerPort,
                    username  = username,
                    password  = password,
                    topic     = topic
                )

                delay(2_000)
                Log.d(TAG, "=== MQTT Service Restart Complete ===")
            } catch (e: Exception) {
                Log.e(TAG, "Error restarting MQTT service", e)
            }
        }
    }

    // ── Database management ───────────────────────────────────────────────────

    suspend fun backupDatabase(): File? = withContext(Dispatchers.IO) {
        BydStatsDatabase.backupDatabase(getApplication())
    }

    fun listDatabaseBackups(): List<File> = BydStatsDatabase.listBackups(getApplication())

    fun resetDatabase() {
        BydStatsDatabase.resetDatabase(getApplication())
    }

    // ── Trip history sort & filter ────────────────────────────────────────────

    enum class TripSortField  { DATE, DISTANCE, DURATION, CONSUMPTION, REGEN_EFF, MAX_SPEED }
    enum class TripSortOrder  { ASC, DESC }

    data class TripFilterState(
        val distanceMin:    Float? = null,
        val distanceMax:    Float? = null,
        val durationMin:    Float? = null,   // minutes
        val durationMax:    Float? = null,
        val consumptionMin: Float? = null,   // kWh/100km
        val consumptionMax: Float? = null,
        val regenEffMin:    Float? = null,   // %
        val regenEffMax:    Float? = null,
        val maxSpeedMin:    Float? = null,   // km/h
        val maxSpeedMax:    Float? = null
    ) {
        val activeFilterCount: Int get() = listOf(
            distanceMin, distanceMax, durationMin, durationMax,
            consumptionMin, consumptionMax, regenEffMin, regenEffMax,
            maxSpeedMin, maxSpeedMax
        ).count { it != null }
    }

    private val historyPrefs: SharedPreferences by lazy {
        getApplication<Application>().getSharedPreferences("trip_history_prefs", 0)
    }

    private fun SharedPreferences.getFloatOrNull(key: String): Float? =
        if (contains(key)) getFloat(key, 0f) else null

    private fun SharedPreferences.Editor.putFloatOrRemove(key: String, v: Float?) {
        if (v != null) putFloat(key, v) else remove(key)
    }

    private val _sortField = MutableStateFlow(
        TripSortField.entries.getOrElse(historyPrefs.getInt("sort_field", 0)) { TripSortField.DATE }
    )
    val sortField: StateFlow<TripSortField> = _sortField.asStateFlow()

    private val _sortOrder = MutableStateFlow(
        TripSortOrder.entries.getOrElse(historyPrefs.getInt("sort_order", 1)) { TripSortOrder.DESC }
    )
    val sortOrder: StateFlow<TripSortOrder> = _sortOrder.asStateFlow()

    private fun loadFilterState() = with(historyPrefs) {
        TripFilterState(
            distanceMin    = getFloatOrNull("f_dist_min"),
            distanceMax    = getFloatOrNull("f_dist_max"),
            durationMin    = getFloatOrNull("f_dur_min"),
            durationMax    = getFloatOrNull("f_dur_max"),
            consumptionMin = getFloatOrNull("f_cons_min"),
            consumptionMax = getFloatOrNull("f_cons_max"),
            regenEffMin    = getFloatOrNull("f_regen_min"),
            regenEffMax    = getFloatOrNull("f_regen_max"),
            maxSpeedMin    = getFloatOrNull("f_speed_min"),
            maxSpeedMax    = getFloatOrNull("f_speed_max")
        )
    }

    private val _filterState = MutableStateFlow(loadFilterState())
    val filterState: StateFlow<TripFilterState> = _filterState.asStateFlow()

    fun setSortField(field: TripSortField) {
        _sortField.value = field
        historyPrefs.edit().putInt("sort_field", field.ordinal).apply()
    }

    fun toggleSortOrder() {
        val new = if (_sortOrder.value == TripSortOrder.DESC) TripSortOrder.ASC else TripSortOrder.DESC
        _sortOrder.value = new
        historyPrefs.edit().putInt("sort_order", new.ordinal).apply()
    }

    fun setFilter(f: TripFilterState) {
        _filterState.value = f
        historyPrefs.edit().apply {
            putFloatOrRemove("f_dist_min",  f.distanceMin);    putFloatOrRemove("f_dist_max",  f.distanceMax)
            putFloatOrRemove("f_dur_min",   f.durationMin);    putFloatOrRemove("f_dur_max",   f.durationMax)
            putFloatOrRemove("f_cons_min",  f.consumptionMin); putFloatOrRemove("f_cons_max",  f.consumptionMax)
            putFloatOrRemove("f_regen_min", f.regenEffMin);    putFloatOrRemove("f_regen_max", f.regenEffMax)
            putFloatOrRemove("f_speed_min", f.maxSpeedMin);    putFloatOrRemove("f_speed_max", f.maxSpeedMax)
            apply()
        }
    }

    fun clearFilters() = setFilter(TripFilterState())

    val sortedFilteredTrips: StateFlow<List<TripEntity>> =
        combine(allTrips, tripDisplayMetrics, _sortField, _sortOrder, _filterState) {
            trips, metrics, field, order, filter ->

            val active    = trips.filter { it.isActive }
            val completed = trips.filter { !it.isActive }

            val filtered = completed.filter { trip ->
                val m      = metrics[trip.id]
                val dist   = trip.distance  ?: 0.0
                val durMin = (trip.duration ?: 0L) / 60_000.0
                val cons   = trip.efficiency ?: Double.MAX_VALUE
                val regen  = m?.regenEfficiencyPct ?: 0.0
                val spd    = trip.maxSpeed

                (filter.distanceMin    == null || dist   >= filter.distanceMin)    &&
                (filter.distanceMax    == null || dist   <= filter.distanceMax)    &&
                (filter.durationMin    == null || durMin >= filter.durationMin)    &&
                (filter.durationMax    == null || durMin <= filter.durationMax)    &&
                (filter.consumptionMin == null || cons   >= filter.consumptionMin) &&
                (filter.consumptionMax == null || cons   <= filter.consumptionMax) &&
                (filter.regenEffMin    == null || regen  >= filter.regenEffMin)    &&
                (filter.regenEffMax    == null || regen  <= filter.regenEffMax)    &&
                (filter.maxSpeedMin    == null || spd    >= filter.maxSpeedMin)    &&
                (filter.maxSpeedMax    == null || spd    <= filter.maxSpeedMax)
            }

            val sorted = when (field) {
                TripSortField.DATE        -> filtered.sortedBy { it.startTime }
                TripSortField.DISTANCE    -> filtered.sortedBy { it.distance   ?: 0.0 }
                TripSortField.DURATION    -> filtered.sortedBy { it.duration   ?: 0L  }
                TripSortField.CONSUMPTION -> filtered.sortedBy { it.efficiency ?: Double.MAX_VALUE }
                TripSortField.REGEN_EFF   -> filtered.sortedBy { metrics[it.id]?.regenEfficiencyPct ?: 0.0 }
                TripSortField.MAX_SPEED   -> filtered.sortedBy { it.maxSpeed }
            }.let { if (order == TripSortOrder.DESC) it.reversed() else it }

            // Active trip always floats to the top
            active + sorted
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // ── Trip deletion ─────────────────────────────────────────────────────────

    fun deleteTrip(tripId: Long) {
        viewModelScope.launch { tripRepository.deleteTrip(tripId) }
    }

    fun deleteTrips(tripIds: List<Long>) {
        viewModelScope.launch { tripRepository.deleteTrips(tripIds) }
    }

    // ── Charging session helpers ───────────────────────────────────────────────

    suspend fun getChargingSessionDataPoints(sessionId: Long): List<ChargingDataPointEntity> =
        chargingRepository.getDataPointsForSessionSync(sessionId)
}