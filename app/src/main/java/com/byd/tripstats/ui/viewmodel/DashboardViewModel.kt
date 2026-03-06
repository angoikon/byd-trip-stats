package com.byd.tripstats.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.byd.tripstats.data.local.BydStatsDatabase
import com.byd.tripstats.data.local.entity.TripDataPointEntity
import com.byd.tripstats.data.local.entity.TripEntity
import com.byd.tripstats.data.local.entity.TripStatsEntity
import com.byd.tripstats.data.model.VehicleTelemetry
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

    private val tripRepository = TripRepository.getInstance(application)

    // ── MQTT state ────────────────────────────────────────────────────────────

    private val _mqttConnected = MutableStateFlow(false)
    val mqttConnected: StateFlow<Boolean> = _mqttConnected.asStateFlow()

    private val _mqttConnectionError = MutableStateFlow<String?>(null)
    val mqttConnectionError: StateFlow<String?> = _mqttConnectionError.asStateFlow()

    // ── Telemetry & trip state (from repository) ──────────────────────────────

    val currentTelemetry: StateFlow<VehicleTelemetry?> = tripRepository.latestTelemetry
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val isInTrip: StateFlow<Boolean> = tripRepository.isInTrip
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val currentTripId: StateFlow<Long?> = tripRepository.currentTripId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

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

    val selectedTrip: StateFlow<TripEntity?> =
        _selectedTripId.flatMapLatest { id ->
            if (id == null) flowOf(null) else tripRepository.getTripById(id)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val selectedTripDataPoints: StateFlow<List<TripDataPointEntity>> =
        _selectedTripId.flatMapLatest { id ->
            if (id == null) flowOf(emptyList()) else tripRepository.getDataPointsForTrip(id)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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
        .map { it.toEfficiencyBuckets(7, "dd-MM") }
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

                val score = run {
                    val eff = trip.efficiency ?: return@run null
                    if (dist == null || dur == null || dist < 0.5 || dur <= 0) return@run null
                    val effScore = when {
                        eff <= 15.0 -> 40
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

    private val _tripDataPoints = MutableStateFlow<List<RangeDataPoint>>(emptyList())
    val tripDataPoints: StateFlow<List<RangeDataPoint>> = _tripDataPoints.asStateFlow()
    private var tripStartOdometer: Double? = null

    // ── Init ──────────────────────────────────────────────────────────────────

    init {
        // Accumulate range projection data points reactively during active trips.
        viewModelScope.launch {
            var wasInTrip = false
            combine(isInTrip, currentTelemetry) { inTrip, telemetry ->
                inTrip to telemetry
            }.collect { (inTrip, telemetry) ->
                if (telemetry == null) return@collect

                when {
                    inTrip && !wasInTrip -> {
                        // Trip just started — anchor odometer and seed the first point
                        tripStartOdometer = telemetry.odometer
                        _tripDataPoints.value = listOf(
                            RangeDataPoint(
                                distanceKm             = 0.0,
                                soc                    = telemetry.soc,
                                electricDrivingRangeKm = telemetry.electricDrivingRangeKm
                            )
                        )
                    }
                    !inTrip && wasInTrip -> {
                        // Trip just ended — keep points visible until next trip starts
                        tripStartOdometer = null
                    }
                    inTrip -> {
                        // One point per 100 m of odometer change.
                        // At ~1 Hz MQTT a 2-hour drive keeps this under ~300 points.
                        val distKm = (telemetry.odometer - (tripStartOdometer ?: telemetry.odometer))
                            .coerceAtLeast(0.0)
                        val lastDist = _tripDataPoints.value.lastOrNull()?.distanceKm ?: 0.0
                        if (distKm - lastDist >= 0.1) {
                            _tripDataPoints.value = _tripDataPoints.value + RangeDataPoint(
                                distanceKm             = distKm,
                                soc                    = telemetry.soc,
                                electricDrivingRangeKm = telemetry.electricDrivingRangeKm
                            )
                        }
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
}