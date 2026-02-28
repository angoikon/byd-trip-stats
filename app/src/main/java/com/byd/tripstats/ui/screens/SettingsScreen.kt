package com.byd.tripstats.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.byd.tripstats.data.preferences.PreferencesManager
import com.byd.tripstats.ui.viewmodel.DashboardViewModel
import kotlinx.coroutines.launch
import android.app.ActivityManager
import android.content.Context
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.BugReport
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: DashboardViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToBackup: () -> Unit
) {
    val context = LocalContext.current
    val preferencesManager = remember { PreferencesManager(context) }
    val scope = rememberCoroutineScope()
    
    // Load saved settings
    val savedSettings by preferencesManager.mqttSettings.collectAsState(
        initial = PreferencesManager.MqttSettings()
    )
    
    var brokerUrl by remember { mutableStateOf("") }
    var brokerPort by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var topic by remember { mutableStateOf("") }
    
    // DEBUG STATE - Add near other state variables like brokerUrl, etc.
    var showDebugDialog by remember { mutableStateOf(false) }
    var debugBrokerRunning by remember { mutableStateOf(false) }
    var debugClientRunning by remember { mutableStateOf(false) }

    // Auto-refresh when dialog open
    LaunchedEffect(showDebugDialog) {
        while (showDebugDialog) {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            
            @Suppress("DEPRECATION")
            val services = manager.getRunningServices(Int.MAX_VALUE)
            
            debugBrokerRunning = services.any { it.service.className.contains("MqttBrokerService") }
            debugClientRunning = services.any { it.service.className.contains("MqttService") }
            
            delay(2000) // Refresh every 2 seconds
        }
    }

    // Update form fields when settings load
    LaunchedEffect(savedSettings) {
        brokerUrl = savedSettings.brokerUrl
        brokerPort = savedSettings.brokerPort.toString()
        username = savedSettings.username
        password = savedSettings.password
        topic = savedSettings.topic
    }
    
    val mqttConnected by viewModel.mqttConnected.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontSize = 24.sp, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Filled.ArrowBack, "Back", modifier = Modifier.size(28.dp))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Embedded MQTT Broker",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Running on port 1883",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // Connection Status Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (mqttConnected) 
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "MQTT Connection",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (mqttConnected) "Connected" else "Disconnected",
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (mqttConnected) 
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            
            HorizontalDivider()
            
            Text(
                text = "MQTT Broker Configuration",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            
            OutlinedTextField(
                value = brokerUrl,
                onValueChange = { brokerUrl = it },
                label = { Text("Broker URL") },
                placeholder = { Text("broker.hivemq.com") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            
            OutlinedTextField(
                value = brokerPort,
                onValueChange = { brokerPort = it },
                label = { Text("Port") },
                placeholder = { Text("1883") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
            
            OutlinedTextField(
                value = topic,
                onValueChange = { topic = it },
                label = { Text("Topic") },
                placeholder = { Text("electro/telemetry/byd-seal/data") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            HorizontalDivider()
            
            Text(
                text = "Authentication (Optional)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("Username") },
                placeholder = { Text("Optional") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                placeholder = { Text("Optional") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation()
            )
            
            // Information card about Electro MQTT settings
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Info,
                        contentDescription = "Info",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        text = "Please make sure that in Electro you have set the interval to 1s (up to a maximum of 10s) when the car is ON and whatever interval you want for when the car is OFF. You must use the same URL / port / user / password for both cases.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            // Action Buttons
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Save & Restart Button
                Button(
                    onClick = {
                        scope.launch {
                            // Save settings to DataStore
                            preferencesManager.saveMqttSettings(
                                brokerUrl = brokerUrl,
                                brokerPort = brokerPort.toIntOrNull() ?: 1883,
                                username = username,
                                password = password,
                                topic = topic
                            )

                            // Restart service with new config
                            viewModel.restartMqttService(
                                brokerUrl = brokerUrl,
                                brokerPort = brokerPort.toIntOrNull() ?: 1883,
                                username = username.ifBlank { null },
                                password = password.ifBlank { null },
                                topic = topic
                            )

                            // Show confirmation
                            snackbarHostState.showSnackbar(
                                message = "Settings saved and service restarted!",
                                duration = SnackbarDuration.Short
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text("Save & Restart MQTT connection", fontSize = 16.sp)
                }

                // Disconnect & Stop Service Button
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            viewModel.stopMqttService()
                            snackbarHostState.showSnackbar(
                                message = "MQTT service stopped",
                                duration = SnackbarDuration.Short
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = mqttConnected,  // Only enable when connected
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                    )
                ) {
                    Icon(
                        imageVector = Icons.Filled.CloudOff,
                        contentDescription = "Disconnect",
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (mqttConnected) "Disconnect & Stop MQTT connection" else "Service Not Running",
                        fontSize = 16.sp
                    )
                }
            }

            HorizontalDivider()
            
            // DEBUG BUTTON - Add this with your other buttons
            OutlinedButton(
                onClick = { showDebugDialog = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Icon(Icons.Filled.BugReport, null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("🔍 Debug Connection Info", fontSize = 16.sp)
            }

            HorizontalDivider()

            Text(
                text = "Data Management",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Database Backup & Restore",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Save the full trip database to local storage, or restore from a previous backup.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Button(
                onClick = onNavigateToBackup,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    Icons.Filled.Backup,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text("Backup & Restore", fontSize = 16.sp)
            }

            HorizontalDivider()

            Text(
                text = "About",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    SettingsDetailRow("App Name", "BYD Trip Stats")
                    SettingsDetailRow("Version", "1.0.0")
                    SettingsDetailRow("Created by", "Angelos Oikonomou / angoikon")
                    SettingsDetailRow("Build", "Release")
                }
            }
            // DEBUG DIALOG - Add right before closing brace of Scaffold
            if (showDebugDialog) {
                AlertDialog(
                    onDismissRequest = { showDebugDialog = false },
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.BugReport, null, tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(8.dp))
                            Text("Debug Info")
                        }
                    },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            // Services
                            Card(colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )) {
                                Column(Modifier.padding(12.dp)) {
                                    Text("Services:", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                    Spacer(Modifier.height(8.dp))
                                    
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text("Embedded Broker:")
                                        Text(
                                            if (debugBrokerRunning) "✅ RUNNING" else "❌ STOPPED",
                                            color = if (debugBrokerRunning) Color(0xFF00FF88) else Color.Red,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text("MQTT Client:")
                                        Text(
                                            if (debugClientRunning) "✅ RUNNING" else "❌ STOPPED",
                                            color = if (debugClientRunning) Color(0xFF00FF88) else Color.Red,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                            
                            // Settings
                            Card(colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )) {
                                Column(Modifier.padding(12.dp)) {
                                    Text("Settings:", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                    Spacer(Modifier.height(8.dp))
                                    Text("Broker: ${savedSettings.brokerUrl}", fontSize = 14.sp)
                                    Text("Port: ${savedSettings.brokerPort}", fontSize = 14.sp)
                                    Text("Topic: ${savedSettings.topic}", fontSize = 14.sp)
                                }
                            }
                            
                            // Mode & Diagnosis
                            val isLocal = savedSettings.brokerUrl.trim().let {
                                it == "127.0.0.1" || it == "localhost" || it == "::1"
                            }
                            
                            Card(colors = CardDefaults.cardColors(
                                containerColor = if (isLocal) 
                                    Color(0xFF00FF88).copy(alpha = 0.2f) 
                                else 
                                    Color(0xFF00D4FF).copy(alpha = 0.2f)
                            )) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(
                                        text = if (isLocal) "Mode: LOCAL BROKER" else "Mode: EXTERNAL BROKER",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp,
                                        color = if (isLocal) Color(0xFF00AA00) else Color(0xFF0099CC)
                                    )
                                }
                            }
                            
                            // Diagnosis
                            Card(colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            )) {
                                Column(Modifier.padding(12.dp)) {
                                    Text("What To Do:", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                    Spacer(Modifier.height(8.dp))
                                    
                                    if (isLocal && !debugBrokerRunning) {
                                        Text("❌ Embedded broker not running!", color = Color.Red)
                                        Text("→ Check AndroidManifest.xml", fontWeight = FontWeight.Bold)
                                        Text("→ Then RESTART APP", fontWeight = FontWeight.Bold)
                                    } else if (isLocal && debugBrokerRunning) {
                                        Text("✅ Embedded broker OK!", color = Color(0xFF00AA00))
                                    }
                                    
                                    if (!debugClientRunning) {
                                        Text("❌ MQTT client not running!", color = Color.Red)
                                        Text("→ Click 'Save & Restart' button", fontWeight = FontWeight.Bold)
                                    } else {
                                        Text("✅ MQTT client OK!", color = Color(0xFF00AA00))
                                    }
                                    
                                    if (isLocal && debugBrokerRunning && debugClientRunning) {
                                        Text("🎉 Both services running!", color = Color(0xFF00AA00), fontWeight = FontWeight.Bold)
                                        Text("If still no data:")
                                        Text("→ Check Electro is publishing")
                                        Text("→ Electro broker: 127.0.0.1:1883")
                                    }
                                }
                            }
                            
                            Text(
                                "Auto-refreshing every 2s...",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showDebugDialog = false }) {
                            Text("Close")
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun SettingsDetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium
        )
    }
}