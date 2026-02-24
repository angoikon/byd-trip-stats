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
import io.moquette.interception.AbstractInterceptHandler
import io.moquette.interception.messages.InterceptPublishMessage
import io.moquette.interception.messages.InterceptSubscribeMessage
import io.moquette.interception.messages.InterceptConnectMessage
import java.util.Properties

/**
 * Embedded MQTT Broker Service with MESSAGE INTERCEPTION for debugging
 */
class MqttBrokerService : Service() {

    private var mqttServer: Server? = null
    private val notificationId = 2002
    
    companion object {
        private const val TAG = "MqttBrokerService"
        private const val NOTIFICATION_CHANNEL_ID = "mqtt_broker_channel"
        private const val MQTT_PORT = 1883
        
        fun start(context: Context) {
            val intent = Intent(context, MqttBrokerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            Log.d(TAG, "MQTT Broker service start requested")
        }
        
        fun stop(context: Context) {
            context.stopService(Intent(context, MqttBrokerService::class.java))
            Log.d(TAG, "MQTT Broker service stop requested")
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "=== MQTT Broker Service Created ===")
        
        startForeground(notificationId, createNotification())
        startMqttBroker()
    }

    private fun startMqttBroker() {
        try {
            Log.d(TAG, "Starting embedded MQTT broker on port $MQTT_PORT...")
            
            val config = Properties().apply {
                setProperty("port", MQTT_PORT.toString())
                setProperty("host", "0.0.0.0")
                setProperty("allow_anonymous", "true")
                setProperty("ssl_port", "0")
                setProperty("persistent_store", "${filesDir.absolutePath}/moquette_store.db")
                setProperty("autosave_interval", "300")
                setProperty("netty.epoll_threads", "1")
                setProperty("netty.max_bytes_in_message", "16384")
                setProperty("timeout", "10")
            }

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
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "=== MQTT Broker Service Destroyed ===")
        stopMqttBroker()
    }

    /**
     * Create notification for foreground service
     */
    private fun createNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "MQTT Broker",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Embedded MQTT broker for local telemetry"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("MQTT Broker Active")
            .setContentText("Local broker running on port $MQTT_PORT")
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }
}