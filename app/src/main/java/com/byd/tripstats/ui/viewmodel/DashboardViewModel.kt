package com.byd.tripstats.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.byd.tripstats.data.local.BydStatsDatabase
import com.byd.tripstats.data.local.dao.TripSohSummary
import com.byd.tripstats.data.analysis.CostAttribution
import com.byd.tripstats.data.analysis.RouteGroup
import com.byd.tripstats.data.analysis.RouteGrouping
import com.byd.tripstats.data.analysis.RouteTripInput
import com.byd.tripstats.data.local.entity.ChargingDataPointEntity
import com.byd.tripstats.data.local.entity.ChargingSessionEntity
import com.byd.tripstats.data.local.entity.TagEntity
import com.byd.tripstats.data.local.entity.TripDataPointEntity
import com.byd.tripstats.data.local.entity.TripEntity
import com.byd.tripstats.data.local.entity.TripStatsEntity
import com.byd.tripstats.data.local.entity.TripTagCrossRef
import com.byd.tripstats.data.model.BatteryVoltageHistoryPoint
import com.byd.tripstats.data.model.VehicleTelemetry
import com.byd.tripstats.data.preferences.PreferencesManager
import com.byd.tripstats.data.preferences.SocSource
import com.byd.tripstats.data.preferences.UnitSystem
import com.byd.tripstats.data.repository.BatteryVoltageHistoryRepository
import com.byd.tripstats.data.repository.ChargingRepository
import com.byd.tripstats.data.repository.LiveProjectionCache
import com.byd.tripstats.data.repository.MergeEligibility
import com.byd.tripstats.data.repository.MergeResult
import com.byd.tripstats.data.repository.TripRepository
import com.byd.tripstats.data.repository.UpdateRepository
import com.byd.tripstats.BuildConfig
import com.byd.tripstats.service.VehicleTelemetryService
import com.byd.tripstats.sdk.VehicleTelemetrySnapshot
import com.byd.tripstats.ui.components.RangeDataPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine as combineFlows
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import android.content.SharedPreferences
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(FlowPreview::class)
class DashboardViewModel(application: Application) : AndroidViewModel(application) {

    private val TAG = "DashboardViewModel"

    private val tripRepository      = TripRepository.getInstance(application)
    private val chargingRepository  = ChargingRepository.getInstance(application)
    private val batteryVoltageHistoryRepository = BatteryVoltageHistoryRepository.getInstance(application)
    private val preferencesManager  = PreferencesManager(application)
    val updateRepository            = UpdateRepository.getInstance(application)
    // Process-scoped, disk-backed cache of the live projection curve so a reopen
    // (back press, or cold start) shows the as-computed-live line instead of a
    // single-rate rebuild. Display aid only — never feeds live tracking math.
    private val liveProjectionCache = LiveProjectionCache.getInstance(application)

    // ── Vehicle service connection state ─────────────────────────────────────

    /** True when the vehicle telemetry service is connected and streaming data. */
    private val _serviceConnected = MutableStateFlow(false)
    val serviceConnected: StateFlow<Boolean> = _serviceConnected.asStateFlow()

    private val _serviceConnectionError = MutableStateFlow<String?>(null)
    val serviceConnectionError: StateFlow<String?> = _serviceConnectionError.asStateFlow()

    private val _vehicleSnapshot = MutableStateFlow<VehicleTelemetrySnapshot?>(null)
    val vehicleSnapshot: StateFlow<VehicleTelemetrySnapshot?> = _vehicleSnapshot.asStateFlow()
    val directVehicleSnapshot: StateFlow<VehicleTelemetrySnapshot?> = vehicleSnapshot
    private val _mockTelemetry = MutableStateFlow<VehicleTelemetry?>(null)
    private val _mockVehicleSnapshot = MutableStateFlow<VehicleTelemetrySnapshot?>(null)
    private val _isMockModeActive = MutableStateFlow(false)
    val isMockModeActive: StateFlow<Boolean> = _isMockModeActive.asStateFlow()
    private val _isReplayActive = MutableStateFlow(false)
    val isReplayActive: StateFlow<Boolean> = _isReplayActive.asStateFlow()
    private val _replayStatus = MutableStateFlow<String?>(null)
    /** Dev-only replay progress/error line surfaced next to the replay button. */
    val replayStatus: StateFlow<String?> = _replayStatus.asStateFlow()
    private var telemetryService: VehicleTelemetryService? = null
    private var mockDriveJob: Job? = null
    private var replayJob: Job? = null
    private var serviceObserverJob: Job? = null
    private var restoreTripJob: Job? = null
    private val updateCheckStarted = AtomicBoolean(false)

    // ── Car config ────────────────────────────────────────────────────────────

    // Eagerly (not WhileSubscribed): the projection/telemetry loop reads
    // selectedCarConfig.value directly without subscribing, and the UI subscribes to
    // its OWN PreferencesManager flow — so under WhileSubscribed this StateFlow's
    // upstream never started and .value stayed null, silently making the projection
    // fall back to FALLBACK_BATTERY_KWH (82.56) with isPhev=false. That hugely
    // inflated small-battery PHEV projections (a Sealion 6 DM-i saturating at WLTP)
    // and only matched by luck on ~82 kWh BEVs. Eager keeps .value current.
    val selectedCarConfig = preferencesManager.selectedCarConfig
        .stateIn(viewModelScope, SharingStarted.Eagerly, preferencesManager.getCachedSelectedCarConfig())

    // Always-available car config for the synchronous reads in the telemetry loop:
    // the eager StateFlow value, with the synchronous prefs cache as a startup-gap
    // fallback. Never returns the catalog fallback unless no car was ever selected.
    private fun currentCarConfig() =
        selectedCarConfig.value ?: preferencesManager.getCachedSelectedCarConfig()

    // ── Electricity cost ──────────────────────────────────────────────────────

