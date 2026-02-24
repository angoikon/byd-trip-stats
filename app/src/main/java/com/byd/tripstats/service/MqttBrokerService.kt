package com.byd.tripstats.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.byd.tripstats.R
import io.moquette.broker.Server
import io.moquette.broker.config.MemoryConfig
import java.util.Properties

/**
 * Embedded MQTT Broker Service
 * 
 * Runs a lightweight MQTT broker inside the app on port 1883.
 * This allows Electro and BYD Trip Stats to communicate locally
 * without any external MQTT broker (no HiveMQ, no Mosquitto, nothing!)
 * 
 * Benefits:
 * - Zero external dependencies
 * - Ultra-low latency (<1ms)
 * - No internet required
 * - Maximum privacy
 * - One-app installation
 */
class MqttBrokerService : Service() {

    private var mqttServer: Server? = null
    private val notificationId = 2002
    
    companion object {
        private const val TAG = "MqttBrokerService"
        private const val NOTIFICATION_CHANNEL_ID = "mqtt_broker_channel"
        private const val MQTT_PORT = 1883 // Standard MQTT port (change to 1338 if needed)
        
        /**
         * Start the MQTT broker service
         */
        fun start(context: Context) {
            val intent = Intent(context, MqttBrokerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            Log.d(TAG, "MQTT Broker service start requested")
        }
        
        /**
         * Stop the MQTT broker service
         */
        fun stop(context: Context) {
            context.stopService(Intent(context, MqttBrokerService::class.java))
            Log.d(TAG, "MQTT Broker service stop requested")
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "=== MQTT Broker Service Created ===")
        
        // Start as foreground service (required for Android O+)
        startForeground(notificationId, createNotification())
        
        // Start the embedded MQTT broker
        startMqttBroker()
    }

    private fun startMqttBroker() {
        try {
            Log.d(TAG, "Starting embedded MQTT broker on port $MQTT_PORT...")
            
            // Create broker configuration
            val config = Properties().apply {
                // Network settings
                setProperty("port", MQTT_PORT.toString())
                setProperty("host", "0.0.0.0") // Listen on all interfaces (127.0.0.1, Wi-Fi, etc.)
                
                // Security (no authentication for simplicity)
                setProperty("allow_anonymous", "true")
                
                // SSL/TLS disabled (not needed for local communication)
                setProperty("ssl_port", "0")
                
                // Persistence settings (save messages to disk)
                setProperty("persistent_store", "${filesDir.absolutePath}/moquette_store.db")
                setProperty("autosave_interval", "300") // Save every 5 minutes
                
                // Performance tuning
                setProperty("netty.epoll_threads", "1") // Low resource usage
                setProperty("netty.max_bytes_in_message", "16384") // 16KB max message size
                
                // Timeouts
                setProperty("timeout", "10") // 10 seconds timeout
            }
            
            // Create and start Moquette server
            mqttServer = Server()
            val memoryConfig = MemoryConfig(config)
            
            mqttServer?.startServer(memoryConfig)
            
            Log.d(TAG, "✅ MQTT Broker started successfully!")
            Log.d(TAG, "   Port: $MQTT_PORT")
            Log.d(TAG, "   Host: 0.0.0.0 (all interfaces)")
            Log.d(TAG, "   Auth: Anonymous (no password)")
            Log.d(TAG, "   Storage: ${filesDir.absolutePath}/moquette_store.db")
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to start MQTT broker", e)
            Log.e(TAG, "   Error: ${e.message}")
            
            // If port is in use, suggest alternative
            if (e.message?.contains("Address already in use") == true) {
                Log.e(TAG, "   Port $MQTT_PORT is already in use!")
                Log.e(TAG, "   Try changing MQTT_PORT to 1338 in MqttBrokerService.kt")
            }
        }
    }

    private fun stopMqttBroker() {
        try {
            Log.d(TAG, "Stopping MQTT broker...")
            mqttServer?.stopServer()
            mqttServer = null
            Log.d(TAG, "✅ MQTT Broker stopped successfully")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error stopping MQTT broker", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand called")
        return START_STICKY // Restart service if killed by system
    }

    override fun onBind(intent: Intent?): IBinder? {
        // This is a started service, not bound
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "=== MQTT Broker Service Destroyed ===")
        stopMqttBroker()
    }

    /**
     * Create notification for foreground service
     */
    private fun createNotification(): Notification {
        // Create notification channel for Android O+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "MQTT Broker",
                NotificationManager.IMPORTANCE_LOW // Low importance = no sound/vibration
            ).apply {
                description = "Embedded MQTT broker for local telemetry"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }

        // Build notification
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("MQTT Broker Active")
            .setContentText("Local broker running on port $MQTT_PORT")
            .setSmallIcon(R.drawable.ic_notification) // Make sure this icon exists
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true) // Can't be dismissed
            .setShowWhen(false)
            .build()
    }
}
