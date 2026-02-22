package com.byd.tripstats.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.byd.tripstats.data.local.entity.TripDataPointEntity
import com.byd.tripstats.data.local.entity.TripEntity
import com.byd.tripstats.data.local.entity.TripStatsEntity
import com.byd.tripstats.data.model.VehicleTelemetry
import com.byd.tripstats.data.repository.TripRepository
import com.byd.tripstats.service.MqttService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DashboardViewModel(application: Application) : AndroidViewModel(application) {
    
    private val tripRepository = TripRepository.getInstance(application)

    // MQTT Connection state - properly tracked from service
    private val _mqttConnected = MutableStateFlow(false)
    val mqttConnected: StateFlow<Boolean> = _mqttConnected.asStateFlow()

    private val _mqttConnectionError = MutableStateFlow<String?>(null)
    val mqttConnectionError: StateFlow<String?> = _mqttConnectionError.asStateFlow()
    
    // Latest telemetry
    private val _currentTelemetry = MutableStateFlow<VehicleTelemetry?>(null)
    val currentTelemetry: StateFlow<VehicleTelemetry?> = _currentTelemetry.asStateFlow()
    
    // Current trip state
    private val _isInTrip = MutableStateFlow(false)
    val isInTrip: StateFlow<Boolean> = _isInTrip.asStateFlow()
    
    private val _currentTripId = MutableStateFlow<Long?>(null)
    val currentTripId: StateFlow<Long?> = _currentTripId.asStateFlow()
    
    // Trip history
    val allTrips: StateFlow<List<TripEntity>> = tripRepository.getAllTrips()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
    
    // Auto trip detection
    private val _autoTripDetection = MutableStateFlow(true)
    val autoTripDetection: StateFlow<Boolean> = _autoTripDetection.asStateFlow()

    init {
        updateTripState()

        // Observe telemetry from repository
        viewModelScope.launch {
            tripRepository.latestTelemetry.collect { telemetry ->
                telemetry?.let {
                    _currentTelemetry.value = it
                }
            }
        }
    }
    
    private fun updateTripState() {
        viewModelScope.launch {
            _isInTrip.value = tripRepository.isCurrentlyInTrip()
            _currentTripId.value = tripRepository.getCurrentTripId()
            _autoTripDetection.value = tripRepository.isAutoTripDetectionEnabled()
        }
    }
    
    fun startMqttService(
        brokerUrl: String,
        brokerPort: Int,
        username: String?,
        password: String?,
        topic: String
    ) {
        // Start service - it will handle connection state internally
        MqttService.start(
            context = getApplication(),
            brokerUrl = brokerUrl,
            brokerPort = brokerPort,
            username = username,
            password = password,
            topic = topic
        )
        // viewModelScope.launch {
        //     delay(5000) // Wait 5 seconds for connection
        //     _mqttConnected.value = true
        // }
    }
    
    fun stopMqttService() {
        MqttService.stop(getApplication())
        _mqttConnected.value = false
        _mqttConnectionError.value = null
    }
    
    fun startManualTrip() {
        viewModelScope.launch {
            val telemetry = _currentTelemetry.value
            if (telemetry != null) {
                tripRepository.startTrip(telemetry, isManual = true)
                updateTripState()
            }
        }
    }

    fun endManualTrip() {
        viewModelScope.launch {
            tripRepository.endCurrentTrip()
            updateTripState()
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
        return tripRepository.getTripById(tripId)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = null
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

    // Update telemetry from service
    fun updateTelemetry(telemetry: VehicleTelemetry) {
        _currentTelemetry.value = telemetry
        updateTripState()
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
                updateTelemetry(telemetry)
                tripRepository.processTelemetry(telemetry)
            }
        }
    }
}