    val electricityPricePerKwh: StateFlow<Double> = preferencesManager.electricityPricePerKwh
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000),
            preferencesManager.getCachedElectricityPrice())

    val currencySymbol: StateFlow<String> = preferencesManager.currencySymbol
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000),
            preferencesManager.getCachedCurrencySymbol())

    fun saveElectricityPrice(price: Double, symbol: String) {
        viewModelScope.launch { preferencesManager.saveElectricityPrice(price, symbol) }
    }

    // ── Unit system ───────────────────────────────────────────────────────────

    val unitSystem: StateFlow<UnitSystem> = preferencesManager.unitSystem
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000),
            preferencesManager.getCachedUnitSystem())

    val socSource: StateFlow<SocSource> = preferencesManager.socSource
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000),
            preferencesManager.getCachedSocSource())

    fun saveUnitSystem(system: UnitSystem) {
        viewModelScope.launch { preferencesManager.saveUnitSystem(system) }
    }

    // ── Telemetry & trip state (from repository) ──────────────────────────────

    // Eagerly kept alive — drives the trip state machine in init{}.
    // WhileSubscribed would drop the subscription when navigating away,
    // causing isInTrip to briefly emit false and resetting trip accumulators.
    val currentTelemetry: StateFlow<VehicleTelemetry?> = tripRepository.latestTelemetry
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val displayTelemetry: StateFlow<VehicleTelemetry?> = combine(
        currentTelemetry,
        _mockTelemetry
    ) { live, mock ->
        mock ?: live
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val displayVehicleSnapshot: StateFlow<VehicleTelemetrySnapshot?> = combine(
        vehicleSnapshot,
        _mockVehicleSnapshot
    ) { live, mock ->
        mock ?: live
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // Eagerly kept alive — drives the trip state machine in init{}.
    val isInTrip: StateFlow<Boolean> = tripRepository.isInTrip
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // Eagerly (not WhileSubscribed) — keeps the DB subscription alive when the Activity
    // goes to background (home button, CarPlay). WhileSubscribed(5_000) would drop it
    // after 5 s, leaving currentTripId.value = null when the Activity returns. That null
    // causes the restore branch to be skipped and beginLiveDriveSession(clearPoints=true)
    // to fire instead, resetting the chart from the current position rather than trip start.
    val currentTripId: StateFlow<Long?> = tripRepository.currentTripId
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val batteryVoltageHistory24h: StateFlow<List<BatteryVoltageHistoryPoint>> =
        batteryVoltageHistoryRepository.history

    // ── Charging session state ────────────────────────────────────────────────

    val isChargingSession: StateFlow<Boolean> = chargingRepository.isCharging

    // ── Update ────────────────────────────────────────────────────────────────

    val updateInfo:       StateFlow<UpdateRepository.UpdateInfo?> = updateRepository.updateInfo
    val downloadedApk:    StateFlow<java.io.File?>                = updateRepository.downloadedApk

    // Managed locally so we can push poll results into it cleanly
    private val _updateDownloadProgress = MutableStateFlow<Int?>(null)
    val downloadProgress: StateFlow<Int?> = _updateDownloadProgress.asStateFlow()

    private val _isCheckingUpdate = MutableStateFlow(false)
    val isCheckingUpdate: StateFlow<Boolean> = _isCheckingUpdate.asStateFlow()

    /**
     * True only when it is safe to install an update:
     *   - Car is parked (gear == P or no telemetry)
     *   - No active trip
     *   - No active charging session
     */
    val canInstallNow: StateFlow<Boolean> = combineFlows(
        isInTrip, isChargingSession, currentTelemetry
    ) { inTrip, charging, telemetry ->
        !inTrip && !charging && (telemetry == null || telemetry.isParked)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val allChargingSessions: StateFlow<List<ChargingSessionEntity>> =
        chargingRepository.getAllSessions()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _chargingFavouritesOnly = MutableStateFlow(
        getApplication<Application>()
            .getSharedPreferences("trip_history_prefs", 0)
            .getBoolean("charging_favourites_only", false)
    )
    val chargingFavouritesOnly: StateFlow<Boolean> = _chargingFavouritesOnly.asStateFlow()

    fun toggleChargingFavouritesOnly() {
        val next = !_chargingFavouritesOnly.value
        _chargingFavouritesOnly.value = next
        getApplication<Application>()
            .getSharedPreferences("trip_history_prefs", 0)
            .edit().putBoolean("charging_favourites_only", next).apply()
    }

    /** Charging sessions after applying the favourites-only quick toggle. */
    val displayedChargingSessions: StateFlow<List<ChargingSessionEntity>> =
        combine(allChargingSessions, _chargingFavouritesOnly) { sessions, favOnly ->
            if (favOnly) sessions.filter { it.isFavourite } else sessions
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _selectedSessionId = MutableStateFlow<Long?>(null)

    // Derived from the Room-backed session list so it reflects row edits (favourite,
    // per-charge price) immediately — not just data-point changes during active charging.
    val selectedSession: StateFlow<ChargingSessionEntity?> =
        combine(_selectedSessionId, allChargingSessions) { id, sessions ->
            id?.let { sid -> sessions.firstOrNull { it.id == sid } }
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

    // ── Cost attribution & distance-between-charges ────────────────────────────

    /**
     * Physical blended electricity rate (currency/kWh) per completed trip — the FIFO
     * "battery cost-basis" ledger ([CostAttribution.blendedTripRates]). The per-trip override is
     * layered on top by the consumers (it's display-only and doesn't change the physical energy).
     * Exposed so the trip-detail screen can show/edit a trip's rate without re-deriving the ledger.
     */
    val tripBlendedRates: StateFlow<Map<Long, Double?>> =
        combine(allTrips, allChargingSessions, electricityPricePerKwh) { trips, sessions, tariff ->
            CostAttribution.blendedTripRates(trips, sessions, tariff)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /**
     * Distance (km) driven since the previous charge, keyed by completed charging-session id.
     * Anchored on each charge's odometer at plug-in ([ChargingSessionEntity.startOdometer]);
     * legacy rows without it fall back to the odometer of the last trip that ended before the
     * charge. A session's value is the delta from the immediately preceding charge's anchor —
     * null when either anchor is missing or the delta is negative (odometer glitch / reset).
     */
    val chargingDistances: StateFlow<Map<Long, Double?>> =
        combine(allChargingSessions, allTrips) { sessions, trips ->
            CostAttribution.distancesSincePreviousCharge(sessions, trips)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    // Level 3 prior (LIFETIME_AVERAGE): the driver's own lifetime Wh/km, recomputed only
    // when the trip list changes. Eagerly so the projection/telemetry loop can read .value
    // synchronously (same reasoning as selectedCarConfig); null until lifetime distance
    // clears LIFETIME_MIN_KM, in which case the cold-start projection stays on BASELINE.
    private val lifetimeWhPerKm: StateFlow<Double?> = allTrips
        .map { trips ->
            val valid = trips.filter { (it.distance ?: 0.0) > 0.0 && (it.energyConsumed ?: 0.0) > 0.0 }
            val totalDistanceKm = valid.sumOf { it.distance!! }
            if (totalDistanceKm > LIFETIME_MIN_KM)
                valid.sumOf { it.energyConsumed!! * 1000.0 } / totalDistanceKm
            else null
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // Eagerly kept alive — drives multiple derived StateFlows simultaneously.
    // WhileSubscribed so Room only queries when a screen is actually showing stats.
    private val allTripStats: StateFlow<List<TripStatsEntity>> = tripRepository.getAllTripStats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ── Tags ──────────────────────────────────────────────────────────────────
    val allTags: StateFlow<List<TagEntity>> = tripRepository.getAllTags()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val tripTagRefs: StateFlow<List<TripTagCrossRef>> = tripRepository.getAllTripTagRefs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** tripId → its tags (name-sorted). Drives the History chips and tag filter. */
    val tripTagsMap: StateFlow<Map<Long, List<TagEntity>>> =
        combine(allTags, tripTagRefs) { tags, refs ->
            val tagsById = tags.associateBy { it.id }
            refs.groupBy { it.tripId }
                .mapValues { (_, rs) ->
                    rs.mapNotNull { tagsById[it.tagId] }.sortedBy { it.name.lowercase() }
                }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

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
                .filter { it in MIN_VALID_CONSUMPTION_KWH_PER_100KM..MAX_VALID_CONSUMPTION_KWH_PER_100KM }
            if (efficiencies.isEmpty()) null
            else DailyEfficiency(label, efficiencies.average())
        }
    }

    /** Past 7 days, one point per day. */
    val weeklyEfficiency: StateFlow<List<DailyEfficiency>> = allTrips
        .map { it.toEfficiencyBuckets(7, "dd/MM") }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Past 30 days, one point per day. */
    val monthlyEfficiency: StateFlow<List<DailyEfficiency>> = allTrips
        .map { it.toEfficiencyBuckets(30, "dd/MM") }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Past 12 calendar months, one point per month. */
    val yearlyEfficiency: StateFlow<List<DailyEfficiency>> = allTrips
        .map { it.toEfficiencyBuckets(12, "MMM", monthly = true) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ── Per-trip display metrics ──────────────────────────────────────────────

    /**
     * Pre-computed display metrics keyed by trip ID. Derived once and cached —
     * the LazyColumn in TripHistoryScreen does a map lookup, never arithmetic.
     */
    data class TripDisplayMetrics(
        val avgSpeedKmh:       Int?,
        val tripScore:         Int?,
        val regenEfficiencyPct: Double?,
        val tripCost:          Double?   // null when price not configured
    )

    val tripDisplayMetrics: StateFlow<Map<Long, TripDisplayMetrics>> =
        combine(allTrips, allTripStats, allChargingSessions, electricityPricePerKwh) { trips, stats, sessions, pricePerKwh ->
            val statsById = stats.associateBy { it.tripId }
            val blendedRates = CostAttribution.blendedTripRates(trips, sessions, pricePerKwh)
            trips.associate { trip ->
                val dist = trip.distance
                val dur  = trip.duration

                // Use the persisted trip-stat average so the history list matches the
                // detail screen. TripRepository now stores this as distance / duration,
                // which aligns with the trip summary shown by the car and companion apps.
                val avgSpeed = statsById[trip.id]?.avgSpeed
                    ?.takeIf { it > 0 }
                    ?.toInt()
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
                    val smoothAvg = avgSpeed?.toDouble() ?: dist / (dur / 3_600_000.0)
                    val smoothScore = if (trip.maxSpeed > 0)
                        ((smoothAvg / trip.maxSpeed) * 30).toInt().coerceIn(0, 30) else 0
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

                val rate = blendedRates[trip.id]
                val tripCost = if (rate != null && trip.energyConsumed != null)
                    trip.energyConsumed!! * rate else null

                trip.id to TripDisplayMetrics(avgSpeed, score, regenPct, tripCost)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

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
    // LEVEL 2 — TRIP_AVERAGE (this trip's cumulative consumption)
    //   Wh/km = clean trip-cumulative energy ÷ trip distance (tripWhPerKm), with the
    //   summed-speed-bin rate kept only as a fallback for the brief window before trip
    //   distance reaches BIN_MIN_DIST_KM ≈ 0.2 km. (Named TRIP_AVERAGE rather than the
    //   old "HISTORICAL_BINS": the value shown is the trip average, not a per-bin blend.)
    //   This is the pre-stabilisation bridge between LIFETIME/BASELINE and LIVE_TRIP:
    //   it provides a meaningful trip-derived rate within ~15 s of starting to drive.
    //   Activated only when Level 1 is not yet available.
    //
    // LEVEL 3 — LIFETIME_AVERAGE (personalised cold-start prior)
    //   Aggregate past-trip consumption: sum(energyConsumed) / sum(distance) across all
    //   completed TripEntity records (see lifetimeWhPerKm), gated to lifetime distance
    //   > LIFETIME_MIN_KM. Used when the current trip hasn't produced a usable rate yet
    //   (Level 2 unavailable, i.e. the first ~0.2 km, or before any energy is recorded):
    //   the driver's own lifetime average is a far better starting estimate than the
    //   catalog rate. Falls through to BASELINE when history is too thin.
    //
    // LEVEL 4 — BASELINE (static fallback)
    //   BYD Seal AWD Excellence WLTP-based static rate: BASELINE_WH_PER_KM = 185 Wh/km.
    //   Always available. Only used when all other levels fail.
    //
    // ── TODO Phase 2 ──────────────────────────────────────────────────────────
    // [ ] Merge past-trip speed bins with live bins (Bayesian prior)
    //     Each bin: mergedWhPerKm = (historicalSamples × historicalRate +
    //                                liveSamples × liveRate) /
    //               (historicalSamples + liveSamples)
    //     historicalRate comes from allTripStats.value
    //         .flatMap { it.energyConsumptionBySpeed.entries }
    //         grouped and averaged per bin key.
    //     As live data accumulates it dominates the prior automatically.
    // ─────────────────────────────────────────────────────────────────────────

    enum class RangeModel { LIVE_TRIP, TRIP_AVERAGE, LIFETIME_AVERAGE, BASELINE }

    companion object {
        // BATTERY_CAPACITY_KWH and BASELINE_WH_PER_KM are no longer hardcoded here —
        // both are derived from selectedCarConfig at runtime so all car models are correct.
        // Fallback values (used only when selectedCarConfig is not yet loaded):
        const val MIN_VALID_CONSUMPTION_KWH_PER_100KM = 7.0
        const val MAX_VALID_CONSUMPTION_KWH_PER_100KM = 35.0
        const val FALLBACK_BATTERY_KWH      = 82.56   // Seal AWD Excellence — worst case
        const val FALLBACK_BASELINE_WH_PER_KM = 185.0 // Seal AWD Excellence reference
        // Sanity floor for the projection divisor (Wh/km). Far below any sustained
        // real-world EV average (efficient production EVs cruise ~120–200 Wh/km; even
        // aggressive hypermiling rarely holds < 80), so it never clips genuine efficiency
        // — it exists only to stop a near-zero rate (a regen-heavy / downhill rolling
        // window, where net discharge ≈ 0) from dividing out to an absurd range.
        const val MIN_PLAUSIBLE_WH_PER_KM   = 40.0
        // Asymmetric optimism cap. The projected range may never claim you're driving more
        // than 1/OPTIMISM_CAP better than your own demonstrated reference rate (this trip's
        // average, else lifetime, else catalog). It bounds the descent balloon *structurally* —
        // independent of how the rolling window got low — while only ever flooring the
        // optimistic side: a rate ABOVE your reference (a climb, the "burning faster than
        // rated" warning) passes through untouched, so range is never over-stated toward
        // stranding. 0.65 ⇒ at most ~1.5× your average range. Raising it toward 1.0 makes the
        // curve steadier (this is the knob a future "projection stability" setting would drive).
        const val OPTIMISM_CAP              = 0.65
        // PHEV consumption-floor fraction. With the rate accumulators now gated on the
        // ICE predicate (see MD/PHEV_ICE_Aware_Projection.md), the measured EV Wh/km is
        // no longer ICE-diluted, so the old hard floor at 1.0 × baseline — which hid all
        // genuine below-rated EV efficiency — is softened: a PHEV may now demonstrate up
        // to ~30 % better than catalog before the floor bites. Kept > 0 as a transitional
        // safety net for pre-gating history and firmware that doesn't report fuel signals.
        const val PHEV_BASELINE_FLOOR_FRACTION = 0.7
        // Per-sample energy-delta ceiling (Wh per km travelled) when building the rolling
        // window's cumulative-energy series from per-sample deltas. Clips the kWh-scale steps a
        // mid-trip discharge-anchor rebase or a power↔BMS source switch would otherwise inject
        // into the window, and amortises idle drain (AC at a red light) instead of dumping it on
        // one 100 m sample — without touching real driving, which never approaches this rate.
        const val MAX_SAMPLE_WH_PER_KM      = 1500.0

        const val STABILISATION_KM      = 3.0    // km before Level 1 is trusted
        // 2.0 was too short: parking-lot crawl at trip start (high power, near-zero speed)
        // produces Wh/km of 500–1000+ that poisons the rolling window. 3km gives enough
        // real driving samples to dilute that noise (~1–3 min of normal driving).
        const val SAMPLE_INTERVAL_KM    = 0.1    // record a chart point every 100 m
        // Level 1 rolling window. 5 km (was 10) so the projection — live AND the
        // per-point reconstruction, which share this constant — tracks recent driving
        // instead of a long average. The old 10 km meant a short trip's window never
        // slid (it stayed cumulative-from-start), which is what made a reopened curve
        // read near-flat. EMA_ALPHA still smooths on top, so the line stays steady.
        // Only feeds the rolling-rate projection; trip distance/energy/bins are
        // computed independently and are unaffected.
        const val ROLLING_WINDOW_KM     = 5.0    // Level 1: rolling window length
        const val EMA_ALPHA             = 0.15   // Level 1: EMA smoothing factor
        const val MAX_DELTA_SECONDS     = 10.0   // discard Δt > this (reconnect / wake)
        // Min consecutive off-time before an engine-on transition resets the segment.
        // Prevents brief stops from wiping segment km. 5 min (was 90 s) so a long
        // red light, a traffic-control wait, or a short stop in P doesn't fragment a
        // continuous drive into a new segment. NOTE: only applies when the car reads
        // OFF (gear=P or the head unit drops carOn) — staying in D keeps isCarOn true,
        // so a red light held in D never resets the segment at any duration. This is
        // independent of the car-off TIMEOUT that ends/prompts the trip: with the
        // default 3 min timeout < 5 min, an off-break of 3–5 min still shows the
        // keep/stop prompt; keeping it leaves the drive as a single segment.
        const val SEGMENT_RESET_OFF_THRESHOLD_MS = 5 * 60_000L   // 5 min
        // Level 2: minimum *total* km accumulated across all populated speed bins
        // before the historical-bins rate is trusted. Lowered from 0.5 to 0.2 in
        // the rework that switched TRIP_AVERAGE from "current bin only" to
        // "cumulative across all bins". With the new aggregation we hit 0.2 km
        // within ~10–15 s of moving, which makes the tier a useful pre-stabilisation
        // bridge instead of a path that almost never fires.
        const val BIN_MIN_DIST_KM       = 0.2
        // Level 3: minimum lifetime distance (km) across completed trips before the
        // lifetime-average rate is trusted as a cold-start prior. Below this the history
        // is too thin to beat the catalog baseline, so we stay on BASELINE.
        const val LIFETIME_MIN_KM       = 50.0
        // PHEV: largest believable jump of the lifetime ICE-km counter between two
        // consecutive ticks. Anything above this is a counter reset, not driving —
        // mirrors PhevTripAnalysis.MAX_PAIR_MILEAGE_KM.
        const val MAX_ICE_COUNTER_STEP_KM = 50.0

        /** EV-only remainder of a distance step, and the ICE debt still owed after it. */
        data class IceWithholding(val evKm: Double, val debtKm: Double)

        /** Believable forward step of the lifetime ICE-km counter, else 0. */
        fun iceCounterStepKm(previous: Int?, current: Int?): Double {
            if (previous == null || current == null) return 0.0
            val step = (current - previous).toDouble()
            return if (step > 0.0 && step <= MAX_ICE_COUNTER_STEP_KM) step else 0.0
        }

        /**
         * Withholds outstanding ICE-counter debt from one distance step.
         *
         * The lifetime ICE-km counter steps in whole kilometres, so the tick where it
         * ticks can owe more distance than that tick actually drove. The surplus stays
         * on the books and is paid off by the ticks that follow, which makes the total
         * distance withheld across a trip equal the counter's own delta — not the far
         * larger stretch that merely had HEV mode selected.
         */
        fun withholdIceDebt(distKm: Double, debtKm: Double): IceWithholding {
            val owed = debtKm.coerceAtLeast(0.0)
            if (distKm <= 0.0) return IceWithholding(0.0, owed)
            if (owed <= 0.0) return IceWithholding(distKm, 0.0)
            val withheld = minOf(distKm, owed)
            return IceWithholding(distKm - withheld, owed - withheld)
        }
        private val SOUTHERN_HEMISPHERE_COUNTRY_CODES = setOf(
            "AU", "NZ", "ZA", "AR", "CL", "UY", "PY",
            "BW", "LS", "NA", "SZ", "ZW", "MZ", "MG"
        )

        internal enum class Hemisphere { NORTHERN, SOUTHERN }

        internal fun isUsableJourneyDistance(journeyDistanceKm: Double?): Boolean =
            journeyDistanceKm != null && journeyDistanceKm.isFinite() && journeyDistanceKm > 0.01

        internal fun hemisphereForCountry(countryCode: String?): Hemisphere =
            if (countryCode?.uppercase(Locale.ROOT) in SOUTHERN_HEMISPHERE_COUNTRY_CODES) {
                Hemisphere.SOUTHERN
            } else {
                Hemisphere.NORTHERN
            }

        internal fun currentHemisphere(): Hemisphere =
            hemisphereForCountry(Locale.getDefault().country)

        /**
         * EV range projection with a PHEV-aware consumption floor.
         *
         * Divides the remaining traction-battery energy by the measured
         * consumption to get electric range. The catch is PHEV-specific:
         * whenever the ICE is propelling the car or charge-sustaining, distance
         * keeps accruing while the traction battery barely discharges, so the
         * measured Wh/km (battery energy ÷ *total* distance) collapses far below
         * what the car can physically achieve on electrons alone. Dividing the
         * remaining pack energy by that deflated rate balloons the projection to
         * multiples of reality — e.g. 87 km projected at 19 % SoC against a BMS
         * reading of 10 km, the bug this guards against.
         *
         * The rate accumulators are gated on the ICE predicate upstream (see
         * MD/PHEV_ICE_Aware_Projection.md), so [whPerKm] reflects electric
         * driving only. The PHEV floor is therefore *soft* — [baselineWhPerKm] ×
         * [PHEV_BASELINE_FLOOR_FRACTION] — a transitional safety net for history
         * recorded before the fuel signals were persisted (and firmware that
         * never reports them), while letting genuine hypermiling project up to
         * ~30 % better than catalog. An inefficient driver still projects
         * *below* the SoC-scaled WLTP range (the useful "you're burning EV
         * charge faster than rated" signal is preserved).
         *
         * BEVs are unaffected: every kilometre is an EV kilometre, so the measured
         * rate is already honest and the chart's WLTP result-cap handles any
         * genuine over-projection on its own.
         */
        internal fun projectedEvRangeKm(
            remainingEnergyWh: Double,
            whPerKm: Double,
            baselineWhPerKm: Double,
            isPhev: Boolean,
            referenceWhPerKm: Double? = null
        ): Double {
            // Softened floor (PHEV_BASELINE_FLOOR_FRACTION × baseline): the rate inputs are
            // ICE-gated upstream, so a genuinely-below-rated EV rate is real and may project
            // above the SoC-scaled catalog figure; the fractional floor only catches the
            // residual dilution on trips/firmware without fuel signals.
            val phevFloored =
                if (isPhev) maxOf(whPerKm, baselineWhPerKm * PHEV_BASELINE_FLOOR_FRACTION)
                else whPerKm
            // A non-positive rate is a "no valid data" signal (callers never pass one in
            // practice — the live/restore tiers all resolve to a positive rate) → return 0
            // rather than NaN/Infinity.
            if (phevFloored <= 0.0) return 0.0
            // Optimism cap: bound the projection to at most 1/OPTIMISM_CAP better than the
            // caller's demonstrated reference rate (this trip's / lifetime average). This is
            // what structurally contains a downhill balloon — a rolling window that has drifted
            // far below your own average can't project a fantasy range. Asymmetric: a rate above
            // the reference (a climb) is left alone, so the pessimistic "burning faster than
            // rated" signal is never dampened. Absent a reference it's a no-op.
            val optimismFloored =
                if (referenceWhPerKm != null && referenceWhPerKm > 0.0)
                    maxOf(phevFloored, OPTIMISM_CAP * referenceWhPerKm)
                else phevFloored
            // Sanity floor against a *small-positive* rate exploding the quotient. totalDischarge
            // is net-of-regen, so a sustained downhill / heavy-regen rolling window can drive the
            // measured Wh/km toward 0; dividing remaining energy by that yields an absurd range.
            // Kept as the absolute backstop for when no reference rate is available (the optimism
            // cap above is the adaptive, usually-tighter bound). It sits far below normal driving,
            // so it only bites in the broken near-zero case.
            val rate = optimismFloored.coerceAtLeast(MIN_PLAUSIBLE_WH_PER_KM)
            return (remainingEnergyWh / rate).coerceAtLeast(0.0)
        }

        /**
         * Remaining traction-battery energy (Wh) feeding the EV range projection.
         *
         * BEVs use catalog capacity × SoC — the whole pack is available for
         * propulsion, so that product is honest. This path is deliberately left
         * unchanged: a BEV never reaches the PHEV branch below.
         *
         * PHEVs differ near the bottom of the charge. The BMS reserves part of the
         * pack for charge-sustaining / hybrid operation, so `usableKwh × SoC%`
         * overstates the energy actually available for EV driving — 19 % of a
         * 44 kWh Tang DM-i pack computes ~8 kWh, yet the car offers only ~10 km of
         * EV range. When the BMS publishes its own remaining-EV-energy figure
         * ([bmsRemainingEvKwh], from `power_battery_remain_power_ev`) we trust it
         * instead, since it already nets out that reserve. The value is accepted
         * only inside a sane envelope (0 < x ≤ pack capacity); outside it — or when
         * the firmware never reports it — we fall back to the SoC product. So the
         * change can only improve a PHEV numerator and can never regress one, and
         * it never touches BEVs.
         */
        internal fun remainingEvEnergyWh(
            batteryKwh: Double,
            socPercent: Double,
            bmsRemainingEvKwh: Double?,
            isPhev: Boolean
        ): Double {
            if (isPhev && bmsRemainingEvKwh != null &&
                bmsRemainingEvKwh > 0.0 && bmsRemainingEvKwh <= batteryKwh
            ) {
                return bmsRemainingEvKwh * 1000.0
            }
            return (batteryKwh * 1000.0 * (socPercent / 100.0)).coerceAtLeast(0.0)
        }

        // Tolerance when matching the cached curve's reach against the live distance.
        private const val CACHE_DISTANCE_EPSILON_KM = 0.05

        /**
         * Stitch the cached live projection curve ([cached], kept by
         * [com.byd.tripstats.data.repository.LiveProjectionCache]) together with the
         * curve [rebuilt] from the database in restoreTripState.
         *
         * The cache holds the curve *as it was computed live*, point by point — far
         * higher fidelity than the rebuild, which has to apply one consumption rate
         * to the whole trip. But it can lag the database: if the ViewModel (or the
         * whole process) died mid-trip, driving continued and the database kept
         * recording while the cache did not. So we keep the cached head and append
         * only the rebuilt tail beyond the cache's reach — preserving live fidelity
         * for the covered portion, filling the gap with the rebuild, and never
         * dropping or duplicating distance:
         *
         *  - No usable cache → return [rebuilt] unchanged (today's behaviour).
         *  - Cache fresh (covers up to the live distance) → tail is empty, so the
         *    curve is the cached one verbatim — the back-press reopen case.
         *  - Cache stale (process was killed earlier) → cached head + recomputed
         *    tail for the kilometres driven while it was dead.
         *
         * [cached] points beyond [liveDistanceKm] are dropped as defensive hygiene
         * against an out-of-date cache reading ahead of reality.
         */
        internal fun mergeProjectionCurve(
            cached: List<RangeDataPoint>?,
            rebuilt: List<RangeDataPoint>,
            liveDistanceKm: Double
        ): List<RangeDataPoint> {
            if (cached.isNullOrEmpty()) return rebuilt
            val head = cached
                .filter { it.distanceKm <= liveDistanceKm + CACHE_DISTANCE_EPSILON_KM }
                .sortedBy { it.distanceKm }
            if (head.isEmpty()) return rebuilt
            val headMaxDist = head.last().distanceKm
            val tail = rebuilt.filter { it.distanceKm > headMaxDist }
            return head + tail
        }

        /**
         * Replays the live rolling-window consumption rate across an ordered series of
         * cumulative ([distanceKm] to [cumulativeEnergyWh]) [samples], returning the
         * per-sample Wh/km the live loop would have held at each point — a trailing
         * [windowKm] window with [emaAlpha] EMA smoothing. `null` where no rate exists
         * yet (no positive consumption inside the window).
         *
         * This is what lets restoreTripState rebuild a projection curve that varies the
         * way the live curve did, rather than applying one trip-average rate to every
         * point (which renders near-flat: SoC barely moves over a short trip, so the
         * shape lives in how the rate changes, not in SoC). [samples] must be ordered by
         * non-decreasing distance — the caller builds them that way from stored points.
         */
        /**
         * Least-squares slope (Wh/km) of cumulative energy vs distance over [samples]
         * (distance → cumulative energy Wh). Returns the positive slope, or `null` when there
         * are fewer than two points, zero distance spread, or a non-positive slope.
         *
         * Used instead of a two-endpoint difference (`last − first`): with the BMS discharge
         * counter moving in coarse quanta, an endpoint-only estimate takes the full quantization
         * error on each of its two samples, so the rolling rate — and the 1/rate projection —
         * jitters as samples enter and leave the window. A regression averages that noise across
         * all ~50 window points, giving a much steadier rate for the same underlying data.
         */
        internal fun windowSlopeWhPerKm(samples: List<Pair<Double, Double>>): Double? {
            val n = samples.size
            if (n < 2) return null
            var sumD = 0.0; var sumE = 0.0
            for (s in samples) { sumD += s.first; sumE += s.second }
            val meanD = sumD / n; val meanE = sumE / n
            var sxx = 0.0; var sxy = 0.0
            for (s in samples) {
                val dx = s.first - meanD
                sxx += dx * dx
                sxy += dx * (s.second - meanE)
            }
            if (sxx <= 1e-9) return null
            return (sxy / sxx).takeIf { it > 0.0 }
        }

        internal fun rollingWhPerKmSeries(
            samples: List<Pair<Double, Double>>,
            windowKm: Double = ROLLING_WINDOW_KM,
            emaAlpha: Double = EMA_ALPHA
        ): List<Double?> {
            val out = ArrayList<Double?>(samples.size)
            var ema: Double? = null
            var windowStart = 0
            for (i in samples.indices) {
                val di = samples[i].first
                while (windowStart < i && samples[windowStart].first < di - windowKm) {
                    windowStart++
                }
                // Least-squares slope over the whole window (subList is a view, no copy),
                // mirroring the live loop so the rebuilt curve stays a faithful twin.
                val raw = if (i > windowStart)
                    windowSlopeWhPerKm(samples.subList(windowStart, i + 1)) else null
                if (raw != null) {
                    ema = ema?.let { emaAlpha * raw + (1.0 - emaAlpha) * it } ?: raw
                }
                out.add(ema)
            }
            return out
        }

        internal fun seasonForMonth(month: Int, hemisphere: Hemisphere): Season = when (month) {
            3, 4, 5 -> if (hemisphere == Hemisphere.SOUTHERN) Season.AUTUMN else Season.SPRING
            6, 7, 8 -> if (hemisphere == Hemisphere.SOUTHERN) Season.WINTER else Season.SUMMER
            9, 10, 11 -> if (hemisphere == Hemisphere.SOUTHERN) Season.SPRING else Season.AUTUMN
            12, 1, 2 -> if (hemisphere == Hemisphere.SOUTHERN) Season.SUMMER else Season.WINTER
            else -> Season.entries.first()
        }

        internal fun deriveLiveSessionAnchorOdometer(
            odometerKm: Double,
            journeyDistanceKm: Double?
        ): Double =
            if (isUsableJourneyDistance(journeyDistanceKm)) {
                (odometerKm - journeyDistanceKm!!).coerceAtLeast(0.0)
            } else {
                odometerKm
            }

        internal fun resolveLiveSessionDistanceKm(
            odometerKm: Double,
            anchorOdometerKm: Double?,
            journeyDistanceKm: Double?
        ): Double {
            val odometerDistanceKm = anchorOdometerKm
                ?.let { (odometerKm - it).coerceAtLeast(0.0) }
            return when {
                odometerDistanceKm != null ->
                    odometerDistanceKm
                isUsableJourneyDistance(journeyDistanceKm) ->
                    journeyDistanceKm!!
                else -> 0.0
            }
        }

        internal fun integrateDistanceKm(speedKmh: Double, deltaSeconds: Double): Double =
            if (speedKmh.isFinite() && deltaSeconds.isFinite() && deltaSeconds > 0.0) {
                maxOf(0.0, speedKmh) * (deltaSeconds / 3600.0)
            } else {
                0.0
            }
    }

    // Rolling buffer entry: cumulative values at a given distance milestone
    private data class EnergySample(val distanceKm: Double, val cumulativeEnergyWh: Double)

    // Live speed-bin accumulator — updated on every telemetry tick for fine granularity
    private data class BinAccumulator(var energyWh: Double = 0.0, var distanceKm: Double = 0.0)

    private val _tripDataPoints  = MutableStateFlow<List<RangeDataPoint>>(emptyList())
    val tripDataPoints: StateFlow<List<RangeDataPoint>> = _tripDataPoints.asStateFlow()

    // Live drive-session distance — updated every telemetry tick, not gated by SAMPLE_INTERVAL_KM.
    // Use this in the UI instead of remember { telemetry.odometer } which resets on
    // recomposition after navigation.
    private val _liveDistanceKm = MutableStateFlow(0.0)
    val liveDistanceKm: StateFlow<Double> = _liveDistanceKm.asStateFlow()

    // Live distance since the most recent engine-on segment.
    private val _liveSegmentDistanceKm = MutableStateFlow(0.0)
    val liveSegmentDistanceKm: StateFlow<Double> = _liveSegmentDistanceKm.asStateFlow()

    // Wall-clock ms when the current trip started (original start, survives resumes).
    private val _liveSessionStartMs = MutableStateFlow<Long?>(null)
    val liveSessionStartMs: StateFlow<Long?> = _liveSessionStartMs.asStateFlow()

    /**
     * Accumulated ms during the current trip when the car was off but the trip
     * stayed open (engine-off resume windows). Mirrors [TripEntity.offStateDurationMs]
     * for the live drive — subtracted from the displayed TIME and AVG in the Trip
     * Tracking card so they reflect actual driving rather than driving + parked.
     * Resets to 0 at every fresh trip start.
     */
    private val _liveOffStateMs = MutableStateFlow(0L)
    val liveOffStateMs: StateFlow<Long> = _liveOffStateMs.asStateFlow()

    // Accumulated energy discharge for the current trip in kWh.
    private val _liveAccumulatedKwh = MutableStateFlow(0.0)
    val liveAccumulatedKwh: StateFlow<Double> = _liveAccumulatedKwh.asStateFlow()

    private val _activeRangeModel = MutableStateFlow(RangeModel.BASELINE)
    val activeRangeModel: StateFlow<RangeModel> = _activeRangeModel.asStateFlow()

    /** Sets the active range-model tier, logging only on a genuine transition (e.g.
     *  BASELINE → LIFETIME_AVERAGE → TRIP_AVERAGE → LIVE_TRIP, and any fallback) so an
     *  unexpectedly low-tier projection on a given car/firmware is diagnosable from logs.
     *  Per-tick no-op when the tier is unchanged, so the high-frequency loop never spams. */
    private fun setActiveRangeModel(model: RangeModel) {
        if (_activeRangeModel.value != model) {
            Log.i(TAG, "Range model: ${_activeRangeModel.value} → $model")
        }
        _activeRangeModel.value = model
    }

    // Odometer-only trip distance (no integration fallback). Used for avg speed display
    // to match the formula the finalized trip stats will use (endOdometer - startOdometer).
    private val _liveOdometerDistanceKm = MutableStateFlow(0.0)
    val liveOdometerDistanceKm: StateFlow<Double> = _liveOdometerDistanceKm.asStateFlow()

    private var liveSessionStartOdometer: Double? = null
    private var segmentStartOdometer: Double? = null
    private var lastTelemetryTimeMs: Long?    = null
    private var lastBinOdo:          Double?  = null
    /** Tracks the previous tick's [VehicleTelemetry.totalDischarge] so we can derive a
     *  per-tick BMS energy delta — preferred over power-integrated when available because
     *  on some firmwares (e.g. Seal Excellence) [VehicleTelemetry.enginePower] is reported
     *  as ~0 for long stretches, starving the rolling-window and bin accumulators and
     *  pinning the projection in BASELINE forever. */
    private var lastTotalDischargeKwh: Double? = null
    /** Previous sample's raw cumulative trip energy (Wh), so the rolling-window series is built
     *  from clamped per-sample deltas (monotone, spike-clipped) rather than raw values. */
    private var lastSampleRawEnergyWh: Double? = null
    /** Whether the optimism cap was flooring the rate last tick — logged only on transition. */
    private var optimismCapBiting: Boolean = false
    private var integratedDistanceKm: Double  = 0.0
    private var integratedSegmentDistanceKm: Double = 0.0
    // Trip-cumulative *net* traction energy (signed): positive power × dt
    // accumulates, negative power × dt (regen) subtracts. NOT used for the
    // user-facing "energy consumed" total — that comes from the BMS
    // totalDischarge counter (see _liveAccumulatedKwh below) so it matches
    // what the post-trip overview screen stores and what reference apps
    // like Electro report for the same drive. This accumulator's role is
    // purely the (distance, energy) sample series feeding the LIVE_TRIP
    // range projection's rolling-window Wh/km — power-integration keeps
    // those samples decoupled from BMS lumpiness so the projection tier
    // engages reliably on cars whose BMS counter is sluggish.
    private var accumulatedEnergyWh: Double   = 0.0
    private var smoothedWhPerKm:     Double?  = null  // Level 1 EMA state
    private val energySamples      = mutableListOf<EnergySample>()
    private val liveSpeedBins      = mutableMapOf<String, BinAccumulator>()
    // PHEV ICE gating (MD/PHEV_ICE_Aware_Projection.md): distance covered while the
    // ICE propels the car must not feed the EV consumption rate, or the rate deflates
    // and the EV projection balloons. These EV-only distance axes replace the trip
    // axes in the rate accumulators on PHEVs; on BEVs every km is an EV km, so the
    // EV axes track the trip axes exactly and nothing changes.
    private var evTripDistanceKm    = 0.0            // EV-only analogue of the trip distance
    private var evWindowDistanceKm  = 0.0            // EV-only x-axis of the rolling window
    private var lastSampleTripDistKm: Double? = null // trip distance at the previous window sample
    private var lastIceMileageKm: Int? = null        // previous tick's lifetime ICE-km counter
    // ICE kilometres reported by the counter but not yet withheld from the EV axes.
    // Two independent pools because the trip/bin axis and the rolling-window axis
    // consume different distance measures on the same tick (see evShareOf*Distance).
    private var iceDebtTripKm   = 0.0
    private var iceDebtWindowKm = 0.0
    private var lastTelemetryWasCarOn: Boolean? = null
    // Wall-clock time (telemetry ms) when the car first appeared off during the
    // active live drive session. Used to gate the segment-reset-on-engine-on so
    // that brief stops (red lights, momentary key cycles) don't wipe the segment.
    private var segmentOffSinceMs: Long? = null

    // Set by endManualTrip(). Consumed on next collect tick to force-clear the live
    // runtime even when the debounce window collapses a stop+auto-restart into a
    // single emission (tripOpenNow never appears false, so the edge detector below
    // wouldn't otherwise fire).
    @Volatile private var manualStopResetPending: Boolean = false

    // When true, the next live-drive session (auto-start after a manual stop, or
    // a fresh manual start) ignores the car's currentJourneyDriveMileage and
    // anchors cumulative/segment distance at the current odometer. Otherwise the
    // journey-distance back-calc would rewind the anchor to the car-on moment,
    // so cumulative would jump back to the pre-stop value on the next tick.
    @Volatile private var forceFreshAnchorNextSession: Boolean = false

    // When true, the active live-drive session ignores currentJourneyDriveMileage
    // entirely and tracks distance purely by odometer delta from the fresh anchor.
    // Set by beginLiveDriveSession / restoreTripState when forceFreshAnchorNextSession
    // was honoured; cleared on a natural session end.
    private var suppressJourneyDistance: Boolean = false

    // Promoted to class scope so endManualTrip can force-reset it alongside
    // liveSessionStartOdometer. Keep these in sync with the local mirrors inside
    // the combine block — they move together.
    private var liveDriveSessionActive: Boolean = false
    // True once restoreTripState has completed for the current trip, so we don't
    // re-restore on every inTrip && !wasInTrip edge during the same engine-on cycle.
    private var sessionRestoredFromDb: Boolean = false

    private val rawPointJson = Json { ignoreUnknownKeys = true }

    /** Mirror of TripRepository.speedBin — kept in sync manually. */
    private fun speedBin(speed: Double) = when {
        speed <  20 -> "0-20"
        speed <  40 -> "20-40"
        speed <  60 -> "40-60"
        speed <  80 -> "60-80"
        speed < 100 -> "80-100"
        else        -> "100+"
    }

    private fun effectiveSpeed(telemetry: VehicleTelemetry): Double =
        maxOf(telemetry.speed, telemetry.locationGpsSpeed ?: 0.0)

    /**
     * Coarse ICE-propulsion predicate for one tick / stored point — the **fallback**
     * used only when the car doesn't expose the lifetime ICE-km counter at all.
     *
     * It reads energy_mode 3 (HEV) / 4 (Fuel) / 5 (Keep, SOC hold) as fully ICE-propelled, which is a
     * substantial over-estimate on a series hybrid: measured against a recorded
     * Shark 6 drive, HEV mode covered 47 km of which the car's own counters
     * attribute only 22 km to the ICE — the pack propelled the rest. On firmwares
     * where instant_fuel_consumption is alive it disambiguates; where it reads a
     * constant 0 (Shark 6 DM-O) the mode flag is all that's left. Over-excluding
     * distance while keeping the energy errs the rate HIGH, so range is never
     * over-stated — but prefer [iceCounterStepKm] whenever the counter exists
     * (see MD/PHEV_ICE_Aware_Projection.md).
     */
    private fun isIceActive(instantFuelConsumption: Double?, energyMode: Int): Boolean =
        (instantFuelConsumption ?: 0.0) > 0.0 ||
            energyMode == 3 || energyMode == 4 || energyMode == 5

    /**
     * Restore-path analogue of [isIceActive] for a stored data point: reads the
     * fuel/energy-mode signals from the point's rawJson blob (persisted per point
     * since the PHEV fields were added to toSchemaJson). Points recorded before
     * that — or with no PHEV signals — read as electric, which reproduces the
     * pre-gating behaviour for old history.
     */
    private fun pointIceActive(rawJson: String): Boolean {
        if (rawJson.isBlank() || rawJson == "{}") return false
        val obj = runCatching { rawPointJson.parseToJsonElement(rawJson).jsonObject }
            .getOrNull() ?: return false
        val instantFuel = obj["instant_fuel_consumption"]?.jsonPrimitive?.doubleOrNull
        val energyMode = obj["energy_mode"]?.jsonPrimitive?.intOrNull ?: 0
        return isIceActive(instantFuel, energyMode)
    }

    /** Restore-path reader for a stored point's lifetime ICE-km counter. */
    private fun pointIceMileageKm(rawJson: String): Int? {
        if (rawJson.isBlank() || rawJson == "{}") return null
        val obj = runCatching { rawPointJson.parseToJsonElement(rawJson).jsonObject }
            .getOrNull() ?: return null
        return obj["ice_mileage_km"]?.jsonPrimitive?.intOrNull
    }

    /**
     * Withholds outstanding ICE-counter debt from [distKm], returning the EV-only
     * remainder that may advance the trip/bin rate axis and updating the debt pool.
     */
    private fun evShareOfTripDistance(distKm: Double): Double {
        val (evKm, debtKm) = withholdIceDebt(distKm, iceDebtTripKm)
        iceDebtTripKm = debtKm
        return evKm
    }

    /** [evShareOfTripDistance] for the rolling-window axis, which keeps its own debt pool. */
    private fun evShareOfWindowDistance(distKm: Double): Double {
        val (evKm, debtKm) = withholdIceDebt(distKm, iceDebtWindowKm)
        iceDebtWindowKm = debtKm
        return evKm
    }

    private fun journeyDistanceKm(telemetry: VehicleTelemetry): Double? =
        if (suppressJourneyDistance) null
        else telemetry.currentJourneyDriveMileage?.takeIf { isUsableJourneyDistance(it) }

    private fun shouldStartLiveDriveSession(inTrip: Boolean, telemetry: VehicleTelemetry): Boolean =
        inTrip ||
            effectiveSpeed(telemetry) > 0.5 ||
            telemetry.enginePower > 1 ||
            telemetry.gear in listOf("D", "R")

    private fun shouldContinueLiveDriveSession(
        inTrip: Boolean,
        tripOpen: Boolean,
        telemetry: VehicleTelemetry
    ): Boolean =
        inTrip ||
            tripOpen ||
            telemetry.isCarOn ||
            effectiveSpeed(telemetry) > 0.5 ||
            telemetry.enginePower > 1 ||
            telemetry.gear in listOf("D", "R")

    private fun currentLiveSessionDistanceKm(telemetry: VehicleTelemetry): Double =
        resolveLiveSessionDistanceKm(
            odometerKm = telemetry.odometer,
            anchorOdometerKm = liveSessionStartOdometer,
            journeyDistanceKm = journeyDistanceKm(telemetry)
        )

    private fun currentLiveSegmentDistanceKm(telemetry: VehicleTelemetry): Double {
        val anchor = segmentStartOdometer ?: return 0.0
        return (telemetry.odometer - anchor).coerceAtLeast(0.0)
    }

    private fun beginLiveDriveSession(
        telemetry: VehicleTelemetry,
        telemetryMs: Long,
        clearPoints: Boolean
    ) {
        val freshAnchor = forceFreshAnchorNextSession
        if (freshAnchor) {
            forceFreshAnchorNextSession = false
            suppressJourneyDistance = true
        }
        val isFirstTripStart = liveSessionStartOdometer == null
        if (isFirstTripStart) {
            liveSessionStartOdometer = if (freshAnchor) telemetry.odometer
            else deriveLiveSessionAnchorOdometer(
                odometerKm = telemetry.odometer,
                journeyDistanceKm = journeyDistanceKm(telemetry)
            )
        }
        segmentStartOdometer = if (freshAnchor) telemetry.odometer
        else deriveLiveSessionAnchorOdometer(
            odometerKm = telemetry.odometer,
            journeyDistanceKm = journeyDistanceKm(telemetry)
        )
        lastTelemetryTimeMs = telemetryMs
        lastBinOdo = telemetry.odometer
        lastTotalDischargeKwh = telemetry.totalDischarge.takeIf { it.isFinite() && it > 0.0 }
        if (isFirstTripStart) {
            integratedDistanceKm = 0.0
            accumulatedEnergyWh = 0.0
            _liveAccumulatedKwh.value = 0.0
            _liveOdometerDistanceKm.value = 0.0
            smoothedWhPerKm = null
            lastSampleRawEnergyWh = null
            optimismCapBiting = false
            energySamples.clear()
            liveSpeedBins.clear()
            evTripDistanceKm = 0.0
            evWindowDistanceKm = 0.0
            lastSampleTripDistKm = null
            lastIceMileageKm = null
            iceDebtTripKm = 0.0
            iceDebtWindowKm = 0.0
            // Back-date the session start to match the back-anchored odometer so
            // avgSpeed = journeyKm / journeyTime rather than journeyKm / a-few-seconds
            // (which produces 400+ km/h for the first minute after the app opens mid-drive).
            // Use the car's own journey drive-time counter when available; otherwise
            // estimate elapsed drive time from journey distance and current speed.
            val journeyKmForBackdate = if (!freshAnchor) journeyDistanceKm(telemetry) else null
            val rawJourneyTimeMs = journeyKmForBackdate
                ?.takeIf { it > 0.0 }
                ?.let {
                    telemetry.currentJourneyDriveTime
                        ?.takeIf { t -> t.isFinite() && t > 0.0 }
                        ?.let { t -> (t * 60_000.0).toLong() }
                }
            // Guard: on some firmwares the journey mileage counter resets slower than the
            // journey time counter. At the start of a new trip the mileage still shows the
            // previous journey's total while the time has just reset to near-zero, producing
            // an implied average speed that is physically impossible (> 200 km/h). When this
            // happens, treat both counters as stale: reset the odometer anchor to the current
            // position so distance starts at 0, and start the clock from now.
            val journeyCountersStale = journeyKmForBackdate != null
                && rawJourneyTimeMs != null && rawJourneyTimeMs > 0L
                && journeyKmForBackdate / (rawJourneyTimeMs / 3_600_000.0) > 200.0
            if (journeyCountersStale) {
                liveSessionStartOdometer = telemetry.odometer
                segmentStartOdometer = telemetry.odometer
            }
            val backdateMs = if (!journeyCountersStale && journeyKmForBackdate != null && journeyKmForBackdate > 0.0) {
                rawJourneyTimeMs
                    ?: run {
                        val speedKmh = effectiveSpeed(telemetry).coerceAtLeast(1.0)
                        ((journeyKmForBackdate / speedKmh) * 3_600_000.0).toLong()
                    }
            } else 0L
            _liveSessionStartMs.value = System.currentTimeMillis() - backdateMs
            _liveOffStateMs.value = 0L
        }
        integratedSegmentDistanceKm = 0.0
        val initialTripDistanceKm = currentLiveSessionDistanceKm(telemetry)
        _liveDistanceKm.value = initialTripDistanceKm
        _liveSegmentDistanceKm.value = if (isFirstTripStart) initialTripDistanceKm else 0.0
        setActiveRangeModel(RangeModel.BASELINE)
        if (clearPoints) {
            _tripDataPoints.value = listOf(
                RangeDataPoint(
                    distanceKm = initialTripDistanceKm,
                    soc = telemetry.soc.takeIf { it > 0 } ?: telemetry.socPanel.toDouble(),
                    electricDrivingRangeKm = telemetry.electricDrivingRangeKm,
                    projectedRangeKm = null,
                    isStabilised = initialTripDistanceKm >= STABILISATION_KM
                )
            )
        }
        lastTelemetryWasCarOn = telemetry.isCarOn
        segmentOffSinceMs = null
        Log.i(
            TAG,
            "Live drive session started: distance=${"%.2f".format(initialTripDistanceKm)}km " +
                "journey=${journeyDistanceKm(telemetry)} odometer=${telemetry.odometer}"
        )
    }

    private fun clearLiveDriveRuntime(keepPoints: Boolean) {
        sessionRestoredFromDb = false
        liveSessionStartOdometer = null
        segmentStartOdometer = null
        lastTelemetryTimeMs = null
        lastBinOdo = null
        lastTotalDischargeKwh = null
        integratedDistanceKm = 0.0
        integratedSegmentDistanceKm = 0.0
        accumulatedEnergyWh = 0.0
        _liveAccumulatedKwh.value = 0.0
        _liveOdometerDistanceKm.value = 0.0
        smoothedWhPerKm = null
        lastSampleRawEnergyWh = null
        optimismCapBiting = false
        energySamples.clear()
        liveSpeedBins.clear()
        evTripDistanceKm = 0.0
        evWindowDistanceKm = 0.0
        lastSampleTripDistKm = null
        lastIceMileageKm = null
        iceDebtTripKm = 0.0
        iceDebtWindowKm = 0.0
        _liveDistanceKm.value = 0.0
        _liveSegmentDistanceKm.value = 0.0
        _liveSessionStartMs.value = null
        _liveOffStateMs.value = 0L
        // Only reset the active model badge when we're also clearing the chart
        // (full reset). When keepPoints=true the chart still shows the trip's
        // LIVE_TRIP-stage projections from earlier in the drive; resetting the
        // badge to BASELINE in that state lies to the user about what the
        // displayed line was computed from.
        if (!keepPoints) {
            setActiveRangeModel(RangeModel.BASELINE)
        }
        lastTelemetryWasCarOn = null
        segmentOffSinceMs = null
        suppressJourneyDistance = false
        if (!keepPoints) {
            _tripDataPoints.value = emptyList()
            // The trip ended (or a new trip replaced it) — drop the cached curve so a
            // later open can't restore a finished trip's line into a fresh one.
            liveProjectionCache.clear()
        }
    }

    // ── Init ──────────────────────────────────────────────────────────────────

    init {
        // One-time (2.9.1): backfill precise SoH from each data point's rawJson into the
        // sohPrecise column so the degradation chart/report are decimal-accurate across all
        // history (no false up-tick where rounded data meets the new precise recording).
        // Runs off the main thread and only once — cheap no-op on later launches.
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val prefs = getApplication<Application>()
                    .getSharedPreferences("maintenance", android.content.Context.MODE_PRIVATE)
                if (!prefs.getBoolean("soh_precise_backfill_done", false)) {
                    tripRepository.backfillPreciseSoh()
                    prefs.edit().putBoolean("soh_precise_backfill_done", true).apply()
                }
            }
        }

        viewModelScope.launch {
            var wasInTrip = false
            var wasTripOpen = false
            var lastSeenTripId: Long? = null

            combine(isInTrip, currentTelemetry) { inTrip, telemetry ->
                inTrip to telemetry
            }
            .debounce(500L)   // coalesce rapid emissions — recompose at most twice/second
            .collect { (inTrip, telemetry) ->
                if (telemetry == null) return@collect

                // Manual stop was requested. The debounce window may collapse the
                // stop+auto-restart into a single emission where tripOpenNow is
                // already true again, so the edge detector below can't see it.
                // Honouring an explicit flag guarantees the live runtime is cleared.
                if (manualStopResetPending) {
                    manualStopResetPending = false
                    clearLiveDriveRuntime(keepPoints = false)
                    liveDriveSessionActive = false
                    wasInTrip = false
                    wasTripOpen = currentTripId.value != null
                    lastSeenTripId = currentTripId.value
                    return@collect
                }

                // Trip just ended (manual stop or auto-end). Clear live runtime so a
                // subsequent auto-start begins from a fresh anchor rather than
                // continuing the previous cumulative distance.
                val tripOpenNow = currentTripId.value != null
                if (wasTripOpen && !tripOpenNow) {
                    clearLiveDriveRuntime(keepPoints = false)
                    liveDriveSessionActive = false
                    wasInTrip = false
                    wasTripOpen = false
                    lastSeenTripId = null
                    return@collect
                }
                wasTripOpen = tripOpenNow

                // Trip id changed under us (e.g. user tapped Manual Start while an
                // auto trip was active — repo closed the auto trip and opened a
                // fresh manual one in the same handler, so we never saw the null
                // gap). Reset the live session so the new trip gets fresh anchors
                // rather than inheriting the previous trip's cumulative distance.
                val curTripId = currentTripId.value
                if (curTripId != null && lastSeenTripId != null && curTripId != lastSeenTripId) {
                    clearLiveDriveRuntime(keepPoints = false)
                    liveDriveSessionActive = false
                    wasInTrip = false
                    lastSeenTripId = curTripId
                    // fall through to the inTrip handler below so the new trip's
                    // restoreTripState fires on this same tick
                }
                if (curTripId != null) lastSeenTripId = curTripId

                // Parse telemetry timestamp — more accurate than system clock.
                // Bound-check against system time: if the ECU sends local time with a
                // 'Z' suffix (treating local as UTC), the parsed epoch is off by the
                // timezone offset (e.g. +2h). A timestamp that far from reality would
                // make offDurationMs huge on the first car-on tick, falsely resetting
                // the segment. Reject anything > 6 hours from system time and fall back.
                val sysNow = System.currentTimeMillis()
                val telemetryMs = runCatching {
                    java.time.Instant.parse(telemetry.currentDatetime).toEpochMilli()
                }.getOrNull()?.takeIf { kotlin.math.abs(it - sysNow) <= 6 * 60 * 60 * 1000L }
                    ?: sysNow

                // Charging telemetry is handled by the vehicle service (service-level),
                // so it survives Activity death and car-off scenarios.

                if (inTrip && !wasInTrip) {
                    val existingTripId = currentTripId.value
                    // Restore whenever we transition into in-trip and haven't yet synced
                    // from the DB for this engine-on cycle. This covers:
                    //  (a) fresh ViewModel start (service restart in Minimal/Deep Sleep)
                    //  (b) the case where beginLiveDriveSession fired before inTrip=true
                    //      propagated — the session was initialised incorrectly (energy=0,
                    //      wrong anchor) and must be overwritten with DB truth.
                    if (existingTripId != null && !sessionRestoredFromDb) {
                        liveDriveSessionActive = true
                        sessionRestoredFromDb = true   // guard against re-entry this cycle
                        restoreTripState(existingTripId, telemetry, telemetryMs)
                        wasInTrip = inTrip
                        return@collect
                    }
                    // Race: repository says we're in a trip but currentTripId hasn't
                    // propagated yet. Skip this tick — the next emission will have the id
                    // and we'll enter restoreTripState above. Starting a fresh session here
                    // would overwrite liveSessionStartOdometer with the current odometer,
                    // zeroing the cumulative distance.
                    if (existingTripId == null && !liveDriveSessionActive) {
                        return@collect
                    }
                }

                if (!liveDriveSessionActive && shouldStartLiveDriveSession(inTrip, telemetry)) {
                    beginLiveDriveSession(
                        telemetry = telemetry,
                        telemetryMs = telemetryMs,
                        clearPoints = true
                    )
                    liveDriveSessionActive = true
                }

                if (liveDriveSessionActive) {
                    val tripOpen = currentTripId.value != null
                    if (!shouldContinueLiveDriveSession(inTrip, tripOpen, telemetry)) {
                        clearLiveDriveRuntime(keepPoints = true)
                        liveDriveSessionActive = false
                        wasInTrip = inTrip
                        return@collect
                    }

                    if (!telemetry.isCarOn) {
                        if (segmentOffSinceMs == null) segmentOffSinceMs = telemetryMs
                        lastTelemetryWasCarOn = false
                        lastTelemetryTimeMs = telemetryMs
                        lastBinOdo = telemetry.odometer
                        lastTotalDischargeKwh = telemetry.totalDischarge.takeIf { it.isFinite() && it > 0.0 }
                        wasInTrip = inTrip
                        return@collect
                    }

                    // Car is back on — if it was previously off for at least the
                    // threshold, start a fresh segment. Brief stops are ignored so
                    // segment km doesn't reset at red lights.
                    if (lastTelemetryWasCarOn == false) {
                        val offSince = segmentOffSinceMs
                        val offDurationMs = if (offSince != null) telemetryMs - offSince else 0L
                        if (offDurationMs >= SEGMENT_RESET_OFF_THRESHOLD_MS) {
                            // Anchor the new segment at the current odometer reading rather
                            // than going through deriveLiveSessionAnchorOdometer. That helper
                            // falls back to the BYD currentJourneyDriveMileage counter, which
                            // on some firmwares does NOT reset across a brief engine-off cycle
                            // — when that happens, journey still reads ~4.5 km (the pre-pause
                            // segment), so the helper returns (currentOdo − 4.5) = the trip's
                            // start odometer. Segment then equals cumulative for the rest of
                            // the drive and the Distance metric loses its "13.3 (17.8)" form.
                            // We're already inside the off→on branch with offDurationMs ≥ 90 s,
                            // so the resume is unambiguous and anchoring at now is correct.
                            segmentStartOdometer = telemetry.odometer
                            integratedSegmentDistanceKm = 0.0
                            _liveSegmentDistanceKm.value = 0.0
                        }
                        // Accumulate off-state time so the live Trip Tracking
                        // card's TIME and AVG reflect actual driving rather
                        // than driving + parked-with-trip-open. Mirrors the
                        // close-time computation in TripRepository.
                        if (offDurationMs > 0L) {
                            _liveOffStateMs.value = _liveOffStateMs.value + offDurationMs
                        }
                        segmentOffSinceMs = null
                    }

                    val prevMs = lastTelemetryTimeMs
                    val effectiveSpeed = effectiveSpeed(telemetry)
                    // ICE gating (PHEV): ICE-propelled distance stays in the trip totals
                    // but is withheld from the EV-rate distance axes below. The authority
                    // is the car's own lifetime ICE-km counter, carried as a debt because
                    // it steps in whole kilometres — energy_mode alone counts all of HEV
                    // mode as ICE even though the pack propels roughly half of it, which
                    // shrank the EV axis ~2.5× and quartered the projected range.
                    val isPhevCarTick = currentCarConfig()?.isPhev == true
                    val iceCounterKm = telemetry.iceMileageKm?.takeIf { isPhevCarTick }
                    if (iceCounterKm != null) {
                        val stepKm = iceCounterStepKm(lastIceMileageKm, iceCounterKm)
                        iceDebtTripKm += stepKm
                        iceDebtWindowKm += stepKm
                        lastIceMileageKm = iceCounterKm
                    }
                    // Coarse per-tick fallback, only for firmwares with no ICE counter.
                    val iceTick = isPhevCarTick && iceCounterKm == null &&
                        isIceActive(telemetry.instantFuelConsumption, telemetry.energyMode)
                    var deltaEnergyWh = 0.0
                    if (prevMs != null) {
                        val deltaSeconds = (telemetryMs - prevMs) / 1000.0
                        if (deltaSeconds in 0.0..MAX_DELTA_SECONDS) {
                            integratedDistanceKm += integrateDistanceKm(effectiveSpeed, deltaSeconds)
                            integratedSegmentDistanceKm += integrateDistanceKm(effectiveSpeed, deltaSeconds)
                            // Per-tick energy delta. We prefer the BMS totalDischarge delta
                            // (kWh consumed since the previous packet) because it's measured
                            // at the battery and matches what the CONS readout already shows
                            // the user. On firmwares where enginePower is unreliable / often
                            // reported as ~0 (e.g. Seal Excellence in city driving), the
                            // power-integrated counter starved both the rolling-window and
                            // speed-bin accumulators, pinning the projection in BASELINE for
                            // the whole trip. Falling back to power × dt covers the case where
                            // totalDischarge is stale or non-monotonic — recently-charged
                            // packets occasionally report a smaller value than the previous
                            // tick, and we don't want a negative delta to corrupt the bins.
                            val powerDeltaWh = telemetry.enginePower * 1000.0 * (deltaSeconds / 3600.0)
                            val prevTd = lastTotalDischargeKwh
                            val currentTd = telemetry.totalDischarge.takeIf { it.isFinite() && it > 0.0 }
                            val bmsDeltaWh = if (prevTd != null && currentTd != null) {
                                ((currentTd - prevTd) * 1000.0)
                                    .takeIf { it.isFinite() && it in 0.0..10_000.0 }
                            } else null
                            deltaEnergyWh = bmsDeltaWh ?: powerDeltaWh
                            accumulatedEnergyWh += deltaEnergyWh
                            if (currentTd != null) lastTotalDischargeKwh = currentTd
                        }
                    }
                    lastTelemetryTimeMs = telemetryMs

                    // Distance is primarily odometer-based (ground truth), but the BYD
                    // odometer / statistic ECU lags after an engine restart — it can keep
                    // reporting a stale value for several seconds while the car is already
                    // moving again. During that window the odometer delta is 0, which used
                    // to freeze the live Distance / segment at 0 (the classic "0.0 (2.5)"
                    // after a brief stop). The speed-integrated counters (advanced every
                    // tick from effectiveSpeed, which already folds in GPS speed) bridge
                    // that gap so distance keeps growing; the odometer reclaims the value
                    // via max() the moment it advances, keeping the figure odometer-accurate.
                    val previousDistanceKm = _liveDistanceKm.value
                    val currentDistanceKm = maxOf(
                        previousDistanceKm,
                        currentLiveSessionDistanceKm(telemetry),
                        integratedDistanceKm
                    )
                    val currentSegmentDistanceKm = maxOf(
                        _liveSegmentDistanceKm.value,
                        currentLiveSegmentDistanceKm(telemetry),
                        integratedSegmentDistanceKm
                    )
                    val prevOdo = lastBinOdo
                    if (prevOdo != null) {
                        // Decouple distance from energy. The earlier
                        // `deltaEnergyWh > 0.0` gate skipped bin updates entirely on
                        // every tick where BMS totalDischarge hadn't ticked up yet —
                        // very common on Seal Excellence where the BMS counter
                        // updates sparsely (every few seconds) rather than every
                        // telemetry packet. Result: bin distance lagged real
                        // distance, often never reaching BIN_MIN_DIST_KM = 0.2 km,
                        // so binWhPerKm stayed null and the projection was pinned
                        // in BASELINE for the entire drive.
                        // Now: bin distance always advances with the odometer
                        // (matching ground truth); bin energy is added only when
                        // positive, so a regen tick or a "stale 0" tick doesn't
                        // poison the bin's energy sum.
                        val odometerDeltaKm = (telemetry.odometer - prevOdo).coerceAtLeast(0.0)
                        val binDistKm = maxOf(
                            odometerDeltaKm,
                            (currentDistanceKm - previousDistanceKm).coerceAtLeast(0.0)
                        )
                        val bin = speedBin(effectiveSpeed)
                        val acc = liveSpeedBins.getOrPut(bin) { BinAccumulator() }
                        // ICE-propelled distance is excluded from the rate axes (the energy
                        // is kept: on a series hybrid the pack still discharges while the
                        // ICE generates, and dropping distance but keeping energy can only
                        // err the rate HIGH — range is never over-stated).
                        if (!iceTick) {
                            val evBinDistKm = evShareOfTripDistance(binDistKm)
                            acc.distanceKm += evBinDistKm
                            evTripDistanceKm += evBinDistKm
                        }
                        if (deltaEnergyWh > 0.0) {
                            acc.energyWh += deltaEnergyWh
                        }
                    }
                    lastBinOdo = telemetry.odometer

                    val distKm = currentDistanceKm
                    _liveDistanceKm.value = distKm
                    _liveSegmentDistanceKm.value = currentSegmentDistanceKm
                    // Re-sync the speed-integrated bridge to the odometer whenever the
                    // odometer advances, so the integration can't drift above ground truth
                    // over a long trip (the BYD speedometer reads a few % high). The
                    // integration therefore only ever *leads* during the brief windows
                    // where the odometer is stale, and snaps back to the odometer the
                    // moment it ticks.
                    if (prevOdo != null && telemetry.odometer > prevOdo) {
                        integratedDistanceKm = currentLiveSessionDistanceKm(telemetry)
                        integratedSegmentDistanceKm = currentLiveSegmentDistanceKm(telemetry)
                    }
                    // Live "energy consumed" comes from the BMS totalDischarge delta
                    // (current reading − the trip's start anchor), so it matches what
                    // the post-trip overview will store and what reference apps like
                    // Electro report for the same drive. We read the start anchor from
                    // TripRepository.currentTripStartTotalDischarge() rather than caching
                    // it locally so that any per-tick stale-zero correction the repo
                    // applies (see correctStaleDischargeAnchorIfNeeded there) is picked
                    // up immediately. As a defence in depth against the brief window
                    // before that correction fires — and against any other case where
                    // the BMS-derived delta would be physically impossible — we coerce
                    // the value into [0, 2 × pack capacity]; outside that envelope we
                    // fall back to the power-integrated gross-traction estimate, which
                    // is always sane.
                    val tripStartTd = tripRepository.currentTripStartTotalDischarge()
                    val bmsDeltaKwh = if (tripStartTd != null)
                        telemetry.totalDischarge - tripStartTd else 0.0
                    val sanityBatteryKwh = currentCarConfig()?.let {
                        if (it.isPhev) it.phevUsableBatteryKwh ?: it.batteryKwh else it.batteryKwh
                    } ?: FALLBACK_BATTERY_KWH
                    val fallbackKwh = (accumulatedEnergyWh / 1000.0).coerceAtLeast(0.0)
                    // Cumulative per-tick positive deltas from the repo. Robust against
                    // BMS counter resets across engine-off segments — which would
                    // otherwise make bmsDeltaKwh go negative for the rest of the trip
                    // and force the fallback (power-integrated) for everything that
                    // followed. Cumulative equals bmsDeltaKwh when no reset happens
                    // and only exceeds it after one — so always take the max.
                    val cumulativeBmsKwh = tripRepository.currentTripCumulativeBmsDeltaKwh() ?: 0.0
                    val plainBmsKwh = if (
                        bmsDeltaKwh.isFinite() &&
                        bmsDeltaKwh in 0.0..(sanityBatteryKwh * 2.0)
                    ) bmsDeltaKwh else 0.0
                    val bestBmsKwh = maxOf(plainBmsKwh, cumulativeBmsKwh)
                    val liveEnergyKwh = if (bestBmsKwh > 0.0) bestBmsKwh else fallbackKwh
                    _liveAccumulatedKwh.value = liveEnergyKwh.coerceAtLeast(0.0)
                    // Pure odometer delta — used for avg speed display to match finalized stats.
                    _liveOdometerDistanceKm.value =
                        (telemetry.odometer - (liveSessionStartOdometer ?: telemetry.odometer))
                            .coerceAtLeast(0.0)

                    val lastDist = _tripDataPoints.value.lastOrNull()?.distanceKm ?: distKm
                    if (distKm - lastDist < SAMPLE_INTERVAL_KM) {
                        lastTelemetryWasCarOn = telemetry.isCarOn
                        wasInTrip = inTrip
                        return@collect
                    }

                    // Sample (cumulative distance, cumulative CLEAN energy) for the
                    // LIVE_TRIP rolling window. We use liveEnergyKwh — the same BMS
                    // total-discharge figure the CONS readout and the trip-restore
                    // rebuild use — NOT the per-tick power-integrated accumulator.
                    //
                    // Why this matters: since instant telemetry arrives ~10×/s, but the
                    // BMS totalDischarge only refreshes about once a second, the per-tick
                    // accumulator fell back to power×dt on every intermediate fast tick
                    // (no fresh discharge to diff against) and ADDED that on top of the
                    // discharge captured when the counter did refresh — double-counting
                    // energy. That inflated the rolling Wh/km and pinned the projection at
                    // roughly half once the LIVE_TRIP tier engaged (e.g. a Seal Excellence
                    // cruising at 15.8 kWh/100 km projecting as if it were ~30). It also
                    // made the live line artificially smooth (power integrates smoothly)
                    // vs the reconstruction. liveEnergyKwh is the clean end-start discharge
                    // (with a whole-trip power-integration fallback only when the BMS total
                    // is unavailable), so the window now matches actual consumption and the
                    // rebuilt curve. It still engages reliably on lumpy feeds because the
                    // end-start total grows smoothly even when the per-tick delta is 0.
                    // Build the window's cumulative-energy series from *clamped per-sample deltas*
                    // rather than storing liveEnergyKwh verbatim. liveEnergyKwh isn't a clean
                    // cumulative — a mid-trip discharge-anchor rebase or a power↔BMS source switch
                    // can step it, and maxOf(plainBmsKwh, cumulativeBmsKwh) can dip on regen — any
                    // of which injects a kWh-scale step inside the window that spikes the rate for
                    // up to a full window length. Coercing each delta to [0, MAX_SAMPLE_WH_PER_KM ×
                    // Δkm] makes the series monotone by construction and clips those steps (and
                    // long idle drain) to a plausible per-100 m cost, while leaving real driving
                    // untouched. This also makes the live series structurally identical to the
                    // restore rebuild, which already sums clamped per-pair deltas.
                    val rawNowWh = liveEnergyKwh * 1000.0
                    val prevSample = energySamples.lastOrNull()
                    // PHEV: the window's x-axis is EV-only distance — an interval driven on
                    // the ICE doesn't advance it, so ICE stretches can't dilute the slope
                    // (during a long pure-ICE stretch the axis freezes, the slope goes
                    // indeterminate, and the EMA simply holds the last electric rate).
                    // On BEVs the EV axis advances with every interval, so the sample
                    // series is identical to the previous distKm-based one.
                    val prevTripSampleKm = lastSampleTripDistKm
                    lastSampleTripDistKm = distKm
                    if (!iceTick) {
                        val sampleStepKm = (distKm - (prevTripSampleKm ?: distKm)).coerceAtLeast(0.0)
                        evWindowDistanceKm += evShareOfWindowDistance(sampleStepKm)
                    }
                    val sampleAxisKm = if (isPhevCarTick) evWindowDistanceKm else distKm
                    val dDistKm = (sampleAxisKm - (prevSample?.distanceKm ?: sampleAxisKm))
                        .coerceAtLeast(SAMPLE_INTERVAL_KM)
                    val deltaWh = lastSampleRawEnergyWh
                        ?.let { (rawNowWh - it).coerceIn(0.0, MAX_SAMPLE_WH_PER_KM * dDistKm) }
                        ?: 0.0
                    energySamples.add(EnergySample(sampleAxisKm, (prevSample?.cumulativeEnergyWh ?: 0.0) + deltaWh))
                    lastSampleRawEnergyWh = rawNowWh
                    val windowFloor = sampleAxisKm - ROLLING_WINDOW_KM
                    while (energySamples.size > 1 && energySamples[0].distanceKm < windowFloor) {
                        energySamples.removeAt(0)
                    }

                    // Least-squares slope across the whole window (see windowSlopeWhPerKm) instead
                    // of a two-endpoint difference, so BMS-counter quantization doesn't jitter the
                    // rate as samples enter and leave the window.
                    val rawWhPerKm: Double? =
                        windowSlopeWhPerKm(energySamples.map { it.distanceKm to it.cumulativeEnergyWh })

                    if (rawWhPerKm != null) {
                        smoothedWhPerKm = smoothedWhPerKm
                            ?.let { EMA_ALPHA * rawWhPerKm + (1.0 - EMA_ALPHA) * it }
                            ?: rawWhPerKm
                    }

                    // Cumulative-across-bins drive Wh/km. Previously this only considered
                    // the *current* speed bin, so a transition from highway to city (or
                    // any speed change into a fresh bin) immediately dropped the fallback
                    // back to BASELINE even though plenty of bin data existed overall.
                    // Summing all populated bins gives a meaningful pre-stabilisation rate
                    // from the moment the trip has accumulated BIN_MIN_DIST_KM of drive
                    // distance, and reflects driving style across all speed regimes.
                    val totalBinDistanceKm = liveSpeedBins.values.sumOf { it.distanceKm }
                    val totalBinEnergyWh   = liveSpeedBins.values.sumOf { it.energyWh }
                    val binWhPerKm: Double? =
                        if (totalBinDistanceKm >= BIN_MIN_DIST_KM && totalBinEnergyWh > 0.0)
                            totalBinEnergyWh / totalBinDistanceKm
                        else null

                    // Trip-cumulative consumption — the robust fallback. liveEnergyKwh is
                    // the same trip-total energy the CONS readout shows (BMS totalDischarge
                    // delta against the trip-start anchor, or the power-integrated estimate
                    // when that delta is implausible). Deriving Wh/km from it always works
                    // once the trip has moved ≥ BIN_MIN_DIST_KM and consumed anything —
                    // it does not depend on the BMS incrementing totalDischarge on the
                    // exact telemetry ticks the rolling-window / speed-bin accumulators
                    // happen to sample, which is what could still leave those two empty
                    // and pin the projection in BASELINE on a sparse-update firmware.
                    // PHEV: divide by EV-only distance so ICE kilometres can't deflate the
                    // EV rate (the numerator is battery energy either way). BEVs keep distKm.
                    val rateDistKm = if (isPhevCarTick) evTripDistanceKm else distKm
                    val tripWhPerKm: Double? =
                        if (rateDistKm >= BIN_MIN_DIST_KM && liveEnergyKwh > 0.0)
                            (liveEnergyKwh * 1000.0) / rateDistKm
                        else null
                    // Trip-cumulative rate preferred: it's derived from the clean
                    // liveEnergyKwh, whereas the speed-bin energy is summed from the same
                    // per-tick power-integrated deltas that over-count on a fast feed, so
                    // binWhPerKm would carry the same inflation the window fix just removed.
                    // As used here the bin rate is only the summed total anyway (no real
                    // per-regime weighting), so the clean trip-cumulative is strictly
                    // better; binWhPerKm stays as a fallback for the brief window before
                    // trip distance reaches BIN_MIN_DIST_KM. The restore path already
                    // prefers the trip-cumulative the same way.
                    val nonBaselineWhPerKm: Double? = tripWhPerKm ?: binWhPerKm

                    // Stabilisation is judged on the rate's own distance axis: a PHEV that
                    // spent most of the trip on the ICE hasn't demonstrated an EV rate yet.
                    val isStabilised = rateDistKm >= STABILISATION_KM
                    val car = currentCarConfig()
                    // PHEVs: use the usable EV-only battery capacity for the EV range leg;
                    // fall back to gross batteryKwh if phevUsableBatteryKwh is not defined.
                    val batteryKwh = car?.let {
                        if (it.isPhev) it.phevUsableBatteryKwh ?: it.batteryKwh else it.batteryKwh
                    } ?: FALLBACK_BATTERY_KWH
                    val baselineWhPerKm = car?.referenceConsumptionKwhPer100km
                        ?.times(10.0)
                        ?: FALLBACK_BASELINE_WH_PER_KM
                    val effectiveSoc = telemetry.soc.takeIf { it > 0 } ?: telemetry.socPanel.toDouble()
                    val isPhev = car?.isPhev == true
                    // For PHEVs prefer the BMS's own remaining-EV-energy reading over
                    // capacity × SoC, which overstates EV-usable energy near the
                    // charge-sustaining floor (see remainingEvEnergyWh). BEVs keep the
                    // SoC product unchanged.
                    val remainingEnergyWh = remainingEvEnergyWh(
                        batteryKwh, effectiveSoc, telemetry.batteryRemainPowerEV, isPhev
                    )
                    // EV-only projection: models how your actual EV consumption affects electric range
                    // vs the BMS/WLTP EV estimate. We used to add the BMS fuel range for PHEVs, but the
                    // chart caps at the EV-only WLTP — so any PHEV with fuel in the tank projected
                    // EV+ICE combined (hundreds of km) and pinned permanently at "≥ WLTP, capped".
                    // Fuel range isn't driven by EV consumption, so it doesn't belong on this curve.
                    // projectedEvRangeKm floors the rate at the reference consumption for PHEVs so
                    // ICE-diluted (near-zero) measured Wh/km can't balloon the EV range — see its doc.
                    // Shrink the trip-cumulative rate toward a stable prior (lifetime, else catalog)
                    // until STABILISATION_KM of distance has accumulated. tripWhPerKm = energy ÷
                    // distance is numerically wild in the first few hundred metres — a parking-lot
                    // crawl reads huge Wh/km, a gentle roll-out reads tiny — which made the projected
                    // line spike violently right at trip start. Weighting it by how far you've driven
                    // pulls those early points onto the stable prior and eases them onto the real trip
                    // average by STABILISATION_KM. This "demonstrated rate" is also the optimism-cap
                    // anchor, so the cap can never fight the shrink.
                    val smoothed = smoothedWhPerKm?.takeIf { it > 0.0 }
                    val stablePrior = lifetimeWhPerKm.value ?: baselineWhPerKm
                    val tripConfidence = (rateDistKm / STABILISATION_KM).coerceIn(0.0, 1.0)
                    val demonstratedRate: Double? = nonBaselineWhPerKm?.let {
                        tripConfidence * it + (1.0 - tripConfidence) * stablePrior
                    }
                    // Pick the consumption-rate tier (and the badge it drives), crossfading from
                    // TRIP_AVERAGE to LIVE_TRIP over a STABILISATION_KM-wide ramp so neither the rate
                    // nor the line steps when the tier flips — the EMA-smoothed rolling rate and the
                    // (shrunk) trip rate can differ by 10-20% there.
                    val (rate, model) = when {
                        smoothed != null && demonstratedRate != null && isStabilised -> {
                            val liveBlend = ((rateDistKm - STABILISATION_KM) / STABILISATION_KM).coerceIn(0.0, 1.0)
                            val blended = liveBlend * smoothed + (1.0 - liveBlend) * demonstratedRate
                            blended to if (liveBlend >= 0.5) RangeModel.LIVE_TRIP else RangeModel.TRIP_AVERAGE
                        }
                        smoothed != null && isStabilised ->
                            smoothed to RangeModel.LIVE_TRIP
                        demonstratedRate != null ->
                            demonstratedRate to when {
                                tripConfidence >= 0.5         -> RangeModel.TRIP_AVERAGE
                                lifetimeWhPerKm.value != null -> RangeModel.LIFETIME_AVERAGE
                                else                          -> RangeModel.BASELINE
                            }
                        lifetimeWhPerKm.value != null ->
                            lifetimeWhPerKm.value!! to RangeModel.LIFETIME_AVERAGE
                        else ->
                            baselineWhPerKm to RangeModel.BASELINE
                    }
                    // Optimism-cap anchor: the demonstrated rate above (shrunk trip average, →
                    // tripWhPerKm once confident), else the stable prior. Bounds a downhill balloon
                    // inside projectedEvRangeKm. Log only when the cap's biting state flips — a cap
                    // that stays engaged means the rolling window is systematically low (an
                    // energy-accounting problem, not terrain), worth catching in logs.
                    val referenceWhPerKm = demonstratedRate ?: stablePrior
                    val capBiting = remainingEnergyWh > 0.0 && referenceWhPerKm > 0.0 &&
                        OPTIMISM_CAP * referenceWhPerKm > rate
                    if (capBiting != optimismCapBiting) {
                        optimismCapBiting = capBiting
                        Log.i(TAG, "Optimism cap ${if (capBiting) "engaged" else "released"}: " +
                            "rate=${"%.0f".format(rate)} ref=${"%.0f".format(referenceWhPerKm)} Wh/km")
                    }
                    // A non-positive remaining-energy read — a transient telemetry glitch, or a
                    // cold BMS reporting soc=socPanel=0 — would divide out to a projected 0. That
                    // false spike-to-zero would be appended to the curve AND frozen into the
                    // LiveProjectionCache permanently, surviving every reopen. Emit null instead so
                    // the projected line simply leaves a gap (the chart drops null points) rather
                    // than diving to 0 and back.
                    val projectedRange: Double? =
                        if (remainingEnergyWh > 0.0)
                            projectedEvRangeKm(remainingEnergyWh, rate, baselineWhPerKm, isPhev, referenceWhPerKm)
                        else null

                    setActiveRangeModel(model)

                    _tripDataPoints.value = _tripDataPoints.value + RangeDataPoint(
                        distanceKm = distKm,
                        // effectiveSoc, not raw telemetry.soc, so the persisted point's SoC
                        // matches the value the projection above was computed from (raw soc can
                        // read 0 while socPanel is valid).
                        soc = effectiveSoc,
                        electricDrivingRangeKm = telemetry.electricDrivingRangeKm,
                        projectedRangeKm = projectedRange,
                        isStabilised = isStabilised || model != RangeModel.BASELINE
                    )
                    // Snapshot the as-computed-live curve so a reopen restores it
                    // verbatim instead of rebuilding it (display aid only — see
                    // LiveProjectionCache). Keyed by trip id so it can never bleed
                    // into a different trip.
                    currentTripId.value?.let { liveProjectionCache.update(it, _tripDataPoints.value) }
                    lastTelemetryWasCarOn = telemetry.isCarOn
                }
                wasInTrip = inTrip
            }
        }

        viewModelScope.launch {
            delay(5_000L)
            ensureUpdateCheckStarted()
        }

    }

    // ── Update actions ────────────────────────────────────────────────────────

    fun downloadUpdate() {
        // DiLink-5: in-app download + silent PackageInstaller can't complete on the head unit
        // (unprivileged, no installer UI), and a post-install self-relaunch is a boot-loop hazard
        // there. Updates on D5 are delivered by manual `adb install -r` — see AboutTab's D5 card.
        // The update badge/notice still shows (checkForUpdate keeps running); only the auto path is off.
        if (com.byd.tripstats.sdk.DiLink5Platform.isDiLink5) return
        val info = updateInfo.value ?: return
        updateRepository.downloadUpdate(info)
        // Poll progress every second and push to our own StateFlow
        viewModelScope.launch {
            while (true) {
                delay(1_000L)
                val p = updateRepository.pollDownloadProgress() ?: break
                _updateDownloadProgress.value = p
                if (p >= 100 || p < 0) break
            }
        }
    }

    fun installUpdate() {
        // DiLink-5: never drive the silent installer / self-relaunch on the head unit (see
        // downloadUpdate). D5 updates are sideloaded via `adb install -r`.
        if (com.byd.tripstats.sdk.DiLink5Platform.isDiLink5) return
        val apk = downloadedApk.value ?: return
        if (!canInstallNow.value) {
            Log.w(TAG, "installUpdate called but canInstallNow = false")
            return
        }
        viewModelScope.launch {
            val backupFile = backupDatabase()
            if (backupFile != null) {
                Log.i(TAG, "Database backup created before update install: ${backupFile.absolutePath}")
            } else {
                Log.w(TAG, "Database backup failed before update install — continuing with install")
            }
            updateRepository.installUpdate(apk)
        }
    }

    fun cancelDownload() = updateRepository.cancelDownload()

    fun ensureUpdateCheckStarted() {
        if (!updateCheckStarted.compareAndSet(false, true)) return
        viewModelScope.launch(Dispatchers.IO) {
            _isCheckingUpdate.value = true
            updateRepository.checkForUpdate(BuildConfig.VERSION_NAME)
            _isCheckingUpdate.value = false
        }
    }

    fun checkForUpdateManually() {
        if (_isCheckingUpdate.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _isCheckingUpdate.value = true
            updateRepository.checkForUpdate(BuildConfig.VERSION_NAME)
            _isCheckingUpdate.value = false
        }
    }

    // ── Trip controls ─────────────────────────────────────────────────────────

    /**
     * Both functions are now plain fun — the repository's public API is
     * fire-and-forget (enqueues a TripEvent), so no coroutine is needed here.
     */
    fun startManualTrip() {
        _tripDataPoints.value = emptyList()   // reset before repo broadcasts isInTrip = true
        forceFreshAnchorNextSession = true
        // Suppress journey-counter distance immediately so live ticks between this call
        // and restoreTripState firing don't inflate distKm with the car's journey counter.
        suppressJourneyDistance = true
        // Pre-anchor the live-session state at the press moment so AVG / TIME / DISTANCE
        // in the trip-controls card don't briefly show enormous values during the gap
        // between this call and restoreTripState firing. Without this reset, the live
        // tick keeps computing distance against the car-on back-anchor (set by
        // beginLiveDriveSession when the app opened mid-drive) — e.g. journey distance
        // 2.6 km — while elapsedMs starts climbing from "since press", giving avg
        // speeds of hundreds of km/h until restoreTripState fires and re-anchors
        // everything to trip.startOdometer / trip.startTime.
        liveSessionStartOdometer = null
        _liveOdometerDistanceKm.value = 0.0
        _liveDistanceKm.value = 0.0
        _liveSegmentDistanceKm.value = 0.0
        _liveSessionStartMs.value = System.currentTimeMillis()
        // Also reset off-state time so elapsedMs isn't skewed by a previous trip's
        // off-state window when the user taps Start immediately after Stop.
        _liveOffStateMs.value = 0L
        tripRepository.requestManualStart()
    }

    fun endManualTrip() {
        manualStopResetPending = true
        forceFreshAnchorNextSession = true
        tripRepository.requestManualStop()
    }

    // ── Car-off auto-stop confirmation ──────────────────────────────────────────
    // True while a car-off auto-stop is held awaiting the user's choice; the
    // dashboard shows a "keep / stop" prompt off this.
    val pendingAutoStop: StateFlow<Boolean> = tripRepository.pendingAutoStop

    /** "Keep recording" on the prompt — hold the trip open across the car-off window. */
    fun keepTripAcrossOff() {
        tripRepository.requestKeepTripAcrossOff()
    }

    /** "Stop" on the prompt — finalise the held auto-stop. */
    fun confirmAutoStop() {
        manualStopResetPending = true
        forceFreshAnchorNextSession = true
        tripRepository.confirmAutoStop()
    }

    /** Drives whether a car-off auto-stop is held for confirmation (UI foreground). */
    fun setUiVisible(visible: Boolean) {
        tripRepository.setUiVisible(visible)
    }

    fun toggleAutoTripDetection() {
        val newValue = !_autoTripDetection.value
        tripRepository.setAutoTripDetection(newValue)
        _autoTripDetection.value = newValue
    }

    // ── Trip state restoration ───────────────────────────────────────────────

    private fun restoreTripState(tripId: Long, telemetry: VehicleTelemetry, telemetryMs: Long) {
        restoreTripJob?.cancel()
        restoreTripJob = viewModelScope.launch(Dispatchers.IO) {
            val trip = tripRepository.getTripById(tripId).first() ?: return@launch
            val dataPoints = tripRepository.getDataPointsForTrip(tripId).first()
            // Read the cached live curve here on the IO dispatcher (it may hit disk on
            // a cold start) so the Main-thread reconstruction below never blocks on it.
            val cachedCurve = liveProjectionCache.load(tripId)

            withContext(Dispatchers.Main) {
                val freshAnchor = forceFreshAnchorNextSession
                if (freshAnchor) {
                    forceFreshAnchorNextSession = false
                    suppressJourneyDistance = true
                }
                // Cumulative anchor = the trip's true start odometer from the DB.
                // This is the only source of truth for trip-level cumulative
                // distance; the car's currentJourneyDriveMileage resets every
                // engine-off cycle and would lose prior segments otherwise.
                liveSessionStartOdometer = trip.startOdometer
                // segAnchor marks the start of the current car-on session. It must
                // never fall earlier than trip.startOdometer: segment distance
                // (car-on travel) is conceptually bounded by cumulative distance
                // (trip travel). When the app was opened mid-drive the journey
                // counter can already be running slightly ahead of the actual
                // car-on distance at open time, leaving journeyAnchor <
                // trip.startOdometer on every later tick — which would flip the
                // display into "seg (cum)" with seg > cum. Clamping up fixes
                // Case 1 and is a no-op for Case 3 (engine-off cycle) where
                // journeyAnchor > trip.startOdometer anyway.
                val journey = journeyDistanceKm(telemetry)
                val journeyAnchor = if (journey != null) {
                    (telemetry.odometer - journey).coerceAtLeast(0.0)
                } else {
                    telemetry.odometer
                }
                // If the stored data points contain a gap longer than the
                // segment-reset threshold (i.e. the trip went through at least one
                // engine-off → engine-on cycle), anchor segment at the first sample
                // AFTER the most recent gap so the Distance metric shows the new
                // segment distinct from cumulative. Same reasoning as the off→on
                // branch above: BYD's currentJourneyDriveMileage isn't reliable
                // across brief engine cycles on every firmware. Falling back to
                // journey-based anchor when no gap exists preserves the original
                // behaviour for fresh restores of trips that never paused.
                val postGapAnchor = run {
                    var anchor: Double? = null
                    for (i in 1 until dataPoints.size) {
                        val prev = dataPoints[i - 1]
                        val curr = dataPoints[i]
                        val gap = curr.timestamp - prev.timestamp
                        if (gap < SEGMENT_RESET_OFF_THRESHOLD_MS) continue
                        val odometerDelta = curr.odometer - prev.odometer
                        // Only treat as a genuine car-off boundary if the odometer didn't
                        // advance. If the car was moving during the gap (service hiccup while
                        // driving), the ECU odometer still counts up.
                        // Note: carOn is NOT used here — on some BYD firmwares powerStateRaw
                        // reports 0 even while driving at speed (gear=D, speed>0), making
                        // carOn==0 an unreliable car-off signal.
                        if (odometerDelta < 0.05) anchor = curr.odometer
                    }
                    anchor
                }
                val segAnchor = postGapAnchor
                    ?: journeyAnchor.coerceAtLeast(trip.startOdometer)
                segmentStartOdometer = segAnchor
                lastTelemetryTimeMs = telemetryMs
                lastBinOdo          = telemetry.odometer
                val odometerCumulative = (telemetry.odometer - trip.startOdometer).coerceAtLeast(0.0)
                val restoredTripDistanceKm = dataPoints.lastOrNull()
                    ?.let { (it.odometer - trip.startOdometer).coerceAtLeast(0.0) }
                    ?: (trip.distance ?: 0.0).coerceAtLeast(0.0)
                val liveDistanceKm = maxOf(odometerCumulative, restoredTripDistanceKm)
                _liveDistanceKm.value = liveDistanceKm
                _liveSegmentDistanceKm.value =
                    (telemetry.odometer - segAnchor).coerceAtLeast(0.0)
                integratedDistanceKm = liveDistanceKm
                integratedSegmentDistanceKm = _liveSegmentDistanceKm.value

                _liveSessionStartMs.value = trip.startTime
                _liveOdometerDistanceKm.value = odometerCumulative
                // Reconstruct off-state duration from the stored data-point gaps so
                // the live TIME/AVG match what the trip will store at close. Mirrors
                // TripRepository.computeOffStateDurationMs — same 20 s threshold.
                val dbOffStateMs = run {
                    if (dataPoints.size < 2) return@run 0L
                    var off = 0L
                    for (i in 1 until dataPoints.size) {
                        val gap = dataPoints[i].timestamp - dataPoints[i - 1].timestamp
                        if (gap > 20_000L) off += gap
                    }
                    off
                }
                // Add the current pending off-state that isn't stored in DB yet — the
                // car just came back on. segmentOffSinceMs is set when the car turned off
                // (same ViewModel session); on a fresh ViewModel start (Minimal/Deep Sleep
                // service restart) it is null, so we fall back to the gap from the last
                // stored data point, which approximates the parked window.
                val currentOffMs = segmentOffSinceMs
                    ?.let { (telemetryMs - it).coerceAtLeast(0L) }
                    ?: dataPoints.lastOrNull()?.let { lastPt ->
                        val gap = telemetryMs - lastPt.timestamp
                        if (gap > 20_000L) gap else 0L
                    } ?: 0L
                _liveOffStateMs.value = dbOffStateMs + currentOffMs

                // Reconstruct accumulated energy by replaying per-pair BMS totalDischarge
                // deltas across the stored data points — the same source the LIVE path
                // uses (since the BMS-delta-primary fix) and the same source the CONS
                // readout uses, so they all agree.
                //
                // We previously did this with `prev.power × dt` (pure power integration)
                // because the BMS counter was unreliable on early firmwares (stale 0 at
                // trip start could make startTotalDischarge → endTotalDischarge produce
                // thousand-kWh ghost deltas). That stale-0 problem is now corrected at
                // anchor time by TripRepository, so per-pair BMS deltas are the better
                // signal. Pure power integration was over-counting on Seal Excellence
                // where engine_power spikes during acceleration get amplified over the
                // sample interval, inflating Wh/km by ~50% and depressing the projected
                // range by half. Power × dt remains as a per-pair fallback when the
                // BMS delta for that pair is itself implausible (negative or > 10 kWh).
                //
                // RESTORE_MAX_GAP_SECONDS = 60s tolerates the gaps between 100m-spaced
                // samples even at low speed; longer gaps (parked stretches) contribute 0.
                val restoreMaxGapSeconds = 60.0
                energySamples.clear()
                var cumEnergyWh = 0.0
                // Mirror of the live path's ICE gating: ICE-propelled kilometres don't
                // advance the EV-only distance axis the window rate is computed on. Same
                // authority as live — the lifetime ICE-km counter carried as a debt, with
                // the coarse energy_mode predicate only when no point carries the counter.
                // Pre-gating history has no fuel signals in rawJson, so every pair reads
                // electric and the rebuild matches old behaviour.
                val isPhevRestore = currentCarConfig()?.isPhev == true
                val iceCounters = arrayOfNulls<Int>(dataPoints.size)
                if (isPhevRestore) {
                    dataPoints.forEachIndexed { i, dp -> iceCounters[i] = pointIceMileageKm(dp.rawJson) }
                }
                val hasIceCounter = iceCounters.any { it != null }
                val pointIce = if (isPhevRestore && !hasIceCounter)
                    BooleanArray(dataPoints.size) { pointIceActive(dataPoints[it].rawJson) }
                else BooleanArray(dataPoints.size)
                var evAxisKm = 0.0
                var restoreIceDebtKm = 0.0
                dataPoints.forEachIndexed { i, dp ->
                    if (i > 0) {
                        val prev = dataPoints[i - 1]
                        restoreIceDebtKm += iceCounterStepKm(iceCounters[i - 1], iceCounters[i])
                        if (!pointIce[i - 1]) {
                            val pairKm = (dp.odometer - prev.odometer).coerceAtLeast(0.0)
                            val withheld = withholdIceDebt(pairKm, restoreIceDebtKm)
                            restoreIceDebtKm = withheld.debtKm
                            evAxisKm += withheld.evKm
                        }
                        val dt = (dp.timestamp - prev.timestamp).coerceAtLeast(0L) / 1000.0
                        if (dt in 0.0..restoreMaxGapSeconds) {
                            val bmsDeltaWh = (dp.totalDischarge - prev.totalDischarge) * 1000.0
                            val powerDeltaWh = prev.power * 1000.0 * (dt / 3600.0)
                            val chosenWh = if (bmsDeltaWh.isFinite() && bmsDeltaWh in 0.0..10_000.0)
                                bmsDeltaWh else powerDeltaWh
                            // Clamp to a monotone, spike-free per-pair contribution exactly like the
                            // live series (MAX_SAMPLE_WH_PER_KM × Δkm), so a regen dip or a stale-0
                            // power fallback can't decrease cumulative energy or inject a window step.
                            val dPairKm = (dp.odometer - prev.odometer).coerceAtLeast(SAMPLE_INTERVAL_KM)
                            cumEnergyWh += chosenWh.coerceIn(0.0, MAX_SAMPLE_WH_PER_KM * dPairKm)
                        }
                    }
                    val dKm = (dp.odometer - trip.startOdometer).coerceAtLeast(0.0)
                    energySamples.add(EnergySample(if (isPhevRestore) evAxisKm else dKm, cumEnergyWh))
                }
                accumulatedEnergyWh = cumEnergyWh
                // Seed the live EV axes so the first post-resume tick continues the
                // restored series without a step.
                evWindowDistanceKm = evAxisKm
                evTripDistanceKm = evAxisKm
                lastSampleTripDistKm = liveDistanceKm
                // Carry the unpaid ICE debt and the counter anchor across the resume so
                // the first live tick continues the same withholding rather than
                // re-counting the restored kilometres.
                iceDebtTripKm = restoreIceDebtKm
                iceDebtWindowKm = restoreIceDebtKm
                lastIceMileageKm = iceCounters.lastOrNull { it != null }
                _liveAccumulatedKwh.value = (cumEnergyWh / 1000.0).coerceAtLeast(0.0)
                // Seed the live per-sample delta tracker so the first post-resume live sample
                // diffs against the restored cumulative energy (≈ trip energy) rather than
                // restarting from null and dropping a sample.
                lastSampleRawEnergyWh = cumEnergyWh
                // Seed the per-tick BMS-delta tracker from the last restored data point
                // so the next live tick computes (currentTd − lastTd) cleanly. Without
                // this, the first post-resume tick has prevTd=null → falls back to
                // power × dt for one sample, which on Seal Excellence is ~0 (under-
                // counts) and very briefly skews the rolling-window Wh/km low.
                lastTotalDischargeKwh = dataPoints.lastOrNull()?.totalDischarge
                    ?.takeIf { it.isFinite() && it > 0.0 }

                // Reconstruct speed bins from all points.
                // Use midpoint speed (avg of a and b) so the segment is classified by
                // the speed actually driven, not the speed at the start of the interval.
                liveSpeedBins.clear()
                if (dataPoints.size >= 2) {
                    // Same ICE gating as the live bins: ICE-propelled distance is excluded
                    // from the rate axis, its energy is kept. This walk keeps its own debt
                    // pool because it consumes the pair distances independently of the
                    // window axis rebuilt above.
                    var binIceDebtKm = 0.0
                    for (i in 1 until dataPoints.size) {
                        val a = dataPoints[i - 1]
                        val b = dataPoints[i]
                        val bin = speedBin((a.speed + b.speed) / 2.0)
                        val dist = (b.odometer - a.odometer).coerceAtLeast(0.0)
                        val energy = (b.totalDischarge - a.totalDischarge).coerceAtLeast(0.0) * 1000.0
                        val acc = liveSpeedBins.getOrPut(bin) { BinAccumulator() }
                        binIceDebtKm += iceCounterStepKm(iceCounters[i - 1], iceCounters[i])
                        if (!pointIce[i - 1]) {
                            val withheld = withholdIceDebt(dist, binIceDebtKm)
                            binIceDebtKm = withheld.debtKm
                            acc.distanceKm += withheld.evKm
                        }
                        acc.energyWh += energy
                    }
                }

                // Per-point rolling consumption rate, replayed across the stored points
                // exactly the way the live loop derives it (trailing ROLLING_WINDOW_KM
                // window + EMA_ALPHA smoothing). THIS is what gives the reconstructed
                // curve its shape. SoC barely moves over a short trip, so a single trip-
                // average rate applied to every point (the previous behaviour) renders as
                // a near-flat line — the information lives in how the consumption rate
                // changes along the drive (city vs motorway, climbs, regen).
                val perPointSmoothed = rollingWhPerKmSeries(
                    energySamples.map { it.distanceKm to it.cumulativeEnergyWh }
                )

                // Seed the live EMA from the LAST rolling value (what the live loop would
                // hold right now) rather than the whole-trip average — so the displayed
                // head point and the first new live tick agree, with no jump at resume.
                // Falls back to the full-window average when no rolling value exists.
                smoothedWhPerKm = perPointSmoothed.lastOrNull()?.takeIf { it > 0.0 }
                    ?: windowSlopeWhPerKm(energySamples.map { it.distanceKm to it.cumulativeEnergyWh })

                // Reconstruct graph points with projected range so the projection line
                // starts from trip start, not from when the Activity opened.
                val car = currentCarConfig()
                val batteryKwh = car?.let {
                    if (it.isPhev) it.phevUsableBatteryKwh ?: it.batteryKwh else it.batteryKwh
                } ?: FALLBACK_BATTERY_KWH
                val baselineWhPerKm = car?.referenceConsumptionKwhPer100km?.times(10.0)
                    ?: FALLBACK_BASELINE_WH_PER_KM
                // Cumulative bin Wh/km — same formula the live path uses (line ~1056).
                // When this resolves to a non-null value we treat all restored points as
                // stabilised, so the orange line is drawn from the start of the trip
                // instead of leaving a 0..STABILISATION_KM visual gap.
                val restoredBinTotalDist = liveSpeedBins.values.sumOf { it.distanceKm }
                val restoredBinTotalEnergy = liveSpeedBins.values.sumOf { it.energyWh }
                val restoredBinWhPerKm: Double? = (
                    if (restoredBinTotalDist >= BIN_MIN_DIST_KM && restoredBinTotalEnergy > 0.0)
                        restoredBinTotalEnergy / restoredBinTotalDist
                    else null
                )
                    // Trip-cumulative fallback — mirrors the live path's tripWhPerKm.
                    // cumEnergyWh / trip distance always resolves once the restored
                    // trip has moved ≥ BIN_MIN_DIST_KM and consumed anything, so a
                    // restored trip whose stored per-pair deltas didn't land in any
                    // bin still leaves the catalog baseline.
                    ?: run {
                        // EV-only denominator on PHEVs — mirrors the live tripWhPerKm.
                        val restoreRateDistKm = if (isPhevRestore) evAxisKm else liveDistanceKm
                        if (restoreRateDistKm >= BIN_MIN_DIST_KM && cumEnergyWh > 0.0)
                            cumEnergyWh / restoreRateDistKm
                        else null
                    }
                // EV-only projection (matches the live path) — fuel range is excluded because it
                // isn't driven by EV consumption and would pin PHEVs at the EV-WLTP cap.
                // Each point is projected from the rate that was current AT THAT POINT
                // (perPointSmoothed), not one trip-wide rate, so the rebuilt line tracks
                // the live curve's shape instead of flattening. Tier order mirrors the
                // live path: rolling EMA once past the stabilisation distance, then a
                // progressive trip-cumulative fallback, then the catalog baseline.
                val isPhevCar = car?.isPhev == true
                val rebuiltPoints = dataPoints.mapIndexed { i, dp ->
                    val dKm = (dp.odometer - trip.startOdometer).coerceAtLeast(0.0)
                    val ppSmoothed = perPointSmoothed.getOrNull(i)?.takeIf { it > 0.0 }
                    // Progressive trip-cumulative rate (energy so far ÷ distance so far) —
                    // the restore analogue of the live tripWhPerKm tier; available from
                    // ~BIN_MIN_DIST_KM so the line still reaches back toward the origin.
                    // The sample's own distance axis is used: on PHEVs that's the EV-only
                    // distance (matching the live rateDistKm), on BEVs it equals dKm.
                    val cumEnergyHere = energySamples.getOrNull(i)?.cumulativeEnergyWh ?: 0.0
                    val ppRateDistKm = energySamples.getOrNull(i)?.distanceKm ?: dKm
                    val ppTrip = if (ppRateDistKm >= BIN_MIN_DIST_KM && cumEnergyHere > 0.0)
                        cumEnergyHere / ppRateDistKm else null
                    val pastStabilisation = ppRateDistKm >= STABILISATION_KM
                    // A point is stabilised if past the distance threshold OR a non-baseline
                    // rate exists — matches the live `isStabilised || model != BASELINE`.
                    val stabilised = pastStabilisation || ppTrip != null
                    val pointSoc = dp.soc.takeIf { it > 0 } ?: dp.socPanel.toDouble()
                    // Match the live path's numerator exactly: for PHEVs prefer the BMS's own
                    // remaining-EV-energy reading (persisted per point since DB v10), which nets
                    // out the charge-sustaining reserve, over capacity × SoC. Pre-v10 / BEV /
                    // non-reporting rows have batteryRemainPowerEV == null and fall back to the
                    // SoC product, exactly as before — so this can only improve a PHEV rebuild,
                    // never regress one, and never touches BEVs.
                    val remainingWh = remainingEvEnergyWh(
                        batteryKwh, pointSoc, dp.batteryRemainPowerEV, isPhevCar
                    )
                    // Same early-distance shrinkage toward the stable prior as the live path, so a
                    // rebuilt curve doesn't spike at trip start where ppTrip = energy ÷ tiny distance
                    // is numerically wild.
                    val ppStablePrior = lifetimeWhPerKm.value ?: baselineWhPerKm
                    val ppConfidence = (ppRateDistKm / STABILISATION_KM).coerceIn(0.0, 1.0)
                    val ppDemonstrated: Double? = ppTrip?.let {
                        ppConfidence * it + (1.0 - ppConfidence) * ppStablePrior
                    }
                    // Rate tier with the same TRIP_AVERAGE→LIVE_TRIP crossfade as the live path,
                    // so the rebuilt line keeps the live curve's shape (no step at STABILISATION_KM).
                    val ppRate: Double = when {
                        ppSmoothed != null && ppDemonstrated != null && pastStabilisation -> {
                            val liveBlend = ((ppRateDistKm - STABILISATION_KM) / STABILISATION_KM).coerceIn(0.0, 1.0)
                            liveBlend * ppSmoothed + (1.0 - liveBlend) * ppDemonstrated
                        }
                        pastStabilisation && ppSmoothed != null -> ppSmoothed
                        ppDemonstrated != null -> ppDemonstrated
                        else -> baselineWhPerKm
                    }
                    // Same optimism-cap anchor as the live path (the shrunk demonstrated rate, else
                    // the stable prior), so the rebuilt curve's downhill excursions are bounded
                    // identically.
                    val ppReference = ppDemonstrated ?: ppStablePrior
                    // Null for non-stabilised points — matches the live path so the orange line
                    // doesn't appear before it would on a live drive. A non-positive remaining-
                    // energy read (a cold-BMS point with soc=socPanel=0) is emitted as null, not a
                    // projected 0 — mirrors the live guard against a false spike-to-zero.
                    val projected: Double? = when {
                        !stabilised -> null
                        remainingWh <= 0.0 -> null
                        else -> projectedEvRangeKm(remainingWh, ppRate, baselineWhPerKm, isPhevCar, ppReference)
                    }
                    RangeDataPoint(
                        distanceKm             = dKm,
                        soc                    = pointSoc,
                        electricDrivingRangeKm = dp.electricDrivingRangeKm,
                        projectedRangeKm       = projected,
                        isStabilised           = stabilised
                    )
                }

                // Prefer the cached as-computed-live curve over the single-rate
                // rebuild when one survives for this trip (in memory after a back
                // press, or on disk after a cold start), appending only the rebuilt
                // tail for any distance driven while the cache was dead. Falls back
                // to the rebuild verbatim when there's no usable cache.
                _tripDataPoints.value = mergeProjectionCurve(
                    cached = cachedCurve,
                    rebuilt = rebuiltPoints,
                    liveDistanceKm = liveDistanceKm
                )

                // Pick the same tier the live path would (line ~1077). Without checking
                // bins, a restored trip with valid bin data but no smoothed EMA would
                // wrongly show BASELINE on the badge even though the line is drawn from
                // a non-baseline calculation. Reuses [restoredBinWhPerKm] computed above
                // for the stabilised gating.
                setActiveRangeModel(when {
                    smoothedWhPerKm != null && smoothedWhPerKm!! > 0.0 -> RangeModel.LIVE_TRIP
                    restoredBinWhPerKm != null                          -> RangeModel.TRIP_AVERAGE
                    lifetimeWhPerKm.value != null                       -> RangeModel.LIFETIME_AVERAGE
                    else                                                -> RangeModel.BASELINE
                })
                lastTelemetryWasCarOn = telemetry.isCarOn
                segmentOffSinceMs = null
                Log.i(TAG, "Restored trip state for id=$tripId with ${dataPoints.size} points")
            }
        }
    }

    // ── Mock drive ────────────────────────────────────────────────────────────

    fun startMockDrive() {
        if (_isMockModeActive.value) {
            stopMockDrive()
            return
        }

        mockDriveJob?.cancel()
        _isMockModeActive.value = true
        mockDriveJob = viewModelScope.launch {
            // The mock drive follows the selected car: picking a PHEV in the car
            // picker produces a DM-i style mock (EV → HEV charge-sustain → EV)
            // with all fuel/ICE fields populated, so PHEV UI can be exercised
            // without a real PHEV.
            val car = currentCarConfig()
            val mockGenerator = com.byd.tripstats.mock.MockDataGenerator(
                phev = car?.isPhev == true,
                batteryKwh = car?.let {
                    if (it.isPhev) it.phevUsableBatteryKwh ?: it.batteryKwh
                    else it.batteryKwh
                } ?: 82.5,
            )
            mockGenerator.generateMockDrive().collect { telemetry ->
                _mockTelemetry.value = telemetry
                _mockVehicleSnapshot.value = telemetry.toMockVehicleSnapshot()
            }
            stopMockDrive()
        }
    }

    fun stopMockDrive() {
        mockDriveJob?.cancel()
        mockDriveJob = null
        _mockTelemetry.value = null
        _mockVehicleSnapshot.value = null
        _isMockModeActive.value = false
    }

    // ── Trip replay (dev tool, behind the same flag as the mock button) ───────

    /**
     * Replays an exported trip JSON from Download/BydTripStats/replay.json through
     * TripRepository.processTelemetry — the full production pipeline (auto trip
     * detection, projection, DB writes) runs against a real recorded drive, e.g.
     * one a PHEV owner shared. Dev tool: the replayed trip IS recorded into the
     * database of the device it runs on.
     */
    fun startTripReplay(speedMultiplier: Double = 10.0) {
        if (_isReplayActive.value) {
            stopTripReplay()
            return
        }
        replayJob?.cancel()
        _isReplayActive.value = true
        _replayStatus.value = "Replay: loading…"
        replayJob = viewModelScope.launch {
            try {
                val file = java.io.File(
                    android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS
                    ),
                    "BydTripStats/replay.json"
                )
                if (!file.exists()) {
                    _replayStatus.value = "Replay: ${file.path} not found"
                    return@launch
                }
                val points = withContext(Dispatchers.IO) {
                    com.byd.tripstats.mock.TripReplayEngine.parse(file.readText())
                }
                var fed = 0
                com.byd.tripstats.mock.TripReplayEngine
                    .replayFlow(points, speedMultiplier)
                    .collect { telemetry ->
                        tripRepository.processTelemetry(telemetry)
                        fed++
                        if (fed % 25 == 0) {
                            _replayStatus.value = "Replay: $fed/${points.size}"
                        }
                    }
                _replayStatus.value = "Replay: done (${points.size} points)"
            } catch (e: Exception) {
                Log.e(TAG, "Trip replay failed", e)
                _replayStatus.value = "Replay failed: ${e.message}"
            } finally {
                _isReplayActive.value = false
                replayJob = null
            }
        }
    }

    fun stopTripReplay() {
        replayJob?.cancel()
        replayJob = null
        _isReplayActive.value = false
        _replayStatus.value = "Replay: stopped"
    }

    private fun VehicleTelemetry.toMockVehicleSnapshot(): VehicleTelemetrySnapshot {
        return VehicleTelemetrySnapshot(
            directSpeedKmh = speed,
            gear = gear,
            chargingPower = chargingPower,
            battery12vVoltage = battery12vVoltage,
            batteryPackTemp = batteryPackTemp.takeIf { it > 0.0 },
            batteryCellVoltageMin = batteryCellVoltageMin.takeIf { it > 0.0 },
            batteryCellVoltageMax = batteryCellVoltageMax.takeIf { it > 0.0 },
            batteryCellTempMin = batteryCellTempMin.takeIf { it > 0 },
            batteryCellTempMax = batteryCellTempMax.takeIf { it > 0 },
            statisticCellVoltageMin = batteryCellVoltageMin.takeIf { it > 0.0 },
            statisticCellVoltageMax = batteryCellVoltageMax.takeIf { it > 0.0 },
            statisticCellTempMin = batteryCellTempMin.takeIf { it > 0 }?.toDouble(),
            statisticCellTempMax = batteryCellTempMax.takeIf { it > 0 }?.toDouble(),
            statisticSocBatteryPct = soc,
            statisticElecPercentageValue = socPanel.toDouble(),
            enginePower = enginePower,
            engineSpeedFront = engineSpeedFront,
            engineSpeedRear = engineSpeedRear,
            powerBatteryRemainPowerEV = batteryRemainPowerEV,
            locationLatitude = locationLatitude,
            locationLongitude = locationLongitude,
            locationAltitude = locationAltitude,
            locationGpsSpeed = locationGpsSpeed,
            locationOrientation = locationOrientation,
            probeValues = probeValues,
            tyrePressureLFPsi = tyrePressureLF,
            tyrePressureRFPsi = tyrePressureRF,
            tyrePressureLRPsi = tyrePressureLR,
            tyrePressureRRPsi = tyrePressureRR,
            turnSignalFlashState = turnSignalFlashState,
            turnSignalLeft = turnSignalLeft,
            turnSignalRight = turnSignalRight,
            energyMode = energyMode,
            statisticFuelPercentageValue = fuelPercentage.takeIf { it > 0 },
            statisticFuelDrivingRangeValue = fuelDrivingRangeKm.takeIf { it > 0 },
            statisticAvgFuelConsumption = avgFuelConsumption,
            statisticInstantFuelCon = instantFuelConsumption,
            statisticTotalFuelConValue = totalFuelConsumption,
            statisticEvMileageValue = evMileageKm,
            statisticHevMileageValue = iceMileageKm,
            statisticWaterTemperature = engineCoolantTemp,
        )
    }

    // ── Vehicle service management ─────────────────────────────────────────────

    /** Called from MainActivity when service binding is established. */
    fun observeTelemetryServiceState(service: VehicleTelemetryService) {
        telemetryService = service
        serviceObserverJob?.cancel()
        serviceObserverJob = viewModelScope.launch {
            launch {
                service.connectionState.collect { state ->
                    when (state) {
                        is VehicleTelemetryService.ConnectionState.Connected    -> {
                            _serviceConnected.value      = true
                            _serviceConnectionError.value = null
                        }
                        is VehicleTelemetryService.ConnectionState.Error        -> {
                            _serviceConnected.value      = false
                            _serviceConnectionError.value = state.message
                        }
                        is VehicleTelemetryService.ConnectionState.Connecting,
                        is VehicleTelemetryService.ConnectionState.Disconnected -> {
                            _serviceConnected.value      = false
                            _serviceConnectionError.value = null
                        }
                    }
                }
            }
            launch {
                service.telemetrySnapshot.collect { snapshot ->
                    _vehicleSnapshot.value = snapshot
                }
            }
        }
    }

    fun refreshVehicleSnapshot() {
        telemetryService?.refreshVehicleSnapshot()
    }

    fun stopTelemetryService() {
        VehicleTelemetryService.stop(getApplication())
        serviceObserverJob?.cancel()
        serviceObserverJob = null
        _serviceConnected.value       = false
        _serviceConnectionError.value = null
    }

    /** Restarts the vehicle telemetry service. */
    fun restartTelemetryService() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "Restarting vehicle telemetry service")
                VehicleTelemetryService.stop(getApplication())
                delay(1_000)
                VehicleTelemetryService.start(getApplication())
                Log.d(TAG, "Vehicle telemetry service restarted")
            } catch (e: Exception) {
                Log.e(TAG, "Error restarting vehicle telemetry service", e)
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
        val maxSpeedMax:    Float? = null,
        // Quick toggle (separate star chip in the UI) — not part of the numeric
        // range-filter sheet, so deliberately excluded from activeFilterCount.
        val favouritesOnly: Boolean = false,
        // Tag filter (chosen inside the filter sheet). A trip passes when it carries
        // at least one of these tags (OR). Empty = no tag filter.
        val tagIds: Set<Long> = emptySet()
    ) {
        val activeFilterCount: Int get() = listOf(
            distanceMin, distanceMax, durationMin, durationMax,
            consumptionMin, consumptionMax, regenEffMin, regenEffMax,
            maxSpeedMin, maxSpeedMax
        ).count { it != null } + (if (tagIds.isEmpty()) 0 else 1)
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
            maxSpeedMax    = getFloatOrNull("f_speed_max"),
            favouritesOnly = getBoolean("f_favourites_only", false),
            tagIds         = getString("f_tag_ids", "")
                ?.split(",")?.mapNotNull { it.toLongOrNull() }?.toSet() ?: emptySet()
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
            putBoolean("f_favourites_only", f.favouritesOnly)
            if (f.tagIds.isEmpty()) remove("f_tag_ids")
            else putString("f_tag_ids", f.tagIds.joinToString(","))
            apply()
        }
    }

    /** Clears only the numeric range filters; preserves the favourites-only toggle. */
    fun clearFilters() = setFilter(TripFilterState(favouritesOnly = _filterState.value.favouritesOnly))

    /** Sets the History tag filter (used by the Tags screen's "view trips" action). */
    fun setTagFilter(tagIds: Set<Long>) =
        setFilter(_filterState.value.copy(tagIds = tagIds))

    fun toggleFavouritesOnly() {
        setFilter(_filterState.value.copy(favouritesOnly = !_filterState.value.favouritesOnly))
    }

    fun setTripFavourite(tripId: Long, favourite: Boolean) {
        viewModelScope.launch { tripRepository.setTripFavourite(tripId, favourite) }
    }

    fun setChargingFavourite(sessionId: Long, favourite: Boolean) {
        viewModelScope.launch { chargingRepository.setFavourite(sessionId, favourite) }
    }

    /** Sets/clears the per-charge electricity price (currency/kWh). null reverts to the global
     *  tariff; 0.0 marks the charge as free. Propagates to derived trip costs via the interval link. */
    fun saveChargingSessionPrice(sessionId: Long, price: Double?) {
        viewModelScope.launch { chargingRepository.setSessionPrice(sessionId, price) }
    }

    // Folded so sortedFilteredTrips stays within combine's 5-flow arity while still
    // seeing per-trip tag membership for the tag filter.
    private val filterAndTags = combine(_filterState, tripTagsMap) { f, m -> f to m }

    val sortedFilteredTrips: StateFlow<List<TripEntity>> =
        combine(allTrips, tripDisplayMetrics, _sortField, _sortOrder, filterAndTags) {
            trips, metrics, field, order, filterTags ->

            val (filter, tagMap) = filterTags
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
                (filter.maxSpeedMax    == null || spd    <= filter.maxSpeedMax)    &&
                (!filter.favouritesOnly || trip.isFavourite)                       &&
                (filter.tagIds.isEmpty() ||
                    tagMap[trip.id]?.any { it.id in filter.tagIds } == true)
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
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ── Trip deletion ─────────────────────────────────────────────────────────

    fun deleteTrip(tripId: Long) {
        viewModelScope.launch { tripRepository.deleteTrip(tripId) }
    }

    fun deleteTrips(tripIds: List<Long>) {
        viewModelScope.launch { tripRepository.deleteTrips(tripIds) }
    }

    // ── Trip merging ────────────────────────────────────────────────────────────

    /** Pure eligibility check the UI uses to gate the merge action / show a reason. */
    fun checkMergeEligibility(a: TripEntity, b: TripEntity): MergeEligibility =
        TripRepository.checkMergeEligibility(a, b)

    /**
     * Merges exactly two completed trips into one (earlier survives). [onResult] is
     * invoked on the main thread so the caller can surface success/failure.
     */
    fun mergeTrips(tripIds: List<Long>, onResult: (MergeResult) -> Unit) {
        viewModelScope.launch {
            val result = if (tripIds.size != 2) {
                MergeResult.Failure("Select exactly two trips to merge")
            } else {
                tripRepository.mergeTrips(tripIds[0], tripIds[1])
            }
            onResult(result)
        }
    }

    // --- Delete charging sessions
    fun deleteChargingSession(sessionId: Long) {
        viewModelScope.launch { chargingRepository.deleteSession(sessionId) }
    }

    fun deleteChargingSessions(sessionIds: List<Long>) {
        viewModelScope.launch { chargingRepository.deleteSessions(sessionIds) }
    }

    // ── Charging session helpers ───────────────────────────────────────────────

    suspend fun getChargingSessionDataPoints(sessionId: Long): List<ChargingDataPointEntity> =
        chargingRepository.getDataPointsForSessionSync(sessionId)

    // ── Trip comparison ───────────────────────────────────────────────────────

    /**
     * Keyed by tripId. Populated by [loadCompareData], cleared by [clearCompareData].
     * Charts and route tab in TripCompareSheet read from this.
     */
    private val _compareDataPoints = MutableStateFlow<Map<Long, List<TripDataPointEntity>>>(emptyMap())
    val compareDataPoints: StateFlow<Map<Long, List<TripDataPointEntity>>> =
        _compareDataPoints.asStateFlow()

    /**
     * Pre-fetches data points for all trips in [tripIds] in parallel and stores
     * them keyed by trip ID. Call before opening the compare sheet.
     */
    fun loadCompareData(tripIds: List<Long>) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = tripIds.associateWith { id ->
                // first() collects a single emission from the Flow and cancels;
                // equivalent to a one-shot DB query without exposing a sync method.
                tripRepository.getDataPointsForTrip(id).first()
            }
            _compareDataPoints.value = result
        }
    }

    fun clearCompareData() {
        _compareDataPoints.value = emptyMap()
    }

    /** Returns TripStatsEntity for each of the given tripIds from the cached allTripStats flow. */
    fun getCompareStats(tripIds: List<Long>): List<TripStatsEntity> {
        val statsById = allTripStats.value.associateBy { it.tripId }
        return tripIds.mapNotNull { statsById[it] }
    }

    // ── Monthly cost summary ─────────────────────────────────────────────────

    data class MonthlyCost(
        val label     : String,  // e.g. "Mar 2026"
        val costAmount: Double,  // total cost for the month
        val kwhTotal  : Double,  // total kWh for the month
        val tripCount : Int,
        val cumulativeCost: Double = 0.0  // running total across the shown window (oldest→this month)
    )

    // Per-trip cost now resolves each trip's effective rate (override → supplying charge →
    // global tariff) instead of applying one flat tariff, so free/priced charges and per-trip
    // overrides are reflected. No longer gated on the global tariff being > 0: a user who only
    // sets per-charge prices still gets a cost history.
    val monthlyCosts: StateFlow<List<MonthlyCost>> =
        combine(allTrips, allChargingSessions, electricityPricePerKwh) { trips, sessions, pricePerKwh ->
            val blendedRates = CostAttribution.blendedTripRates(trips, sessions, pricePerKwh)
            val fmt = java.text.SimpleDateFormat("MMM yyyy", java.util.Locale.getDefault())
            val cal = java.util.Calendar.getInstance()
            fun monthStart(t: Long): Long {
                cal.timeInMillis = t
                cal.set(java.util.Calendar.DAY_OF_MONTH, 1)
                cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
                cal.set(java.util.Calendar.MINUTE, 0)
                cal.set(java.util.Calendar.SECOND, 0)
                cal.set(java.util.Calendar.MILLISECOND, 0)
                return cal.timeInMillis
            }
            // (monthStart, kwh, cost) for every completed trip that has a resolvable rate.
            val priced = trips
                .filter { !it.isActive && it.energyConsumed != null && it.energyConsumed!! > 0 }
                .mapNotNull { trip ->
                    val rate = blendedRates[trip.id]
                        ?: return@mapNotNull null
                    val kwh = trip.energyConsumed!!
                    Triple(monthStart(trip.startTime), kwh, kwh * rate)
                }
            if (priced.isEmpty()) return@combine emptyList()

            val byMonth = priced.groupBy { it.first }
            // Running cumulative computed oldest→newest, then presented newest-first (12 months).
            var running = 0.0
            val cumulativeByMonth = HashMap<Long, Double>()
            byMonth.keys.sorted().forEach { epoch ->
                running += byMonth.getValue(epoch).sumOf { it.third }
                cumulativeByMonth[epoch] = running
            }
            byMonth.entries
                .sortedByDescending { it.key }
                .take(13)   // 13 months so the current month lines up with the same month last year
                .map { (epochMs, rows) ->
                    MonthlyCost(
                        label          = fmt.format(java.util.Date(epochMs)),
                        costAmount     = rows.sumOf { it.third },
                        kwhTotal       = rows.sumOf { it.second },
                        tripCount      = rows.size,
                        cumulativeCost = cumulativeByMonth[epochMs] ?: 0.0
                    )
                }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ── Seasonal analysis ────────────────────────────────────────────────────

    enum class Season(val label: String, val emoji: String) {
        SPRING("Spring", "🌱"),
        SUMMER("Summer", "☀️"),
        AUTUMN("Autumn", "🍂"),
        WINTER("Winter", "❄️")
    }

    data class SeasonStats(
        val season          : Season,
        val avgConsumption  : Double,   // kWh/100km
        val avgTempC        : Double,   // avg battery temp as proxy for ambient
        val tripCount       : Int,
        val totalDistanceKm : Double,
        val totalKwh        : Double
    )

    /** Groups completed trips by meteorological season across all recorded years. */
    val seasonalStats: StateFlow<List<SeasonStats>> = allTrips
        .map { trips ->
            val cal = Calendar.getInstance()
            val hemisphere = currentHemisphere()
            val completed = trips.filter {
                !it.isActive &&
                it.efficiency != null &&
                (it.distance ?: 0.0) >= 1.0
            }
            Season.entries.mapNotNull { season ->
                val seasonTrips = completed.filter { trip ->
                    cal.timeInMillis = trip.startTime
                    val month = cal.get(Calendar.MONTH) + 1  // 1-based
                    seasonForMonth(month, hemisphere) == season
                }
                if (seasonTrips.isEmpty()) return@mapNotNull null
                SeasonStats(
                    season         = season,
                    avgConsumption = seasonTrips.mapNotNull { it.efficiency }.average(),
                    avgTempC       = seasonTrips.map { it.avgBatteryTemp }.average(),
                    tripCount      = seasonTrips.size,
                    totalDistanceKm = seasonTrips.sumOf { it.distance ?: 0.0 },
                    totalKwh       = seasonTrips.sumOf { it.energyConsumed ?: 0.0 }
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ── Recurring route detection ───────────────────────────────────────────────

    /**
     * Completed trips grouped into recurring routes (same start, end and ~distance;
     * direction-sensitive), each driven at least [RouteGrouping.DEFAULT_MIN_INSTANCES]
     * times. Joins [allTrips] (efficiency / distance / duration) with [allTripStats]
     * (start/end coordinates). The clustering runs on Dispatchers.Default so a large
     * history doesn't block the main thread. See [RouteGrouping].
     */
    val recurringRoutes: StateFlow<List<RouteGroup>> =
        combine(allTrips, allTripStats) { trips, stats ->
            val statsById = stats.associateBy { it.tripId }
            val inputs = trips.mapNotNull { trip ->
                if (trip.isActive) return@mapNotNull null
                val s = statsById[trip.id] ?: return@mapNotNull null
                val eff = trip.efficiency ?: return@mapNotNull null
                val dist = trip.distance ?: return@mapNotNull null
                val dur = trip.duration ?: return@mapNotNull null
                if (dist < 0.5) return@mapNotNull null
                if (!RouteGrouping.hasValidGps(s.startLatitude, s.startLongitude)) return@mapNotNull null
                if (!RouteGrouping.hasValidGps(s.endLatitude, s.endLongitude)) return@mapNotNull null
                RouteTripInput(
                    tripId = trip.id,
                    startTime = trip.startTime,
                    startLat = s.startLatitude,
                    startLon = s.startLongitude,
                    endLat = s.endLatitude,
                    endLon = s.endLongitude,
                    distanceKm = dist,
                    efficiencyKwhPer100km = eff,
                    durationMs = dur,
                    energyKwh = trip.energyConsumed ?: 0.0
                )
            }
            RouteGrouping.group(inputs)
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ── Tag operations & per-tag analytics ──────────────────────────────────────

    /** Tags on a single trip — observed by the Trip Detail screen. */
    fun tagsForTrip(tripId: Long): Flow<List<TagEntity>> = tripRepository.getTagsForTrip(tripId)

    /** Creates [name] if needed (case-insensitive) and applies it to [tripId]. */
    fun addNewTagToTrip(tripId: Long, name: String) {
        viewModelScope.launch {
            tripRepository.createOrGetTag(name)?.let { tripRepository.addTagToTrip(tripId, it.id) }
        }
    }

    fun addTagToTrip(tripId: Long, tagId: Long) {
        viewModelScope.launch { tripRepository.addTagToTrip(tripId, tagId) }
    }

    fun removeTagFromTrip(tripId: Long, tagId: Long) {
        viewModelScope.launch { tripRepository.removeTagFromTrip(tripId, tagId) }
    }

    /** Bulk-applies an existing tag to several trips (History selection mode). */
    fun applyTagToTrips(tagId: Long, tripIds: List<Long>) {
        viewModelScope.launch { tripRepository.applyTagToTrips(tagId, tripIds) }
    }

    /** Creates [name] if needed and bulk-applies it to [tripIds]. */
    fun addNewTagToTrips(tripIds: List<Long>, name: String) {
        viewModelScope.launch {
            tripRepository.createOrGetTag(name)?.let {
                tripRepository.applyTagToTrips(it.id, tripIds)
            }
        }
    }

    fun renameTag(tagId: Long, newName: String) {
        viewModelScope.launch { tripRepository.renameTag(tagId, newName) }
    }

    fun recolorTag(tagId: Long, colorIndex: Int) {
        viewModelScope.launch { tripRepository.recolorTag(tagId, colorIndex) }
    }

    fun deleteTag(tagId: Long) {
        viewModelScope.launch {
            tripRepository.deleteTag(tagId)
            // Drop the now-stale tag from any active filter so it can't hide trips.
            if (tagId in _filterState.value.tagIds) {
                setFilter(_filterState.value.copy(tagIds = _filterState.value.tagIds - tagId))
            }
        }
    }

    data class TagStat(
        val tag: TagEntity,
        val tripCount: Int,
        val totalDistanceKm: Double,
        val avgConsumption: Double,   // kWh/100km; 0 when no trip carries an efficiency
        val totalKwh: Double
    )

    /** Per-tag rollups for the Tags screen (count, distance, avg efficiency). */
    val tagStats: StateFlow<List<TagStat>> =
        combine(allTrips, tripTagRefs, allTags) { trips, refs, tags ->
            val completedById = trips.filter { !it.isActive }.associateBy { it.id }
            val tripsByTag = refs.groupBy { it.tagId }
            tags.map { tag ->
                val tagTrips = tripsByTag[tag.id].orEmpty().mapNotNull { completedById[it.tripId] }
                val effs = tagTrips.mapNotNull { it.efficiency }
                TagStat(
                    tag             = tag,
                    tripCount       = tagTrips.size,
                    totalDistanceKm = tagTrips.sumOf { it.distance ?: 0.0 },
                    avgConsumption  = if (effs.isNotEmpty()) effs.average() else 0.0,
                    totalKwh        = tagTrips.sumOf { it.energyConsumed ?: 0.0 }
                )
            }.sortedWith(compareByDescending<TagStat> { it.tripCount }.thenBy { it.tag.name.lowercase() })
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ── Trip goals ────────────────────────────────────────────────────────────

    data class TripGoals(
        val targetConsumptionKwhPer100km: Double? = null,  // null = not set
        val targetDistanceKmPerMonth     : Double? = null
    )

    data class PersonalBests(
        val bestConsumption : Double?,  // lowest kWh/100km, min 1 km distance
        val bestDistance    : Double?,  // longest single trip
        val longestStreak   : Int       // consecutive days with ≥1 trip
    )

    private val GOAL_CONSUMPTION_KEY = "goal_consumption"
    private val GOAL_DISTANCE_KEY    = "goal_distance_monthly"
    private val goalPrefs by lazy {
        getApplication<android.app.Application>()
            .getSharedPreferences("trip_goals", 0)
    }

    private val _tripGoals = MutableStateFlow(
        TripGoals(
            targetConsumptionKwhPer100km = goalPrefs.getFloat(GOAL_CONSUMPTION_KEY, 0f)
                .takeIf { it > 0f }?.toDouble(),
            targetDistanceKmPerMonth     = goalPrefs.getFloat(GOAL_DISTANCE_KEY, 0f)
                .takeIf { it > 0f }?.toDouble()
        )
    )
    val tripGoals: StateFlow<TripGoals> = _tripGoals.asStateFlow()

    fun saveTripGoals(consumption: Double?, distancePerMonth: Double?) {
        _tripGoals.value = TripGoals(consumption, distancePerMonth)
        goalPrefs.edit().apply {
            if (consumption != null) putFloat(GOAL_CONSUMPTION_KEY, consumption.toFloat())
            else remove(GOAL_CONSUMPTION_KEY)
            if (distancePerMonth != null) putFloat(GOAL_DISTANCE_KEY, distancePerMonth.toFloat())
            else remove(GOAL_DISTANCE_KEY)
        }.apply()
    }

    val personalBests: StateFlow<PersonalBests> = allTrips
        .map { trips ->
            val completed = trips.filter { !it.isActive }
            val bestCons = completed
                .filter { (it.distance ?: 0.0) >= 1.0 && it.efficiency != null && it.efficiency!! >= 10.0 }
                .minOfOrNull { it.efficiency!! }
            val bestDist = completed.maxOfOrNull { it.distance ?: 0.0 }

            // Longest streak: consecutive calendar days with at least one trip
            val cal = Calendar.getInstance()
            val tripDays = completed.map { trip ->
                cal.timeInMillis = trip.startTime
                cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0);       cal.set(Calendar.MILLISECOND, 0)
                cal.timeInMillis
            }.toSortedSet()
            val dayMs = 86_400_000L
            var longestStreak = if (tripDays.isEmpty()) 0 else 1
            var currentStreak = 1
            tripDays.zipWithNext { a, b ->
                if (b - a == dayMs) {
                    currentStreak++
                    if (currentStreak > longestStreak) longestStreak = currentStreak
                } else {
                    currentStreak = 1
                }
            }

            PersonalBests(bestCons, bestDist, longestStreak)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000),
            PersonalBests(null, null, 0))

    /**
     * Distance driven this calendar month (completed trips only).
     * Used to track progress against monthly distance goal.
     */
    val distanceThisMonth: StateFlow<Double> = allTrips
        .map { trips ->
            val monthStart = Calendar.getInstance().apply {
                set(Calendar.DAY_OF_MONTH, 1)
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0);       set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            trips.filter { !it.isActive && it.startTime >= monthStart }
                 .sumOf { it.distance ?: 0.0 }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0.0)

    // ── Battery degradation ──────────────────────────────────────────────────

    /**
     * One entry per completed trip that has SoH data recorded.
     * Sorted chronologically — ready for the degradation chart to consume directly.
     */
    data class SohDataPoint(
        val tripId     : Long,
        val timestamp  : Long,   // trip start time — used as X axis
        val odometer   : Double, // trip start odometer — alternative X axis
        val avgSoh     : Double  // average SoH across all data points in the trip
    )

    // Statistical SoH shipped in v2.4.0 (2026-05-13). Trips recorded before this were
    // produced by the legacy *calculated* method, which under-estimated SoH and creates a
    // spurious dip-and-recover in the chart, so they are excluded by default. (= 2026-05-13
    // 00:00 UTC in epoch ms.)
    private val LEGACY_SOH_CUTOVER_MS = 1_778_630_400_000L

    /** How the legacy SoH era is excluded from the degradation views. */
    enum class SohExclusionMode { AUTO, CUSTOM, OFF }

    /** Resolved exclusion state. [cutoffMs] is the "exclude trips before" epoch, null when OFF. */
    data class SohExclusion(val mode: SohExclusionMode, val cutoffMs: Long?)

    /**
     * Resolves the persisted prefs (and the legacy baseline key) into an effective
     * exclusion. Defaults to AUTO so the legacy calculated-SoH dip is hidden out of the
     * box; an existing user's old baseline is preserved as a CUSTOM cutoff until they
     * choose otherwise.
     */
    val sohExclusion: StateFlow<SohExclusion> =
        combine(
            preferencesManager.sohExclusionMode,
            preferencesManager.sohCustomCutoffMs,
            preferencesManager.sohBaselineEpochMs
        ) { mode, custom, legacy ->
            when (mode) {
                "OFF"    -> SohExclusion(SohExclusionMode.OFF, null)
                "CUSTOM" -> SohExclusion(SohExclusionMode.CUSTOM, custom ?: LEGACY_SOH_CUTOVER_MS)
                "AUTO"   -> SohExclusion(SohExclusionMode.AUTO, LEGACY_SOH_CUTOVER_MS)
                else     ->  // never explicitly set: migrate a legacy baseline, else default to AUTO
                    if (legacy != null) SohExclusion(SohExclusionMode.CUSTOM, legacy)
                    else SohExclusion(SohExclusionMode.AUTO, LEGACY_SOH_CUTOVER_MS)
            }
        }.stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5_000),
            SohExclusion(SohExclusionMode.AUTO, LEGACY_SOH_CUTOVER_MS)
        )

    fun setSohExclusionAuto() {
        viewModelScope.launch { preferencesManager.saveSohExclusionMode("AUTO") }
    }

    fun setSohExclusionOff() {
        viewModelScope.launch { preferencesManager.saveSohExclusionMode("OFF") }
    }

    fun setSohExclusionCutoff(epochMs: Long) {
        viewModelScope.launch { preferencesManager.saveSohCustomCutoffMs(epochMs) }
    }

    /** Re-apply a previous exclusion state — used by the Snackbar UNDO action. */
    fun restoreSohExclusion(prev: SohExclusion) {
        when (prev.mode) {
            SohExclusionMode.AUTO   -> setSohExclusionAuto()
            SohExclusionMode.OFF    -> setSohExclusionOff()
            SohExclusionMode.CUSTOM -> setSohExclusionCutoff(prev.cutoffMs ?: LEGACY_SOH_CUTOVER_MS)
        }
    }

    /** All completed trips with usable SoH, before exclusion — source for both views below. */
    private val eligibleSohPoints: Flow<List<SohDataPoint>> =
        combine(
            tripRepository.getAllTrips(),
            tripRepository.getAvgSohPerTrip()
        ) { trips, sohSummaries ->
            val tripById = trips.associateBy { it.id }
            sohSummaries
                .mapNotNull { summary ->
                    val trip = tripById[summary.tripId] ?: return@mapNotNull null
                    if (trip.isActive) return@mapNotNull null          // skip live trip
                    if (summary.avgSoh < 50.0) return@mapNotNull null  // sanity filter
                    SohDataPoint(
                        tripId    = trip.id,
                        timestamp = trip.startTime,
                        odometer  = trip.startOdometer,
                        avgSoh    = summary.avgSoh
                    )
                }
                .sortedBy { it.timestamp }
        }

    val batteryDegradationData: StateFlow<List<SohDataPoint>> =
        combine(eligibleSohPoints, sohExclusion) { points, exclusion ->
            val cutoff = exclusion.cutoffMs ?: return@combine points
            points.filter { it.timestamp >= cutoff }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Count of eligible trips hidden by the current exclusion (for UI + the report note). */
    val sohExcludedTripCount: StateFlow<Int> =
        combine(eligibleSohPoints, sohExclusion) { points, exclusion ->
            val cutoff = exclusion.cutoffMs ?: return@combine 0
            points.count { it.timestamp < cutoff }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
}
