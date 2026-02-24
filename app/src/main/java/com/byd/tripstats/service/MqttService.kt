package com.byd.tripstats.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.byd.tripstats.MainActivity
import com.byd.tripstats.R
import com.byd.tripstats.data.mqtt.MqttClientManager
import com.byd.tripstats.data.repository.TripRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MqttService : Service() {
    private val TAG = "MqttService"
    private val CHANNEL_ID = "mqtt_service_channel"
    private val NOTIFICATION_ID = 1
    
    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    private var mqttClientManager: MqttClientManager? = null
    private var tripRepository: TripRepository? = null
    private var wakeLock: PowerManager.WakeLock? = null
    
    // Connection state - now properly tracked
    sealed class ConnectionState {
        object Disconnected : ConnectionState()
        object Connecting : ConnectionState()
        object Connected : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    
    private val _telemetryCount = MutableStateFlow(0)
    val telemetryCount: StateFlow<Int> = _telemetryCount.asStateFlow()
    
    inner class LocalBinder : Binder() {
        fun getService(): MqttService = this@MqttService
    }
    
    override fun onBind(intent: Intent): IBinder {
        return binder
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        createNotificationChannel()
        acquireWakeLock()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service started")

        val brokerUrl = intent?.getStringExtra("broker_url")
        val brokerPort = intent?.getIntExtra("broker_port", 1883)
        val username = intent?.getStringExtra("username")
        val password = intent?.getStringExtra("password")
        val topic = intent?.getStringExtra("topic")

        // Validate configuration
        if (brokerUrl.isNullOrBlank() || topic.isNullOrBlank()) {
            Log.e(TAG, "Invalid MQTT configuration")
            _connectionState.value = ConnectionState.Error("Invalid configuration")
            updateNotification("Configuration error")
            return START_STICKY
        }

        Log.d(TAG, "=== MQTT CONFIG ===")
        Log.d(TAG, "Broker: $brokerUrl:$brokerPort")
        Log.d(TAG, "Topic: $topic")

        startForeground(NOTIFICATION_ID, createNotification("Starting..."))

        // Initialize MQTT client
        mqttClientManager = MqttClientManager(
            brokerUrl = brokerUrl,
            brokerPort = brokerPort ?: 1883,
            username = username,
            password = password,
            topic = topic
        )

        // Initialize repository
        tripRepository = TripRepository.getInstance(applicationContext)

        startMqttConnection()

        return START_STICKY
    }

    private fun startMqttConnection() {
        Log.d(TAG, "=== startMqttConnection CALLED ===")
        serviceScope.launch {
            try {
                _connectionState.value = ConnectionState.Connecting
                updateNotification("Connecting...")

                mqttClientManager?.connect()?.collect { state ->
                    Log.d(TAG, "=== Connection state received: $state ===")

                    when (state) {
                        is MqttClientManager.ConnectionState.Connected -> {
                            Log.d(TAG, "=== CONNECTED! ===")
                            _connectionState.value = ConnectionState.Connected
                            updateNotification("Connected")
                            subscribeTelemetry()
                        }
                        is MqttClientManager.ConnectionState.Error -> {
                            Log.e(TAG, "=== CONNECTION ERROR: ${state.message} ===")
                            _connectionState.value = ConnectionState.Error(state.message)
                            updateNotification("Connection error: ${state.message}")
                        }
                        is MqttClientManager.ConnectionState.Connecting -> {
                            Log.d(TAG, "=== CONNECTING... ===")
                            _connectionState.value = ConnectionState.Connecting
                            updateNotification("Connecting...")
                        }
                        is MqttClientManager.ConnectionState.Disconnected -> {
                            Log.d(TAG, "=== DISCONNECTED ===")
                            _connectionState.value = ConnectionState.Disconnected
                            updateNotification("Disconnected")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "=== EXCEPTION in startMqttConnection ===", e)
                _connectionState.value = ConnectionState.Error(e.message ?: "Unknown error")
                updateNotification("Error: ${e.message}")
            }
        }
    }
    
    private fun subscribeTelemetry() {
        Log.d(TAG, "=== STARTING TELEMETRY SUBSCRIPTION ===")
        serviceScope.launch {
            mqttClientManager?.subscribeToTelemetry()?.collect { result ->
                result.onSuccess { telemetry ->
                    Log.d(TAG, "📨 MESSAGE RECEIVED! SoC=${telemetry.soc}%, Gear=${telemetry.gear}")
                    _telemetryCount.value++
                    updateNotification("Receiving data (${_telemetryCount.value} messages)")

                    Log.d(TAG, "MQTT received: SOC=${telemetry.soc}, Speed=${telemetry.speed}, Gear=${telemetry.gear}")

                    // Process telemetry through repository
                    tripRepository?.processTelemetry(telemetry)
                }.onFailure { error ->
                    Log.e(TAG, "Telemetry error", error)
                }
            }
        }
    }
    
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "MQTT Connection Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Maintains connection to vehicle telemetry"
        }
        
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }
    
    private fun createNotification(message: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BYD Trip Stats")
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
    
    private fun updateNotification(message: String) {
        val notification = createNotification(message)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
    
    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "BydStats::MqttServiceWakeLock"
        ).apply {
            acquire(10 * 60 * 1000L) // 10 minutes, will be renewed
        }
    }
    
    override fun onDestroy() {
        Log.d(TAG, "Service destroyed")
        mqttClientManager?.disconnect()
        wakeLock?.release()
        serviceScope.cancel()
        _connectionState.value = ConnectionState.Disconnected
        super.onDestroy()
    }
    
    companion object {
        fun start(
            context: Context,
            brokerUrl: String,
            brokerPort: Int,
            username: String?,
            password: String?,
            topic: String
        ) {
            val intent = Intent(context, MqttService::class.java).apply {
                putExtra("broker_url", brokerUrl)
                putExtra("broker_port", brokerPort)
                putExtra("username", username)
                putExtra("password", password)
                putExtra("topic", topic)
            }
            context.startForegroundService(intent)
        }
        
        fun stop(context: Context) {
            val intent = Intent(context, MqttService::class.java)
            context.stopService(intent)
        }
    }
}
