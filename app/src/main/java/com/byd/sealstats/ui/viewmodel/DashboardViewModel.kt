package com.byd.sealstats.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.byd.sealstats.data.local.entity.TripDataPointEntity
import com.byd.sealstats.data.local.entity.TripEntity
import com.byd.sealstats.data.local.entity.TripStatsEntity
import com.byd.sealstats.data.model.VehicleTelemetry
import com.byd.sealstats.data.repository.TripRepository
import com.byd.sealstats.service.MqttService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DashboardViewModel(application: Application) : AndroidViewModel(application) {
    
    private val tripRepository = TripRepository.getInstance(application)

    // MQTT Connection state
    private val _mqttConnected = MutableStateFlow(false)
    val mqttConnected: StateFlow<Boolean> = _mqttConnected.asStateFlow()
    
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
        MqttService.start(
            context = getApplication(),
            brokerUrl = brokerUrl,
            brokerPort = brokerPort,
            username = username,
            password = password,
            topic = topic
        )
        viewModelScope.launch {
            delay(5000) // Wait 5 seconds for connection
            _mqttConnected.value = true
        }
    }
    
    fun stopMqttService() {
        MqttService.stop(getApplication())
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

    fun setMqttConnectionState(connected: Boolean) {
        _mqttConnected.value = connected
    }

    // Mock data for testing
    fun startMockDrive() {
        viewModelScope.launch {
            val mockGenerator = com.byd.sealstats.mock.MockDataGenerator()
            mockGenerator.generateMockDrive().collect { telemetry ->
                updateTelemetry(telemetry)
                tripRepository.processTelemetry(telemetry)
            }
        }
    }
}
