package com.byd.sealstats.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import com.byd.sealstats.ui.components.AltitudeChart
import com.byd.sealstats.ui.components.EnergyConsumptionChart
import com.byd.sealstats.ui.components.MotorRpmChart
import com.byd.sealstats.ui.components.OsmRouteMap
import com.byd.sealstats.ui.components.PowerDistributionChart
import com.byd.sealstats.ui.components.RouteAnalysisTab
import com.byd.sealstats.ui.components.RouteMap
import com.byd.sealstats.ui.components.SpeedChart
import com.byd.sealstats.ui.theme.*
import com.byd.sealstats.ui.viewmodel.DashboardViewModel
import kotlin.math.abs

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
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Trip Details", fontSize = 24.sp, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Filled.ArrowBack, "Back", modifier = Modifier.size(28.dp))
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
                        1 -> TripChartsTab(trip = trip!!, dataPoints = dataPoints, stats = stats)
                        2 -> TripRouteTab(dataPoints = dataPoints)
                        3 -> RouteAnalysisTab(dataPoints = dataPoints)
                    }
                }
            }
        }
    }
}

@Composable
fun TripOverviewTab(
    trip: com.byd.sealstats.data.local.entity.TripEntity,
    stats: com.byd.sealstats.data.local.entity.TripStatsEntity?
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
                title = "Efficiency",
                value = String.format("%.1f", trip.efficiency ?: 0.0),
                unit = "kWh/100km",
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
                Divider(modifier = Modifier.padding(vertical = 8.dp))
                
                DetailRow("Start SOC", "${String.format("%.1f", trip.startSoc)}%")
                DetailRow("End SOC", trip.endSoc?.let { "${String.format("%.1f", it)}%" } ?: "-")
                DetailRow("SOC Change", trip.socDelta?.let { "${String.format("%.1f", it)}%" } ?: "-")
                Divider(modifier = Modifier.padding(vertical = 8.dp))
                
                DetailRow("Max Speed", "${trip.maxSpeed.toInt()} km/h")
                DetailRow("Avg Speed", stats?.avgSpeed?.toInt()?.toString()?.plus(" km/h") ?: "-")
                Divider(modifier = Modifier.padding(vertical = 8.dp))
                
                DetailRow("Max Power", "${trip.maxPower.toInt()} kW")
                DetailRow("Max Regen", "${abs(trip.maxRegenPower).toInt()} kW")
                DetailRow("Energy used", trip.energyConsumed?.let { String.format("%.2f kWh", it) } ?: "-")
                DetailRow("Total Regen Energy", stats?.totalRegenEnergy?.let { String.format("%.2f kWh", it) } ?: "-")
                Divider(modifier = Modifier.padding(vertical = 8.dp))
                
                DetailRow("Battery Temp Range", "${trip.minBatteryCellTemp}°C - ${trip.maxBatteryCellTemp}°C")
                DetailRow("Avg Battery Temp", "${trip.avgBatteryTemp.toInt()}°C")
            }
        }
    }
}

@Composable
fun TripChartsTab(
    trip: com.byd.sealstats.data.local.entity.TripEntity,
    dataPoints: List<com.byd.sealstats.data.local.entity.TripDataPointEntity>,
    stats: com.byd.sealstats.data.local.entity.TripStatsEntity?
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Energy consumption over time
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
                    text = "Energy Consumption",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                EnergyConsumptionChart(
                    dataPoints = dataPoints,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        
        // Speed over time
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
                    text = "Speed Profile",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                SpeedChart(
                    dataPoints = dataPoints,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        
        // Power distribution
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
        
        // Motor RPM Chart
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(350.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Motor RPM",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                MotorRpmChart(
                    dataPoints = dataPoints,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        
        // Altitude/Elevation Profile
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
                    text = "Elevation Profile",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                AltitudeChart(
                    dataPoints = dataPoints,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

@Composable
fun TripRouteTab(
    dataPoints: List<com.byd.sealstats.data.local.entity.TripDataPointEntity>
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()  // ✅ Width only, not height
            .padding(16.dp)
    ) {
        OsmRouteMap(
            dataPoints = dataPoints,
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
fun TripAnalysisTab(
    dataPoints: List<com.byd.sealstats.data.local.entity.TripDataPointEntity>
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