package com.byd.tripstats.ui.screens.dashboard

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch
import com.byd.tripstats.R
import com.byd.tripstats.data.model.VehicleTelemetry
import com.byd.tripstats.data.preferences.PreferencesManager
import com.byd.tripstats.data.preferences.SocSource
import com.byd.tripstats.data.preferences.convertDistance
import com.byd.tripstats.data.preferences.convertSpeed
import com.byd.tripstats.data.preferences.distanceUnit
import com.byd.tripstats.data.preferences.speedUnit
import com.byd.tripstats.data.preferences.isImperial
import com.byd.tripstats.ui.components.ConsumptionChartExpanded
import com.byd.tripstats.ui.components.ConsumptionThumbnail
import com.byd.tripstats.ui.components.RangeDataPoint
import com.byd.tripstats.ui.components.RangeProjectionChart
import com.byd.tripstats.ui.theme.AccelerationOrange
import com.byd.tripstats.ui.theme.BydEcoTealDim
import com.byd.tripstats.ui.theme.BatteryBlue
import com.byd.tripstats.ui.theme.RegenGreen
import com.byd.tripstats.ui.theme.extendedColors
import com.byd.tripstats.ui.components.LiquidFillBattery
import com.byd.tripstats.ui.viewmodel.DashboardViewModel
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

private fun formatDistanceDisplay(segmentKm: Double, cumulativeKm: Double, showDecimal: Boolean = false): String {
    val segRaw = segmentKm.coerceAtLeast(0.0)
    val cumRaw = cumulativeKm.coerceAtLeast(0.0)
    val segment = segRaw.toInt()
    val cumulative = cumRaw.toInt()
    return if (cumulative > 0 && kotlin.math.abs(segment - cumulative) >= 1) {
        if (showDecimal) "%.1f (%.1f)".format(segRaw, cumRaw)
        else "$segment, ($cumulative)"
    } else {
        if (showDecimal) "%.1f".format(maxOf(segRaw, cumRaw))
        else "${maxOf(segment, cumulative)}"
    }
}

private fun formatDistanceValue(km: Double): String = "${km.toInt()}"

