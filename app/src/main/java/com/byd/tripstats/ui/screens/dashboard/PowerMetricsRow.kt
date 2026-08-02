package com.byd.tripstats.ui.screens.dashboard

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.byd.tripstats.R
import com.byd.tripstats.data.model.VehicleTelemetry
import com.byd.tripstats.data.preferences.PowerMetricId
import com.byd.tripstats.data.preferences.PreferencesManager
import com.byd.tripstats.data.preferences.SocSource
import com.byd.tripstats.data.preferences.convertDistance
import com.byd.tripstats.data.preferences.convertSpeed
import com.byd.tripstats.data.preferences.distanceUnit
import com.byd.tripstats.data.preferences.speedUnit
import com.byd.tripstats.sdk.VehicleTelemetrySnapshot
import com.byd.tripstats.ui.components.RangeDataPoint
import com.byd.tripstats.ui.theme.AccelerationOrange
import com.byd.tripstats.ui.theme.BatteryBlue
import com.byd.tripstats.ui.theme.BydEcoTealDim
import com.byd.tripstats.ui.theme.RegenGreen
import com.byd.tripstats.ui.theme.extendedColors
import com.byd.tripstats.ui.theme.isNeon
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Fixed height of a power-metric tile — deliberately tall so the big numbers breathe. */
private val PowerTileHeight = 150.dp

/**
 * The top power-metric row (Power / Speed / SoC / Range / Distance) rendered as
 * large tiles for the CARDS layout. Each tile has a static, thin accent stripe flush
 * to its left edge and a dynamic subtitle. The Range tile is clickable — it opens the
 * range-projection chart.
 */
