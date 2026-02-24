package com.byd.tripstats

import android.app.Application
import android.util.Log
import com.byd.tripstats.data.preferences.PreferencesManager
import com.byd.tripstats.service.MqttBrokerService
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Custom Application class for BYD Trip Stats
 * 
 * HYBRID APPROACH:
 * - If user configured 127.0.0.1 → Start embedded broker
 * - If user configured external IP → Skip embedded broker
 * 
 * This gives users flexibility:
 * - Option 1: Standalone (embedded broker)
 * - Option 2: External broker (HiveMQ, custom, etc.)
 */
class BydStatsApplication : Application() {

    companion object {
        private const val TAG = "BydStatsApp"
    }

    override fun onCreate() {
        super.onCreate()
        
        Log.d(TAG, "=== BYD Trip Stats Application Starting ===")
        Log.d(TAG, "Version: 1.0.0")
        Log.d(TAG, "Package: $packageName")
        
        // Check if user wants embedded broker
        checkAndStartEmbeddedBroker()
        
        Log.d(TAG, "=== Application initialization complete ===")
    }

    private fun checkAndStartEmbeddedBroker() {
        // Launch coroutine to check settings
        @OptIn(DelicateCoroutinesApi::class)
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val preferencesManager = PreferencesManager(applicationContext)
                
                // Load settings with timeout
                val settings = withTimeout(3000L) {
                    preferencesManager.mqttSettings.first()
                }
                
                Log.d(TAG, "Loaded MQTT settings:")
                Log.d(TAG, "  Broker: ${settings.brokerUrl}")
                Log.d(TAG, "  Port: ${settings.brokerPort}")
                
                // Check if local broker
                val isLocalBroker = settings.brokerUrl.trim().let {
                    it == "127.0.0.1" || it == "localhost" || it == "::1" || it.isBlank()
                }
                
                if (isLocalBroker) {
                    Log.d(TAG, "✓ Local broker mode detected")
                    Log.d(TAG, "  Starting embedded MQTT broker...")
                    
                    MqttBrokerService.start(applicationContext)
                    
                    Log.d(TAG, "✅ Embedded MQTT broker started")
                    Log.d(TAG, "   Listening on: 127.0.0.1:${settings.brokerPort}")
                    
                } else {
                    Log.d(TAG, "✓ External broker mode detected")
                    Log.d(TAG, "  Broker: ${settings.brokerUrl}:${settings.brokerPort}")
                    Log.d(TAG, "  Embedded broker NOT started (not needed)")
                }
                
            } catch (e: TimeoutCancellationException) {
                Log.w(TAG, "⚠ Timeout loading settings, using defaults")
                Log.w(TAG, "  Starting embedded broker as fallback...")
                
                // Default to embedded broker if settings not loaded
                MqttBrokerService.start(applicationContext)
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error checking broker mode", e)
                Log.w(TAG, "  Starting embedded broker as fallback...")
                
                // Default to embedded broker on error
                try {
                    MqttBrokerService.start(applicationContext)
                } catch (e2: Exception) {
                    Log.e(TAG, "❌ Failed to start embedded broker", e2)
                }
            }
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        Log.d(TAG, "Application terminating...")
        
        // Note: onTerminate() is never called in production
        // Services will be stopped by Android when app is killed
    }
}
