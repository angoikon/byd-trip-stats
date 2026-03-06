package com.byd.tripstats

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.navigation.compose.rememberNavController
import com.byd.tripstats.service.MqttService
import com.byd.tripstats.ui.navigation.AppNavigation
import com.byd.tripstats.ui.theme.BydTripStatsTheme
import com.byd.tripstats.ui.viewmodel.DashboardViewModel

class MainActivity : ComponentActivity() {

    private val TAG = "MainActivity"

    private val viewModel: DashboardViewModel by viewModels()
    private var mqttService: MqttService? = null
    private var bound = false

    // ── Service binding ───────────────────────────────────────────────────────

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as MqttService.LocalBinder
            mqttService = binder.getService()
            bound = true
            Log.i(TAG, "MqttService connected")
            mqttService?.let { viewModel.observeMqttServiceState(it) }
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            bound = false
            mqttService = null
            Log.w(TAG, "MqttService disconnected unexpectedly")
        }
    }

    // ── Permission launcher ───────────────────────────────────────────────────

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.entries.all { it.value }
        Log.d(TAG, "Permissions result — all granted: $allGranted")
        if (allGranted) bindToMqttService()
        else Log.w(TAG, "Some permissions were denied")
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestRequiredPermissions()

        setContent {
            BydTripStatsTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    AppNavigation(
                        navController = navController,
                        viewModel = viewModel
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (bound) {
            unbindService(connection)
            bound = false
        }
        // Do NOT stop the service — it must keep running in the background
        // for auto trip detection and MQTT telemetry collection.
    }

    // ── Permissions ───────────────────────────────────────────────────────────

    private fun requestRequiredPermissions() {
        val required = buildList {
            // POST_NOTIFICATIONS required on Android 13+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        val missing = required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            bindToMqttService()
        }
    }

    // ── Service binding ───────────────────────────────────────────────────────

    /**
     * Binds to MqttService. The service is started by [BydStatsApplication] on
     * every process start, so it should already be running by the time the
     * Activity reaches this point. [Context.BIND_AUTO_CREATE] ensures it is
     * started if somehow it is not yet running (e.g. during first-ever launch
     * before Application.onCreate has finished the 6-second broker-init delay).
     *
     * We no longer use the deprecated [android.app.ActivityManager.getRunningServices]
     * to detect whether the service is running — that API is unreliable on
     * modern Android and was removed from the call path entirely.
     */
    private fun bindToMqttService() {
        Log.d(TAG, "Binding to MqttService")
        val intent = Intent(this, MqttService::class.java)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

}