@Composable
fun PowerMetricsRow(
    telemetry: VehicleTelemetry,
    vehicleSnapshot: VehicleTelemetrySnapshot?,
    tripDataPoints: List<RangeDataPoint>,
    sessionDistanceKm: Double,
    tripDistanceKm: Double,
    socSource: SocSource,
    editMode: Boolean = false,
    compact: Boolean = false,
    onRangeClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val prefs = remember { PreferencesManager(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val unitSystem by prefs.unitSystem.collectAsState(initial = prefs.getCachedUnitSystem())
    val order by prefs.dashboardPowerOrder.collectAsState(initial = prefs.getCachedDashboardPowerOrder())
    val distanceUnit = unitSystem.distanceUnit
    val speedUnit = unitSystem.speedUnit

    val power = if (telemetry.isCharging && telemetry.chargingPower > 0.1) {
        -telemetry.chargingPower
    } else {
        (vehicleSnapshot?.enginePower ?: telemetry.enginePower).toDouble()
    }
    val displaySpeedKmh = vehicleSnapshot?.directSpeedKmh?.takeIf { it > 0.1 } ?: telemetry.speed
    val isCharging = telemetry.isCharging
    val parked = displaySpeedKmh < 0.5

    val powerDisplay = if (isCharging) String.format("%.1f", power) else power.roundToInt().toString()
    val powerSubtitle = when {
        isCharging || (power < 0.0 && parked) -> stringResource(R.string.power_state_charging)
        power < 0.0 -> stringResource(R.string.power_state_decelerating)
        power > 5.0 -> stringResource(R.string.power_state_accelerating)
        else -> stringResource(R.string.power_state_idle)
    }
    val speedSubtitle = if (parked) stringResource(R.string.speed_state_parked)
    else stringResource(R.string.speed_state_driving)

    val rangeKm = tripDataPoints.lastOrNull()?.projectedRangeKm?.takeIf { it > 0 }
        ?: telemetry.electricDrivingRangeKm.toDouble().takeIf { it > 0 }
        ?: 0.0

    // Battery tile: a tap toggles the view between the settings-chosen SoC and kWh remaining.
    // The Panel/BMS choice stays in Settings → Preferences; this never changes it.
    val showRemainingKwh by prefs.dashboardShowRemainingKwh.collectAsState(
        initial = prefs.getCachedDashboardShowRemainingKwh()
    )
    val batteryMode = batteryReadoutMode(socSource, showRemainingKwh)
    val batteryRemainingKwh = vehicleSnapshot?.powerBatteryRemainPowerEV ?: telemetry.batteryRemainPowerEV
    val (batteryValue, batteryUnit) = batteryReadoutValueUnit(
        batteryMode, telemetry.socPanel, telemetry.soc, batteryRemainingKwh
    )
    val toggleBatteryReadout: () -> Unit = {
        scope.launch { prefs.saveDashboardShowRemainingKwh(!showRemainingKwh) }
    }

    // PHEV: the tile keeps the EV projection as its headline figure, and the
    // subtitle carries the petrol + combined range instead of the generic hint.
    val selectedCar by prefs.selectedCarConfig.collectAsState(initial = prefs.getCachedSelectedCarConfig())
    val fuelRangeKm = phevFuelRangeKm(telemetry, selectedCar)
    val rangeSubtitle = if (fuelRangeKm != null) {
        stringResource(
            R.string.range_subtitle_phev,
            "${unitSystem.convertDistance(fuelRangeKm.toDouble()).toInt()}",
            "${unitSystem.convertDistance(rangeKm + fuelRangeKm).toInt()}"
        )
    } else {
        stringResource(R.string.range_subtitle_projection)
    }

    val tiles: Map<PowerMetricId, PowerTileData> = mapOf(
        PowerMetricId.POWER to PowerTileData(
            label = stringResource(R.string.tab_power),
            value = powerDisplay, unit = "kW", subtitle = powerSubtitle,
            accent = when {
                telemetry.isRegenerating -> RegenGreen
                power > 0 -> AccelerationOrange
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            onClick = null
        ),
        PowerMetricId.SPEED to PowerTileData(
            label = stringResource(R.string.stat_speed),
            value = "${unitSystem.convertSpeed(displaySpeedKmh.toDouble()).toInt()}",
            unit = speedUnit, subtitle = speedSubtitle, accent = BydEcoTealDim, onClick = null
        ),
        PowerMetricId.SOC to PowerTileData(
            label = batteryReadoutLabel(batteryMode),
            value = batteryValue,
            unit = batteryUnit, subtitle = stringResource(R.string.soc_subtitle_usable),
            accent = BatteryBlue, onClick = toggleBatteryReadout
        ),
        PowerMetricId.RANGE to PowerTileData(
            label = stringResource(R.string.stat_range),
            value = "${unitSystem.convertDistance(rangeKm).toInt()}", unit = distanceUnit,
            subtitle = rangeSubtitle,
            accent = MaterialTheme.extendedColors.range, onClick = onRangeClick
        ),
        PowerMetricId.DISTANCE to PowerTileData(
            label = stringResource(R.string.stat_distance),
            value = formatSessionDistance(
                unitSystem.convertDistance(sessionDistanceKm),
                unitSystem.convertDistance(tripDistanceKm)
            ),
            unit = distanceUnit, subtitle = stringResource(R.string.distance_subtitle_session),
            accent = MaterialTheme.colorScheme.secondary, onClick = null
        ),
    )

    when {
        editMode -> PowerEditRow(
            order = order,
            tiles = tiles,
            onReorder = { scope.launch { prefs.saveDashboardPowerOrder(it) } },
            modifier = modifier.fillMaxWidth().height(PowerTileHeight)
        )
        // Narrow (split-screen): ALL power metrics on one short row, value + unit only,
        // so the chart below gets the most possible height.
        compact -> Row(
            modifier = modifier.fillMaxWidth().height(52.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            order.forEach { id ->
                tiles[id]?.let { t ->
                    PowerMetricCard(
                        label = t.label, value = t.value, unit = t.unit, subtitle = t.subtitle,
                        accent = t.accent, onClick = t.onClick, compact = true, valueOnly = true,
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                }
            }
        }
        else -> Row(
            modifier = modifier.fillMaxWidth().height(PowerTileHeight),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            order.forEach { id ->
                tiles[id]?.let { t ->
                    PowerMetricCard(
                        label = t.label, value = t.value, unit = t.unit, subtitle = t.subtitle,
                        accent = t.accent, onClick = t.onClick, modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

private data class PowerTileData(
    val label: String,
    val value: String,
    val unit: String,
    val subtitle: String,
    val accent: Color,
    val onClick: (() -> Unit)?,
)

/**
 * Edit mode for the power row: tiles wobble and can be dragged to reorder (never
 * hideable). A 1-D version of the split-grid drag — the target index is the finger's
 * absolute column, so fast drags land correctly and the rest spring aside.
 */
@Composable
private fun PowerEditRow(
    order: List<PowerMetricId>,
    tiles: Map<PowerMetricId, PowerTileData>,
    onReorder: (List<PowerMetricId>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val gapPx = with(density) { 12.dp.toPx() }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var dragId by remember { mutableStateOf<PowerMetricId?>(null) }
    var dragX by remember { mutableStateOf(0f) }
    val latestOrder by rememberUpdatedState(order)

    val n = order.size
    val w = size.width.toFloat()
    val cellW = if (w > 0) (w - (n - 1) * gapPx) / n else 0f
    fun slotX(i: Int) = i * (cellW + gapPx)
    fun indexAt(x: Float) = (x / (cellW + gapPx)).toInt().coerceIn(0, n - 1)

    Box(
        modifier = modifier
            .onSizeChanged { size = it }
            .pointerInput(size, n) {
                var grab = 0f
                detectDragGestures(
                    onDragStart = { start ->
                        if (cellW > 0f) {
                            val idx = indexAt(start.x)
                            dragId = latestOrder.getOrNull(idx)
                            grab = start.x - slotX(idx)
                            dragX = start.x - grab
                        }
                    },
                    onDragEnd = { dragId = null },
                    onDragCancel = { dragId = null },
                    onDrag = { change, _ ->
                        val id = dragId ?: return@detectDragGestures
                        change.consume()
                        dragX = change.position.x - grab
                        val target = indexAt(dragX + cellW / 2f)
                        val cur = latestOrder.indexOf(id)
                        if (cur >= 0 && cur != target) {
                            onReorder(latestOrder.toMutableList().apply { add(target, removeAt(cur)) })
                        }
                    }
                )
            }
    ) {
        if (cellW > 0f) {
            val cellWDp = with(density) { cellW.toDp() }
            order.forEachIndexed { index, id ->
                key(id) {
                    val isDragging = id == dragId
                    val x by animateFloatAsState(
                        targetValue = if (isDragging) dragX else slotX(index),
                        animationSpec = if (isDragging) snap() else spring(stiffness = Spring.StiffnessMediumLow),
                        label = "powerTileX"
                    )
                    val wobble = rememberInfiniteTransition(label = "wobble")
                    val angle by wobble.animateFloat(
                        initialValue = -1.3f,
                        targetValue = 1.3f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(170, easing = LinearEasing),
                            repeatMode = RepeatMode.Reverse,
                            initialStartOffset = StartOffset(index * 45)
                        ),
                        label = "angle"
                    )
                    Box(
                        modifier = Modifier
                            .offset { IntOffset(x.roundToInt(), 0) }
                            .width(cellWDp)
                            .fillMaxHeight()
                            .zIndex(if (isDragging) 1f else 0f)
                            .graphicsLayer {
                                if (isDragging) { scaleX = 1.05f; scaleY = 1.05f } else { rotationZ = angle }
                            }
                    ) {
                        tiles[id]?.let { t ->
                            PowerMetricCard(
                                label = t.label, value = t.value, unit = t.unit, subtitle = t.subtitle,
                                accent = t.accent, onClick = null, modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PowerMetricCard(
    label: String,
    value: String,
    unit: String,
    subtitle: String,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    compact: Boolean = false,
    valueOnly: Boolean = false,
) {
    val neon = MaterialTheme.isNeon
    val glowStyle = if (neon) TextStyle(
        shadow = Shadow(color = accent.copy(alpha = 0.9f), offset = Offset.Zero, blurRadius = 32f)
    ) else TextStyle.Default
    Card(
        modifier = modifier
            .fillMaxHeight()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        shape = RoundedCornerShape(14.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Bordered content. Modifier.border draws over its own children, so the
            // accent stripe below is a later sibling — it paints on top of the border,
            // sitting truly flush to the card's left edge.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .border(
                        width = if (neon) 1.5.dp else 1.dp,
                        color = if (neon) accent.copy(alpha = 0.45f)
                        else MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(14.dp)
                    )
            ) {
                if (valueOnly) {
                    // Split-screen single row: value + unit only, centred (no label).
                    Row(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = value,
                            fontSize = 19.sp,
                            fontWeight = FontWeight.Bold,
                            color = accent,
                            maxLines = 1,
                            style = glowStyle
                        )
                        Spacer(Modifier.width(2.dp))
                        Text(
                            text = unit,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                } else if (compact) {
                    // Dense single line: LABEL … value unit (no subtitle) to save height.
                    Row(
                        modifier = Modifier.fillMaxSize().padding(start = 18.dp, end = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = label.uppercase(),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = value,
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Bold,
                            color = accent,
                            maxLines = 1,
                            style = glowStyle
                        )
                        Spacer(Modifier.width(3.dp))
                        Text(
                            text = unit,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(start = 20.dp, end = 16.dp, top = 14.dp, bottom = 14.dp),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = label.uppercase(),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(
                                text = value,
                                fontSize = 46.sp,
                                fontWeight = FontWeight.Bold,
                                color = accent,
                                maxLines = 1,
                                style = glowStyle
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                text = unit,
                                fontSize = 16.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                        }
                        Text(
                            text = subtitle,
                            fontSize = 18.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
            }
            // Static accent stripe, flush to the very left edge, on top of the border.
            // In Neon it gets a soft outward glow halo (gradient, no blur — works on any
            // Android version on the head unit).
            Box(
                modifier = Modifier.align(Alignment.CenterStart).fillMaxHeight()
            ) {
                if (neon) {
                    Box(
                        modifier = Modifier
                            .width(18.dp)
                            .fillMaxHeight()
                            .background(
                                Brush.horizontalGradient(
                                    listOf(accent.copy(alpha = 0.55f), Color.Transparent)
                                )
                            )
                    )
                }
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .fillMaxHeight()
                        .background(accent)
                )
            }
        }
    }
}

private fun formatSessionDistance(segmentKm: Double, cumulativeKm: Double): String {
    val seg = segmentKm.coerceAtLeast(0.0)
    val cum = cumulativeKm.coerceAtLeast(0.0)
    return if (cum > 0 && kotlin.math.abs(seg.toInt() - cum.toInt()) >= 1)
        "%.1f (%.1f)".format(seg, cum)
    else
        "%.1f".format(maxOf(seg, cum))
}
