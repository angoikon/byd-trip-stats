package com.byd.tripstats.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.byd.tripstats.adb.AdbPermissionManager
import com.byd.tripstats.data.local.BydStatsDatabase
import com.byd.tripstats.data.local.dao.TripSohSummary
import com.byd.tripstats.data.analysis.CostAttribution
import com.byd.tripstats.data.analysis.InstantConsumptionEstimator
import com.byd.tripstats.data.analysis.RouteGroup
import com.byd.tripstats.data.analysis.RouteGrouping
import com.byd.tripstats.data.analysis.RouteTripInput
import com.byd.tripstats.data.analysis.TripReport
import com.byd.tripstats.data.config.CarConfig
import com.byd.tripstats.data.local.entity.ChargingDataPointEntity
import com.byd.tripstats.data.local.entity.ChargingSessionEntity
import com.byd.tripstats.data.local.entity.TagEntity
import com.byd.tripstats.data.local.entity.TripDataPointEntity
import com.byd.tripstats.data.local.entity.TripEntity
import com.byd.tripstats.data.local.entity.TripStatsEntity
import com.byd.tripstats.data.local.entity.TripTagCrossRef
import com.byd.tripstats.data.local.entity.combinedEfficiency
import com.byd.tripstats.data.model.BatteryVoltageHistoryPoint
import com.byd.tripstats.data.model.VehicleTelemetry
import com.byd.tripstats.data.preferences.PreferencesManager
import com.byd.tripstats.data.preferences.RangeWindow
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
import com.byd.tripstats.sdk.DiLink5Platform
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
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.combine as combineFlows
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transform
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

    // Throttled for the dashboard only: it recomposes on every emission, and the telemetry daemon
    // alone publishes up to 10 snapshots a second. A lone change (a gear shift, a speed step) still
    // goes out at once; only a burst inside DISPLAY_SNAPSHOT_MIN_INTERVAL_MS collapses to its
    // newest value, which is never dropped.
    val displayVehicleSnapshot: StateFlow<VehicleTelemetrySnapshot?> = combine(
        vehicleSnapshot.throttleLatest(DISPLAY_SNAPSHOT_MIN_INTERVAL_MS),
        _mockVehicleSnapshot
    ) { live, mock ->
        mock ?: live
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // Instant consumption for the dashboard's Power tile (see InstantConsumptionEstimator), from
    // the same power and speed the tile shows. Fed only while the dashboard is open — that includes
    // the app sitting in the background after Home, as collectAsState keeps collecting until the
    // activity closes; the extra load it learns starts over with each trip (see init).
    private val instantConsumption = InstantConsumptionEstimator()

    val instantConsumptionKwhPer100km: StateFlow<Double?> =
        combine(displayVehicleSnapshot, displayTelemetry) { snapshot, telemetry ->
            if (telemetry == null) return@combine null
            instantConsumption.update(
                timeMs = android.os.SystemClock.elapsedRealtime(),
                motorKw = (snapshot?.enginePower ?: telemetry.enginePower).toDouble(),
                speedKmh = snapshot?.directSpeedKmh?.takeIf { it > 0.1 } ?: telemetry.speed,
                totalDischargeKwh = telemetry.totalDischarge
            )
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

    /**
     * [allTrips] without the trip in progress, emitted only when a completed trip changes — one
     * closes, is merged, edited or deleted. The trip in progress rewrites its row every second and
     * Room re-runs the whole trips query on each write, so whatever is built from [allTrips] was
     * rebuilt every second of a drive. Filtered and compared off the main thread.
     */
    private val completedTrips: StateFlow<List<TripEntity>> = allTrips
        .map { trips -> trips.filter { !it.isActive } }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The recorded trips' combined consumption (kWh/100 km) and when the first of them started. */
    data class RecordedTripsAverage(val kwhPer100km: Double, val sinceMs: Long)

    /**
     * Consumption over every completed trip — energy over distance, as the car averages it. The
     * Total Discharge card's info dialog sets it beside the car's lifetime counter, which also
     * counts energy used between trips. Null with no completed trip that has a figure.
     */
    val recordedTripsAverage: StateFlow<RecordedTripsAverage?> = allTrips
        .map { trips ->
            val completed = trips.filter { !it.isActive }
            completed.combinedEfficiency()?.let { avg ->
                RecordedTripsAverage(avg, completed.minOf { it.startTime })
            }
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

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

    /**
     * Distance (km) driven since the most recent charge — the dashboard Distance tile's other
     * view (a tap toggles it with the session distance). Live odometer minus the charge's anchor
     * ([CostAttribution.lastChargeOdometer]), so a charge in progress reads about 0. Null when
     * there is no charge yet, no odometer reading (0 while the car wakes), or the delta is
     * negative (odometer glitch / reset). The anchor's trip fallback only reads ended trips, so
     * [completedTrips] gives the same anchor as [allTrips].
     */
    val distanceSinceLastChargeKm: StateFlow<Double?> =
        combine(
            combine(allChargingSessions, completedTrips) { sessions, trips ->
                CostAttribution.lastChargeOdometer(sessions, trips)
            }.distinctUntilChanged().flowOn(Dispatchers.Default),
            displayTelemetry.map { it?.odometer }.distinctUntilChanged()
        ) { anchor, odometer ->
            if (anchor == null || odometer == null || odometer <= 0.0) null
            else (odometer - anchor).takeIf { it >= 0.0 }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // How far back the range projection takes its consumption from (Settings → Preferences →
    // Battery). Eagerly so the telemetry loop can read .value synchronously.
    private val rangeWindow: StateFlow<RangeWindow> = preferencesManager.rangeWindow
        .stateIn(viewModelScope, SharingStarted.Eagerly, preferencesManager.getCachedRangeWindow())

    // The completed driving the projection's window reaches back into (see windowRate): the
    // newest plausible trips covering MAX_WINDOW_KM, the same trips the efficiency charts count.
    // Rebuilt only when that set changes — a trip closing, a merge, a delete — never on the
    // active trip's own updates, or when the car's levelling mass does (see WindowLeveller).
    // Eagerly so the telemetry loop can read .value synchronously (same reasoning as
    // selectedCarConfig); null with no completed trips yet.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val drivingHistory: StateFlow<DrivingHistory?> = combine(
        completedTrips.map { trips -> recentCompletedTrips(trips) }.distinctUntilChanged(),
        selectedCarConfig.map { levellingMassKg(it) }.distinctUntilChanged(),
    ) { trips, massKg -> trips to massKg }
        .mapLatest { (trips, massKg) -> loadDrivingHistory(trips, massKg) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private fun recentCompletedTrips(trips: List<TripEntity>): List<TripEntity> {
        var coveredKm = 0.0
        return trips
            .filter {
                !it.isActive &&
                    (it.distance ?: 0.0) >= 0.5 &&
                    it.efficiency?.let { e ->
                        e in MIN_VALID_CONSUMPTION_KWH_PER_100KM..MAX_VALID_CONSUMPTION_KWH_PER_100KM
                    } == true
            }
            .sortedByDescending { it.startTime }
            .takeWhile { trip -> (coveredKm < MAX_WINDOW_KM).also { coveredKm += trip.distance!! } }
    }

    private suspend fun loadDrivingHistory(tripsNewestFirst: List<TripEntity>, massKg: Double?): DrivingHistory? =
        withContext(Dispatchers.IO) {
            var coveredKm = 0.0
            val trips = tripsNewestFirst.map { trip ->
                // Point detail only where the shortest window reaches; a failed read (e.g. the
                // database closing for a restore) just spreads that trip evenly.
                val points = if (coveredKm < HISTORY_DETAIL_KM) {
                    runCatching { tripRepository.getOdometerDischargeForTrip(trip.id) }.getOrNull()
                        ?.filter { it.odometer > 0.0 }
                } else null
                coveredKm += trip.distance!!
                HistoryTrip(
                    trip.distance!!,
                    trip.energyConsumed!! * 1000.0,
                    points?.map { it.odometer to it.totalDischarge },
                    points?.map { it.altitude },
                )
            }
            buildDrivingHistory(trips, MAX_WINDOW_KM, massKg)
        }

    /** [drivingHistory] where it may feed the projection for [car] (see historyApplies). */
    private fun drivingHistoryFor(car: CarConfig?): DrivingHistory? =
        if (historyApplies(car)) drivingHistory.value else null

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

    /**
     * One time bucket of the consumption charts (a day, a week or a month).
     *
     * [distanceKm] and [energyKwh] are every completed trip that started in it — what was driven.
     * [consumptionKwhPer100km] is the plausible trips' energy over their distance (≥ 0.5 km and
     * 7–35 kWh/100 km, so a parked pre-heat can't spike the line), over [consumptionDistanceKm];
     * null when the bucket has none.
     */
    data class UsageBucket(
        val dateLabel: String,
        val distanceKm: Double,
        val energyKwh: Double,
        val consumptionKwhPer100km: Double?,
        val consumptionDistanceKm: Double,
    )

    enum class UsageBucketSize { DAY, WEEK, MONTH }

    /**
     * A date range picked on the consumption charts: its first and last day (local midnight, ms).
     * Up to a month is shown day by day, up to half a year week by week, longer month by month.
     */
    data class UsageRange(val firstDayMs: Long, val lastDayMs: Long) {
        val bucketSize: UsageBucketSize
            get() {
                // Rounded, so a DST change (a 23- or 25-hour day) inside the range can't drop a day.
                val days = Math.round((lastDayMs - firstDayMs) / 86_400_000.0) + 1
                return when {
                    days <= 31  -> UsageBucketSize.DAY
                    days <= 182 -> UsageBucketSize.WEEK
                    else        -> UsageBucketSize.MONTH
                }
            }
    }

    /** Everything the consumption charts show: the three fixed periods and the picked range. */
    data class UsageCharts(
        val week: List<UsageBucket> = emptyList(),
        val month: List<UsageBucket> = emptyList(),
        val year: List<UsageBucket> = emptyList(),
        val custom: List<UsageBucket> = emptyList(),
        val customRange: UsageRange? = null,
    )

    private val _customUsageRange = MutableStateFlow<UsageRange?>(null)

    /** Shows [range] under the consumption charts' custom tab (kept while the app stays open). */
    fun setCustomUsageRange(range: UsageRange) {
        _customUsageRange.value = range
    }

    /**
     * Past 7 days and past 30 days day by day, the past 12 calendar months month by month, and the
     * custom range if one was picked. Built from [completedTrips] (the buckets skip the trip in
     * progress anyway), off the main thread.
     */
    val usageCharts: StateFlow<UsageCharts> =
        combine(completedTrips, _customUsageRange) { trips, range ->
            val today = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            fun daysAgo(days: Int) = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, -days) }.timeInMillis
            val tomorrow = daysAgo(-1)
            val yearStart = (today.clone() as Calendar).apply {
                set(Calendar.DAY_OF_MONTH, 1)
                add(Calendar.MONTH, -11)
            }.timeInMillis
            UsageCharts(
                week  = usageBuckets(trips, daysAgo(6), tomorrow, UsageBucketSize.DAY, "dd/MM"),
                month = usageBuckets(trips, daysAgo(29), tomorrow, UsageBucketSize.DAY, "dd/MM"),
                year  = usageBuckets(trips, yearStart, tomorrow, UsageBucketSize.MONTH, "MMM"),
                custom = range?.let {
                    val end = Calendar.getInstance().apply {
                        timeInMillis = it.lastDayMs
                        add(Calendar.DAY_OF_MONTH, 1)
                    }.timeInMillis
                    val fmt = if (it.bucketSize == UsageBucketSize.MONTH) "MMM yy" else "dd/MM"
                    usageBuckets(trips, it.firstDayMs, end, it.bucketSize, fmt)
                } ?: emptyList(),
                customRange = range,
            )
        }.flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UsageCharts())

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
                //
                // The formulas themselves live in TripReport so the HTML export and the
                // web companion report the same score as this list.
                // ─────────────────────────────────────────────────────────────
                val score = TripReport.tripScore(trip, avgSpeed?.toDouble())

                val regenPct = TripReport.regenEfficiencyPct(trip, statsById[trip.id])

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
    // Projected range = remaining EV energy ÷ the consumption over the last RangeWindow.km
    // of driving (10, 100 or 500 km, 100 by default — Settings → Preferences → Battery and
    // range estimate, or a tap on the dashboard's Range tile).
    //
    // The window runs back across trips: until the current trip has covered it, the rest
    // comes from the completed trips before it (drivingHistory). So the projection at
    // switch-on is already the driver's own recent consumption — nothing has to be
    // re-learned each drive — and once the trip is longer than the window, it is the
    // trip's own last N km. See windowRate / rangeRate.
    //
    // On a BEV the window's energy is levelled for height (WindowLeveller): the energy that went
    // into climbing comes off and what a descent gave back goes on, so a hill inside a short
    // window doesn't read as the car's consumption.
    //
    // RECENT_DRIVING — the window holds at least half of STABILISATION_KM of real driving.
    //   Below STABILISATION_KM the measured rate is shrunk toward the catalog rate, so a
    //   fresh install's first few hundred metres (a car-park crawl reads 1000 Wh/km) can't
    //   swing the line.
    // BASELINE — not enough driving yet: the car's catalog consumption.
    // ─────────────────────────────────────────────────────────────────────────

    enum class RangeModel { RECENT_DRIVING, BASELINE }

    companion object {
        // displayVehicleSnapshot emits at most this often (5 Hz) — still instant to the eye.
        const val DISPLAY_SNAPSHOT_MIN_INTERVAL_MS = 200L
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
        // Per-sample energy-delta bound (Wh per km travelled, either sign on a BEV) when building
        // the window's cumulative-energy series from per-sample deltas. Clips the kWh-scale steps
        // a mid-trip discharge-anchor rebase or a power↔BMS source switch would otherwise inject
        // into the window — without touching real driving or regen, which never approach it. It
        // also caps standing energy: what the car uses while it stands lands on the first sample
        // after it moves off, so a stop adds at most this × 100 m (150 Wh). A red light with the
        // climate on passes; a long stop's drain, which has no distance to be a rate over, mostly
        // doesn't (see WindowStepper).
        const val MAX_SAMPLE_WH_PER_KM      = 1500.0

        /**
         * One step of the projection window's energy series: the trip energy's change [stepWh]
         * over [stepKm], bounded to ±[MAX_SAMPLE_WH_PER_KM] per km.
         *
         * The battery's discharge counter is net — it steps back while regenerating (the dev
         * Seal's Total Discharge fell 0.1 kWh during an 8 s regen, 2026-10-21) — and so is
         * everything the window is read against: the trip's CONS and every completed trip's kWh.
         * So on a BEV a step that regenerated more than it used counts negative. Clamping it to 0,
         * as this did before, threw that regen away and only ever erred one way: on the dev Seal's
         * trip 821 (2026-10-21) the 10 km window read 27 kWh/100 km over a last 10 km that, after
         * a 230 m descent, had used 11 net — 199 km projected where the net said 345.
         * PHEVs keep the ≥ 0 floor: their counter also steps back while the engine charges the
         * pack, and that energy says nothing about what electric driving costs.
         */
        internal fun windowStepWh(stepWh: Double, stepKm: Double, isPhev: Boolean): Double {
            if (!stepWh.isFinite()) return 0.0
            val bound = MAX_SAMPLE_WH_PER_KM * stepKm
            return stepWh.coerceIn(if (isPhev) 0.0 else -bound, bound)
        }

        /**
         * The live window's booking rule replayed over stored points, so the restore rebuild and
         * the trip history count the energy the live series did. Live, a sample is taken each time
         * the car has moved [SAMPLE_INTERVAL_KM] and [windowStepWh] bounds the energy used since the
         * one before; energy used while the car stands waits for the next sample and is bounded with
         * it. Bounding each stored pair on its own (they are 10 s apart while the car stands) never
         * clipped, so a stop the live window had capped came back in full after a reopen and in the
         * next drive's history: on a Sealion 7 loaded for 1.9 h with the climate on (2026-10-25,
         * reported by luads), 1.3 kWh — 13 kWh/100 km on the 10 km window.
         */
        internal class WindowStepper(private val isPhev: Boolean, private val leveller: WindowLeveller? = null) {
            /** Energy fed in since the last booked step, not in the window yet. */
            var pendingWh = 0.0
                private set
            private var pendingKm = 0.0
            private var pendingAxisKm = 0.0

            /**
             * One pair of stored points: [stepWh] used over [stepKm] of odometer, [axisKm] of it on
             * the window's axis (EV-only on a PHEV), ending at [altitudeM]. Returns the energy booked
             * into the window at this pair, 0 until the odometer has moved on since the last booking.
             */
            fun add(stepWh: Double, stepKm: Double, axisKm: Double = stepKm, altitudeM: Double = 0.0): Double {
                if (stepWh.isFinite()) pendingWh += stepWh
                if (stepKm > 0.0) pendingKm += stepKm
                if (axisKm > 0.0) pendingAxisKm += axisKm
                // The stored odometer moves in 0.1 km counts read through a Float, which can land
                // under 0.1 (10000.1f − 10000.0f = 0.0996), so half a sample is one count.
                if (pendingKm < SAMPLE_INTERVAL_KM / 2) return 0.0
                val gravityWh = leveller?.gravityWh(altitudeM, pendingKm) ?: 0.0
                val booked = windowStepWh(pendingWh - gravityWh, maxOf(pendingAxisKm, SAMPLE_INTERVAL_KM), isPhev)
                pendingWh = 0.0
                pendingKm = 0.0
                pendingAxisKm = 0.0
                return booked
            }
        }

        const val GRAVITY_MS2 = 9.80665
        // The steepest a window step's rise may be, as a share of the distance it covers (15 m per
        // 100 m). Few roads pass it; GPS altitude does: luads' Sealion 7 logged a 54.8 m jump over
        // 94 m of road at 68 km/h (2026-10-20).
        const val MAX_WINDOW_GRADE = 0.15

        /** The mass a car's window is levelled with, or null where it isn't levelled (see [WindowLeveller]). */
        internal fun levellingMassKg(car: CarConfig?): Double? =
            car?.takeUnless { it.isPhev }?.estimatedKerbMassKg?.takeIf { it.isFinite() && it > 0.0 }

        /**
         * The window's gravity term: each step gives up the energy that went into lifting the car,
         * m·g·Δh, and gets back what lowering it supplied, so a short window reads the driving and
         * not the hill. On luads' Sealion 7 the discharge counter put the Illawarra escarpment's
         * 8 km at 59 kWh/100 km up and −20 down, where the road driven both ways came to 22; over
         * 559 rolling 10 km windows of that car's driving the term cut the spread by 48 % and moved
         * the median by under 1 kWh/100 km (2026-10-28).
         *
         * The credit is m·g·Δh in full both ways, not a regen efficiency. A gentle descent saves the
         * motor work it would have done at about 1/0.88 of it, a steep one returns its excess through
         * regen at about 0.7–0.8, so the real figure sits either side of 1.0; and a full credit
         * cancels exactly over a road driven up and back, so the energy a hill really costs on the
         * round trip stays in the rate. The optimism cap and [MIN_PLAUSIBLE_WH_PER_KM] stay as the
         * backstops they were.
         *
         * BEVs only ([levellingMassKg]). On a PHEV the engine can pay for some or all of a climb, and
         * can charge the pack on the way, so the battery's step is not the whole m·g·Δh and taking
         * it all off drives the step negative; the PHEV floor at zero then clips the climbs but not
         * the descents' credit, so the two no longer cancel. Levelling only the electric share of a
         * step might hold, but no PHEV data has tested it.
         *
         * The altitude is GPS, so a step's rise is the reading now less the last good one — the
         * noise in between cancels and only a window's two ends count — held to [MAX_WINDOW_GRADE]
         * of the distance since that reading; a 0 is no fix and is passed over. A leveller lasts
         * one trip, so nothing is counted across the stand between two.
         */
        internal class WindowLeveller(private val massKg: Double) {
            private var altitudeM: Double? = null
            private var sinceKm = 0.0

            /** Takes [altitudeM] as the height the next step rises from, counting nothing. */
            fun anchor(altitudeM: Double) {
                gravityWh(altitudeM, 0.0)
            }

            /** Battery Wh of the height gained over a step of [stepKm] that ends at [altitudeM]; negative for height lost. */
            fun gravityWh(altitudeM: Double, stepKm: Double): Double {
                if (stepKm > 0.0) sinceKm += stepKm
                if (!altitudeM.isFinite() || altitudeM == 0.0) return 0.0
                val previous = this.altitudeM
                val maxRiseM = MAX_WINDOW_GRADE * sinceKm * 1000.0
                // The reading becomes the reference even when its rise was clipped, so a jump that
                // stays (a fix re-acquired 50 m off) counts once, clipped, and one that comes back
                // cancels.
                this.altitudeM = altitudeM
                sinceKm = 0.0
                if (previous == null) return 0.0
                val riseM = (altitudeM - previous).coerceIn(-maxRiseM, maxRiseM)
                return massKg * GRAVITY_MS2 * riseM / 3600.0
            }
        }

        // Driving the window must hold before its rate is trusted outright; below this it is
        // shrunk toward the catalog rate. 2.0 was too short: parking-lot crawl at trip start
        // (high power, near-zero speed) produces Wh/km of 500–1000+. 3 km gives enough real
        // driving to dilute that noise (~1–3 min of normal driving). Only a fresh install
        // (no completed trips yet) ever sits below it for long.
        const val STABILISATION_KM      = 3.0
        const val SAMPLE_INTERVAL_KM    = 0.1    // record a chart point every 100 m
        // EMA on the window rate, per 100 m sample. It mainly evens out the BMS discharge
        // counter's coarse steps at the two ends of a 10 km window; on 100 or 500 km it
        // changes nothing visible.
        const val EMA_ALPHA             = 0.15
        // The longest RangeWindow. Live window samples are kept this far back, the history
        // is built this deep, and the optimism cap's reference rate is taken over it.
        val MAX_WINDOW_KM               = RangeWindow.entries.maxOf { it.km }
        // Completed trips this close to the most recent drive are read point by point, so
        // the shortest window is the real last 10 km at switch-on rather than the last
        // trip's average; deeper history is whole-trip totals.
        const val HISTORY_DETAIL_KM     = 10.0
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
        // Least driving the window must hold to have a rate at all (~10–15 s of moving).
        const val MIN_WINDOW_DIST_KM    = 0.2
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
         *
         * Next, a PHEV whose reserve has been measured ([evReserveSocPct], from
         * CarConfig.phevEvReserveSocPct) uses gross [packKwh] × (SoC − reserve):
         * the energy the car will actually spend on electric range. A Shark 6 holds
         * ~25 % back, so at 43 % SoC the SoC product counted 11 kWh where the car
         * had about 5 kWh to give — 54 km projected against the car's 19.
         */
        internal fun remainingEvEnergyWh(
            batteryKwh: Double,
            socPercent: Double,
            bmsRemainingEvKwh: Double?,
            isPhev: Boolean,
            packKwh: Double? = null,
            evReserveSocPct: Double? = null,
        ): Double {
            if (isPhev && bmsRemainingEvKwh != null &&
                bmsRemainingEvKwh > 0.0 && bmsRemainingEvKwh <= batteryKwh
            ) {
                return bmsRemainingEvKwh * 1000.0
            }
            if (isPhev && packKwh != null && evReserveSocPct != null) {
                return (packKwh * 1000.0 * ((socPercent - evReserveSocPct) / 100.0)).coerceAtLeast(0.0)
            }
            return (batteryKwh * 1000.0 * (socPercent / 100.0)).coerceAtLeast(0.0)
        }

        /**
         * Whether completed trips may fill the projection's window for [car].
         *
         * A completed trip is battery energy over all its distance. On a PHEV that distance
         * includes the kilometres the engine drove, so it reads far below what EV driving costs:
         * at the start of a Shark 6 trip the trip-history average sat under the 196 Wh/km floor
         * and projected 103 km against the car's 61. So a PHEV with a measured reserve (CarConfig
         * .phevEvReserveSocPct — the same cars whose EV energy is modelled properly) works from
         * the current trip's EV-only kilometres alone. Every other car uses its history.
         */
        internal fun historyApplies(car: CarConfig?): Boolean =
            !(car?.isPhev == true && car.phevEvReserveSocPct != null)

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
         * Completed driving before the current trip, laid end to end back from the most recent
         * drive: [energyOverLastKm] (x) is the energy the last x km of it used. Built by
         * [buildDrivingHistory]; between breakpoints it is linear. [km] starts at 0 and rises
         * strictly; [wh] is net energy, so inside a detailed trip it can dip where it regenerated.
         */
        internal class DrivingHistory(private val km: DoubleArray, private val wh: DoubleArray) {
            val totalKm: Double get() = km.last()

            fun energyOverLastKm(distanceKm: Double): Double {
                if (!(distanceKm > 0.0)) return 0.0   // also NaN, which binarySearch would place past the end
                val x = minOf(distanceKm, totalKm)
                val found = km.binarySearch(x)
                if (found >= 0) return wh[found]
                val hi = -found - 1
                val lo = hi - 1
                return wh[lo] + (x - km[lo]) / (km[hi] - km[lo]) * (wh[hi] - wh[lo])
            }
        }

        /**
         * One completed trip for [buildDrivingHistory]: its distance and energy, and — for the
         * trips nearest the present — its points as (odometer km, discharge counter kWh), oldest
         * first, with each point's GPS altitude in [altitudesM] (0 where there was no fix).
         */
        internal class HistoryTrip(
            val distanceKm: Double,
            val energyWh: Double,
            val points: List<Pair<Double, Double>>? = null,
            val altitudesM: List<Double>? = null,
        )

        /**
         * Lays [tripsNewestFirst] end to end, back from the most recent, until [maxKm] is covered.
         *
         * A trip without points is spread evenly over its distance. A trip with points takes its
         * shape from them — where along it the discharge counter moved — scaled to the trip's own
         * distance and energy. So a 10 km window at switch-on is the real last 10 km rather than
         * the last trip's average, and every trip boundary still agrees with the trip totals the
         * rest of the app shows — less, on a trip with points, what the live window left out too:
         * a long stop's drain (see WindowStepper) and, with a [massKg] to level by, the height the
         * trip gained or lost (see WindowLeveller).
         */
        internal fun buildDrivingHistory(
            tripsNewestFirst: List<HistoryTrip>,
            maxKm: Double,
            massKg: Double? = null,
        ): DrivingHistory? {
            val km = arrayListOf(0.0)
            val wh = arrayListOf(0.0)
            // Energy may dip between breakpoints (regen inside a detailed trip); only km must rise.
            fun add(atKm: Double, energyWh: Double) {
                if (atKm > km.last() + 1e-9) {
                    km += atKm
                    wh += energyWh
                }
            }
            var baseKm = 0.0
            var baseWh = 0.0
            for (trip in tripsNewestFirst) {
                if (baseKm >= maxKm) break
                if (!(trip.distanceKm > 0.0) || !(trip.energyWh > 0.0)) continue   // NaN-safe
                val shape = trip.points?.let { pointShape(it, trip.altitudesM, massKg) }
                // Less what its window left out, so the trip reads here as it did to the live window
                // while it was driven.
                val tripWh = shape?.let { trip.energyWh - it.excludedWh }?.takeIf { it > 0.0 } ?: trip.energyWh
                shape?.shares?.forEach { (kmShare, energyShare) ->
                    add(baseKm + kmShare * trip.distanceKm, baseWh + energyShare * tripWh)
                }
                baseKm += trip.distanceKm
                baseWh += tripWh
                add(baseKm, baseWh)
            }
            return if (km.size > 1) DrivingHistory(km.toDoubleArray(), wh.toDoubleArray()) else null
        }

        /**
         * [shares]: at each of a trip's points, counted back from its end, the share of its distance
         * and the share of its booked energy covered so far (a share can dip where it regenerated).
         * [excludedWh]: what the points used that the window leaves out — a long stop's drain past
         * the bound, and the energy of the height gained (negative where it was lost).
         */
        private class PointShape(val shares: List<Pair<Double, Double>>, val excludedWh: Double)

        /**
         * The [PointShape] of a trip's points, levelled by [massKg] where [altitudesM] has one per
         * point. Null when they can't give one (too few, no odometer progress, or no net energy).
         */
        private fun pointShape(
            points: List<Pair<Double, Double>>,
            altitudesM: List<Double>?,
            massKg: Double?,
        ): PointShape? {
            if (points.size < 2) return null
            val endOdometer = points.last().first
            val spanKm = endOdometer - points.first().first
            if (spanKm <= 0.0) return null
            // Each step's net discharge — negative where it regenerated, as on the net counter the
            // trip totals come from — and nothing implausible for one step, booked the way the live
            // window booked it (WindowStepper, WindowLeveller), so a long stop or a hill counts no
            // more here than it did there.
            val altitudes = altitudesM?.takeIf { it.size == points.size }
            val leveller = if (massKg != null && altitudes != null) {
                WindowLeveller(massKg).apply { anchor(altitudes[0]) }
            } else null
            val stepper = WindowStepper(isPhev = false, leveller)
            var fedWh = 0.0
            val bookedWh = DoubleArray(points.size - 1) { i ->
                val stepWh = (points[i + 1].second - points[i].second) * 1000.0
                val plausibleWh = if (stepWh.isFinite() && kotlin.math.abs(stepWh) <= 10_000.0) stepWh else 0.0
                fedWh += plausibleWh
                stepper.add(plausibleWh, points[i + 1].first - points[i].first, altitudeM = altitudes?.get(i + 1) ?: 0.0)
            }
            val totalWh = bookedWh.sum()
            if (totalWh <= 0.0) return null
            var coveredWh = 0.0
            val shares = (bookedWh.lastIndex downTo 0).map { i ->
                coveredWh += bookedWh[i]
                ((endOdometer - points[i].first) / spanKm).coerceIn(0.0, 1.0) to coveredWh / totalWh
            }
            return PointShape(shares, excludedWh = fedWh - totalWh)
        }

        /** Consumption over a window, and how much driving the window actually held. */
        internal data class WindowRate(val whPerKm: Double, val coveredKm: Double)

        /**
         * Consumption (Wh/km) over the last [windowKm] of driving.
         *
         * [trip] is the current trip's cumulative (distance km, energy Wh) series, oldest first,
         * on the projection's distance axis (EV-only on a PHEV). Once the trip is longer than the
         * window, the window is the trip's own last [windowKm]; until then it is the whole trip
         * plus as much of [history] as makes up the rest. Null with less than
         * [MIN_WINDOW_DIST_KM] of driving, or before any energy has been recorded.
         */
        internal fun windowRate(
            trip: List<Pair<Double, Double>>,
            history: DrivingHistory?,
            windowKm: Double
        ): WindowRate? {
            var tripKm = 0.0
            var tripWh = 0.0
            if (trip.isNotEmpty()) {
                val (endKm, endWh) = trip.last()
                tripKm = (endKm - trip.first().first).coerceAtLeast(0.0)
                if (tripKm >= windowKm) {
                    // A zero here is a real stretch with no net discharge (a long descent); the
                    // optimism cap and MIN_PLAUSIBLE_WH_PER_KM bound what it projects.
                    val windowWh = (endWh - energyAt(trip, endKm - windowKm)).coerceAtLeast(0.0)
                    return WindowRate(windowWh / windowKm, windowKm)
                }
                tripWh = endWh - trip.first().second
            }
            val historyKm = history?.let { minOf(windowKm - tripKm, it.totalKm) } ?: 0.0
            val coveredKm = tripKm + historyKm
            val coveredWh = tripWh + (history?.energyOverLastKm(historyKm) ?: 0.0)
            if (coveredKm < MIN_WINDOW_DIST_KM || coveredWh <= 0.0) return null
            return WindowRate(coveredWh / coveredKm, coveredKm)
        }

        /** Cumulative energy at [distanceKm] along [series] (non-decreasing distance), interpolated. */
        private fun energyAt(series: List<Pair<Double, Double>>, distanceKm: Double): Double {
            var lo = 0
            var hi = series.lastIndex
            if (distanceKm <= series[lo].first) return series[lo].second
            if (distanceKm >= series[hi].first) return series[hi].second
            while (hi - lo > 1) {
                val mid = (lo + hi) ushr 1
                if (series[mid].first <= distanceKm) lo = mid else hi = mid
            }
            val (d0, e0) = series[lo]
            val (d1, e1) = series[hi]
            return if (d1 > d0) e0 + (distanceKm - d0) / (d1 - d0) * (e1 - e0) else e0
        }

        /** The rate the projection divides by, the reference its optimism cap holds it to, and its tier. */
        internal data class RangeRate(val whPerKm: Double, val referenceWhPerKm: Double, val model: RangeModel)

        /**
         * [windowRate] over [windowKm], shrunk toward [baselineWhPerKm] until the window holds
         * [STABILISATION_KM] of driving. The optimism cap's reference is the same over
         * [MAX_WINDOW_KM], so a short window on a long descent can't claim much more than the
         * driver's longer-run consumption; on the longest window the cap never bites.
         */
        internal fun rangeRate(
            trip: List<Pair<Double, Double>>,
            history: DrivingHistory?,
            windowKm: Double,
            baselineWhPerKm: Double
        ): RangeRate {
            fun confidence(w: WindowRate?) = ((w?.coveredKm ?: 0.0) / STABILISATION_KM).coerceIn(0.0, 1.0)
            fun shrunk(w: WindowRate?): Double {
                val c = confidence(w)
                return if (w == null) baselineWhPerKm else c * w.whPerKm + (1.0 - c) * baselineWhPerKm
            }
            val window = windowRate(trip, history, windowKm)
            return RangeRate(
                whPerKm = shrunk(window),
                referenceWhPerKm = shrunk(windowRate(trip, history, MAX_WINDOW_KM)),
                model = if (confidence(window) >= 0.5) RangeModel.RECENT_DRIVING else RangeModel.BASELINE
            )
        }

        /** One step of the window rate's EMA (see [EMA_ALPHA]). */
        internal fun emaStep(previous: Double?, raw: Double): Double =
            previous?.let { EMA_ALPHA * raw + (1.0 - EMA_ALPHA) * it } ?: raw

        /**
         * Buckets of [size] from the one containing [fromMs] up to [toMsExclusive], counting only trips
         * that started inside that span. Every bucket is returned, empty ones as zero, so distance and
         * energy read as a time series; Calendar steps the days, so a DST change can't shift one.
         */
        internal fun usageBuckets(
            trips: List<TripEntity>,
            fromMs: Long,
            toMsExclusive: Long,
            size: UsageBucketSize,
            fmt: String
        ): List<UsageBucket> {
            val formatter = SimpleDateFormat(fmt, Locale.getDefault())
            val inRange = trips.filter { !it.isActive && it.endOdometer != null && it.startTime in fromMs until toMsExclusive }
            val cal = Calendar.getInstance().apply {
                timeInMillis = fromMs
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                when (size) {
                    UsageBucketSize.DAY   -> Unit
                    UsageBucketSize.WEEK  -> set(Calendar.DAY_OF_WEEK, firstDayOfWeek)
                    UsageBucketSize.MONTH -> set(Calendar.DAY_OF_MONTH, 1)
                }
            }
            val step = when (size) {
                UsageBucketSize.DAY   -> Calendar.DAY_OF_MONTH
                UsageBucketSize.WEEK  -> Calendar.WEEK_OF_YEAR
                UsageBucketSize.MONTH -> Calendar.MONTH
            }
            val buckets = ArrayList<UsageBucket>()
            while (cal.timeInMillis < toMsExclusive) {
                val bucketStart = cal.timeInMillis
                cal.add(step, 1)
                val bucketEnd = cal.timeInMillis
                val bucketTrips = inRange.filter { it.startTime in bucketStart until bucketEnd }
                val plausible = bucketTrips.filter {
                    (it.distance ?: 0.0) >= 0.5 &&
                        it.efficiency?.let { e ->
                            e in MIN_VALID_CONSUMPTION_KWH_PER_100KM..MAX_VALID_CONSUMPTION_KWH_PER_100KM
                        } == true
                }
                buckets += UsageBucket(
                    // A week or month that starts before the range is labelled from the range's start.
                    dateLabel = formatter.format(java.util.Date(maxOf(bucketStart, fromMs))),
                    distanceKm = bucketTrips.sumOf { (it.distance ?: 0.0).coerceAtLeast(0.0) },
                    energyKwh = bucketTrips.sumOf { (it.energyConsumed ?: 0.0).coerceAtLeast(0.0) },
                    consumptionKwhPer100km = plausible.combinedEfficiency(),
                    consumptionDistanceKm = plausible.sumOf { it.distance ?: 0.0 },
                )
            }
            return buckets
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
     *  BASELINE → RECENT_DRIVING, and any fallback) so an
     *  unexpectedly low-tier projection on a given car/firmware is diagnosable from logs.
     *  Per-tick no-op when the tier is unchanged, so the high-frequency loop never spams. */
    private fun setActiveRangeModel(model: RangeModel) {
        if (_activeRangeModel.value != model) {
            Log.i(TAG, "Range model: ${_activeRangeModel.value} → $model")
        }
        _activeRangeModel.value = model
    }

    /**
     * No live session and no trip open — a parked car, or a head unit woken remotely, which reads
     * the car as off until its power level comes up (about 20 s after the wake on the dev Seal,
     * diag.log 2026-10-17): the dashboard still shows the range from the driving before and the
     * current SoC, as one point at 0 km. Switch-on replaces it with the session's own first point;
     * with no trip id it never reaches the projection cache.
     */
    private fun refreshIdleProjection(telemetry: VehicleTelemetry) {
        smoothedWhPerKm = null
        val (projectedRange, model) = projectRange(telemetry)
        val point = RangeDataPoint(
            distanceKm = 0.0,
            soc = telemetry.soc.takeIf { it > 0 } ?: telemetry.socPanel.toDouble(),
            electricDrivingRangeKm = telemetry.electricDrivingRangeKm,
            projectedRangeKm = projectedRange,
            isStabilised = model != RangeModel.BASELINE
        )
        if (_tripDataPoints.value != listOf(point)) _tripDataPoints.value = listOf(point)
    }

    /**
     * Projects the newest chart point again from the current window, history and [telemetry],
     * without adding a point. The EMA restarts from this projection. [withReadings] also refreshes
     * the point's SoC and BMS range — for the switch-on point while the car hasn't moved.
     */
    private fun reprojectLatestPoint(telemetry: VehicleTelemetry, withReadings: Boolean) {
        val points = _tripDataPoints.value
        val latest = points.lastOrNull() ?: return
        smoothedWhPerKm = null
        val (projectedRange, model) = projectRange(telemetry)
        val updated = latest.copy(
            soc = if (withReadings) telemetry.soc.takeIf { it > 0 } ?: telemetry.socPanel.toDouble() else latest.soc,
            electricDrivingRangeKm = if (withReadings) telemetry.electricDrivingRangeKm else latest.electricDrivingRangeKm,
            projectedRangeKm = projectedRange,
            isStabilised = model != RangeModel.BASELINE
        )
        if (updated == latest) return
        _tripDataPoints.value = points.dropLast(1) + updated
        currentTripId.value?.let { liveProjectionCache.update(it, _tripDataPoints.value) }
    }

    /**
     * Projected range now, from the window rate over [energySamples] and the driving before this
     * trip (see rangeRate). Advances the rate's EMA and sets the active tier. The range is null
     * when the remaining-energy read is unusable.
     */
    private fun projectRange(telemetry: VehicleTelemetry): Pair<Double?, RangeModel> {
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
            batteryKwh, effectiveSoc, telemetry.batteryRemainPowerEV, isPhev,
            packKwh = car?.batteryKwh, evReserveSocPct = car?.phevEvReserveSocPct,
        )
        val rangeRate = rangeRate(
            trip = energySamples.map { it.distanceKm to it.cumulativeEnergyWh },
            history = drivingHistoryFor(car),
            windowKm = rangeWindow.value.km,
            baselineWhPerKm = baselineWhPerKm
        )
        val rate = emaStep(smoothedWhPerKm, rangeRate.whPerKm)
        smoothedWhPerKm = rate
        // The optimism cap bounds a downhill balloon inside projectedEvRangeKm. Log only when
        // its biting state flips — a cap that stays engaged means the window is systematically
        // low (an energy-accounting problem, not terrain), worth catching in logs.
        val referenceWhPerKm = rangeRate.referenceWhPerKm
        val capBiting = remainingEnergyWh > 0.0 && referenceWhPerKm > 0.0 &&
            OPTIMISM_CAP * referenceWhPerKm > rate
        if (capBiting != optimismCapBiting) {
            optimismCapBiting = capBiting
            Log.i(TAG, "Optimism cap ${if (capBiting) "engaged" else "released"}: " +
                "rate=${"%.0f".format(rate)} ref=${"%.0f".format(referenceWhPerKm)} Wh/km")
        }
        setActiveRangeModel(rangeRate.model)
        // EV-only projection: models how your actual EV consumption affects electric range
        // vs the BMS/WLTP EV estimate. We used to add the BMS fuel range for PHEVs, but the
        // chart caps at the EV-only WLTP — so any PHEV with fuel in the tank projected
        // EV+ICE combined (hundreds of km) and pinned permanently at "≥ WLTP, capped".
        // Fuel range isn't driven by EV consumption, so it doesn't belong on this curve.
        // projectedEvRangeKm floors the rate at the reference consumption for PHEVs so
        // ICE-diluted (near-zero) measured Wh/km can't balloon the EV range — see its doc.
        //
        // A non-positive remaining-energy read — a transient telemetry glitch, or a
        // cold BMS reporting soc=socPanel=0 — would divide out to a projected 0. That
        // false spike-to-zero would be appended to the curve AND frozen into the
        // LiveProjectionCache permanently, surviving every reopen. Emit null instead so
        // the projected line simply leaves a gap (the chart drops null points) rather
        // than diving to 0 and back.
        val projectedRange =
            if (remainingEnergyWh > 0.0)
                projectedEvRangeKm(remainingEnergyWh, rate, baselineWhPerKm, isPhev, referenceWhPerKm)
            else null
        return projectedRange to rangeRate.model
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
    /** This trip's gravity term on the window series; null on a car that isn't levelled. */
    private var windowLeveller: WindowLeveller? = null
    /** Whether the optimism cap was flooring the rate last tick — logged only on transition. */
    private var optimismCapBiting: Boolean = false
    private var integratedDistanceKm: Double  = 0.0
    private var integratedSegmentDistanceKm: Double = 0.0
    // Trip-cumulative *net* traction energy (signed): positive power × dt
    // accumulates, negative power × dt (regen) subtracts. NOT used for the
    // user-facing "energy consumed" total — that comes from the BMS
    // totalDischarge counter (see _liveAccumulatedKwh below) so it matches
    // what the post-trip overview screen stores and what reference apps
    // like Electro report for the same drive. This accumulator is only the
    // fallback for the trip's energy when the BMS-derived delta is
    // implausible (see liveEnergyKwh in the telemetry loop).
    private var accumulatedEnergyWh: Double   = 0.0
    private var smoothedWhPerKm:     Double?  = null  // window-rate EMA state
    // The trip's (distance, cumulative energy) series the projection window reads, kept
    // MAX_WINDOW_KM back.
    private val energySamples      = mutableListOf<EnergySample>()
    // PHEV ICE gating (MD/PHEV_ICE_Aware_Projection.md): distance covered while the
    // ICE propels the car must not feed the EV consumption rate, or the rate deflates
    // and the EV projection balloons. The window's distance axis is EV-only on PHEVs;
    // on BEVs every km is an EV km, so it tracks the trip distance exactly.
    private var evWindowDistanceKm  = 0.0            // EV-only x-axis of the window series
    private var lastSampleTripDistKm: Double? = null // trip distance at the previous window sample
    private var lastIceMileageKm: Int? = null        // previous tick's lifetime ICE-km counter
    // ICE kilometres reported by the counter but not yet withheld from the EV axis.
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
     * remainder that may advance the window's distance axis and updating the debt pool.
     */
    private fun evShareOfWindowDistance(distKm: Double): Double {
        val (evKm, debtKm) = withholdIceDebt(distKm, iceDebtWindowKm)
        iceDebtWindowKm = debtKm
        return evKm
    }

    private fun journeyDistanceKm(telemetry: VehicleTelemetry): Double? =
        if (suppressJourneyDistance) null
        else telemetry.currentJourneyDriveMileage?.takeIf { isUsableJourneyDistance(it) }

    /**
     * Car-on as the live trip figures should see it: [VehicleTelemetry.isCarOn], plus a DiLink-5
     * standstill the trip repository is counting as car-on (TripRepository.di5StandstillHeld —
     * always false on DiLink-3), so the live TIME, AVG and segment agree with the stored trip.
     */
    private fun carOnForLive(telemetry: VehicleTelemetry): Boolean =
        telemetry.isCarOn || tripRepository.di5StandstillHeld.value

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
            windowLeveller = null
            optimismCapBiting = false
            energySamples.clear()
            evWindowDistanceKm = 0.0
            lastSampleTripDistKm = null
            lastIceMileageKm = null
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
            // The window already holds the driving before this trip, so the projection is the
            // driver's own from switch-on — not the car's figure until the first 100 m.
            val (projectedRange, model) = projectRange(telemetry)
            _tripDataPoints.value = listOf(
                RangeDataPoint(
                    distanceKm = initialTripDistanceKm,
                    soc = telemetry.soc.takeIf { it > 0 } ?: telemetry.socPanel.toDouble(),
                    electricDrivingRangeKm = telemetry.electricDrivingRangeKm,
                    projectedRangeKm = projectedRange,
                    isStabilised = model != RangeModel.BASELINE
                )
            )
        }
        lastTelemetryWasCarOn = carOnForLive(telemetry)
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
        windowLeveller = null
        optimismCapBiting = false
        energySamples.clear()
        evWindowDistanceKm = 0.0
        lastSampleTripDistKm = null
        lastIceMileageKm = null
        iceDebtWindowKm = 0.0
        _liveDistanceKm.value = 0.0
        _liveSegmentDistanceKm.value = 0.0
        _liveSessionStartMs.value = null
        _liveOffStateMs.value = 0L
        // Only reset the active model badge when we're also clearing the chart
        // (full reset). When keepPoints=true the chart still shows the trip's
        // RECENT_DRIVING projections from earlier in the drive; resetting the
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

        // A new window (Settings, or a tap on the dashboard's Range tile) or newly loaded driving
        // history changes the projection now, not 100 m later: project the newest point again,
        // from scratch rather than through the EMA, so the switch shows at once. On a cold start
        // this is also what fills in the history the switch-on point was projected without.
        viewModelScope.launch {
            combine(drivingHistory, rangeWindow) { _, _ -> }.collect {
                val telemetry = currentTelemetry.value ?: return@collect
                reprojectLatestPoint(telemetry, withReadings = false)
            }
        }

        // A new trip (or the end of one) starts the instant consumption's extra-load learning over.
        viewModelScope.launch {
            currentTripId.collect { instantConsumption.resetExtraLoad() }
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
                // Nothing being recorded (parked, car off): keep the parked projection current.
                if (!liveDriveSessionActive && !inTrip && currentTripId.value == null) {
                    refreshIdleProjection(telemetry)
                }

                if (liveDriveSessionActive) {
                    val tripOpen = currentTripId.value != null
                    if (!shouldContinueLiveDriveSession(inTrip, tripOpen, telemetry)) {
                        clearLiveDriveRuntime(keepPoints = true)
                        liveDriveSessionActive = false
                        wasInTrip = inTrip
                        return@collect
                    }

                    if (!carOnForLive(telemetry)) {
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
                        // close-time computation in TripRepository — including
                        // DiLink-5's longer threshold, where a short "off" window
                        // is a red light (no power-state signal there).
                        val countsAsParked = !DiLink5Platform.isDiLink5 ||
                            offDurationMs > TripRepository.offStateGapThresholdMs(isDiLink5 = true)
                        if (offDurationMs > 0L && countsAsParked) {
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
                        iceDebtWindowKm += iceCounterStepKm(lastIceMileageKm, iceCounterKm)
                        lastIceMileageKm = iceCounterKm
                    }
                    // Coarse per-tick fallback, only for firmwares with no ICE counter.
                    val iceTick = isPhevCarTick && iceCounterKm == null &&
                        isIceActive(telemetry.instantFuelConsumption, telemetry.energyMode)
                    if (prevMs != null) {
                        val deltaSeconds = (telemetryMs - prevMs) / 1000.0
                        if (deltaSeconds in 0.0..MAX_DELTA_SECONDS) {
                            integratedDistanceKm += integrateDistanceKm(effectiveSpeed, deltaSeconds)
                            integratedSegmentDistanceKm += integrateDistanceKm(effectiveSpeed, deltaSeconds)
                            // Per-tick energy delta. We prefer the BMS totalDischarge delta
                            // (kWh consumed since the previous packet) because it's measured
                            // at the battery and matches what the CONS readout already shows
                            // the user. On firmwares where enginePower is unreliable / often
                            // reported as ~0 (e.g. Seal Excellence in city driving), a purely
                            // power-integrated counter reads near zero. Falling back to power × dt
                            // covers the case where totalDischarge is stale or non-monotonic —
                            // recently-charged packets occasionally report a smaller value than
                            // the previous tick, and a negative delta must not count.
                            val powerDeltaWh = telemetry.enginePower * 1000.0 * (deltaSeconds / 3600.0)
                            val prevTd = lastTotalDischargeKwh
                            val currentTd = telemetry.totalDischarge.takeIf { it.isFinite() && it > 0.0 }
                            val bmsDeltaWh = if (prevTd != null && currentTd != null) {
                                ((currentTd - prevTd) * 1000.0)
                                    .takeIf { it.isFinite() && it in 0.0..10_000.0 }
                            } else null
                            accumulatedEnergyWh += bmsDeltaWh ?: powerDeltaWh
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
                        // Not moved since switch-on: keep that point's SoC, BMS range and
                        // projection current while the car stands (pre-heating, a charge).
                        if (_tripDataPoints.value.size == 1) reprojectLatestPoint(telemetry, withReadings = true)
                        lastTelemetryWasCarOn = carOnForLive(telemetry)
                        wasInTrip = inTrip
                        return@collect
                    }

                    // Sample (cumulative distance, cumulative CLEAN energy) for the
                    // projection window. We use liveEnergyKwh — the same BMS
                    // total-discharge figure the CONS readout and the trip-restore
                    // rebuild use — NOT the per-tick power-integrated accumulator.
                    //
                    // Why this matters: since instant telemetry arrives ~10×/s, but the
                    // BMS totalDischarge only refreshes about once a second, the per-tick
                    // accumulator fell back to power×dt on every intermediate fast tick
                    // (no fresh discharge to diff against) and ADDED that on top of the
                    // discharge captured when the counter did refresh — double-counting
                    // energy. That inflated the rolling Wh/km and pinned the projection at
                    // roughly half once the rolling window engaged (e.g. a Seal Excellence
                    // cruising at 15.8 kWh/100 km projecting as if it were ~30). It also
                    // made the live line artificially smooth (power integrates smoothly)
                    // vs the reconstruction. liveEnergyKwh is the clean end-start discharge
                    // (with a whole-trip power-integration fallback only when the BMS total
                    // is unavailable), so the window now matches actual consumption and the
                    // rebuilt curve. It still engages reliably on lumpy feeds because the
                    // end-start total grows smoothly even when the per-tick delta is 0.
                    // Build the window's cumulative-energy series from *bounded per-sample deltas*
                    // rather than storing liveEnergyKwh verbatim. liveEnergyKwh isn't a clean
                    // cumulative — a mid-trip discharge-anchor rebase or a power↔BMS source switch
                    // can step it — and either injects a kWh-scale step inside the window that
                    // spikes the rate for up to a full window length. windowStepWh bounds each
                    // delta to ±MAX_SAMPLE_WH_PER_KM × Δkm, which clips those steps (and long idle
                    // drain) to a plausible per-100 m cost while keeping real driving and its regen
                    // (≥ 0 on PHEVs; see windowStepWh). The restore rebuild and the trip history
                    // book the same steps from the stored points (WindowStepper).
                    val rawNowWh = liveEnergyKwh * 1000.0
                    val prevSample = energySamples.lastOrNull()
                    // PHEV: the window's x-axis is EV-only distance — an interval driven on
                    // the ICE doesn't advance it, so ICE stretches can't dilute the rate
                    // (during a long pure-ICE stretch the axis freezes and the window keeps
                    // covering the electric kilometres either side of it).
                    // On BEVs the EV axis advances with every interval, so the sample
                    // series follows distKm.
                    val prevTripSampleKm = lastSampleTripDistKm
                    lastSampleTripDistKm = distKm
                    if (!iceTick) {
                        val sampleStepKm = (distKm - (prevTripSampleKm ?: distKm)).coerceAtLeast(0.0)
                        evWindowDistanceKm += evShareOfWindowDistance(sampleStepKm)
                    }
                    val sampleAxisKm = if (isPhevCarTick) evWindowDistanceKm else distKm
                    val dDistKm = (sampleAxisKm - (prevSample?.distanceKm ?: sampleAxisKm))
                        .coerceAtLeast(SAMPLE_INTERVAL_KM)
                    // Less the height the step gained (see WindowLeveller); a trip's first sample
                    // only anchors it.
                    val leveller = windowLeveller
                        ?: levellingMassKg(currentCarConfig())?.let { WindowLeveller(it) }?.also { windowLeveller = it }
                    val gravityWh = leveller?.gravityWh(telemetry.locationAltitude, dDistKm) ?: 0.0
                    val deltaWh = lastSampleRawEnergyWh
                        ?.let { windowStepWh(rawNowWh - it - gravityWh, dDistKm, isPhevCarTick) }
                        ?: 0.0
                    energySamples.add(EnergySample(sampleAxisKm, (prevSample?.cumulativeEnergyWh ?: 0.0) + deltaWh))
                    lastSampleRawEnergyWh = rawNowWh
                    // Keep MAX_WINDOW_KM of samples, plus the one just before it to interpolate from.
                    val windowFloor = sampleAxisKm - MAX_WINDOW_KM
                    while (energySamples.size > 1 && energySamples[1].distanceKm <= windowFloor) {
                        energySamples.removeAt(0)
                    }

                    val effectiveSoc = telemetry.soc.takeIf { it > 0 } ?: telemetry.socPanel.toDouble()
                    val (projectedRange, model) = projectRange(telemetry)

                    _tripDataPoints.value = _tripDataPoints.value + RangeDataPoint(
                        distanceKm = distKm,
                        // effectiveSoc, not raw telemetry.soc, so the persisted point's SoC
                        // matches the value the projection above was computed from (raw soc can
                        // read 0 while socPanel is valid).
                        soc = effectiveSoc,
                        electricDrivingRangeKm = telemetry.electricDrivingRangeKm,
                        projectedRangeKm = projectedRange,
                        isStabilised = model != RangeModel.BASELINE
                    )
                    // Snapshot the as-computed-live curve so a reopen restores it
                    // verbatim instead of rebuilding it (display aid only — see
                    // LiveProjectionCache). Keyed by trip id so it can never bleed
                    // into a different trip.
                    currentTripId.value?.let { liveProjectionCache.update(it, _tripDataPoints.value) }
                    lastTelemetryWasCarOn = carOnForLive(telemetry)
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

    /**
     * On head units where the silent [android.content.pm.PackageInstaller] session can't commit
     * (unprivileged app, no installer dialog to fall back to), the update can still be installed
     * over the local adb channel as uid 2000 — a privileged installer that needs no UI. Requires
     * the adb setup to have completed; without it there is no channel and the manual
     * `adb install -r` card is the only route.
     *
     * Verified end-to-end on a Sealion 7: the replace kills this process and `MY_PACKAGE_REPLACED`
     * restarts the telemetry service by itself, with no boot loop — the OS does the kill and the
     * restart, so this is not the self-kill+relaunch pattern that boot-loops DiLink-5.
     */
    private val canShellInstall: Boolean
        get() = com.byd.tripstats.sdk.DiLink5Platform.selfRestartUnsafe &&
            AdbPermissionManager.isSetupComplete(getApplication())

    /** Set when a shell install was attempted and failed — the UI falls back to manual instructions. */
    private val _shellInstallFailed = MutableStateFlow(false)
    val shellInstallFailed: StateFlow<Boolean> = _shellInstallFailed.asStateFlow()

    /** True when neither auto path is available, so only `adb install -r` remains. */
    val manualInstallOnly: Boolean
        get() = com.byd.tripstats.sdk.DiLink5Platform.selfRestartUnsafe && !canShellInstall

    fun downloadUpdate() {
        // Newer than DiLink-3: the silent PackageInstaller can't complete (unprivileged, no
        // installer UI). That no longer means "no in-app update" — the adb channel gives us a
        // privileged `pm install`, so the download is allowed whenever that channel exists.
        // Without it there is nothing to install with, so stay on the manual card.
        if (com.byd.tripstats.sdk.DiLink5Platform.selfRestartUnsafe && !canShellInstall) return
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
        val shellInstall = com.byd.tripstats.sdk.DiLink5Platform.selfRestartUnsafe
        // Newer head unit with no usable channel: nothing here can install, so don't pretend to.
        if (shellInstall && !canShellInstall) return
        val apk = downloadedApk.value ?: return
        if (!canInstallNow.value) {
            Log.w(TAG, "installUpdate called but canInstallNow = false")
            return
        }
        // One install at a time — see UpdateRepository.installInProgress. The backup below takes
        // seconds with nothing else on screen, which is exactly when a second tap comes.
        if (!updateRepository.beginInstall()) {
            Log.i(TAG, "installUpdate ignored — an install is already running")
            return
        }
        viewModelScope.launch {
            // Set once the install itself has taken over; anything that ends this coroutine
            // before then (an error, the screen closing mid-backup) must free the slot.
            var handedOver = false
            try {
                val backupFile = backupDatabase()
                if (backupFile != null) {
                    Log.i(TAG, "Database backup created before update install: ${backupFile.absolutePath}")
                } else {
                    Log.w(TAG, "Database backup failed before update install — continuing with install")
                }
                if (shellInstall) {
                    // Never falls through to the PackageInstaller paths — both are known to fail on
                    // these units, so a second attempt would only produce a more confusing error.
                    // A failure surfaces the manual `adb install -r` card instead.
                    val ok = updateRepository.installUpdateViaShell(apk)
                    handedOver = ok
                    if (!ok) _shellInstallFailed.value = true
                } else {
                    // The result arrives through InstallStatusReceiver, which frees the slot on a
                    // failure, or through the repository's own timeout.
                    handedOver = true
                    updateRepository.installUpdate(apk)
                }
            } finally {
                if (!handedOver) updateRepository.endInstall()
            }
        }
    }

    /** True while an install is running — the Install button waits for it. */
    val installInProgress: StateFlow<Boolean> get() = updateRepository.installInProgress

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
                // No trip meter (DiLink-5 has none; also suppressed after a manual start/stop):
                // with no ≥5 min gap found below, the current segment began with the trip.
                // Anchoring at the *current* odometer instead pinned the segment at 0.0 until
                // the car moved again — "0.0 (9.9)" on a DiLink-5 dashboard opened after a drive.
                val journeyAnchor = if (journey != null) {
                    (telemetry.odometer - journey).coerceAtLeast(0.0)
                } else {
                    trip.startOdometer
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
                // the live TIME/AVG match what the trip will store at close — the same
                // function and threshold TripRepository uses at close.
                val offStateGapMs = TripRepository.offStateGapThresholdMs(DiLink5Platform.isDiLink5)
                val dbOffStateMs = TripRepository.computeOffStateDurationMs(
                    points = dataPoints,
                    tripEndMs = null,
                    gapThresholdMs = offStateGapMs
                )
                // Add the current pending off-state that isn't stored in DB yet — the
                // car just came back on. segmentOffSinceMs is set when the car turned off
                // (same ViewModel session); on a fresh ViewModel start (Minimal/Deep Sleep
                // service restart) it is null, so we fall back to the gap from the last
                // stored data point, which approximates the parked window.
                val currentOffMs = segmentOffSinceMs
                    ?.let { (telemetryMs - it).coerceAtLeast(0L) }
                    ?: dataPoints.lastOrNull()?.let { lastPt ->
                        val gap = telemetryMs - lastPt.timestamp
                        if (gap > offStateGapMs) gap else 0L
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
                // The trip's energy (seeds the accumulators below), and the window's series,
                // booked as the live loop booked it — a long stop's drain is capped at move-off
                // rather than kept in full (see WindowStepper).
                var cumEnergyWh = 0.0
                var windowEnergyWh = 0.0
                // Mirror of the live path's ICE gating: ICE-propelled kilometres don't
                // advance the EV-only distance axis the window rate is computed on. Same
                // authority as live — the lifetime ICE-km counter carried as a debt, with
                // the coarse energy_mode predicate only when no point carries the counter.
                // Pre-gating history has no fuel signals in rawJson, so every pair reads
                // electric and the rebuild matches old behaviour.
                val isPhevRestore = currentCarConfig()?.isPhev == true
                // Levelled as live, from the trip's first point; the live loop carries it on.
                val restoreLeveller = levellingMassKg(currentCarConfig())?.let { WindowLeveller(it) }
                dataPoints.firstOrNull()?.let { restoreLeveller?.anchor(it.altitude) }
                val windowStepper = WindowStepper(isPhevRestore, restoreLeveller)
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
                        val pairKm = (dp.odometer - prev.odometer).coerceAtLeast(0.0)
                        var evPairKm = 0.0
                        if (!pointIce[i - 1]) {
                            val withheld = withholdIceDebt(pairKm, restoreIceDebtKm)
                            restoreIceDebtKm = withheld.debtKm
                            evPairKm = withheld.evKm
                            evAxisKm += evPairKm
                        }
                        val dt = (dp.timestamp - prev.timestamp).coerceAtLeast(0L) / 1000.0
                        var pairWh = 0.0
                        if (dt in 0.0..restoreMaxGapSeconds) {
                            val bmsDeltaWh = (dp.totalDischarge - prev.totalDischarge) * 1000.0
                            val powerDeltaWh = prev.power * 1000.0 * (dt / 3600.0)
                            // A negative BMS delta is regen on the net counter (kept on BEVs, like
                            // the live series); only an implausible one falls back to power × dt.
                            val bmsPlausible = bmsDeltaWh.isFinite() && kotlin.math.abs(bmsDeltaWh) <= 10_000.0 &&
                                (bmsDeltaWh >= 0.0 || !isPhevRestore)
                            pairWh = if (bmsPlausible) bmsDeltaWh else powerDeltaWh
                            // Bounded like the live series (windowStepWh), so a stale-0 power
                            // fallback or a counter step can't inject a window spike.
                            cumEnergyWh += windowStepWh(pairWh, pairKm.coerceAtLeast(SAMPLE_INTERVAL_KM), isPhevRestore)
                        }
                        windowEnergyWh += windowStepper.add(pairWh, pairKm, if (isPhevRestore) evPairKm else pairKm, dp.altitude)
                    }
                    val dKm = (dp.odometer - trip.startOdometer).coerceAtLeast(0.0)
                    energySamples.add(EnergySample(if (isPhevRestore) evAxisKm else dKm, windowEnergyWh))
                }
                accumulatedEnergyWh = cumEnergyWh
                // Seed the live EV axes so the first post-resume tick continues the
                // restored series without a step.
                evWindowDistanceKm = evAxisKm
                lastSampleTripDistKm = liveDistanceKm
                // Carry the unpaid ICE debt and the counter anchor across the resume so
                // the first live tick continues the same withholding rather than
                // re-counting the restored kilometres.
                iceDebtWindowKm = restoreIceDebtKm
                lastIceMileageKm = iceCounters.lastOrNull { it != null }
                _liveAccumulatedKwh.value = (cumEnergyWh / 1000.0).coerceAtLeast(0.0)
                // Seed the live per-sample delta tracker so the first post-resume live sample
                // diffs against the restored cumulative energy (≈ trip energy) rather than
                // restarting from null and dropping a sample. Less what the stepper still holds,
                // so it is the trip energy at the last booked step, as live: a stop the car is
                // still standing in at the reopen is then bounded with the move-off sample.
                lastSampleRawEnergyWh = cumEnergyWh - windowStepper.pendingWh
                windowLeveller = restoreLeveller
                // Seed the per-tick BMS-delta tracker from the last restored data point
                // so the next live tick computes (currentTd − lastTd) cleanly. Without
                // this, the first post-resume tick has prevTd=null → falls back to
                // power × dt for one sample, which on Seal Excellence is ~0 (under-
                // counts) and very briefly skews the window's Wh/km low.
                lastTotalDischargeKwh = dataPoints.lastOrNull()?.totalDischarge
                    ?.takeIf { it.isFinite() && it > 0.0 }

                // Reconstruct graph points with projected range so the projection line
                // starts from trip start, not from when the Activity opened. Each point is
                // projected from the window rate as it stood AT THAT POINT — the same
                // rangeRate over the trip so far plus the driving before it, with the same
                // EMA replayed — so the rebuilt line is a faithful twin of the live one.
                val car = currentCarConfig()
                val batteryKwh = car?.let {
                    if (it.isPhev) it.phevUsableBatteryKwh ?: it.batteryKwh else it.batteryKwh
                } ?: FALLBACK_BATTERY_KWH
                val baselineWhPerKm = car?.referenceConsumptionKwhPer100km?.times(10.0)
                    ?: FALLBACK_BASELINE_WH_PER_KM
                val isPhevCar = car?.isPhev == true
                val series = energySamples.map { it.distanceKm to it.cumulativeEnergyWh }
                val history = drivingHistoryFor(car)
                val windowKm = rangeWindow.value.km
                var replayedWhPerKm: Double? = null
                var lastModel = RangeModel.BASELINE
                val rebuiltPoints = dataPoints.mapIndexed { i, dp ->
                    val dKm = (dp.odometer - trip.startOdometer).coerceAtLeast(0.0)
                    val pointRate = rangeRate(series.subList(0, i + 1), history, windowKm, baselineWhPerKm)
                    val rate = emaStep(replayedWhPerKm, pointRate.whPerKm)
                    replayedWhPerKm = rate
                    lastModel = pointRate.model
                    val pointSoc = dp.soc.takeIf { it > 0 } ?: dp.socPanel.toDouble()
                    // Match the live path's numerator exactly: for PHEVs prefer the BMS's own
                    // remaining-EV-energy reading (persisted per point since DB v10), which nets
                    // out the charge-sustaining reserve, over capacity × SoC. Pre-v10 / BEV /
                    // non-reporting rows have batteryRemainPowerEV == null and fall back to the
                    // SoC product, exactly as before — so this can only improve a PHEV rebuild,
                    // never regress one, and never touches BEVs.
                    val remainingWh = remainingEvEnergyWh(
                        batteryKwh, pointSoc, dp.batteryRemainPowerEV, isPhevCar,
                        packKwh = car?.batteryKwh, evReserveSocPct = car?.phevEvReserveSocPct,
                    )
                    // A non-positive remaining-energy read (a cold-BMS point with
                    // soc=socPanel=0) is emitted as null, not a projected 0 — mirrors the live
                    // guard against a false spike-to-zero.
                    val projected: Double? =
                        if (remainingWh > 0.0)
                            projectedEvRangeKm(remainingWh, rate, baselineWhPerKm, isPhevCar, pointRate.referenceWhPerKm)
                        else null
                    RangeDataPoint(
                        distanceKm             = dKm,
                        soc                    = pointSoc,
                        electricDrivingRangeKm = dp.electricDrivingRangeKm,
                        projectedRangeKm       = projected,
                        isStabilised           = pointRate.model != RangeModel.BASELINE
                    )
                }
                // Seed the live EMA from the last replayed rate (what the live loop would hold
                // right now), so the head point and the first new live tick agree.
                smoothedWhPerKm = replayedWhPerKm

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

                setActiveRangeModel(lastModel)
                lastTelemetryWasCarOn = carOnForLive(telemetry)
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
        val kmTotal   : Double,  // distance of the same trips
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
            // Every completed trip that has a resolvable rate. Distance is summed over the same
            // trips as the kWh and cost, so a month's three figures always describe one set.
            class PricedTrip(val monthStart: Long, val kwh: Double, val km: Double, val cost: Double)
            val priced = trips
                .filter { !it.isActive && it.energyConsumed != null && it.energyConsumed!! > 0 }
                .mapNotNull { trip ->
                    val rate = blendedRates[trip.id]
                        ?: return@mapNotNull null
                    val kwh = trip.energyConsumed!!
                    PricedTrip(monthStart(trip.startTime), kwh, (trip.distance ?: 0.0).coerceAtLeast(0.0), kwh * rate)
                }
            if (priced.isEmpty()) return@combine emptyList()

            val byMonth = priced.groupBy { it.monthStart }
            // Running cumulative computed oldest→newest, then presented newest-first (12 months).
            var running = 0.0
            val cumulativeByMonth = HashMap<Long, Double>()
            byMonth.keys.sorted().forEach { epoch ->
                running += byMonth.getValue(epoch).sumOf { it.cost }
                cumulativeByMonth[epoch] = running
            }
            byMonth.entries
                .sortedByDescending { it.key }
                .take(13)   // 13 months so the current month lines up with the same month last year
                .map { (epochMs, rows) ->
                    MonthlyCost(
                        label          = fmt.format(java.util.Date(epochMs)),
                        costAmount     = rows.sumOf { it.cost },
                        kwhTotal       = rows.sumOf { it.kwh },
                        kmTotal        = rows.sumOf { it.km },
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
                val avgConsumption = seasonTrips.combinedEfficiency() ?: return@mapNotNull null
                SeasonStats(
                    season         = season,
                    avgConsumption = avgConsumption,
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
                TagStat(
                    tag             = tag,
                    tripCount       = tagTrips.size,
                    totalDistanceKm = tagTrips.sumOf { it.distance ?: 0.0 },
                    avgConsumption  = tagTrips.combinedEfficiency() ?: 0.0,
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

    /**
     * Re-reads the goals from disk. Called after a settings restore, which writes the
     * prefs behind this ViewModel's back — without it the dashboard would keep showing
     * the old goals until the process restarted.
     */
    fun reloadTripGoals() {
        _tripGoals.value = TripGoals(
            targetConsumptionKwhPer100km = goalPrefs.getFloat(GOAL_CONSUMPTION_KEY, 0f)
                .takeIf { it > 0f }?.toDouble(),
            targetDistanceKmPerMonth     = goalPrefs.getFloat(GOAL_DISTANCE_KEY, 0f)
                .takeIf { it > 0f }?.toDouble()
        )
    }

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

/**
 * Emits a value at once, then nothing for [periodMs]; whatever arrived meanwhile collapses to its
 * newest value, which goes out when the period ends. Unlike debounce it never stalls a continuous
 * stream, and unlike sample a lone change isn't held back to the next tick.
 */
private fun <T> Flow<T>.throttleLatest(periodMs: Long): Flow<T> =
    conflate().transform { value ->
        emit(value)
        delay(periodMs)
    }
