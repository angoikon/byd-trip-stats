package com.byd.tripstats.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.byd.tripstats.service.MqttBrokerService
import com.byd.tripstats.data.local.entity.TripDataPointEntity
import com.byd.tripstats.data.local.entity.TripEntity
import com.byd.tripstats.data.local.entity.TripStatsEntity
import com.byd.tripstats.data.model.VehicleTelemetry
import com.byd.tripstats.data.repository.TripRepository
import com.byd.tripstats.service.MqttService
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.byd.tripstats.ui.components.RangeDataPoint
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class DashboardViewModel(application: Application) : AndroidViewModel(application) {
    private val TAG = "DashboardViewModel"

    private val tripRepository = TripRepository.getInstance(application)

    // MQTT Connection state - properly tracked from service
    private val _mqttConnected = MutableStateFlow(false)
    val mqttConnected: StateFlow<Boolean> = _mqttConnected.asStateFlow()

    private val _mqttConnectionError = MutableStateFlow<String?>(null)
    val mqttConnectionError: StateFlow<String?> = _mqttConnectionError.asStateFlow()

    // Latest telemetry - observe from repository
    val currentTelemetry: StateFlow<VehicleTelemetry?> = tripRepository.latestTelemetry
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    // FIXED: Current trip state - observe from repository StateFlows
    val isInTrip: StateFlow<Boolean> = tripRepository.isInTrip
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    val currentTripId: StateFlow<Long?> = tripRepository.currentTripId
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    // Trip history
    val allTrips: StateFlow<List<TripEntity>> = tripRepository.getAllTrips()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // All trip stats in one query — needed to compute regen efficiency per trip
    private val allTripStats: StateFlow<List<TripStatsEntity>> = tripRepository.getAllTripStats()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList()
        )

    /** One entry per day for the past 7 days that has at least one completed trip. */
    data class DailyEfficiency(val dateLabel: String, val avgKwhPer100km: Double)

    val weeklyEfficiency: StateFlow<List<DailyEfficiency>> = allTrips
        .map { trips ->
            val fmt = SimpleDateFormat("dd-MM", Locale.getDefault())
            val cal = Calendar.getInstance()
            // Snap to midnight of today
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val todayMidnight = cal.timeInMillis

            (6 downTo 0).mapNotNull { daysAgo ->
                val dayStart = todayMidnight - daysAgo * 86_400_000L
                val dayEnd   = dayStart + 86_400_000L - 1L
                val label    = fmt.format(java.util.Date(dayStart))
                val efficiencies = trips
                    .filter { it.startTime in dayStart..dayEnd && it.efficiency != null && (it.distance ?: 0.0) >= 0.5 }
                    .mapNotNull { it.efficiency }
                if (efficiencies.isEmpty()) null
                else DailyEfficiency(label, efficiencies.average())
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList()
        )

    /** One entry per day for the past 30 days that has at least one completed trip. */
    val monthlyEfficiency: StateFlow<List<DailyEfficiency>> = allTrips
        .map { trips ->
            val fmt = SimpleDateFormat("dd/MM", Locale.getDefault())
            val cal = Calendar.getInstance()
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val todayMidnight = cal.timeInMillis

            (29 downTo 0).mapNotNull { daysAgo ->
                val dayStart = todayMidnight - daysAgo * 86_400_000L
                val dayEnd   = dayStart + 86_400_000L - 1L
                val label    = fmt.format(java.util.Date(dayStart))
                val efficiencies = trips
                    .filter { it.startTime in dayStart..dayEnd && it.efficiency != null && (it.distance ?: 0.0) >= 0.5 }
                    .mapNotNull { it.efficiency }
                if (efficiencies.isEmpty()) null
                else DailyEfficiency(label, efficiencies.average())
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList()
        )

    /** One entry per calendar month for the past 12 months that has at least one completed trip. */
    val yearlyEfficiency: StateFlow<List<DailyEfficiency>> = allTrips
        .map { trips ->
            val labelFmt = SimpleDateFormat("MMM", Locale.getDefault())
            val cal = Calendar.getInstance()
            cal.set(Calendar.DAY_OF_MONTH, 1)
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)

            (11 downTo 0).mapNotNull { monthsAgo ->
                val monthCal = cal.clone() as Calendar
                monthCal.add(Calendar.MONTH, -monthsAgo)
                val monthStart = monthCal.timeInMillis
                monthCal.add(Calendar.MONTH, 1)
                val monthEnd = monthCal.timeInMillis - 1L
                val label = labelFmt.format(java.util.Date(monthStart))
                val efficiencies = trips
                    .filter { it.startTime in monthStart..monthEnd && it.efficiency != null && (it.distance ?: 0.0) >= 0.5 }
                    .mapNotNull { it.efficiency }
                if (efficiencies.isEmpty()) null
                else DailyEfficiency(label, efficiencies.average())
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList()
        )

    /**
     * Pre-computed display metrics for every trip, keyed by trip ID.
     * Derived from allTrips + allTripStats so the LazyColumn never does
     * any arithmetic during scroll — just a map lookup.
     */
    data class TripDisplayMetrics(val avgSpeedKmh: Int?, val tripScore: Int?, val regenEfficiencyPct: Double?)

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
                        else -> ((25.0 - eff) / (25.0 - 15.0) * 40).toInt()
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
                    tripStat?.totalRegenEnergy != null &&
                    trip.energyConsumed != null &&
                    trip.energyConsumed!! > 0
                ) {
                    val regen = tripStat.totalRegenEnergy!!
                    (regen / (trip.energyConsumed!! + regen)) * 100.0
                } else null

                trip.id to TripDisplayMetrics(avgSpeed, score, regenPct)
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly, // compute immediately and keep up-to-date for the entire app lifecycle since it's cheap to compute and used in multiple places, else SharingStarted.WhileSubscribed(5000)
            initialValue = emptyMap()
        )

    // Auto trip detection
    private val _autoTripDetection = MutableStateFlow(true)
    val autoTripDetection: StateFlow<Boolean> = _autoTripDetection.asStateFlow()

    // Live range projection data points for the current trip (in-memory, not persisted)
    private val _tripDataPoints = MutableStateFlow<List<RangeDataPoint>>(emptyList())
    val tripDataPoints: StateFlow<List<RangeDataPoint>> = _tripDataPoints.asStateFlow()
    private var tripStartOdometer: Double? = null

    init {
        // Initialize auto trip detection state
        _autoTripDetection.value = tripRepository.isAutoTripDetectionEnabled()

        // Accumulate range projection data points reactively during active trips.
        // Uses odometer delta for distance so no extra telemetry field is required.
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
                        // Throttle to one point per 100 m of odometer change.
                        // At ~1 Hz MQTT a 2-hour drive would otherwise accumulate
                        // 7,200 points; 100 m spacing keeps it under ~300 per trip.
                        val distKm = (telemetry.odometer - (tripStartOdometer ?: telemetry.odometer))
                            .coerceAtLeast(0.0)
                        val lastDist = _tripDataPoints.value.lastOrNull()?.distanceKm ?: 0.0
                        if (distKm - lastDist >= 0.1) { // You can tighten it to 0.05 (50 m) if you want finer granularity on short city trips.
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

    // For Settings screen to restart service with new config
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
                Log.d(TAG, "Broker: $brokerUrl:$brokerPort")
                Log.d(TAG, "Topic: $topic")

                // Step 1: Stop current MQTT client service
                Log.d(TAG, "Stopping current MQTT client...")
                MqttService.stop(getApplication())
                delay(2000) // Wait for service to fully stop

                // Step 2: Check if user wants embedded broker
                val isLocalBroker = brokerUrl.trim().let {
                    it == "127.0.0.1" || it == "localhost" || it == "::1"
                }

                if (isLocalBroker) {
                    Log.d(TAG, "✓ Local broker detected ($brokerUrl)")
                    Log.d(TAG, "  Ensuring embedded broker is running...")

                    // Start embedded broker (safe to call even if already running)
                    try {
                        MqttBrokerService.start(getApplication())
                        Log.d(TAG, "✓ Embedded broker service started/verified")

                        // CRITICAL: Wait for broker to be ready
                        Log.d(TAG, "  Waiting 6s for broker initialization...")
                        delay(6000)

                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Error starting embedded broker", e)
                        // Continue anyway - broker might already be running
                    }
                } else {
                    Log.d(TAG, "✓ External broker detected ($brokerUrl)")
                    Log.d(TAG, "  Using external broker, no embedded broker needed")
                }

                // Step 3: Start MQTT client with new settings
                Log.d(TAG, "Starting MQTT client with new settings...")
                MqttService.start(
                    context = getApplication(),
                    brokerUrl = brokerUrl,
                    brokerPort = brokerPort,
                    username = username,
                    password = password,
                    topic = topic
                )

                Log.d(TAG, "✓ MQTT client service start command sent")
                Log.d(TAG, "  Connection attempt in progress...")

                // Wait for connection to establish
                delay(2000)

                Log.d(TAG, "=== MQTT Service Restart Complete ===")

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error restarting MQTT service", e)
                Log.e(TAG, "   Error: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    fun stopMqttService() {
        MqttService.stop(getApplication())
        _mqttConnected.value = false
        _mqttConnectionError.value = null
    }

    fun startManualTrip() {
        viewModelScope.launch {
            val telemetry = currentTelemetry.value
            if (telemetry != null) {
                _tripDataPoints.value = emptyList() // reset before repository triggers isInTrip → true
                tripRepository.startTrip(telemetry, isManual = true)
            }
        }
    }

    fun endManualTrip() {
        viewModelScope.launch {
            tripRepository.endCurrentTrip()
            // No need to update state - repository will broadcast via StateFlow
        }
    }

    fun toggleAutoTripDetection() {
        viewModelScope.launch {
            val newValue = !_autoTripDetection.value
            tripRepository.setAutoTripDetection(newValue)
            _autoTripDetection.value = newValue
        }
    }

    fun deleteTrip(tripId: Long) {
        viewModelScope.launch {
            tripRepository.deleteTrip(tripId)
        }
    }

    fun mergeTrips(tripIds: List<Long>) {
        viewModelScope.launch {
            tripRepository.mergeTrips(tripIds)
        }
    }

    fun getTripDetails(tripId: Long): StateFlow<TripEntity?> {
        // Seed with the already-loaded list entry so the details screen never
        // renders a null frame — eliminates the one-frame flicker on navigation.
        val cached = allTrips.value.firstOrNull { it.id == tripId }
        return tripRepository.getTripById(tripId)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = cached
            )
    }

    fun getTripDataPoints(tripId: Long): StateFlow<List<TripDataPointEntity>> {
        return tripRepository.getDataPointsForTrip(tripId)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptyList()
            )
    }

    fun getTripStats(tripId: Long): StateFlow<TripStatsEntity?> {
        return tripRepository.getStatsForTrip(tripId)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = null
            )
    }

    // Called from MainActivity when service binding is established
    fun observeMqttServiceState(service: MqttService) {
        viewModelScope.launch {
            service.connectionState.collect { state ->
                when (state) {
                    is MqttService.ConnectionState.Connected -> {
                        _mqttConnected.value = true
                        _mqttConnectionError.value = null
                    }
                    is MqttService.ConnectionState.Error -> {
                        _mqttConnected.value = false
                        _mqttConnectionError.value = state.message
                    }
                    is MqttService.ConnectionState.Connecting -> {
                        _mqttConnected.value = false
                        _mqttConnectionError.value = null
                    }
                    is MqttService.ConnectionState.Disconnected -> {
                        _mqttConnected.value = false
                        _mqttConnectionError.value = null
                    }
                }
            }
        }
    }

    // Legacy method - kept for backward compatibility
    fun setMqttConnectionState(connected: Boolean) {
        _mqttConnected.value = connected
    }

    // Mock data for testing
    fun startMockDrive() {
        viewModelScope.launch {
            val mockGenerator = com.byd.tripstats.mock.MockDataGenerator()
            mockGenerator.generateMockDrive().collect { telemetry ->
                tripRepository.processTelemetry(telemetry)
            }
        }
    }
}