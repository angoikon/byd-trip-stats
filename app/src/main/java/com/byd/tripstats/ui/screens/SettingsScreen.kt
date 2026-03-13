package com.byd.tripstats.ui.screens

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.byd.tripstats.data.preferences.PreferencesManager
import com.byd.tripstats.R
import com.byd.tripstats.ui.theme.*
import com.byd.tripstats.ui.viewmodel.DashboardViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val DEBUG_CONNECTIONS = false  // Set to true for connection debugging during development

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel         : DashboardViewModel,
    onNavigateBack    : () -> Unit,
    onNavigateToBackup: () -> Unit
) {
    val context           = LocalContext.current
    val preferencesManager = remember { PreferencesManager(context) }
    val scope             = rememberCoroutineScope()

    // ── MQTT form ─────────────────────────────────────────────────────────────
    val savedSettings by preferencesManager.mqttSettings.collectAsState(
        initial = PreferencesManager.MqttSettings()
    )
    var brokerUrl  by remember { mutableStateOf("") }
    var brokerPort by remember { mutableStateOf("") }
    var username   by remember { mutableStateOf("") }
    var password   by remember { mutableStateOf("") }
    var topic      by remember { mutableStateOf("") }

    LaunchedEffect(savedSettings) {
        brokerUrl  = savedSettings.brokerUrl
        brokerPort = savedSettings.brokerPort.toString()
        username   = savedSettings.username
        password   = savedSettings.password
        topic      = savedSettings.topic
    }

    // ── Debug dialog ──────────────────────────────────────────────────────────
    var showDebugDialog    by remember { mutableStateOf(false) }
    var debugBrokerRunning by remember { mutableStateOf(false) }
    var debugClientRunning by remember { mutableStateOf(false) }

    LaunchedEffect(showDebugDialog) {
        while (showDebugDialog) {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            @Suppress("DEPRECATION")
            val services = am.getRunningServices(Int.MAX_VALUE)
            debugBrokerRunning = services.any { it.service.className.contains("MqttBrokerService") }
            debugClientRunning = services.any { it.service.className.contains("MqttService") }
            delay(2000)
        }
    }

    val mqttConnected     by viewModel.mqttConnected.collectAsState()
    val snackbarHostState  = remember { SnackbarHostState() }
    var selectedTab       by remember { mutableStateOf(0) }
    val tabs = listOf("Network", "Data", "About & FAQ")

    val telemetry by viewModel.currentTelemetry.collectAsState()
    val mqttConnectionError by viewModel.mqttConnectionError.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontSize = 24.sp, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", modifier = Modifier.size(28.dp))
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
        ) {
            TabRow(selectedTabIndex = selectedTab, modifier = Modifier.fillMaxWidth()) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick  = { selectedTab = index },
                        text = {
                            Text(
                                title,
                                fontSize   = 17.sp,
                                fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    )
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                when (selectedTab) {
                    0 -> MqttTab(
                        brokerUrl     = brokerUrl,    onBrokerUrl  = { brokerUrl = it },
                        brokerPort    = brokerPort,   onBrokerPort = { brokerPort = it },
                        topic         = topic,        onTopic      = { topic = it },
                        username      = username,     onUsername   = { username = it },
                        password      = password,     onPassword   = { password = it },
                        mqttConnected = mqttConnected,
                        telemetryReceived = telemetry != null,
                        mqttConnectionError = mqttConnectionError,
                        showDebug     = DEBUG_CONNECTIONS,
                        onShowDebug   = { showDebugDialog = true },
                        onSave = {
                            scope.launch {
                                preferencesManager.saveMqttSettings(
                                    brokerUrl  = brokerUrl,
                                    brokerPort = brokerPort.toIntOrNull() ?: 1883,
                                    username   = username,
                                    password   = password,
                                    topic      = topic
                                )
                                viewModel.restartMqttService(
                                    brokerUrl  = brokerUrl,
                                    brokerPort = brokerPort.toIntOrNull() ?: 1883,
                                    username   = username.ifBlank { null },
                                    password   = password.ifBlank { null },
                                    topic      = topic
                                )
                                snackbarHostState.showSnackbar(
                                    message  = "Settings saved and service restarted!",
                                    duration = SnackbarDuration.Short
                                )
                            }
                        },
                        onDisconnect = {
                            scope.launch {
                                viewModel.stopMqttService()
                                snackbarHostState.showSnackbar(
                                    message  = "MQTT service stopped",
                                    duration = SnackbarDuration.Short
                                )
                            }
                        }
                    )
                    1 -> DataManagementTab(
                        viewModel         = viewModel,
                        context           = context,
                        onNavigateToBackup = onNavigateToBackup,
                        scope             = scope
                    )
                    2 -> AboutTab()
                }
            }
        }

        if (showDebugDialog) {
            DebugDialog(
                brokerRunning = debugBrokerRunning,
                clientRunning = debugClientRunning,
                savedSettings = savedSettings,
                onDismiss     = { showDebugDialog = false }
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// Tab 1 — MQTT Connection
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun MqttTab(
    brokerUrl    : String, onBrokerUrl  : (String) -> Unit,
    brokerPort   : String, onBrokerPort : (String) -> Unit,
    topic        : String, onTopic      : (String) -> Unit,
    username     : String, onUsername   : (String) -> Unit,
    password     : String, onPassword   : (String) -> Unit,
    mqttConnected: Boolean,
    telemetryReceived   : Boolean,
    mqttConnectionError : String?,
    showDebug    : Boolean,
    onShowDebug  : () -> Unit,
    onSave       : () -> Unit,
    onDisconnect : () -> Unit
) {
    Column(
        modifier            = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        SectionHeader(icon = Icons.Filled.Hub, title = "MQTT Connection")

        // Unified broker + connection status card
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Broker card
            Card(
                modifier = Modifier.weight(1f),
                colors   = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(
                    modifier            = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        painter            = painterResource(R.drawable.ic_network_node),
                        contentDescription = null,
                        tint               = MaterialTheme.colorScheme.primary,
                        modifier           = Modifier.size(24.dp)
                    )
                    Text("Embedded MQTT Broker",
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Running internally on port 1883",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Running",
                        style      = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color      = MaterialTheme.colorScheme.primary)
                }
            }

            // Connection card
            Card(
                modifier = Modifier.weight(1f),
                colors   = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(
                    modifier            = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val usingInternalBroker = brokerUrl.trim().let {
                        it == "127.0.0.1" || it == "localhost" || it == "::1"
                    }

                    val statusIcon = when {
                        mqttConnectionError != null -> Icons.Filled.SyncProblem
                        telemetryReceived -> Icons.Filled.Sync
                        else -> Icons.Filled.SyncDisabled
                    }

                    val statusColor = when {
                        mqttConnectionError != null -> MaterialTheme.colorScheme.error
                        telemetryReceived -> RegenGreen
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }

                    val statusText = when {
                        mqttConnectionError != null -> "Connection error"
                        telemetryReceived -> "Receiving telemetry ✓"
                        mqttConnected -> "Connected, waiting for data"
                        else -> "Disconnected"
                    }

                    Icon(
                        imageVector = statusIcon,
                        contentDescription = null,
                        tint = statusColor,
                        modifier = Modifier.size(24.dp)
                    )

                    Text(
                        "Connection status",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        if (usingInternalBroker) "MQTT · Internal broker" else "MQTT · External broker",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Text(
                        statusText,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = statusColor
                    )
                }
            }
        }

        HorizontalDivider()
        Text("Broker Configuration",
            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

        OutlinedTextField(
            value = brokerUrl, onValueChange = onBrokerUrl,
            label = { Text("Broker URL") }, placeholder = { Text("127.0.0.1") },
            modifier = Modifier.fillMaxWidth(), singleLine = true
        )
        OutlinedTextField(
            value = brokerPort, onValueChange = onBrokerPort,
            label = { Text("Port") }, placeholder = { Text("1883") },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
        OutlinedTextField(
            value = topic, onValueChange = onTopic,
            label = { Text("Topic") },
            placeholder = { Text("electro/telemetry/byd-seal/data") },
            modifier = Modifier.fillMaxWidth(), singleLine = true
        )

        HorizontalDivider()
        Text("Authentication (Optional)",
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

        OutlinedTextField(
            value = username, onValueChange = onUsername,
            label = { Text("Username") }, placeholder = { Text("Optional") },
            modifier = Modifier.fillMaxWidth(), singleLine = true
        )
        OutlinedTextField(
            value = password, onValueChange = onPassword,
            label = { Text("Password") }, placeholder = { Text("Optional") },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
            visualTransformation = PasswordVisualTransformation()
        )

        // Info card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors   = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Filled.Info, null,
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                Text(
                    "In Electro app, set the publish interval to 1 second while the car is ON " +
                    "and any longer interval while OFF (5 min is fine).\n\n" +
                    "For the internal broker use 127.0.0.1 · port 1883 · no SSL · no credentials. " +
                    "Find the topic in Electro → Integrations → MQTT ",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        // Action buttons
        Button(
            onClick  = onSave,
            modifier = Modifier.fillMaxWidth(),
            colors   = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            Icon(Icons.Filled.Save, null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Save & Restart MQTT connection", fontSize = 16.sp)
        }

        OutlinedButton(
            onClick  = onDisconnect,
            modifier = Modifier.fillMaxWidth(),
            enabled  = mqttConnected,
            colors   = ButtonDefaults.outlinedButtonColors(
                contentColor         = MaterialTheme.colorScheme.error,
                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
            )
        ) {
            Icon(Icons.Filled.CloudOff, null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (mqttConnected) "Disconnect & Stop MQTT connection" else "Service Not Running",
                fontSize = 16.sp)
        }

        if (showDebug) {
            HorizontalDivider()
            OutlinedButton(
                onClick  = onShowDebug,
                modifier = Modifier.fillMaxWidth(),
                colors   = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(Icons.Filled.BugReport, null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Debug Connection Info", fontSize = 16.sp)
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// Tab 2 — Data Management
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun DataManagementTab(
    viewModel         : DashboardViewModel,
    context           : Context,
    onNavigateToBackup: () -> Unit,
    scope             : CoroutineScope
) {
    val lastBackup     = remember { viewModel.listDatabaseBackups().firstOrNull() }
    val lastBackupLabel = remember(lastBackup) {
        if (lastBackup == null) "No local backup found"
        else {
            val sdf = java.text.SimpleDateFormat("dd MMM yyyy, HH:mm", java.util.Locale.getDefault())
            "Last backup: ${sdf.format(java.util.Date(lastBackup.lastModified()))}"
        }
    }

    var showResetConfirm by remember { mutableStateOf(false) }

    Column(
        modifier            = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SectionHeader(icon = Icons.Filled.Backup, title = "Backup & Restore")

        // Two-column summary row
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            BackupSummaryCard(
                modifier   = Modifier.weight(1f),
                icon       = Icons.Filled.Storage,
                title      = "Local",
                body       = "Download/BydTripStats/",
                statusLine = lastBackupLabel,
                statusOk   = lastBackup != null
            )
            BackupSummaryCard(
                modifier   = Modifier.weight(1f),
                icon       = Icons.AutoMirrored.Filled.Send,
                title      = "Telegram",
                body       = "Private bot chat",
                statusLine = "Manual & scheduled",
                statusOk   = true
            )
        }

        Button(onClick = onNavigateToBackup, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Backup, null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Open Backup & Restore", fontSize = 16.sp)
        }
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            icon  = { Icon(Icons.Filled.Warning, null,
                tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(32.dp)) },
            title = { Text("Reset all trip data?", fontWeight = FontWeight.Bold) },
            text  = {
                Text(
                    "This will permanently delete all trips and statistics.\n\n" +
                    "A backup will be saved to Download automatically before the reset. " +
                    "The app will close and reopen.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showResetConfirm = false
                        scope.launch {
                            viewModel.resetDatabase()
                            val launchIntent = context.packageManager
                                .getLaunchIntentForPackage(context.packageName)
                                ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK) }
                            if (launchIntent != null) {
                                val pending = PendingIntent.getActivity(
                                    context, 1, launchIntent,
                                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                                )
                                val alarm = context.getSystemService(AlarmManager::class.java)
                                alarm.set(AlarmManager.RTC, System.currentTimeMillis() + 800L, pending)
                            }
                            android.os.Process.killProcess(android.os.Process.myPid())
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BydErrorRed)
                ) { Text("Yes, reset everything") }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun BackupSummaryCard(
    modifier  : Modifier,
    icon      : ImageVector,
    title     : String,
    body      : String,
    statusLine: String,
    statusOk  : Boolean
) {
    Card(
        modifier = modifier,
        colors   = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(
            modifier            = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(body, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                statusLine,
                style      = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color      = if (statusOk) MaterialTheme.colorScheme.primary
                             else MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// Tab 3 — About & FAQ
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun AboutTab() {
    Column(
        modifier            = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SectionHeader(icon = Icons.Filled.Info, title = "About")

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors   = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                SettingsDetailRow("App",         "BYD Trip Stats")
                SettingsDetailRow("Version",     "1.1.0")
                SettingsDetailRow("Changelog", "What's new", url = "https://github.com/angoikon/byd-trip-stats/blob/main/CHANGELOG.md")
                SettingsDetailRow("Author",      "Angelos Oikonomou (angoikon)")
                SettingsDetailRow("Platform",    "Android 10 · API 29")
                SettingsDetailRow("License",     "BUSL 1.1")
                SettingsDetailRow("Link",      "github.com/angoikon/byd-trip-stats", url = "https://github.com/angoikon/byd-trip-stats")
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors   = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Filled.Shield, null, tint = RegenGreen, modifier = Modifier.size(22.dp))
                Text(
                    "All data stays on your device — no analytics, no ads, no tracking. " +
                    "BYD Trip Stats is a companion to Electro by Rory; it does not replace it.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        HorizontalDivider()
        SectionHeader(icon = Icons.AutoMirrored.Filled.Help, title = "FAQ")

        buildFaqList().forEach { (q, a, u) -> FaqItem(question = q, answer = a, url = u) }

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun FaqItem(question: String, answer: String, url: String? = null) {
    val context  = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .border(
                1.dp,
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f),
                MaterialTheme.shapes.medium
            ),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Text(
                    question,
                    style      = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier   = Modifier.weight(1f).padding(end = 8.dp)
                )
                Icon(
                    if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            AnimatedVisibility(visible = expanded, enter = expandVertically(), exit = shrinkVertically()) {
                Column {
                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(10.dp))
                    Text(answer, style = MaterialTheme.typography.bodyMedium)
                    if (url != null) {
                        Spacer(Modifier.height(10.dp))
                        Row(
                            modifier = Modifier
                                .clickable {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                    )
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector        = Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = "Open link",
                                tint               = MaterialTheme.colorScheme.primary,
                                modifier           = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                url.removePrefix("https://").removePrefix("http://"),
                                style      = MaterialTheme.typography.bodySmall,
                                color      = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
    }
}

private data class FaqEntry(val question: String, val answer: String, val url: String? = null)

private fun buildFaqList(): List<FaqEntry> = listOf(

    FaqEntry(
        "Do I need the Electro app?",
        "Yes — Electro is the bridge between your BYD and this app. It acquires telemetry " +
        "data and publishes it over MQTT. BYD Trip Stats subscribes to that stream. Without " +
        "Electro running and publishing, no telemetry arrives and no trips are recorded.\n\n" +
        "Electro requires an active subscription (~€30/year)."
    ),

    FaqEntry(
        "What should my Electro publish interval be?",
        "Set the interval to 1 second while the car is ON — this gives smooth " +
        "charts and accurate statistics - you don't need faster intervals. " +
        "While the car is OFF, any longer interval is fine; " +
        "5 minutes is a sensible default and reduces unnecessary load."
    ),

    FaqEntry(
        "Why is the MQTT connection failing?",
        "Work through this checklist:\n\n" +
        "1. Electro is open and showing as connected\n" +
        "2. Broker URL has no http:// or https:// prefix — bare hostname or IP only\n" +
        "3. Port 1883 for plain connections, 8883 for TLS/SSL\n" +
        "4. For the internal broker: URL = 127.0.0.1, Port = 1883, no username/password\n" +
        "5. The topic matches Electro exactly — " +
        "find your model's topic in Electro → Integrations → MQTT)\n" +
        "6. Tap Save & Restart after every change"
    ),

    FaqEntry(
        "Trips are not being detected automatically",
        "Auto-detection watches the gear position in the MQTT stream:\n\n" +
        "• Trip starts when gear changes to D or R\n" +
        "• Trip ends when gear returns to P\n\n" +
        "If trips are not starting, confirm the dashboard is receiving live values from MQTT. " +
        "Also verify that auto-detection is enabled. A manual start/stop override is available " +
        "on the dashboard for edge cases."
    ),

    FaqEntry(
        "The app stops working after a car restart",
        "You need to exclude BYD Trip Stats from the autostart-killer:\n\n" +
        "1. Open the Disable Autostart app (native BYD app, usually near the file explorer)\n" +
        "2. Find BYD Trip Stats and toggle its entry OFF — same as you have done for Electro\n" +
        "3. Hold the volume button for ~10 seconds to restart the car UI\n" +
        "4. Reopen the app\n\n" +
        "Important: you must repeat this step after every app update, because the permission " +
        "resets on reinstall."
    ),

    FaqEntry(
        "How do I back up my trip data?",
        "Three methods are available — all produce the same standard SQLite file:\n\n" +
        "Local (simplest — no setup): Settings → Data → Open Backup & Restore → Backup Now. " +
        "The file lands in Download/BydTripStats/ on the car's internal storage.\n\n" +
        "Telegram (recommended): Connect a bot once via Backup & Restore → Telegram Backup, " +
        "then send manually or enable scheduled backups (daily / weekly / monthly). " +
        "Backups appear in your private Telegram chat and can be restored directly from any device.\n\n" +
        "ADB (technical): Every in-app backup also writes a copy to the app's private directory. " +
        "Pull it wirelessly with:\n" +
        "adb shell run-as com.byd.tripstats cat files/db_backup/FILENAME.db > local.db\n\n" +
        "The private directory keeps the 5 most recent backups and prunes older ones automatically."
    ),

    FaqEntry(
        "I reinstalled the app — can I recover my backups?",
        "Yes, for both backup methods:\n\n" +
        "Telegram: After reinstalling, reconnect your bot token in Backup & Restore → " +
        "Telegram Backup. Then open Restore from Telegram and tap the refresh icon. " +
        "The app reads Download/BydTripStats/telegram_registry.json — which survives " +
        "uninstalls — and rediscovers all previous backups automatically.\n\n" +
        "Local: The .db files in Download/BydTripStats/ survive a reinstall. Open " +
        "Backup & Restore, tap the refresh icon under Local Restore, and they reappear " +
        "in the list. The app now reads that folder directly from disk for exactly this case.\n\n" +
        "As long as Download/BydTripStats/ has not been manually deleted, no data is lost."
    ),

    FaqEntry(
        "How does Telegram automatic backup work?",
        "Once your bot is connected, enable the Auto-backup toggle and pick an interval: " +
        "Daily, Weekly, or Monthly.\n\n" +
        "Key behaviours:\n" +
        "• The first automatic backup runs after one full interval has elapsed — enabling " +
        "the toggle does not send immediately\n" +
        "• Changing the interval reschedules from that moment forward\n" +
        "• If the car is off when a backup is due, it fires automatically on the next boot\n" +
        "• Failed attempts are retried with exponential backoff before giving up until the next window\n\n" +
        "The 'Last auto-backup' timestamp in the Telegram section confirms when the most recent run completed.\n\n" +
        "To stop: tap Disconnect bot. This cancels the schedule and clears saved credentials. " +
        "Your previous backups in Telegram and the registry file in Download are unaffected."
    ),

    FaqEntry(
        "How do I restore a backup pushed from my PC via ADB?",
        "1. Push the file to the car's Download folder:\n" +
        "   adb push backup.db /sdcard/Download/BydTripStats/backup.db\n\n" +
        "2. Open the app → Backup & Restore → Local Restore\n" +
        "3. Tap the refresh icon — the pushed file appears in the list\n" +
        "4. Tap Restore and confirm\n\n" +
        "The app validates the file as a genuine SQLite database before touching any live data. " +
        "It then restores and restarts automatically."
    ),

    FaqEntry(
        "Will this drain my 12V battery?",
        "No meaningful impact. In an EV the 12V battery is continuously trickle-charged from " +
        "the high-voltage pack. The MQTT client is highly efficient — it sits idle between " +
        "messages and wakes only when telemetry arrives."
    ),

    FaqEntry(
        "Does this work on BYD Dolphin or Atto 3?",
        "Potentially yes — any model that publishes telemetry through Electro should work. " +
        "The app has been tested on the Seal. Users of other models may need to adjust the " +
        "MQTT topic in Settings to match their car. Check Electro → Integrations → MQTT for " +
        "the correct topic string."
    ),

    FaqEntry(
        "Can I export data to Excel?",
        "Yes. Open any trip, tap the share icon (top-right), then Save as CSV. The file lands " +
        "in the Download folder and can be opened directly in Excel, Google Sheets, or any " +
        "data tool.\n\nA JSON export is also available from the same menu. Both formats include " +
        "every recorded data point: timestamp, GPS, speed, power, SOC, altitude, battery " +
        "temperature, gear, and motor RPM (front and rear)."
    ),

    FaqEntry(
        "Is my data private?",
        "Completely. BYD Trip Stats collects zero information about you.\n\n" +
        "Everything stays on your device: trip data, GPS coordinates, MQTT credentials, settings. " +
        "The only external network traffic is to your own MQTT broker (127.0.0.1 for the " +
        "internal broker) or whichever external broker you configured in Settings. The " +
        "telegram private bot (if you decided to used it) is yours"
    ),

    FaqEntry(
        "Where can I get help or report a bug?",
        "Check the README.md file or open an issue on GitHub — tap the link below.\n\n" +
        "Include your BYD model, the steps to reproduce the problem, and logcat output " +
        "if available. Feature requests are also welcome — use the 'enhancement' label.",
        url = "https://github.com/angoikon/byd-trip-stats/issues"
    )
)

// ═══════════════════════════════════════════════════════════════════════════════
// Debug dialog
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun DebugDialog(
    brokerRunning: Boolean,
    clientRunning: Boolean,
    savedSettings: PreferencesManager.MqttSettings,
    onDismiss    : () -> Unit
) {
    val isLocal = savedSettings.brokerUrl.trim().let {
        it == "127.0.0.1" || it == "localhost" || it == "::1"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.BugReport, null, tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(8.dp))
                Text("Debug Info")
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Services:", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Embedded Broker:")
                            Text(if (brokerRunning) "✅ RUNNING" else "❌ STOPPED",
                                color = if (brokerRunning) Color(0xFF00FF88) else BydErrorRed,
                                fontWeight = FontWeight.Bold)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("MQTT Client:")
                            Text(if (clientRunning) "✅ RUNNING" else "❌ STOPPED",
                                color = if (clientRunning) Color(0xFF00FF88) else BydErrorRed,
                                fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Settings:", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Spacer(Modifier.height(8.dp))
                        Text("Broker: ${savedSettings.brokerUrl}", fontSize = 14.sp)
                        Text("Port: ${savedSettings.brokerPort}", fontSize = 14.sp)
                        Text("Topic: ${savedSettings.topic}", fontSize = 14.sp)
                    }
                }
                Card(colors = CardDefaults.cardColors(
                    containerColor = if (isLocal) Color(0xFF00FF88).copy(alpha = 0.2f)
                                     else BydElectricBlue.copy(alpha = 0.2f)
                )) {
                    Box(Modifier.padding(12.dp)) {
                        Text(if (isLocal) "Mode: LOCAL BROKER" else "Mode: EXTERNAL BROKER",
                            fontWeight = FontWeight.Bold, fontSize = 16.sp,
                            color = if (isLocal) Color(0xFF00AA00) else Color(0xFF0099CC))
                    }
                }
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("What To Do:", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Spacer(Modifier.height(8.dp))
                        if (isLocal && !brokerRunning) {
                            Text("❌ Embedded broker not running!", color = BydErrorRed)
                            Text("→ Restart app", fontWeight = FontWeight.Bold)
                        } else if (isLocal && brokerRunning) {
                            Text("✅ Embedded broker OK!", color = RegenGreen)
                        }
                        if (!clientRunning) {
                            Text("❌ MQTT client not running!", color = BydErrorRed)
                            Text("→ Tap Save & Restart above", fontWeight = FontWeight.Bold)
                        } else {
                            Text("✅ MQTT client OK!", color = RegenGreen)
                        }
                        if (isLocal && brokerRunning && clientRunning) {
                            Spacer(Modifier.height(4.dp))
                            Text("🎉 Both services running!", color = Color(0xFF00AA00), fontWeight = FontWeight.Bold)
                            Text("If still no data → check Electro is publishing to 127.0.0.1:1883")
                        }
                    }
                }
                Text("Auto-refreshing every 2s…", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

// ═══════════════════════════════════════════════════════════════════════════════
// Shared private helpers
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun SectionHeader(
    icon : ImageVector,
    title: String,
    color: Color = MaterialTheme.colorScheme.primary
) {
    Row(
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier              = Modifier.padding(start = 4.dp, bottom = 2.dp)
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
        Text(title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = color)
    }
}

@Composable
private fun SettingsDetailRow(label: String, value: String, url: String? = null) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .then(
                if (url != null) Modifier.clickable {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } else Modifier
            ),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                value,
                style      = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color      = if (url != null) MaterialTheme.colorScheme.primary
                             else MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (url != null) {
                Spacer(Modifier.width(4.dp))
                Icon(
                    imageVector        = Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = "Open link",
                    tint               = MaterialTheme.colorScheme.primary,
                    modifier           = Modifier.size(14.dp)
                )
            }
        }
    }
}