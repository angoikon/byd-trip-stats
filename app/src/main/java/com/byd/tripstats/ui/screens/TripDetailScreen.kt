package com.byd.tripstats.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.byd.tripstats.ui.components.AltitudeChart
import com.byd.tripstats.ui.components.CondensedAltitudeChart
import com.byd.tripstats.ui.components.CondensedEnergyChart
import com.byd.tripstats.ui.components.CondensedMotorRpmChart
import com.byd.tripstats.ui.components.CondensedSocChart
import com.byd.tripstats.ui.components.CondensedPowerChart
import com.byd.tripstats.ui.components.CondensedSpeedChart
import com.byd.tripstats.ui.components.EnergyConsumptionChart
import com.byd.tripstats.ui.components.MotorRpmChart
import com.byd.tripstats.ui.components.OsmRouteMap
import com.byd.tripstats.ui.components.PowerDistributionChart
import com.byd.tripstats.ui.components.PowerChart
import com.byd.tripstats.ui.components.RouteAnalysisTab
import com.byd.tripstats.ui.components.SocChart
import com.byd.tripstats.ui.components.SpeedChart
import com.byd.tripstats.ui.components.SpeedDistributionChart
import com.byd.tripstats.ui.theme.*
import com.byd.tripstats.ui.viewmodel.DashboardViewModel
import kotlin.math.abs
import com.byd.tripstats.ui.components.condenseData

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripDetailScreen(
    tripId: Long,
    viewModel: DashboardViewModel,
    onNavigateBack: () -> Unit
) {
    val trip by viewModel.getTripDetails(tripId).collectAsState()
    val dataPoints by viewModel.getTripDataPoints(tripId).collectAsState()
    val stats by viewModel.getTripStats(tripId).collectAsState()
    
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("Overview", "Charts", "Route", "Analysis")
    
    var showExportDialog by remember { mutableStateOf(false) }
    
    // Only capture dataPoints when dialog is requested to open
    var exportSnapshot by remember { mutableStateOf<Pair<com.byd.tripstats.data.local.entity.TripEntity?, List<com.byd.tripstats.data.local.entity.TripDataPointEntity>>?>(null) }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Trip Details", fontSize = 24.sp, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Filled.ArrowBack, "Back", modifier = Modifier.size(28.dp))
                    }
                },
                actions = {
                    // Export/Share button
                    IconButton(onClick = { 
                        exportSnapshot = trip to dataPoints.toList()  // Capture immutable snapshot
                        showExportDialog = true 
                    }) {
                        Icon(
                            Icons.Filled.Share,
                            contentDescription = "Export trip data",
                            modifier = Modifier.size(24.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { paddingValues ->
        if (trip == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.size(60.dp))
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                // Tab selector
                TabRow(
                    selectedTabIndex = selectedTab,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = {
                                Text(
                                    text = title,
                                    fontSize = 18.sp,
                                    fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        )
                    }
                }
                
                // Tab content - constrained to remaining space
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    when (selectedTab) {
                        0 -> TripOverviewTab(trip = trip!!, stats = stats)
                        1 -> TripChartsTab(dataPoints = dataPoints, stats = stats)
                        2 -> TripRouteTab(dataPoints = dataPoints)
                        3 -> RouteAnalysisTab(dataPoints = dataPoints)
                    }
                }
            }
        }
    }
    
    // Export Dialog - isolated from parent recomposition
    exportSnapshot?.let { (snapshotTrip, snapshotData) ->
        if (showExportDialog && snapshotTrip != null) {
            ExportDialogHandler(
                trip = snapshotTrip,
                dataPoints = snapshotData,
                onDismiss = { 
                    showExportDialog = false
                    exportSnapshot = null
                }
            )
        }
    }
}

@Composable
private fun ExportDialogHandler(
    trip: com.byd.tripstats.data.local.entity.TripEntity,
    dataPoints: List<com.byd.tripstats.data.local.entity.TripDataPointEntity>,
    onDismiss: () -> Unit
) {
    // Completely isolated - won't recompose with parent
    ExportDialog(
        trip = trip,
        dataPoints = dataPoints,
        onDismiss = onDismiss
    )
}

