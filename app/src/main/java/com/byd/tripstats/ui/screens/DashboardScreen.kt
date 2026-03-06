package com.byd.tripstats.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import android.content.SharedPreferences
import androidx.compose.ui.platform.LocalContext
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
import com.byd.tripstats.ui.components.WeeklyEnergyThumbnail
import com.byd.tripstats.ui.components.ConsumptionChartExpanded
import com.byd.tripstats.ui.viewmodel.DashboardViewModel
import com.byd.tripstats.ui.theme.*
import kotlin.math.abs
import com.byd.tripstats.R
import androidx.compose.animation.core.tween
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.StrokeCap

private const val SHOW_MOCK_BUTTON = false  // Set to true for testing, false for production

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3WindowSizeClassApi::class)
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
    val weeklyEfficiency by viewModel.weeklyEfficiency.collectAsState()
    val monthlyEfficiency by viewModel.monthlyEfficiency.collectAsState()
    val yearlyEfficiency by viewModel.yearlyEfficiency.collectAsState()

    val activity = LocalContext.current as androidx.activity.ComponentActivity
    val windowSizeClass = calculateWindowSizeClass(activity)
    val widthSizeClass = windowSizeClass.widthSizeClass

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("BYD trip stats", fontSize = 24.sp, fontWeight = FontWeight.Bold) },
                actions = {
                    // Mock Data Button - only shown if SHOW_MOCK_BUTTON is true
                    if (SHOW_MOCK_BUTTON) {
                        IconButton(onClick = { viewModel.startMockDrive() }) {
                            Icon(
                                imageVector = Icons.Filled.PlayArrow,
                                contentDescription = "Mock Drive",
                                tint = MaterialTheme.colorScheme.primary,
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
                            tint = BatteryBlue,
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
                            mqttConnected -> RegenGreen
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
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
                weeklyEfficiency = weeklyEfficiency,
                monthlyEfficiency = monthlyEfficiency,
                yearlyEfficiency = yearlyEfficiency,
                onStartTrip = { viewModel.startManualTrip() },
                onEndTrip = { viewModel.endManualTrip() },
                onToggleAutoDetection = { viewModel.toggleAutoTripDetection() },
                widthSizeClass = widthSizeClass,
                modifier = Modifier.padding(paddingValues)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
fun DashboardContent(
    modifier: Modifier = Modifier,
    telemetry: VehicleTelemetry,
    isInTrip: Boolean,
    autoTripDetection: Boolean,
    tripDataPoints: List<RangeDataPoint>,
    weeklyEfficiency: List<DashboardViewModel.DailyEfficiency>,
    monthlyEfficiency: List<DashboardViewModel.DailyEfficiency>,
    yearlyEfficiency: List<DashboardViewModel.DailyEfficiency>,
    onStartTrip: () -> Unit,
    onEndTrip: () -> Unit,
    onToggleAutoDetection: () -> Unit,
    widthSizeClass: WindowWidthSizeClass = WindowWidthSizeClass.Expanded
) {
    val styledModifier = modifier.background(MaterialTheme.colorScheme.background)

    // Expanded  (>840 dp) → full landscape: side-by-side 75/25 — original layout
    // Medium    (600–840dp) → split-screen landscape or large tablet portrait: side-by-side 65/35
    // Compact   (<600 dp) → split-screen portrait or phone portrait: stacked vertically
    when (widthSizeClass) {
        WindowWidthSizeClass.Compact -> {
            // ── Compact: stacked portrait / narrow split-screen ───────────────
            // Stats moves above the energy diagram so nothing gets squeezed.
            Column(
                modifier = styledModifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Stats row at top — condensed horizontal strip
                VehicleStats(
                    telemetry = telemetry,
                    modifier = Modifier.fillMaxWidth()
                )

                // Energy flow / range card
                EnergyFlowDiagram(
                    telemetry = telemetry,
                    tripDataPoints = tripDataPoints,
                    weeklyEfficiency = weeklyEfficiency,
                    monthlyEfficiency = monthlyEfficiency,
                    yearlyEfficiency = yearlyEfficiency,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 320.dp)
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant,
                            shape = RoundedCornerShape(12.dp)
                        )
                )

                // Trip controls
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
        }

        WindowWidthSizeClass.Medium -> {
            // ── Medium: split-screen landscape or large portrait ─────────────
            // Same side-by-side structure but slightly wider right column so
            // stats don't get too cramped at 600–840 dp.
            Row(
                modifier = styledModifier
                    .fillMaxSize()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
        // Left column - Energy Flow Diagram
                Column(
                    modifier = Modifier
                        .weight(0.65f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    EnergyFlowDiagram(
                        telemetry = telemetry,
                        tripDataPoints = tripDataPoints,
                        weeklyEfficiency = weeklyEfficiency,
                        monthlyEfficiency = monthlyEfficiency,
                        yearlyEfficiency = yearlyEfficiency,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outlineVariant,
                                shape = RoundedCornerShape(12.dp)
                            )
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

                Column(
                    modifier = Modifier
                        .weight(0.35f)
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

        else -> {
            // ── Expanded: full landscape — original 75/25 layout ─────────────
            Row(
                modifier = styledModifier
                    .fillMaxSize()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(
                    modifier = Modifier
                        .weight(0.75f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    EnergyFlowDiagram(
                        telemetry = telemetry,
                        tripDataPoints = tripDataPoints,
                        weeklyEfficiency = weeklyEfficiency,
                        monthlyEfficiency = monthlyEfficiency,
                        yearlyEfficiency = yearlyEfficiency,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                                .border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant,
                                    shape = RoundedCornerShape(12.dp)
                                )
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

        // Right column - Stats
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
    }

}

@Composable
fun EnergyFlowDiagram(
    telemetry: VehicleTelemetry,
    tripDataPoints: List<RangeDataPoint>,
    weeklyEfficiency: List<DashboardViewModel.DailyEfficiency>,
    monthlyEfficiency: List<DashboardViewModel.DailyEfficiency>,
    yearlyEfficiency: List<DashboardViewModel.DailyEfficiency>,
    modifier: Modifier = Modifier
) {
    val power = telemetry.enginePower
    val isRegenerating = telemetry.isRegenerating
    val isCharging = telemetry.isCharging

    // Two independent expanded states
    var consumptionExpanded by remember { mutableStateOf(false) }
    var rangeFlipped   by remember { mutableStateOf(false) }

    // Tyre pressure unit — persisted in SharedPreferences
    val context = LocalContext.current
    val tyrePrefs: SharedPreferences = remember {
        context.getSharedPreferences("tyre_unit_prefs", 0)
    }
    var tyreUnit by remember {
        mutableStateOf(
            TyrePressureUnit.entries.getOrElse(
                tyrePrefs.getInt("unit", TyrePressureUnit.BAR.ordinal)
            ) { TyrePressureUnit.BAR }
        )
    }
    var showTyreUnitDialog by remember { mutableStateOf(false) }


    val rotation by animateFloatAsState(
        targetValue = if (rangeFlipped) 180f else 0f,
        animationSpec = tween(
            durationMillis = 650, // Slightly longer duration adds visual "weight"
            easing = FastOutSlowInEasing 
        ),
        label = "range_flip"
    )
    val isRangeBack = rotation > 90f

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
            .graphicsLayer {
                rotationY = rotation
                cameraDistance = 48f * density
            },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        if (isRangeBack) {
            // ── Back face: full-size range projection chart ───────────────────
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        rotationY = 180f
                        cameraDistance = 48f * density
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                    .clickable { rangeFlipped = false }
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
                    liveElectricRangeKm = telemetry.electricDrivingRangeKm,
                    modifier = Modifier.fillMaxSize()
                )
            }
        } else if (consumptionExpanded) {
            // ── Expanded consumption chart (tabbed: 7d / 30d / 12m) ───────────
            ConsumptionChartExpanded(
                weeklyData  = weeklyEfficiency,
                monthlyData = monthlyEfficiency,
                yearlyData  = yearlyEfficiency,
                onClose     = { consumptionExpanded = false },
                modifier    = Modifier.fillMaxSize()
            )
        } else {
            // ── Normal front face ─────────────────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .padding(12.dp)
            ) {
                // Energy flow row — visualization + thumbnail side by side
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(110.dp),
                        // contentAlignment = Alignment.TopCenter
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
                            .padding(start = 16.dp)
                    )

                // AWD drivetrain with tyre pressures
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 12.dp)
                    ) {
                        // AWD Image — tap to change pressure unit
                        Image(
                            painter = painterResource(R.drawable.awd),
                            contentDescription = "AWD drivetrain — tap to change pressure unit",
                            modifier = Modifier
                                .size(90.dp)
                                .clickable { showTyreUnitDialog = true }
                        )

                        // Tyre Pressure Overlays
                        // Left Front (recommended: 2.6 bar)
                        TyrePressureIndicator(
                            pressure = telemetry.tyrePressureLF,
                            unit = tyreUnit,
                            isFront = true,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .offset(x = (-13).dp, y = (-10).dp)
                        )

                        // Right Front (recommended: 2.6 bar)
                        TyrePressureIndicator(
                            pressure = telemetry.tyrePressureRF,
                            unit = tyreUnit,
                            isFront = true,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = 15.dp, y = (-10).dp)
                        )

                        // Left Rear (recommended: 2.9 bar)
                        TyrePressureIndicator(
                            pressure = telemetry.tyrePressureLR,
                            unit = tyreUnit,
                            isFront = false,
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .offset(x = (-13).dp, y = (10).dp)
                        )

                        // Right Rear (recommended: 2.9 bar)
                        TyrePressureIndicator(
                            pressure = telemetry.tyrePressureRR,
                            unit = tyreUnit,
                            isFront = false,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .offset(x = 15.dp, y = (10).dp)
                        )
                    }

                    // Consumption charts thumbnail — tap to open tabbed chart
                    Column(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .width(120.dp)
                            .fillMaxHeight()
                            .background(
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f),
                                RoundedCornerShape(8.dp)
                            )
                            .clickable { consumptionExpanded = true }
                            .padding(4.dp)
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outlineVariant,
                                shape = RoundedCornerShape(8.dp)
                            ),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "consumption charts",
                            fontSize = 9.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(2.dp))
                        WeeklyEnergyThumbnail(
                            data = weeklyEfficiency,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        )
                    }
                }

                // Range Projection Chart — tap to flip to expanded view
                Box(modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                ) {
                    RangeProjectionChart(
                        dataPoints = tripDataPoints,
                        liveSoc = telemetry.soc,
                        liveElectricRangeKm = telemetry.electricDrivingRangeKm,
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable { rangeFlipped = true }
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

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
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    PowerMetric(
                        label = "Speed",
                        value = "${telemetry.speed.toInt()}",
                        unit = "km/h",
                        color = BydEcoTealDim
                    )
                    PowerMetric(
                        label = "Battery (SoC)",
                        value = "${telemetry.soc.toInt()}",
                        unit = "%",
                        color = BatteryBlue
                    )
                    PowerMetric(
                        label = "Range (BMS)",
                        value = "${telemetry.electricDrivingRangeKm}",
                        unit = "km",
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
        }
    }

    // ── Tyre pressure unit selection dialog ──────────────────────────────────
    if (showTyreUnitDialog) {
        AlertDialog(
            onDismissRequest = { showTyreUnitDialog = false },
            title = { Text("Tyre Pressure Unit") },
            text = {
                Column {
                    TyrePressureUnit.entries.forEach { u ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    tyreUnit = u
                                    tyrePrefs.edit().putInt("unit", u.ordinal).apply()
                                    showTyreUnitDialog = false
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = tyreUnit == u,
                                onClick = {
                                    tyreUnit = u
                                    tyrePrefs.edit().putInt("unit", u.ordinal).apply()
                                    showTyreUnitDialog = false
                                }
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = when (u) {
                                    TyrePressureUnit.BAR -> "Bar  (default, e.g. 2.6)"
                                    TyrePressureUnit.PSI -> "PSI  (e.g. 37.7)"
                                    TyrePressureUnit.KPA -> "kPa  (e.g. 260)"
                                },
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showTyreUnitDialog = false }) { Text("Cancel") }
            }
        )
    }

}


// ── Tyre pressure unit support ────────────────────────────────────────────────

enum class TyrePressureUnit { BAR, PSI, KPA }

private fun Double.toDisplayPressure(unit: TyrePressureUnit): Double = when (unit) {
    TyrePressureUnit.BAR -> this / 14.5038
    TyrePressureUnit.PSI -> this
    TyrePressureUnit.KPA -> this * 6.89476
}

private fun Double.toBarFromPsi(): Double = this / 14.5038

private fun TyrePressureUnit.label(): String = when (this) {
    TyrePressureUnit.BAR -> "bar"
    TyrePressureUnit.PSI -> "psi"
    TyrePressureUnit.KPA -> "kPa"
}

private fun TyrePressureUnit.formatValue(psi: Double): String {
    if (psi < 0.1) return "--"
    return when (this) {
        TyrePressureUnit.BAR -> String.format("%.1f", psi.toDisplayPressure(this))
        TyrePressureUnit.PSI -> String.format("%.1f", psi.toDisplayPressure(this))
        TyrePressureUnit.KPA -> String.format("%.0f", psi.toDisplayPressure(this))
    }
}

@Composable
fun TyrePressureIndicator(
    pressure: Double,           // Always PSI from telemetry
    isFront: Boolean,           // true = front, false = rear
    unit: TyrePressureUnit = TyrePressureUnit.BAR,
    modifier: Modifier = Modifier
) {
    // Alarm thresholds are always evaluated in bar regardless of display unit
    val pressureBar = pressure.toBarFromPsi()
    val recommendedPressure = if (isFront) 2.6 else 2.9 // TODO: make this dynamic via config
    val isNoData = pressure < 0.1
    val isLow    = !isNoData && pressureBar < recommendedPressure - 0.2
    val isHigh   = !isNoData && pressureBar > recommendedPressure + 0.2

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(4.dp),
        color = when {
            isNoData -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.9f)
            isLow    -> AccelerationOrange.copy(alpha = 0.9f)
            isHigh   -> BydErrorRed.copy(alpha = 0.9f)
            else     -> RegenGreen.copy(alpha = 0.9f)
        }
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
        ) {
            Text(
                text = unit.formatValue(pressure),
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
    val idleColor = MaterialTheme.colorScheme.onSurfaceVariant

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
                else -> idleColor
            }
            
            // Create animated flow effect
            val dashPhase = flowOffset * 30f
            
            // batteryEdge is derived from the actual LiquidFillBattery icon geometry:
            //   padding(start = 16.dp), width = 60.dp → right edge at 76.dp from left.
            // This ensures the flow line docks flush into the battery icon regardless
            // of canvas width, instead of relying on the guessed batteryX proportion.
            val batteryEdge = Offset((16.dp + 60.dp).toPx(), batteryY)
            val motorEdge   = Offset(motorX   - motorSize   / 2f, motorY)

            if (isRegenerating) {
                // Motor → Battery (regeneration): arrows travel right-to-left
                drawEnergyFlow(
                    from = batteryEdge,
                    to   = motorEdge,
                    color = flowColor,
                    dashPhase = dashPhase,
                    reverse = true
                )
            } else if (power > 0) {
                // Battery → Motor (acceleration): arrows travel left-to-right
                drawEnergyFlow(
                    from = motorEdge,
                    to   = batteryEdge,
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
    var showStopConfirmDialog by remember { mutableStateOf(false) }

    // ── Stop recording confirmation ────────────────────────────────────────────
    if (showStopConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showStopConfirmDialog = false },
            icon = {
                Icon(
                    Icons.Filled.Stop,
                    contentDescription = null,
                    tint = BydErrorRed,
                    modifier = Modifier.size(32.dp)
                )
            },
            title = { Text("Stop recording?", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "The current trip will be saved and closed. " +
                    "This cannot be undone.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showStopConfirmDialog = false
                        onEndTrip()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BydErrorRed)
                ) { Text("Stop trip") }
            },
            dismissButton = {
                TextButton(onClick = { showStopConfirmDialog = false }) { Text("Keep recording") }
            }
        )
    }

    Card(
        modifier = modifier
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(12.dp)
            ),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
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
                        onCheckedChange = { onToggleAutoDetection() },
                        thumbContent = if (!autoTripDetection) {
                            {
                                // Donut effect: white outer thumb + coloured inner circle
                                Box(
                                    modifier = Modifier
                                        .size(12.dp)
                                        .background(ToggleUncheckedTrack, CircleShape)
                                )
                            }
                        } else null,
                        colors = SwitchDefaults.colors(
                            uncheckedThumbColor  = Color.White,
                            uncheckedTrackColor  = ToggleUncheckedTrack,
                            uncheckedBorderColor = ToggleUncheckedTrack
                        )
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // ── Unified layout: gear status (left, fills) + action button (right, fixed) ──
            // Fixed height so toggling auto on/off doesn't shift the gear/status text vertically
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left: gear circle + status text
                val gearColor = when (telemetry.gear) {
                    "R"  -> AccelerationOrange
                    else -> if (isInTrip) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                }
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    color = Color.Transparent,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            modifier = Modifier.size(28.dp),
                            shape = CircleShape,
                            color = gearColor
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
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = when {
                                isInTrip && telemetry.speed > 0.5              -> "Driving"
                                isInTrip && telemetry.gear in listOf("D", "R") -> "Ready"
                                isInTrip                                        -> "Trip in Progress"
                                telemetry.gear == "D"                           -> "Ready to Drive"
                                telemetry.gear == "R"                           -> "Reverse"
                                telemetry.gear == "P"                           -> "Waiting for Trip..."
                                telemetry.gear == "N"                           -> "Neutral"
                                else                                            -> "Waiting for Trip..."
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                // Right: fixed-width action button — same size regardless of manual/auto
                if (isInTrip || !autoTripDetection) {
                    Button(
                        onClick = if (isInTrip) {
                            { showStopConfirmDialog = true }
                        } else {
                            onStartTrip
                        },
                        modifier = Modifier
                            .width(120.dp)
                            .fillMaxHeight(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isInTrip) BydErrorRed else BydElectricAzure
                        )
                    ) {
                        Icon(
                            imageVector = if (isInTrip) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isInTrip) "Stop" else "Record",
                            fontSize = 15.sp
                        )
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
            color = BydErrorRed
        )
        
        StatCard(
            title = "HV / 12V",
            value = "${telemetry.batteryTotalVoltage} V / ${String.format("%.2f", telemetry.battery12vVoltage)} V",
            subtitle = "Cell: ${String.format("%.3f", telemetry.batteryCellVoltageMin)} - ${String.format("%.3f", telemetry.batteryCellVoltageMax)} V",
            icon = Icons.Filled.Bolt,
            color = BydEcoTealDim
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
            color = BydElectricBlue
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
        modifier = modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(12.dp)
            ),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
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