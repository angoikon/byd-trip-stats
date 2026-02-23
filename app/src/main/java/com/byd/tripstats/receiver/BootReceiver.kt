package com.byd.tripstats.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.byd.tripstats.service.MqttService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.byd.tripstats.data.preferences.PreferencesManager
import kotlinx.coroutines.flow.first

/**
 * Receives BOOT_COMPLETED broadcast and starts MQTT service
 * This ensures the app runs automatically when the car starts
 * 
 * CRITICAL: This starts the SERVICE, not MainActivity
 * The service runs in the background and connects to MQTT
 * No UI is shown - the app is completely silent
 */
class BootReceiver : BroadcastReceiver() {
    private val TAG = "BootReceiver"
    
    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "=== Boot event received ===")
        Log.i(TAG, "Action: ${intent.action}")

        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_REBOOT,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON" -> {
                Log.i(TAG, "Device booted - starting MQTT service in background")
                
                // Load MQTT settings and start service
                val preferencesManager = PreferencesManager(context.applicationContext)
                
                // Use goAsync() to allow coroutine to complete
                val pendingResult = goAsync()
                
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val settings = preferencesManager.mqttSettings.first()
                        
                        Log.i(TAG, "Loaded settings: broker=${settings.brokerUrl}, topic=${settings.topic}")
                        
                        // Only start service if configuration is valid
                        if (settings.brokerUrl.isNotBlank() && settings.topic.isNotBlank()) {
                            Log.i(TAG, "Valid MQTT config found - starting service")
                            
                            MqttService.start(
                                context = context.applicationContext,
                                brokerUrl = settings.brokerUrl,
                                brokerPort = settings.brokerPort,
                                username = settings.username.ifBlank { null },
                                password = settings.password.ifBlank { null },
                                topic = settings.topic
                            )
                            
                            Log.i(TAG, "MQTT service started successfully in background")
                        } else {
                            Log.w(TAG, "MQTT not configured - skipping service start")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to start MQTT service", e)
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
            else -> {
                Log.d(TAG, "Unhandled boot action: ${intent.action}")
            }
        }
    }
}