@Composable
fun ExportDialog(
    trip: com.byd.tripstats.data.local.entity.TripEntity,
    dataPoints: List<com.byd.tripstats.data.local.entity.TripDataPointEntity>,
    onDismiss: () -> Unit
) {
    // Capture stable references
    val context = androidx.compose.ui.platform.LocalContext.current
    val stableTrip = remember { trip }
    val stableDataPoints = remember { dataPoints.toList() }  // Create immutable copy
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export Trip Data", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Choose export format:", style = MaterialTheme.typography.bodyLarge)
                
                // Export as CSV
                OutlinedButton(
                    onClick = {
                        exportTripAsCSV(context, stableTrip, stableDataPoints)
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.TableChart, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Export as CSV")
                }
                
                // Export as JSON
                OutlinedButton(
                    onClick = {
                        exportTripAsJSON(context, stableTrip, stableDataPoints)
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.DataObject, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Export as JSON")
                }
                
                // Save summary as text
                OutlinedButton(
                    onClick = {
                        saveTripSummaryAsText(context, stableTrip)
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.Description, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Save Summary as Text")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

fun exportTripAsCSV(
    context: android.content.Context,
    trip: com.byd.tripstats.data.local.entity.TripEntity,
    dataPoints: List<com.byd.tripstats.data.local.entity.TripDataPointEntity>
) {
    try {
        val fileName = "trip_${trip.id}_${System.currentTimeMillis()}.csv"
        val csvContent = buildString {
            // Header
            appendLine("timestamp,latitude,longitude,altitude,speed,power,soc,odometer,batteryTemp,gear")
            // Data
            dataPoints.forEach { point ->
                appendLine("${point.timestamp},${point.latitude},${point.longitude},${point.altitude},${point.speed},${point.power},${point.soc},${point.odometer},${point.batteryTemp},${point.gear}")
            }
        }
        
        // Save to Downloads folder
        val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOWNLOADS
        )
        val file = java.io.File(downloadsDir, fileName)
        file.writeText(csvContent)
        
        android.widget.Toast.makeText(
            context, 
            "Saved to Downloads/$fileName", 
            android.widget.Toast.LENGTH_LONG
        ).show()
        
    } catch (e: Exception) {
        android.widget.Toast.makeText(
            context, 
            "Export failed: ${e.message}", 
            android.widget.Toast.LENGTH_LONG
        ).show()
    }
}

fun exportTripAsJSON(
    context: android.content.Context,
    trip: com.byd.tripstats.data.local.entity.TripEntity,
    dataPoints: List<com.byd.tripstats.data.local.entity.TripDataPointEntity>
) {
    try {
        val fileName = "trip_${trip.id}_${System.currentTimeMillis()}.json"
        val jsonContent = buildString {
            appendLine("{")
            appendLine("  \"tripId\": ${trip.id},")
            appendLine("  \"startTime\": ${trip.startTime},")
            appendLine("  \"endTime\": ${trip.endTime},")
            appendLine("  \"distance\": ${trip.distance},")
            appendLine("  \"duration\": ${trip.duration},")
            appendLine("  \"consumption\": ${trip.efficiency},")
            appendLine("  \"energyConsumed\": ${trip.energyConsumed},")
            appendLine("  \"dataPoints\": [")
            dataPoints.forEachIndexed { index, point ->
                appendLine("    {")
                appendLine("      \"timestamp\": ${point.timestamp},")
                appendLine("      \"latitude\": ${point.latitude},")
                appendLine("      \"longitude\": ${point.longitude},")
                appendLine("      \"altitude\": ${point.altitude},")
                appendLine("      \"speed\": ${point.speed},")
                appendLine("      \"power\": ${point.power},")
                appendLine("      \"soc\": ${point.soc}")
                appendLine("    }${if (index < dataPoints.size - 1) "," else ""}")
            }
            appendLine("  ]")
            appendLine("}")
        }
        
        // Save to Downloads folder
        val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOWNLOADS
        )
        val file = java.io.File(downloadsDir, fileName)
        file.writeText(jsonContent)
        
        android.widget.Toast.makeText(
            context, 
            "Saved to Downloads/$fileName", 
            android.widget.Toast.LENGTH_LONG
        ).show()
        
    } catch (e: Exception) {
        android.widget.Toast.makeText(
            context, 
            "Export failed: ${e.message}", 
            android.widget.Toast.LENGTH_LONG
        ).show()
    }
}

fun saveTripSummaryAsText(
    context: android.content.Context,
    trip: com.byd.tripstats.data.local.entity.TripEntity
) {
    try {
        val fileName = "trip_summary_${trip.id}_${System.currentTimeMillis()}.txt"
        val summary = buildString {
            appendLine("🚗 BYD Trip Stats")
            appendLine("")
            appendLine("📅 Date: ${formatTimestamp(trip.startTime)}")
            appendLine("🛣️ Distance: ${String.format("%.1f", trip.distance ?: 0.0)} km")
            appendLine("⏱️ Duration: ${formatDuration(trip.duration ?: 0)}")
            appendLine("⚡ Energy: ${String.format("%.2f", trip.energyConsumed ?: 0.0)} kWh")
            appendLine("🌿 Consumption: ${String.format("%.1f", trip.efficiency ?: 0.0)} kWh/100km")
            appendLine("🔋 SOC: ${String.format("%.1f", trip.startSoc)}% → ${String.format("%.1f", trip.endSoc ?: 0.0)}%")
            appendLine("⚡ Max Power: ${trip.maxPower.toInt()} kW")
            appendLine("🔋 Max Regen: ${kotlin.math.abs(trip.maxRegenPower).toInt()} kW")
            appendLine("🏎️ Max Speed: ${trip.maxSpeed.toInt()} km/h")
        }
        
        // Save to Downloads folder
        val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOWNLOADS
        )
        val file = java.io.File(downloadsDir, fileName)
        file.writeText(summary)
        
        android.widget.Toast.makeText(
            context, 
            "Saved to Downloads/$fileName", 
            android.widget.Toast.LENGTH_LONG
        ).show()
        
    } catch (e: Exception) {
        android.widget.Toast.makeText(
            context, 
            "Export failed: ${e.message}", 
            android.widget.Toast.LENGTH_LONG
        ).show()
    }
}

@Composable
fun TripOverviewTab(
    trip: com.byd.tripstats.data.local.entity.TripEntity,
    stats: com.byd.tripstats.data.local.entity.TripStatsEntity?
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Key metrics cards
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            MetricCard(
                title = "Distance",
                value = String.format("%.1f", trip.distance ?: 0.0),
                unit = "km",
                icon = Icons.Filled.Route,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f)
            )
            
            MetricCard(
                title = "Duration",
                value = formatDuration(trip.duration ?: 0),
                unit = "",
                icon = Icons.Filled.Timer,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.weight(1f)
            )
        }
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            MetricCard(
                title = "Energy Used",
                value = String.format("%.2f", trip.energyConsumed ?: 0.0),
                unit = "kWh",
                icon = Icons.Filled.BatteryChargingFull,
                color = AccelerationOrange,
                modifier = Modifier.weight(1f)
            )
            
            MetricCard(
                title = "Consumption",
                value = String.format("%.2f", trip.efficiency ?: 0.0),
                unit = "kWh / 100km",
                icon = Icons.Filled.Eco,
                color = RegenGreen,
                modifier = Modifier.weight(1f)
            )
        }
        
        // Detailed stats
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "Trip Statistics",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(16.dp))

                DetailRow("Start Time", formatTimestamp(trip.startTime))
                DetailRow("End Time", trip.endTime?.let { formatTimestamp(it) } ?: "In Progress")
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                DetailRow("Initial mileage", "${String.format("%.1f", trip.startOdometer)} km")
                DetailRow("Final mileage", trip.endOdometer?.let { "${String.format("%.1f", it)} km" } ?: "-")
                DetailRow("Trip distance", trip.distance?.let { "${String.format("%.1f", it)} km" } ?: "-")
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                DetailRow("Start SOC", "${String.format("%.1f", trip.startSoc)}%")
                DetailRow("End SOC", trip.endSoc?.let { "${String.format("%.1f", it)}%" } ?: "-")
                DetailRow("SOC Change", trip.socDelta?.let { "${String.format("%.1f", it)}%" } ?: "-")
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                DetailRow("Max Speed", "${trip.maxSpeed.toInt()} km/h")
                DetailRow("Avg Speed", stats?.avgSpeed?.toInt()?.toString()?.plus(" km/h") ?: "-")
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                DetailRow("Max Power", "${trip.maxPower.toInt()} kW")
                DetailRow("Max Regen", "${abs(trip.maxRegenPower).toInt()} kW")
                DetailRow("Energy consumed", trip.energyConsumed?.let { String.format("%.2f kWh", it) } ?: "-")
                DetailRow("Energy regenerated", stats?.totalRegenEnergy?.let { String.format("%.2f kWh", it) } ?: "-")
                DetailRow("Regeneration efficiency", 
                    if (trip.energyConsumed != null && stats?.totalRegenEnergy != null && trip.energyConsumed!! > 0) {
                        val regenPercent = (stats.totalRegenEnergy!! / (trip.energyConsumed!! + stats.totalRegenEnergy!!)) * 100
                        String.format("%.2f%%", regenPercent)
                    } else "-"
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                DetailRow("Battery Temp Range", "${trip.minBatteryCellTemp}°C - ${trip.maxBatteryCellTemp}°C")
                DetailRow("Avg Battery Temp", "${trip.avgBatteryTemp.toInt()}°C")
            }
        }
    }
}

