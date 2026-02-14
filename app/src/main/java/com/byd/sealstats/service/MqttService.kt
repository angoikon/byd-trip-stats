package com.byd.sealstats.service

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
import com.byd.sealstats.MainActivity
import com.byd.sealstats.R
import com.byd.sealstats.data.mqtt.MqttClientManager
import com.byd.sealstats.data.repository.TripRepository
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
    
    private val _connectionState = MutableStateFlow<MqttClientManager.ConnectionState>(
        MqttClientManager.ConnectionState.Disconnected
    )
    val connectionState: StateFlow<MqttClientManager.ConnectionState> = _connectionState.asStateFlow()
    
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
        Log.d(TAG, "Intent: $intent")
        Log.d(TAG, "broker_url: ${intent?.getStringExtra("broker_url")}")
        Log.d(TAG, "Service started")

        val brokerUrl = intent?.getStringExtra("broker_url") ?: "broker.hivemq.com"
        val brokerPort = intent?.getIntExtra("broker_port", 1883) ?: 1883
        val username = intent?.getStringExtra("username")
        val password = intent?.getStringExtra("password")
        val topic = intent?.getStringExtra("topic") ?: "electro/telemetry/byd-seal/data"

        Log.d(TAG, "=== MQTT CONFIG ===")
        Log.d(TAG, "Broker: $brokerUrl")
        Log.d(TAG, "Port: $brokerPort")
        Log.d(TAG, "Username: $username")
        Log.d(TAG, "Topic: $topic")
        Log.d(TAG, "Topic: $password")

        startForeground(NOTIFICATION_ID, createNotification("Connecting..."))
        
        // Initialize MQTT client
        mqttClientManager = MqttClientManager(
            brokerUrl = brokerUrl,
            brokerPort = brokerPort,
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
                Log.d(TAG, "=== Inside coroutine, about to connect ===")
                Log.d(TAG, "mqttClientManager = $mqttClientManager")

                mqttClientManager?.connect()?.collect { state ->
                    Log.d(TAG, "=== Connection state received: $state ===")
                    _connectionState.value = state

                    when (state) {
                        is MqttClientManager.ConnectionState.Connected -> {
                            Log.d(TAG, "=== CONNECTED! ===")
                            updateNotification("Connected to MQTT")
                            subscribeTelemetry()
                        }
                        is MqttClientManager.ConnectionState.Error -> {
                            Log.e(TAG, "=== CONNECTION ERROR: ${state.message} ===")
                            updateNotification("Connection error: ${state.message}")
                        }
                        is MqttClientManager.ConnectionState.Connecting -> {
                            Log.d(TAG, "=== CONNECTING... ===")
                            updateNotification("Connecting...")
                        }
                        else -> {
                            Log.d(TAG, "=== Other state: $state ===")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "=== EXCEPTION in startMqttConnection ===", e)
                Log.e(TAG, "Exception message: ${e.message}")
                e.printStackTrace()
            }
        }
        Log.d(TAG, "=== startMqttConnection RETURNED (coroutine launched) ===")
    }
    
    private fun subscribeTelemetry() {
        Log.d(TAG, "=== STARTING TELEMETRY SUBSCRIPTION ===")
        serviceScope.launch {
            Log.d(TAG, "Calling mqttClientManager.subscribeToTelemetry()...")
            mqttClientManager?.subscribeToTelemetry()?.collect { result ->
                result.onSuccess { telemetry ->
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
            .setContentTitle("BYD Info Stats")
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
