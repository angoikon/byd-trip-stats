package com.byd.tripstats.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.byd.tripstats.data.model.VehicleTelemetry
import com.byd.tripstats.ui.components.LiquidFillBattery
import com.byd.tripstats.ui.components.StatsGlassCard
import com.byd.tripstats.ui.components.GlassmorphicCard
import com.byd.tripstats.ui.components.RangeProjectionChart
import com.byd.tripstats.ui.components.RangeDataPoint
import com.byd.tripstats.ui.theme.*
import com.byd.tripstats.ui.viewmodel.DashboardViewModel
import kotlin.math.abs
import com.byd.tripstats.R
import androidx.compose.animation.core.tween
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.StrokeCap

private const val SHOW_MOCK_BUTTON = false  // Set to true for testing, false for production

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
    val mqttConnectionError by viewModel.mqttConnectionError.collectAsState()
    val autoTripDetection by viewModel.autoTripDetection.collectAsState()
    val tripDataPoints by viewModel.tripDataPoints.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("BYD trip stats", fontSize = 24.sp, fontWeight = FontWeight.Bold) },
                actions = {
                    // Mock Data Button - only shown if SHOW_MOCK_BUTTON is true
                    if (SHOW_MOCK_BUTTON) {
                        IconButton(onClick = { viewModel.startMockDrive() }) {
                            Icon(
                                imageVector = Icons.Filled.Analytics,
                                contentDescription = "Mock Drive",
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    // History Button
                    IconButton(onClick = onNavigateToHistory) {
                        Icon(
                            imageVector = Icons.Filled.History,
                            contentDescription = "Trip History",
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(24.dp))

                    // MQTT Status indicator - properly shows connection state
                    Icon(
                        imageVector = when {
                            mqttConnectionError != null -> Icons.Filled.SyncProblem
                            mqttConnected -> Icons.Filled.Sync
                            else -> Icons.Filled.SyncDisabled
                        },
                        contentDescription = "MQTT Status",
                        tint = when {
                            mqttConnectionError != null -> MaterialTheme.colorScheme.error
                            mqttConnected -> Color.Green
                            else -> Color.Gray
                        },
                        modifier = Modifier.size(28.dp)
                    )

                    Spacer(modifier = Modifier.width(24.dp))

                    // Settings Button
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
        // Show error state if MQTT connection failed
        if (mqttConnectionError != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Filled.CloudOff,
                        contentDescription = null,
                        modifier = Modifier.size(80.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Connection Failed",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = mqttConnectionError!!,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 32.dp)
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(onClick = onNavigateToSettings) {
                        Text("Check Settings")
                    }
                }
            }
        } else if (telemetry == null && !mqttConnected) {
            // Show "Not configured" state
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Filled.SettingsSuggest,
                        contentDescription = null,
                        modifier = Modifier.size(80.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "MQTT Not Configured",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Configure MQTT settings to connect",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(onClick = onNavigateToSettings) {
                        Icon(Icons.Filled.Settings, null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Configure MQTT")
                    }
                }
            }
        } else if (telemetry == null && mqttConnected) {
            // Connected but waiting for data
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
                        text = "Waiting for data...",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Listening to MQTT broker",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            // Normal dashboard with telemetry
            DashboardContent(
                telemetry = telemetry!!,
                isInTrip = isInTrip,
                autoTripDetection = autoTripDetection,
                tripDataPoints = tripDataPoints,
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
    tripDataPoints: List<RangeDataPoint>,
    onStartTrip: () -> Unit,
    onEndTrip: () -> Unit,
    onToggleAutoDetection: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxSize()
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Left column - Energy Flow Diagram (75%)
        Column(
            modifier = Modifier
                .weight(0.75f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            EnergyFlowDiagram(
                telemetry = telemetry,
                tripDataPoints = tripDataPoints,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )

            TripControls(
                telemetry = telemetry,
                isInTrip = isInTrip,
                autoTripDetection = autoTripDetection,
                onStartTrip = onStartTrip,
                onEndTrip = onEndTrip,
                onToggleAutoDetection = onToggleAutoDetection,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Right column - Stats (25%)
        Column(
            modifier = Modifier
                .weight(0.25f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
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
    tripDataPoints: List<RangeDataPoint>,
    modifier: Modifier = Modifier
) {
    val power = telemetry.enginePower
    val isRegenerating = telemetry.isRegenerating
    val isCharging = telemetry.isCharging

    // ── Card flip state ───────────────────────────────────────────────────────
    var flipped by remember { mutableStateOf(false) }
    val rotation by animateFloatAsState(
        targetValue = if (flipped) 180f else 0f,
        animationSpec = tween(durationMillis = 500, easing = FastOutSlowInEasing),
        label = "card_flip"
    )
    val isBack = rotation > 90f

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
        modifier = modifier
            .clipToBounds()
            .clickable { flipped = !flipped }
            .graphicsLayer {
                rotationY = rotation
                cameraDistance = 12f * density
            },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        if (isBack) {
            // ── Back face: full-size range projection chart ───────────────────
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { rotationY = 180f }  // un-mirror text
                    .padding(8.dp)
            ) {
                Text(
                    text = "Range Projection",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 4.dp)
                )
                RangeProjectionChart(
                    dataPoints = tripDataPoints,
                    liveSoc = telemetry.soc,
                    wltpRangeKm = 520, // TODO: make this dynamic based on car model via config
                    modifier = Modifier.fillMaxSize()
                )
            }
        } else {
            // ── Front face: normal energy flow + compact chart ────────────────
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp)
            ) {
            // Main energy flow visualization
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp),
                contentAlignment = Alignment.TopCenter
            ) {
                EnergyFlowCanvas(
                    power = power,
                    isRegenerating = isRegenerating,
                    isCharging = isCharging,
                    flowOffset = flowOffset
                )

                // Animated liquid fill battery
                LiquidFillBattery(
                    soc = telemetry.soc.toFloat(),
                    isCharging = isCharging,
                    width = 60.dp,
                    height = 100.dp,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 90.dp)
                        // .offset(x = -50.dp, y = -7.dp)
                )

                // AWD drivetrain with tyre pressures
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 12.dp)
                ) {
                    // AWD Image
                    Image(
                        painter = painterResource(R.drawable.awd),
                        contentDescription = "AWD drivetrain",
                        modifier = Modifier.size(90.dp)
                    )
    
                    // Tyre Pressure Overlays
                    // Left Front (recommended: 2.5 bar)
                    TyrePressureIndicator(
                        pressure = telemetry.tyrePressureLF,
                        isFront = true,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .offset(x = (-10).dp, y = (-10).dp)
                    )

                    // Right Front (recommended: 2.5 bar)
                    TyrePressureIndicator(
                        pressure = telemetry.tyrePressureRF,
                        isFront = true,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 10.dp, y = (-10).dp)
                    )

                    // Left Rear (recommended: 2.9 bar)
                    TyrePressureIndicator(
                        pressure = telemetry.tyrePressureLR,
                        isFront = false,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .offset(x = (-10).dp, y = (10).dp)
                    )

                    // Right Rear (recommended: 2.9 bar)
                    TyrePressureIndicator(
                        pressure = telemetry.tyrePressureRR,
                        isFront = false,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .offset(x = 10.dp, y = (10).dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Range Projection Chart
            RangeProjectionChart(
                dataPoints = tripDataPoints,
                liveSoc = telemetry.soc,
                wltpRangeKm = 520, // TODO: make this dynamic based on car model via config
                modifier = Modifier
                    .fillMaxWidth()
                    .height(190.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Power metrics
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                PowerMetric(
                    label = "Power",
                    value = "${power.toInt()}",
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
        }   // end front Column
        }   // end if/else
    }       // end Card
}

@Composable
fun TyrePressureIndicator(
    pressure: Double,  // In PSI
    isFront: Boolean,  // true for front tyres, false for rear
    modifier: Modifier = Modifier
) {
    // Convert PSI to bar (1 bar = 14.5038 PSI)
    val pressureBar = pressure / 14.5038
    
    // BYD Seal recommended pressure: Front 2.5 bar, Rear 2.9 bar
    // Tolerance: ±0.2 bar
    val recommendedPressure = if (isFront) 2.5 else 2.9
    val minPressure = recommendedPressure - 0.2
    val maxPressure = recommendedPressure + 0.2
    
    val isLow = pressureBar < minPressure
    val isHigh = pressureBar > maxPressure
    
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(4.dp),
        color = when {
            pressureBar < 0.1 -> Color.Gray.copy(alpha = 0.9f)  // Gray for no data
            isLow -> Color(0xFFFF9800).copy(alpha = 0.9f)  // Orange for low
            isHigh -> Color(0xFFF44336).copy(alpha = 0.9f) // Red for high
            else -> Color(0xFF4CAF50).copy(alpha = 0.9f)   // Green for normal
        }
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
        ) {
            Text(
                text = if (pressureBar < 0.1) "--" else String.format("%.1f", pressureBar),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                fontSize = 10.sp
            )
        }
    }
}

@Composable
fun EnergyFlowCanvas(
    power: Double,
    isRegenerating: Boolean,
    isCharging: Boolean,
    flowOffset: Float
) {
    Canvas(modifier = Modifier.fillMaxSize()) {

        val topY = size.height * 0.3f + 30f  // Move up - 30% from top + 30px for better alignment with battery/motor icons
        
        val batteryX = size.width * 0.15f
        val batteryY = topY
        
        val motorX = size.width * 0.5f
        val motorY = topY

        // Battery (left)
        val batterySize = 120f

        // Motor (center)
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
                    from = Offset(batteryX + batterySize / 3, batteryY),
                    to = Offset(motorX - motorSize / 3 - 15, motorY),
                    color = flowColor,
                    dashPhase = dashPhase,
                    reverse = true
                )
            } else if (power > 0) {
                // Battery to Motor (acceleration)
                drawEnergyFlow(
                    from = Offset(motorX - motorSize / 3 - 15, motorY),
                    to = Offset(batteryX + batterySize / 3, batteryY),
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
    // Draw solid line
    drawLine(
        color = color,
        start = from,
        end = to,
        strokeWidth = 6f
    )
    
    // Draw animated arrows along the line
    val dx = to.x - from.x
    val dy = to.y - from.y
    val lineLength = kotlin.math.sqrt(dx * dx + dy * dy)
    val arrowSpacing = 50f  // Increased spacing for smoother animation
    val numArrows = (lineLength / arrowSpacing).toInt() + 2  // Extra arrows for smooth loop
    
    val angle = kotlin.math.atan2(dy.toDouble(), dx.toDouble()).toFloat()
    val arrowAngle = if (!reverse) angle else (angle + kotlin.math.PI).toFloat()
    
    for (i in 0 until numArrows) {
        // Smooth continuous animation using modulo
        val offset = (dashPhase * 1.5) % arrowSpacing
        val position = (i * arrowSpacing - offset)
        
        if (position < 0 || position > lineLength) continue
        
        val progress = position / lineLength
        val arrowX = (from.x + dx * progress).toFloat()
        val arrowY = (from.y + dy * progress).toFloat()
        
        // Draw arrow head (simplified for performance)
        val arrowSize = 10f
        val path = Path().apply {
            moveTo(arrowX, arrowY)
            lineTo(
                arrowX - arrowSize * kotlin.math.cos(arrowAngle - 0.4).toFloat(),
                arrowY - arrowSize * kotlin.math.sin(arrowAngle - 0.4).toFloat()
            )
            moveTo(arrowX, arrowY)
            lineTo(
                arrowX - arrowSize * kotlin.math.cos(arrowAngle + 0.4).toFloat(),
                arrowY - arrowSize * kotlin.math.sin(arrowAngle + 0.4).toFloat()
            )
        }
        
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = 3f, cap = StrokeCap.Round)
        )
    }
}

@Composable
fun PowerMetric(
    label: String,
    value: String,
    unit: String,
    color: Color,
    modifier: Modifier = Modifier,
    compact: Boolean = true
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = value,
                style = if (compact) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = color
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = unit,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }
    }
}

