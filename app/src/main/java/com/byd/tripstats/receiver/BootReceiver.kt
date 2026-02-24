package com.byd.tripstats.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.byd.tripstats.service.MqttBrokerService  // ← ADD THIS IMPORT
import com.byd.tripstats.service.MqttService
import com.byd.tripstats.data.preferences.PreferencesManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/**
 * CRITICAL: Receives BOOT_COMPLETED and starts MQTT service
 * This is what enables auto-start functionality
 * 
 * SUPPORTS BOTH:
 * - Local embedded broker (127.0.0.1) - starts MqttBrokerService
 * - External broker (HiveMQ, etc.) - skips MqttBrokerService
 * 
 * DEBUGGING: If this doesn't work, check:
 * 1. AndroidManifest has NO android:permission on <receiver>
 * 2. App has RECEIVE_BOOT_COMPLETED permission
 * 3. Battery optimization disabled for app
 * 4. Device allows app to auto-start (Settings → Apps → Autostart)
 */
class BootReceiver : BroadcastReceiver() {
    private val TAG = "BootReceiver"
    
    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "=================================================================")
        Log.i(TAG, "=== BOOT RECEIVER TRIGGERED ===")
        Log.i(TAG, "Action: ${intent.action}")
        Log.i(TAG, "Package: ${context.packageName}")
        Log.i(TAG, "Time: ${System.currentTimeMillis()}")
        Log.i(TAG, "=================================================================")

        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_REBOOT,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON" -> {
                
                Log.i(TAG, "✓ Boot action recognized, starting service...")
                
                // Use goAsync() to allow async work beyond receiver's lifecycle
                val pendingResult = goAsync()
                
                // Use GlobalScope to ensure completion even if receiver dies
                // This is acceptable here because we MUST complete the service start
                @OptIn(DelicateCoroutinesApi::class)
                GlobalScope.launch(Dispatchers.IO) {
                    try {
                        Log.i(TAG, "Loading MQTT settings from DataStore...")
                        
                        val preferencesManager = PreferencesManager(context.applicationContext)
                        
                        // Give DataStore 5 seconds max to load
                        // BroadcastReceiver has 10s total, so this is safe
                        val settings = withTimeout(5000L) {
                            preferencesManager.mqttSettings.first()
                        }
                        
                        Log.i(TAG, "Settings loaded successfully:")
                        Log.i(TAG, "  - Broker: ${settings.brokerUrl}")
                        Log.i(TAG, "  - Port: ${settings.brokerPort}")
                        Log.i(TAG, "  - Topic: ${settings.topic}")
                        Log.i(TAG, "  - Has Username: ${settings.username.isNotBlank()}")
                        Log.i(TAG, "  - Has Password: ${settings.password.isNotBlank()}")
                        
                        // Validate configuration
                        if (settings.brokerUrl.isBlank()) {
                            Log.w(TAG, "❌ MQTT broker URL is blank - cannot start service")
                            Log.w(TAG, "   User needs to configure MQTT in Settings")
                            return@launch
                        }
                        
                        if (settings.topic.isBlank()) {
                            Log.w(TAG, "❌ MQTT topic is blank - cannot start service")
                            Log.w(TAG, "   User needs to configure MQTT in Settings")
                            return@launch
                        }

                        // Check if user wants local embedded broker
                        val isLocalBroker = settings.brokerUrl.trim().let {
                            it == "127.0.0.1" || it == "localhost" || it == "::1"
                        }

                        if (isLocalBroker) {
                            Log.i(TAG, "✓ Local broker detected (${settings.brokerUrl})")
                            Log.i(TAG, "  Starting embedded MQTT broker first...")

                            try {
                                MqttBrokerService.start(context.applicationContext)
                                Log.i(TAG, "✓ Embedded broker service started")

                                // Wait for broker to initialize before starting client
                                Log.i(TAG, "  Waiting 2s for broker initialization...")
                                delay(2000)

                            } catch (e: Exception) {
                                Log.e(TAG, "❌ Failed to start embedded broker", e)
                                Log.e(TAG, "   Continuing anyway - client will try to connect")
                            }
                        } else {
                            Log.i(TAG, "✓ External broker detected (${settings.brokerUrl})")
                            Log.i(TAG, "  Skipping embedded broker, will use external")
                        }

                        Log.i(TAG, "✓ Configuration valid, starting MqttService...")

                        // Start the MQTT client service
                        try {
                            MqttService.start(
                                context = context.applicationContext,
                                brokerUrl = settings.brokerUrl,
                                brokerPort = settings.brokerPort,
                                username = settings.username.ifBlank { null },
                                password = settings.password.ifBlank { null },
                                topic = settings.topic
                            )
                            
                            Log.i(TAG, "=================================================================")
                            Log.i(TAG, "✓✓✓ MQTT SERVICE START COMMAND SENT ✓✓✓")
                            Log.i(TAG, "=================================================================")
                            
                            // Small delay to ensure service actually starts
                            delay(500)
                            
                            Log.i(TAG, "Service should now be running in background")
                            
                        } catch (e: Exception) {
                            Log.e(TAG, "❌ FAILED TO START SERVICE", e)
                            Log.e(TAG, "   Error: ${e.message}")
                            e.printStackTrace()
                        }
                        
                    } catch (e: TimeoutCancellationException) {
                        Log.e(TAG, "❌ TIMEOUT loading MQTT settings (>5s)")
                        Log.e(TAG, "   DataStore may be corrupted or slow")
                        Log.e(TAG, "   Service NOT started")
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ EXCEPTION in BootReceiver", e)
                        Log.e(TAG, "   Error type: ${e.javaClass.simpleName}")
                        Log.e(TAG, "   Error message: ${e.message}")
                        e.printStackTrace()
                    } finally {
                        Log.i(TAG, "Finishing broadcast receiver")
                        try {
                            pendingResult.finish()
                            Log.i(TAG, "✓ Broadcast result finished successfully")
                        } catch (e: Exception) {
                            Log.e(TAG, "Error finishing pendingResult", e)
                        }
                    }
                }
            }
            else -> {
                Log.d(TAG, "Unhandled boot action: ${intent.action}")
                Log.d(TAG, "This receiver only handles BOOT_COMPLETED actions")
            }
        }
    }
}