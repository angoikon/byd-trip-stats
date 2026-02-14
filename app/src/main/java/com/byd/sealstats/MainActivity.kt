package com.byd.sealstats

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
import com.byd.sealstats.data.preferences.PreferencesManager
import com.byd.sealstats.service.MqttService
import com.byd.sealstats.ui.navigation.AppNavigation
import com.byd.sealstats.ui.theme.BydSealStatsTheme
import com.byd.sealstats.ui.viewmodel.DashboardViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    
    private val viewModel: DashboardViewModel by viewModels()
    private var mqttService: MqttService? = null
    private var bound = false
    private lateinit var preferencesManager: PreferencesManager
    
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as MqttService.LocalBinder
            mqttService = binder.getService()
            bound = true
        }
        
        override fun onServiceDisconnected(arg0: ComponentName) {
            bound = false
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        Log.d("MainActivity", "=== Permission result received ===")
        val allGranted = permissions.entries.all { it.value }
        Log.d("MainActivity", "All granted: $allGranted")
        if (allGranted) {
            startMqttService()
        } else {
            Log.d("MainActivity", "Permissions denied!")
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.d("MainActivity", "=== onCreate() called ===")

        preferencesManager = PreferencesManager(applicationContext)
        requestPermissions()

        Log.d("MainActivity", "=== About to setContent ===")

        setContent {
            BydSealStatsTheme {
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
        Log.d("MainActivity", "=== requestPermissions() called ===")
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            Log.d("MainActivity", "Added POST_NOTIFICATIONS permission")
        }

        val permissionsToRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        Log.d("MainActivity", "Permissions to request: ${permissionsToRequest.size}")

        if (permissionsToRequest.isNotEmpty()) {
            Log.d("MainActivity", "Launching permission request")
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            Log.d("MainActivity", "All permissions granted, starting service")
            startMqttService()
        }
    }
    
    private fun startMqttService() {
        Log.d("MainActivity", "=== startMqttService() called ===")
        // Load saved MQTT settings from DataStore
        lifecycleScope.launch {
            val settings = preferencesManager.mqttSettings.first()

            Log.d("MainActivity", "Settings loaded: broker=${settings.brokerUrl}, topic=${settings.topic}")

            viewModel.startMqttService(
                brokerUrl = settings.brokerUrl,
                brokerPort = settings.brokerPort,
                username = settings.username.ifBlank { null },
                password = settings.password.ifBlank { null },
                topic = settings.topic
            )

            Log.d("MainActivity", "Service start called, now binding...")

            Intent(this@MainActivity, MqttService::class.java).also { intent ->
                bindService(intent, connection, Context.BIND_AUTO_CREATE)
            }

            Log.d("MainActivity", "Bind service called")
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        if (bound) {
            unbindService(connection)
            bound = false
        }
    }
}
