package com.byd.sealstats.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.byd.sealstats.data.model.VehicleTelemetry
import com.byd.sealstats.ui.screens.StatCard
import com.byd.sealstats.ui.theme.*
import com.byd.sealstats.ui.viewmodel.DashboardViewModel
import kotlin.math.abs
import com.byd.sealstats.R
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.translate
import kotlin.math.sin
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Image
import androidx.compose.runtime.remember
import androidx.compose.ui.viewinterop.AndroidView
import android.widget.ImageView

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    onNavigateToHistory: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val telemetry by viewModel.currentTelemetry.collectAsState()
    val isInTrip by viewModel.isInTrip.collectAsState()
    val mqttConnected by viewModel.mqttConnected.collectAsState()
    val autoTripDetection by viewModel.autoTripDetection.collectAsState()
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("BYD Info Stats", fontSize = 24.sp, fontWeight = FontWeight.Bold) },
                actions = {
                    // Mock Data Button (for testing)
                    IconButton(onClick = { viewModel.startMockDrive() }) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = "Start Mock Drive",
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    
                    // MQTT Status indicator
                    Icon(
                        imageVector = if (mqttConnected) Icons.Filled.CloudDone else Icons.Filled.CloudOff,
                        contentDescription = "MQTT Status",
                        tint = if (mqttConnected) Color.Green else Color.Gray,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    
                    IconButton(onClick = onNavigateToHistory) {
                        Icon(
                            imageVector = Icons.Filled.History,
                            contentDescription = "Trip History",
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = "Settings",
                            modifier = Modifier.size(28.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { paddingValues ->
        if (telemetry == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(modifier = Modifier.size(60.dp))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = if (mqttConnected) "Waiting for data..." else "Connecting to MQTT...",
                        style = MaterialTheme.typography.titleLarge
                    )
                }
            }
        } else {
            DashboardContent(
                telemetry = telemetry!!,
                isInTrip = isInTrip,
                autoTripDetection = autoTripDetection,
                onStartTrip = { viewModel.startManualTrip() },
                onEndTrip = { viewModel.endManualTrip() },
                onToggleAutoDetection = { viewModel.toggleAutoTripDetection() },
                modifier = Modifier.padding(paddingValues)
            )
        }
    }
}

@Composable
fun DashboardContent(
    telemetry: VehicleTelemetry,
    isInTrip: Boolean,
    autoTripDetection: Boolean,
    onStartTrip: () -> Unit,
    onEndTrip: () -> Unit,
    onToggleAutoDetection: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Left column - Energy Flow Diagram (60%)
        Column(
            modifier = Modifier
                .weight(0.6f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            EnergyFlowDiagram(
                telemetry = telemetry,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
            
            TripControls(
                isInTrip = isInTrip,
                autoTripDetection = autoTripDetection,
                currentGear = telemetry.gear,
                onStartTrip = onStartTrip,
                onEndTrip = onEndTrip,
                onToggleAutoDetection = onToggleAutoDetection,
                modifier = Modifier.fillMaxWidth()
            )
        }
        
        // Right column - Stats (40%)
        Column(
            modifier = Modifier
                .weight(0.4f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            VehicleStats(
                telemetry = telemetry,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
fun EnergyFlowDiagram(
    telemetry: VehicleTelemetry,
    modifier: Modifier = Modifier
) {
    val power = telemetry.enginePower
    val isRegenerating = telemetry.isRegenerating
    val isCharging = telemetry.isCharging
    
    // Animation for energy flow
    val infiniteTransition = rememberInfiniteTransition(label = "energy_flow")
    val flowOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "flow_offset"
    )
    
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
        ) {
            Text(
                text = "Energy Flow",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Main energy flow visualization
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                EnergyFlowCanvas(
                    power = power,
                    soc = telemetry.soc,
                    speed = telemetry.speed,
                    isRegenerating = isRegenerating,
                    isCharging = isCharging,
                    flowOffset = flowOffset
                )

                // Battery image based on SOC and charging state
                val batteryImage = when {
                    isCharging -> painterResource(R.drawable.battery_charging)
                    telemetry.soc > 85 -> painterResource(R.drawable.battery_full)
                    telemetry.soc > 60 -> painterResource(R.drawable.battery_high)
                    telemetry.soc >= 30 -> painterResource(R.drawable.battery_medium)
                    else -> painterResource(R.drawable.battery_low)
                }

                Image(
                    painter = batteryImage,
                    contentDescription = "Battery level",
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 100.dp)
                        .offset(x = -50.dp, y = -7.dp)
                        .size(140.dp)
                )

                // 4WD drivetrain
                Image(
                    painter = painterResource(R.drawable.awd),
                    contentDescription = "AWD drivetrain",
                    modifier = Modifier
                        .align(Alignment.Center)
                        .offset(x = -20.dp)
                        .size(120.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            
            // Power metrics
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                PowerMetric(
                    label = "Power",
                    value = "${abs(power).toInt()}",
                    unit = "kW",
                    color = when {
                        isRegenerating -> RegenGreen
                        power > 0 -> AccelerationOrange
                        else -> Color.Gray
                    }
                )
                
                PowerMetric(
                    label = "Speed",
                    value = "${telemetry.speed.toInt()}",
                    unit = "km/h",
                    color = MaterialTheme.colorScheme.primary
                )
                
                PowerMetric(
                    label = "Battery",
                    value = "${telemetry.soc.toInt()}",
                    unit = "%",
                    color = BatteryBlue
                )
                
                PowerMetric(
                    label = "Range",
                    value = "${telemetry.electricDrivingRangeKm}",
                    unit = "km",
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
        }
    }
}

@Composable
fun EnergyFlowCanvas(
    power: Double,
    soc: Double,
    speed: Double,
    isRegenerating: Boolean,
    isCharging: Boolean,
    flowOffset: Float
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height
        val centerX = width / 2
        val centerY = height / 2
        
        // Battery (left)
        val batteryX = width * 0.2f
        val batteryY = centerY
        val batterySize = 120f

        // Motor (center)
        val motorX = centerX
        val motorY = centerY
        val motorSize = 150f

        // Energy flow lines
        if (abs(power) > 1 && !isCharging) {
            val flowColor = when {
                isRegenerating -> RegenGreen
                power > 0 -> AccelerationOrange
                else -> Color.Gray
            }
            
            // Create animated flow effect
            val dashPhase = flowOffset * 30f
            
            if (isRegenerating) {
                // Motor to Battery (regeneration)
                drawEnergyFlow(
                    from = Offset(motorX - motorSize / 3, motorY),
                    to = Offset(batteryX + batterySize / 3, batteryY),
                    color = flowColor,
                    dashPhase = dashPhase,
                    reverse = true
                )
            } else if (power > 0) {
                // Battery to Motor (acceleration)
                drawEnergyFlow(
                    from = Offset(batteryX + batterySize / 3, batteryY),
                    to = Offset(motorX - motorSize / 3, motorY),
                    color = flowColor,
                    dashPhase = dashPhase,
                    reverse = true
                )
            }
        }
    }
}

fun androidx.compose.ui.graphics.drawscope.DrawScope.drawEnergyFlow(
    from: Offset,
    to: Offset,
    color: Color,
    dashPhase: Float,
    reverse: Boolean
) {
    val path = Path().apply {
        moveTo(from.x, from.y)
        lineTo(to.x, to.y)
    }
    
    drawPath(
        path = path,
        color = color,
        style = Stroke(
            width = 6f,
            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                intervals = floatArrayOf(20f, 10f),
                phase = if (reverse) -dashPhase else dashPhase
            )
        )
    )
}

@Composable
fun PowerMetric(
    label: String,
    value: String,
    unit: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
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

@Composable
fun TripControls(
    isInTrip: Boolean,
    autoTripDetection: Boolean,
    currentGear: String,
    onStartTrip: () -> Unit,
    onEndTrip: () -> Unit,
    onToggleAutoDetection: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = if (isInTrip) 
                MaterialTheme.colorScheme.primaryContainer 
            else 
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Trip Tracking",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Auto",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Switch(
                        checked = autoTripDetection,
                        onCheckedChange = { onToggleAutoDetection() }
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (!autoTripDetection) {
                    Button(
                        onClick = if (isInTrip) onEndTrip else onStartTrip,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isInTrip) 
                                MaterialTheme.colorScheme.error 
                            else 
                                MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(
                            imageVector = if (isInTrip) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isInTrip) "End Trip" else "Start Trip",
                            fontSize = 18.sp
                        )
                    }
                } else {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = if (isInTrip) 
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                        else 
                            Color.Transparent,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (isInTrip) Icons.Filled.DirectionsCar else Icons.Filled.LocalParking,
                                contentDescription = null,
                                tint = if (isInTrip) MaterialTheme.colorScheme.primary else Color.Gray,
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = if (isInTrip) "Trip in Progress" else "Waiting for Trip...",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun VehicleStats(
    telemetry: VehicleTelemetry,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        StatCard(
            title = "Battery health",
            value = "${telemetry.soh}%",
            icon = Icons.Filled.BatteryChargingFull,
            color = BatteryBlue
        )
        
        StatCard(
            title = "Battery temperature",
            value = "${telemetry.batteryTempAvg.toInt()}°C",
            subtitle = "${telemetry.batteryCellTempMin}°C - ${telemetry.batteryCellTempMax}°C",
            icon = Icons.Filled.Thermostat,
            color = MaterialTheme.colorScheme.tertiary
        )
        
        StatCard(
            title = "Voltage HV / 12V",
            value = "${telemetry.batteryTotalVoltage} V / ${String.format("%.2f", telemetry.battery12vVoltage)} V",
            subtitle = "Cell: ${String.format("%.3f", telemetry.batteryCellVoltageMin)} - ${String.format("%.3f", telemetry.batteryCellVoltageMax)} V",
            icon = Icons.Filled.Bolt,
            color = MaterialTheme.colorScheme.secondary
        )

        StatCard(
            title = "Front / Rear Motors",
            value = "${telemetry.engineSpeedFront} / ${telemetry.engineSpeedRear} RPM",
            subtitle = if (telemetry.engineSpeedRear > 0) {
                "${((telemetry.enginePower * 160 / 390).toInt())} / ${((telemetry.enginePower * 230 / 390).toInt())} kW"
            } else {
                "0 / 0 kW"
            },
            iconRes = R.drawable.ic_motor_axle,
            color = Color(0xFF1976D2)
        )

        StatCard(
            title = "Odometer",
            value = "${String.format("%.1f", telemetry.odometer)} km",
            icon = Icons.Filled.Speed,
            color = MaterialTheme.colorScheme.primary
        )
        
        StatCard(
            title = "Total Discharge",
            value = "${String.format("%.1f", telemetry.totalDischarge)} kWh",
            icon = Icons.Filled.ElectricalServices,
            color = AccelerationOrange
        )
    }
}

@Composable
fun StatCard(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    icon: Any? = null,
    iconRes: Int? = null,
    color: Color,
    subtitle: String? = null,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when {
                icon is ImageVector -> Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(32.dp)
                )
                iconRes != null -> Icon(
                    painter = painterResource(id = iconRes),
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(32.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                subtitle?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