@Composable
fun TripChartsTab(
    dataPoints: List<com.byd.tripstats.data.local.entity.TripDataPointEntity>,
    stats: com.byd.tripstats.data.local.entity.TripStatsEntity?
) {
    // Track which chart is expanded
    var expandedChart by remember { mutableStateOf<ChartType?>(null) }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Energy consumption over time
        ClickableChartCard(
            title = "Energy Consumption",
            subtitle = "kWh over time",
            onClick = { expandedChart = ChartType.ENERGY }
        ) {
            CondensedEnergyChart(
                dataPoints = dataPoints,
                modifier = Modifier.fillMaxSize()
            )
        }
        
        // Speed over time
        ClickableChartCard(
            title = "Speed Profile",
            subtitle = "km/h over time",
            onClick = { expandedChart = ChartType.SPEED }
        ) {
            CondensedSpeedChart(
                dataPoints = dataPoints,
                modifier = Modifier.fillMaxSize()
            )
        }

        // SoC over time
        ClickableChartCard(
            title = "State of Charge",
            subtitle = "SoC% over time",
            onClick = { expandedChart = ChartType.SOC }
        ) {
            CondensedSocChart(
                dataPoints = dataPoints,
                modifier = Modifier.fillMaxSize()
            )
        }

        // Motor RPM Chart
        ClickableChartCard(
            title = "Motor RPM",
            subtitle = "RPM over time",
            onClick = { expandedChart = ChartType.MOTOR_RPM }
        ) {
            CondensedMotorRpmChart(
                dataPoints = dataPoints,
                modifier = Modifier.fillMaxSize()
            )
        }
        
        // Altitude/Elevation Profile
        ClickableChartCard(
            title = "Elevation Profile",
            subtitle = "Altitude over time",
            onClick = { expandedChart = ChartType.ALTITUDE }
        ) {
            CondensedAltitudeChart(
                dataPoints = dataPoints,
                modifier = Modifier.fillMaxSize()
            )
        }

        // Power Profile
        ClickableChartCard(
            title = "Power Profile",
            subtitle = "kW over time",
            onClick = { expandedChart = ChartType.POWER }
        ) {
            CondensedPowerChart(
                dataPoints = dataPoints,
                modifier = Modifier.fillMaxSize()
            )
        }

        // Power distribution (no expansion needed - it's already a summary)
        if (stats != null) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Power Distribution",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    PowerDistributionChart(
                        powerDistribution = stats.powerDistribution,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        // Speed distribution
        if (stats != null) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Speed Distribution",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    SpeedDistributionChart(
                        speedDistribution = stats.speedDistribution,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
    
    // Fullscreen chart dialog
    expandedChart?.let { chartType ->
        FullscreenChartDialog(
            chartType = chartType,
            dataPoints = dataPoints,  // Full data
            onDismiss = { expandedChart = null }
        )
    }
}

