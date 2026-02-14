package com.byd.sealstats.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.byd.sealstats.data.preferences.PreferencesManager
import com.byd.sealstats.ui.viewmodel.DashboardViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: DashboardViewModel,
    onNavigateBack: () -> Unit
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
    
    // Update form fields when settings load
    LaunchedEffect(savedSettings) {
        brokerUrl = savedSettings.brokerUrl
        brokerPort = savedSettings.brokerPort.toString()
        username = savedSettings.username
        password = savedSettings.password
        topic = savedSettings.topic
    }
    
    val mqttConnected by viewModel.mqttConnected.collectAsState()
    var showSaveSnackbar by remember { mutableStateOf(false) }
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
            
            Divider()
            
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
            
            Divider()
            
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
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
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
                            
                            // Restart MQTT service with new settings
                            viewModel.stopMqttService()
                            viewModel.startMqttService(
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
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Save & Restart Service", fontSize = 16.sp)
                }
            }
            
            Divider()
            
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
                    SettingsDetailRow("App Name", "BYD Info Stats")
                    SettingsDetailRow("Version", "1.0.0")
                    SettingsDetailRow("Build", "Debug")
                }
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
