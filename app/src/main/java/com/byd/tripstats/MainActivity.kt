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
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import com.byd.tripstats.data.preferences.PreferencesManager
import com.byd.tripstats.service.MqttService
import com.byd.tripstats.ui.navigation.AppNavigation
import com.byd.tripstats.ui.theme.BydTripStatsTheme
import com.byd.tripstats.ui.viewmodel.DashboardViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import android.os.PowerManager
import android.provider.Settings
import android.net.Uri

class MainActivity : ComponentActivity() {
    private val TAG = "MainActivity"
    
    private val viewModel: DashboardViewModel by viewModels()
    private var mqttService: MqttService? = null
    private var bound = false
    private lateinit var preferencesManager: PreferencesManager
    
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as MqttService.LocalBinder
            mqttService = binder.getService()
            bound = true
            
            Log.i(TAG, "Service connected successfully")

            // Observe service connection state
            mqttService?.let { viewModel.observeMqttServiceState(it) }
        }
        
        override fun onServiceDisconnected(arg0: ComponentName) {
            bound = false
            mqttService = null
            Log.w(TAG, "Service disconnected")
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        Log.d(TAG, "=== Permission result received ===")
        val allGranted = permissions.entries.all { it.value }
        Log.d(TAG, "All granted: $allGranted")
        
        // After permissions granted, bind to service
        if (allGranted) {
            bindToMqttService()
        } else {
            Log.w(TAG, "Permissions denied!")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.d(TAG, "=== onCreate() called ===")

        preferencesManager = PreferencesManager(applicationContext)
        
        // Just request permissions - don't start service!
        requestPermissions()

        Log.d(TAG, "=== About to setContent ===")

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

    private fun requestPermissions() {
        Log.d(TAG, "=== requestPermissions() called ===")
        val permissions = mutableListOf<String>()

        // POST_NOTIFICATIONS only needed on API 33+ (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            Log.d(TAG, "Added POST_NOTIFICATIONS permission (API 33+)")
        } else {
            Log.d(TAG, "Skipping POST_NOTIFICATIONS (API ${Build.VERSION.SDK_INT} < 33)")
        }

        val permissionsToRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        Log.d(TAG, "Permissions to request: ${permissionsToRequest.size}")

        if (permissionsToRequest.isNotEmpty()) {
            Log.d(TAG, "Launching permission request")
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            Log.d(TAG, "All permissions granted, binding to service")
            bindToMqttService()
        }
    }
    
    /**
     * CRITICAL CHANGE: This now BINDS to the service instead of STARTING it
     * 
     * The service should already be running (started by BootReceiver on boot).
     * MainActivity just connects to it to observe state and display UI.
     * 
     * If service isn't running (e.g., first app launch or after force stop),
     * we start it first, then bind.
     */
    private fun bindToMqttService() {
        Log.d(TAG, "=== bindToMqttService() called ===")
        
        lifecycleScope.launch {
            val settings = preferencesManager.mqttSettings.first()

            Log.d(TAG, "Settings loaded: broker=${settings.brokerUrl}, topic=${settings.topic}")

            // Only proceed if configuration is valid
            if (settings.brokerUrl.isNotBlank() && settings.topic.isNotBlank()) {
                
                // Create intent for service
                val intent = Intent(this@MainActivity, MqttService::class.java)
                
                // Check if service is already running
                val isServiceRunning = isServiceRunning(MqttService::class.java)
                
                if (!isServiceRunning) {
                    Log.i(TAG, "Service not running - starting it first (e.g., first launch)")
                    
                    // Start service with settings
                    // This happens on first app launch or after force stop
                    // BootReceiver will handle it on subsequent boots
                    MqttService.start(
                        context = applicationContext,
                        brokerUrl = settings.brokerUrl,
                        brokerPort = settings.brokerPort,
                        username = settings.username.ifBlank { null },
                        password = settings.password.ifBlank { null },
                        topic = settings.topic
                    )
                    
                    Log.i(TAG, "Service started, now binding...")
                } else {
                    Log.i(TAG, "Service already running (started by BootReceiver) - just binding")
                }
                
                // Bind to the service (whether we just started it or it was already running)
                bindService(intent, connection, Context.BIND_AUTO_CREATE)
                
                Log.d(TAG, "Bind service called")
            } else {
                Log.w(TAG, "MQTT not configured - skipping service")
                viewModel.setMqttConnectionState(false)
            }
        }
    }
    
    /**
     * Check if a service is currently running
     */
    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        @Suppress("DEPRECATION")
        for (service in manager.getRunningServices(Int.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }
    
    /**
     * Request battery optimization exemption (optional but recommended)
     * This helps ensure the service keeps running in background
     */
    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            val packageName = packageName

            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                Log.i(TAG, "Requesting battery optimization exemption...")
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to request battery optimization exemption", e)
                }
            } else {
                Log.i(TAG, "Already exempt from battery optimization")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (bound) {
            unbindService(connection)
            bound = false
        }
        // IMPORTANT: Don't stop the service!
        // It should keep running in background for auto-detection
    }
}