@Composable
fun EnergyFlowDiagram(
    telemetry: VehicleTelemetry,
    liveSpeedKmh: Double? = null,
    livePowerKw: Int? = null,
    tripDataPoints: List<RangeDataPoint>,
    weeklyEfficiency: List<DashboardViewModel.DailyEfficiency>,
    monthlyEfficiency: List<DashboardViewModel.DailyEfficiency>,
    yearlyEfficiency: List<DashboardViewModel.DailyEfficiency>,
    sessionDistanceKm: Double = 0.0,
    tripDistanceKm: Double = 0.0,
    consumptionExpanded: Boolean = false,
    onConsumptionExpand: () -> Unit = {},
    onConsumptionClose: () -> Unit = {},
    dashboardIconsEnabled: Boolean = true,
    socSource: SocSource = SocSource.PANEL,
    activeRangeModel: DashboardViewModel.RangeModel = DashboardViewModel.RangeModel.BASELINE,
    onNavigateToCharging: () -> Unit = {},
    onShowTyreDialog: () -> Unit = {},
    tyreUnit: TyrePressureUnit = TyrePressureUnit.BAR,
    modifier: Modifier = Modifier
) {
    val power = if (telemetry.isCharging && telemetry.chargingPower > 0.1) {
        -telemetry.chargingPower
    } else {
        (livePowerKw ?: telemetry.enginePower).toDouble()
    }
    val displaySpeedKmh = liveSpeedKmh?.takeIf { it > 0.1 } ?: telemetry.speed
    val isRegenerating = telemetry.isRegenerating
    val isCharging = telemetry.isCharging
    val hasActiveEnergyFlow = abs(power) > 1.0 || isCharging
    val isFullScreen = LocalConfiguration.current.screenWidthDp >= 840

    val context = LocalContext.current
    val appPrefs = remember { PreferencesManager(context.applicationContext) }

    val unitSystem by appPrefs.unitSystem.collectAsState(initial = appPrefs.getCachedUnitSystem())
    val distanceUnit = unitSystem.distanceUnit
    val speedUnit = unitSystem.speedUnit

    // Battery readout: a tap toggles the view between the settings-chosen SoC and kWh remaining.
    // The Panel/BMS choice stays in Settings → Preferences; this never changes it.
    val scope = rememberCoroutineScope()
    val showRemainingKwh by appPrefs.dashboardShowRemainingKwh.collectAsState(
        initial = appPrefs.getCachedDashboardShowRemainingKwh()
    )
    val batteryMode = batteryReadoutMode(socSource, showRemainingKwh)
    val (batteryValue, batteryUnit) = batteryReadoutValueUnit(
        batteryMode, telemetry.socPanel, telemetry.soc, telemetry.batteryRemainPowerEV
    )
    val toggleBatteryReadout: () -> Unit = {
        scope.launch { appPrefs.saveDashboardShowRemainingKwh(!showRemainingKwh) }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateFlow.collectAsState()
    val isResumed = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    val flowOffsetAnim = remember { Animatable(0f) }
    LaunchedEffect(isResumed, dashboardIconsEnabled, hasActiveEnergyFlow) {
        if (dashboardIconsEnabled && isResumed && hasActiveEnergyFlow) {
            while (true) {
                delay(50L)
                val next = (flowOffsetAnim.value + 0.05f) % 1f
                flowOffsetAnim.snapTo(next)
            }
        }
    }
    val flowOffset = if (dashboardIconsEnabled && hasActiveEnergyFlow) flowOffsetAnim.value else 0f

    val awdBitmap: ImageBitmap? = if (dashboardIconsEnabled) {
        ImageBitmap.imageResource(id = R.drawable.awd)
    } else null

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
    ) {
        if (consumptionExpanded) {
            ConsumptionChartExpanded(
                weeklyData  = weeklyEfficiency,
                monthlyData = monthlyEfficiency,
                yearlyData  = yearlyEfficiency,
                onClose     = onConsumptionClose,
                modifier    = Modifier.fillMaxSize()
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp)
            ) {
                if (dashboardIconsEnabled && awdBitmap != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(110.dp)
                    ) {
                        EnergyFlowCanvas(
                            power = power,
                            isRegenerating = isRegenerating,
                            isCharging = isCharging,
                            flowOffset = { flowOffset },
                            animationsEnabled = true
                        )

                        LiquidFillBattery(
                            soc = if (socSource == SocSource.PANEL) telemetry.socPanel.toFloat() else telemetry.soc.toFloat(),
                            isCharging = isCharging,
                            animationsEnabled = true,
                            width = 60.dp,
                            height = 100.dp,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(start = 16.dp)
                                .clickable { onNavigateToCharging() }
                        )

                        Box(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 12.dp)
                        ) {
                            Image(
                                bitmap = awdBitmap,
                                contentDescription = stringResource(R.string.awd_tap_hint),
                                modifier = Modifier
                                    .size(90.dp)
                                    .clickable { onShowTyreDialog() },
                                contentScale = ContentScale.Fit,
                                filterQuality = FilterQuality.High
                            )

                            TyrePressureIndicator(
                                pressure = telemetry.tyrePressureLF,
                                tempC = telemetry.tyreTempLF.takeIf { it > 0 },
                                unit = tyreUnit,
                                isFront = true,
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .offset(x = (-24).dp, y = (-12).dp)
                            )

                            TyrePressureIndicator(
                                pressure = telemetry.tyrePressureRF,
                                tempC = telemetry.tyreTempRF.takeIf { it > 0 },
                                unit = tyreUnit,
                                isFront = true,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = 26.dp, y = (-12).dp)
                            )

                            TyrePressureIndicator(
                                pressure = telemetry.tyrePressureLR,
                                tempC = telemetry.tyreTempLR.takeIf { it > 0 },
                                unit = tyreUnit,
                                isFront = false,
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .offset(x = (-24).dp, y = (14).dp)
                            )

                            TyrePressureIndicator(
                                pressure = telemetry.tyrePressureRR,
                                tempC = telemetry.tyreTempRR.takeIf { it > 0 },
                                unit = tyreUnit,
                                isFront = false,
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .offset(x = 26.dp, y = (14).dp)
                            )
                        }

                        Column(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .width(120.dp)
                                .fillMaxHeight()
                                .background(
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f),
                                    RoundedCornerShape(8.dp)
                                )
                                .clickable { onConsumptionExpand() }
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
                                text = stringResource(R.string.nav_consumption_charts),
                                fontSize = 9.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.height(2.dp))
                            ConsumptionThumbnail(
                                data = monthlyEfficiency,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                            )
                        }
                    }
                }

                Box(modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                ) {
                    RangeProjectionChart(
                        dataPoints = tripDataPoints,
                        activeRangeModel = activeRangeModel,
                        liveSoc = if (socSource == SocSource.PANEL) telemetry.socPanel.toDouble() else telemetry.soc,
                        liveElectricRangeKm = telemetry.electricDrivingRangeKm,
                        useImperial = unitSystem.isImperial,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    val powerDisplay = if (isCharging) {
                        String.format("%.1f", power)
                    } else {
                        power.roundToInt().toString()
                    }
                    PowerMetric(
                        label = stringResource(R.string.tab_power),
                        value = powerDisplay,
                        unit = "kW",
                        color = when {
                            isRegenerating -> RegenGreen
                            power > 0 -> AccelerationOrange
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    PowerMetric(
                        label = stringResource(R.string.stat_speed),
                        value = "${unitSystem.convertSpeed(displaySpeedKmh.toDouble()).toInt()}",
                        unit = speedUnit,
                        color = BydEcoTealDim
                    )
                    PowerMetric(
                        label = batteryReadoutLabel(batteryMode),
                        value = batteryValue,
                        unit = batteryUnit,
                        color = BatteryBlue,
                        onClick = toggleBatteryReadout
                    )
                    PowerMetric(
                        label = stringResource(R.string.stat_range),
                        value = run {
                            val km = tripDataPoints.lastOrNull()?.projectedRangeKm?.takeIf { it > 0 }
                                ?: telemetry.electricDrivingRangeKm.toDouble().takeIf { it > 0 }
                                ?: 0.0
                            formatDistanceValue(unitSystem.convertDistance(km))
                        },
                        unit = distanceUnit,
                        color = MaterialTheme.extendedColors.range
                    )
                    // PHEV: petrol range as its own metric next to the EV range.
                    val selectedCarForFuel by appPrefs.selectedCarConfig
                        .collectAsState(initial = appPrefs.getCachedSelectedCarConfig())
                    phevFuelRangeKm(telemetry, selectedCarForFuel)?.let { fuelKm ->
                        PowerMetric(
                            label = stringResource(R.string.stat_fuel_range),
                            value = "${unitSystem.convertDistance(fuelKm.toDouble()).toInt()}",
                            unit = distanceUnit,
                            color = AccelerationOrange
                        )
                    }
                    PowerMetric(
                        label = stringResource(R.string.stat_distance),
                        value = formatDistanceDisplay(unitSystem.convertDistance(sessionDistanceKm), unitSystem.convertDistance(tripDistanceKm), isFullScreen),
                        unit = distanceUnit,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }
    }
}

@Composable
fun EnergyFlowCanvas(
    power: Double,
    isRegenerating: Boolean,
    isCharging: Boolean,
    flowOffset: () -> Float,
    animationsEnabled: Boolean
) {
    val idleColor = MaterialTheme.colorScheme.onSurfaceVariant

    Canvas(modifier = Modifier.fillMaxSize()) {
        val topY = size.height * 0.3f + 30f
        val batteryY = topY
        val motorX = size.width * 0.5f
        val motorY = topY
        val motorSize = 150f

        if (abs(power) > 1 && !isCharging) {
            val flowColor = when {
                isRegenerating -> RegenGreen
                power > 0 -> AccelerationOrange
                else -> idleColor
            }

            val dashPhase = if (animationsEnabled) flowOffset() * 50f else 0f

            val batteryEdge = Offset((16.dp + 60.dp).toPx(), batteryY)
            val motorEdge   = Offset(motorX - motorSize / 2f, motorY)

            if (isRegenerating) {
                drawEnergyFlow(
                    from = batteryEdge,
                    to   = motorEdge,
                    color = flowColor,
                    dashPhase = dashPhase,
                    reverse = true,
                    animated = animationsEnabled
                )
            } else if (power > 0) {
                drawEnergyFlow(
                    from = motorEdge,
                    to   = batteryEdge,
                    color = flowColor,
                    dashPhase = dashPhase,
                    reverse = true,
                    animated = animationsEnabled
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
    reverse: Boolean,
    animated: Boolean
) {
    drawLine(color = color, start = from, end = to, strokeWidth = 6f)
    if (!animated) return

    val dx = to.x - from.x
    val dy = to.y - from.y
    val lineLength = kotlin.math.sqrt(dx * dx + dy * dy)
    if (lineLength == 0f) return

    val ux = dx / lineLength
    val uy = dy / lineLength
    val cosA = 0.921f; val sinA = 0.389f
    val dir = if (!reverse) 1f else -1f
    val wx1 = dir * (-ux * cosA + uy * sinA) * 10f
    val wy1 = dir * (-uy * cosA - ux * sinA) * 10f
    val wx2 = dir * (-ux * cosA - uy * sinA) * 10f
    val wy2 = dir * ( ux * sinA - uy * cosA) * 10f

    val arrowSpacing = 50f
    val numArrows = (lineLength / arrowSpacing).toInt() + 2
    val phase = dashPhase % arrowSpacing
    val path = Path()

    for (i in 0 until numArrows) {
        val position = i * arrowSpacing - phase
        if (position < 0f || position > lineLength) continue
        val progress = position / lineLength
        val ax = from.x + dx * progress
        val ay = from.y + dy * progress
        path.reset()
        path.moveTo(ax + wx1, ay + wy1)
        path.lineTo(ax, ay)
        path.lineTo(ax + wx2, ay + wy2)
        drawPath(path = path, color = color, style = Stroke(width = 3f, cap = StrokeCap.Round))
    }
}