@Composable
fun TripControls(
    telemetry: VehicleTelemetry,
    isInTrip: Boolean,
    autoTripDetection: Boolean,
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
                .height(100.dp)
                .padding(
                    start = 8.dp,
                    end = 8.dp,
                    top = 8.dp,
                    bottom = 4.dp  // ← Less bottom padding
                )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(30.dp)
                    .padding(horizontal = 12.dp),
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
                    Spacer(modifier = Modifier.width(12.dp))
                    Switch(
                        checked = autoTripDetection,
                        onCheckedChange = { onToggleAutoDetection() }
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (!autoTripDetection) {
                    // Manual mode - show start/stop button
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
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isInTrip) "End Trip" else "Record Trip",
                            fontSize = 18.sp
                        )
                    }
                } else {
                    // Auto mode - show status
                    if (isInTrip) {
                        // Show stop button for ongoing auto trip
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .height(IntrinsicSize.Min),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Surface(
                                modifier = Modifier.weight(1f),
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Show actual gear letter in a circle
                                    Surface(
                                        modifier = Modifier.size(28.dp),
                                        shape = CircleShape,
                                        color = when {
                                            telemetry.gear == "D" -> MaterialTheme.colorScheme.primary
                                            telemetry.gear == "R" -> Color(0xFFFF9800) // Orange for reverse
                                            else -> MaterialTheme.colorScheme.primary
                                        }
                                    ) {
                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier.fillMaxSize()
                                        ) {
                                            Text(
                                                text = telemetry.gear,
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = when {
                                            telemetry.speed > 0.5 -> "Driving"  // Actually moving
                                            telemetry.gear in listOf("D", "R") -> "Ready"  // In gear but not moving
                                            else -> "Trip in Progress"
                                        },
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                            
                            // Stop button for auto trip
                            Button(
                                onClick = onEndTrip,
                                modifier = Modifier.fillMaxHeight(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Stop,
                                    contentDescription = "Stop trip",
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    } else {
                        // Not in trip - show waiting status
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = Color.Transparent,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Show actual gear letter in a circle
                                Surface(
                                    modifier = Modifier.size(28.dp),
                                    shape = CircleShape,
                                    color = Color.Gray
                                ) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier.fillMaxSize()
                                    ) {
                                        Text(
                                            text = telemetry.gear,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = when (telemetry.gear) {
                                        "D" -> "Ready to Drive"
                                        "R" -> "Reverse"
                                        "P" -> "Waiting for Trip..."
                                        "N" -> "Neutral"
                                        else -> "Waiting for Trip..."
                                    },
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
            subtitle = "Cells: ${telemetry.batteryCellTempMin}°C - ${telemetry.batteryCellTempMax}°C",
            icon = Icons.Filled.Thermostat,
            color = MaterialTheme.colorScheme.tertiary
        )
        
        StatCard(
            title = "HV / 12V",
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