@Composable
private fun ClickableChartCard(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Icon(
                    imageVector = Icons.Filled.Fullscreen,
                    contentDescription = "Expand",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            content()
        }
    }
}

enum class ChartType {
    ENERGY, SPEED, MOTOR_RPM, ALTITUDE, SOC, POWER
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FullscreenChartDialog(
    chartType: ChartType,
    dataPoints: List<com.byd.tripstats.data.local.entity.TripDataPointEntity>,
    onDismiss: () -> Unit
) {
    // Condense to 144 points for readability
    val condensedData = remember(dataPoints) {
        condenseData(dataPoints, maxPoints = 144)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header with close button
                TopAppBar(
                    title = {
                        Text(
                            text = when (chartType) {
                                ChartType.ENERGY -> "Energy Consumption (Detailed)"
                                ChartType.SOC -> "State of Charge (Detailed)"
                                ChartType.SPEED -> "Speed Profile (Detailed)"
                                ChartType.MOTOR_RPM -> "Motor RPM (Detailed)"
                                ChartType.ALTITUDE -> "Elevation Profile (Detailed)"
                                ChartType.POWER -> "Power profile (Detailed)"
                            },
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Filled.Close, "Close")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                )
                
                // Chart with condensed data
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    when (chartType) {
                        ChartType.ENERGY -> EnergyConsumptionChart(
                            dataPoints = condensedData,
                            modifier = Modifier.fillMaxSize()
                        )
                        ChartType.SOC -> SocChart(
                            dataPoints = condensedData,
                            modifier = Modifier.fillMaxSize()
                        )
                        ChartType.SPEED -> SpeedChart(
                            dataPoints = condensedData,
                            modifier = Modifier.fillMaxSize()
                        )
                        ChartType.MOTOR_RPM -> MotorRpmChart(
                            dataPoints = condensedData,
                            modifier = Modifier.fillMaxSize()
                        )
                        ChartType.ALTITUDE -> AltitudeChart(
                            dataPoints = condensedData,
                            modifier = Modifier.fillMaxSize()
                        )
                        ChartType.POWER -> PowerChart(
                            dataPoints = condensedData,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TripRouteTab(
    dataPoints: List<com.byd.tripstats.data.local.entity.TripDataPointEntity>
) {
    Card(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        OsmRouteMap(
            dataPoints = dataPoints,
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
fun TripAnalysisTab(
    dataPoints: List<com.byd.tripstats.data.local.entity.TripDataPointEntity>
) {
    RouteAnalysisTab(
        dataPoints = dataPoints,
        modifier = Modifier.fillMaxSize()
    )
}

@Composable
fun MetricCard(
    title: String,
    value: String,
    unit: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = color
                )
                if (unit.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = unit,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun DetailRow(label: String, value: String) {
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

private fun formatDuration(milliseconds: Long): String {
    val seconds = milliseconds / 1000
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    return if (hours > 0) {
        String.format("%dh %dm", hours, minutes)
    } else {
        String.format("%dm", minutes)
    }
}

private fun formatTimestamp(timestamp: Long): String {
    val instant = java.time.Instant.ofEpochMilli(timestamp)
    val formatter = java.time.format.DateTimeFormatter
        .ofPattern("MMM dd, yyyy HH:mm")
        .withZone(java.time.ZoneId.systemDefault())
    return formatter.format(instant)
}