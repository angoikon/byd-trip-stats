package com.byd.tripstats

import android.app.Application
import android.util.Log
import com.byd.tripstats.data.preferences.PreferencesManager
import com.byd.tripstats.service.MqttBrokerService
import com.byd.tripstats.service.MqttService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Application entry point for BYD Trip Stats.
 *
 * Owns the full MQTT startup sequence so it runs correctly on every process
 * start — first launch, reboot via BootReceiver, or process restart by Android:
 *
 *   1. Read persisted MQTT settings.
 *   2. If broker URL is local (127.0.0.1 / localhost / blank) → start the
 *      embedded Moquette broker first and wait for it to be ready.
 *   3. Start the MqttService client with the configured settings.
 *
 * MainActivity only binds to the already-running service; it never starts it.
 */
class BydStatsApplication : Application() {

    companion object {
        private const val TAG = "BydStatsApp"
    }

    /**
     * Application-scoped coroutine scope. SupervisorJob means a failure in one
     * child does not cancel the others. Cancelled automatically when the process
     * dies — no leak, no GlobalScope.
     */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "=== BYD Trip Stats starting (pid=${android.os.Process.myPid()}) ===")
        startMqttStack()
    }

    private fun startMqttStack() {
        appScope.launch {
            try {
                val prefs    = PreferencesManager(applicationContext)
                val settings = withTimeout(3_000L) { prefs.mqttSettings.first() }

                Log.d(TAG, "Settings: broker=${settings.brokerUrl}:${settings.brokerPort} topic=${settings.topic}")

                val isLocal = settings.brokerUrl.trim().let {
                    it.isBlank() || it == "127.0.0.1" || it == "localhost" || it == "::1"
                }

                if (isLocal) {
                    Log.d(TAG, "Local broker mode — starting embedded Moquette broker")
                    MqttBrokerService.start(applicationContext)
                    // Give the broker time to bind its port before the client connects.
                    // 6 s matches the delay previously used in DashboardViewModel.restartMqttService.
                    kotlinx.coroutines.delay(6_000)
                    Log.d(TAG, "Embedded broker ready")
                } else {
                    Log.d(TAG, "External broker mode — skipping embedded broker")
                }

                // Only start the client if the minimum required config is present
                if (settings.brokerUrl.isNotBlank() && settings.topic.isNotBlank()) {
                    Log.d(TAG, "Starting MQTT client")
                    MqttService.start(
                        context    = applicationContext,
                        brokerUrl  = settings.brokerUrl,
                        brokerPort = settings.brokerPort,
                        username   = settings.username.ifBlank { null },
                        password   = settings.password.ifBlank { null },
                        topic      = settings.topic
                    )
                    Log.d(TAG, "MQTT client start command sent")
                } else {
                    Log.w(TAG, "MQTT not configured yet — skipping client start")
                }

            } catch (e: TimeoutCancellationException) {
                Log.w(TAG, "Timed out reading settings — falling back to embedded broker only")
                tryStartEmbeddedBroker()
            } catch (e: Exception) {
                Log.e(TAG, "Error during MQTT stack startup", e)
                tryStartEmbeddedBroker()
            }
        }
    }

    private fun tryStartEmbeddedBroker() {
        try {
            MqttBrokerService.start(applicationContext)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start embedded broker as fallback", e)
        }
    }
}