package com.byd.tripstats.ui.screens.dashboard

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import com.byd.tripstats.R
import com.byd.tripstats.data.model.VehicleTelemetry
import com.byd.tripstats.data.preferences.DashboardCardId
import com.byd.tripstats.data.preferences.PreferencesManager
import com.byd.tripstats.data.preferences.SocSource
import com.byd.tripstats.data.preferences.UnitSystem
import com.byd.tripstats.data.preferences.isImperial
import com.byd.tripstats.sdk.VehicleTelemetrySnapshot
import com.byd.tripstats.ui.components.RangeDataPoint
import com.byd.tripstats.ui.components.RangeProjectionChart
import com.byd.tripstats.ui.theme.extendedColors
import com.byd.tripstats.ui.theme.isNeon
import com.byd.tripstats.ui.viewmodel.DashboardViewModel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The Pro-only CARDS dashboard: a large power-metric row, a customisable middle
 * section, and the trip-tracking bar. The range-projection chart is pinned in the
 * centre of the middle section spanning both rows, flanked by two cards on each side
 * per row. Tapping the chart (or the Range power tile) opens the full-size chart. The
 * chart is modular too — hide it and the cards reclaim the full width; it is then only
 * reachable via the Range tile.
 */
@Composable
fun CardsDashboard(
    telemetry: VehicleTelemetry,
    vehicleSnapshot: VehicleTelemetrySnapshot?,
    tripDataPoints: List<RangeDataPoint>,
    activeRangeModel: DashboardViewModel.RangeModel,
    socSource: SocSource,
    editMode: Boolean,
    sessionDistanceKm: Double,
    tripDistanceKm: Double,
    distanceSinceChargeKm: Double? = null,
    tyreUnit: TyrePressureUnit,
    isInTrip: Boolean,
    autoTripDetection: Boolean,
    onStartTrip: () -> Unit,
    onEndTrip: () -> Unit,
    onToggleAutoDetection: () -> Unit,
    liveSessionStartMs: Long?,
    liveOffStateMs: Long,
    liveOdometerDistanceKm: Double,
    liveAccumulatedKwh: Double,
    unitSystem: UnitSystem,
    onNavigateToBatteryDegradation: () -> Unit,
    onShowBattery12vHistory: () -> Unit,
    onShowTyreDialog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val prefs = remember { PreferencesManager(context.applicationContext) }
    val scope = rememberCoroutineScope()

    val persistedOrder by prefs.dashboardCardOrder.collectAsState(initial = prefs.getCachedDashboardCardOrder())
    val persistedHidden by prefs.dashboardHiddenCards.collectAsState(initial = prefs.getCachedDashboardHiddenCards())
    val persistedChartHidden by prefs.dashboardChartHidden.collectAsState(initial = prefs.getCachedDashboardChartHidden())

    // Working copy edited in place while in edit mode; seeded from prefs on entry.
    var workingOrder by remember { mutableStateOf(persistedOrder) }
    var workingHidden by remember { mutableStateOf(persistedHidden) }
    var workingChartHidden by remember { mutableStateOf(persistedChartHidden) }
    LaunchedEffect(editMode) {
        if (editMode) {
            workingOrder = persistedOrder
            workingHidden = persistedHidden
            workingChartHidden = persistedChartHidden
        }
    }

    fun persistCards(order: List<DashboardCardId>, hidden: Set<DashboardCardId>) {
        scope.launch { prefs.saveDashboardCardLayout(order, hidden) }
    }

    var showChart by remember { mutableStateOf(false) }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
    // Narrow windows (split-screen) shrink all text proportionally so nothing wraps or
    // clips; full-landscape width keeps the font scale at 1 (fonts unchanged). Only
    // fontScale is touched — dp layout and drag geometry are unaffected. Scale = this
    // window's width relative to the physical display's long edge (= full landscape
    // width), so it's device-independent: fullscreen ≈ 1.0, half-split ≈ 0.5.
    val d = LocalDensity.current
    val dm = android.content.res.Resources.getSystem().displayMetrics
    val fullPx = maxOf(dm.widthPixels, dm.heightPixels).toFloat()
    val appPx = with(d) { maxWidth.toPx() }
    val ratio = if (fullPx > 0f) appPx / fullPx else 1f
    val isNarrow = ratio < 0.72f

    if (isNarrow && !editMode) {
        // Split-screen reflow: a single height-filling column (no scroll). Power tiles and
        // cards are single-line at a fixed short height; the chart takes ALL the remaining
        // space via weight(1f), so it's as tall as possible. Trip controls sit at the bottom.
        val visible = persistedOrder.filter { it !in persistedHidden }
        CompositionLocalProvider(LocalDashboardCardDense provides true) {
            Column(
                modifier = Modifier.fillMaxSize().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PowerMetricsRow(
                    telemetry = telemetry,
                    vehicleSnapshot = vehicleSnapshot,
                    tripDataPoints = tripDataPoints,
                    sessionDistanceKm = sessionDistanceKm,
                    tripDistanceKm = tripDistanceKm,
                    distanceSinceChargeKm = distanceSinceChargeKm,
                    socSource = socSource,
                    compact = true,
                    onRangeClick = { showChart = true },
                    modifier = Modifier.fillMaxWidth()
                )
                if (!persistedChartHidden) {
                    ChartTile(
                        telemetry = telemetry,
                        tripDataPoints = tripDataPoints,
                        activeRangeModel = activeRangeModel,
                        socSource = socSource,
                        unitSystem = unitSystem,
                        editMode = false,
                        hidden = false,
                        onClick = { showChart = true },
                        onToggleHidden = {},
                        compact = true,
                        modifier = Modifier.fillMaxWidth().weight(1f)
                    )
                }
                visible.chunked(2).forEach { pair ->
                    Row(
                        modifier = Modifier.fillMaxWidth().height(46.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        pair.forEach { id ->
                            DashboardStatCard(
                                id = id,
                                telemetry = telemetry,
                                vehicleSnapshot = vehicleSnapshot,
                                tyreUnit = tyreUnit,
                                onNavigateToBatteryDegradation = onNavigateToBatteryDegradation,
                                onShowBattery12vHistory = onShowBattery12vHistory,
                                onShowTyreDialog = onShowTyreDialog,
                                modifier = Modifier.weight(1f).fillMaxHeight()
                            )
                        }
                    }
                }
                TripControls(
                    telemetry = telemetry,
                    liveGear = vehicleSnapshot?.gear,
                    isInTrip = isInTrip,
                    autoTripDetection = autoTripDetection,
                    onStartTrip = onStartTrip,
                    onEndTrip = onEndTrip,
                    onToggleAutoDetection = onToggleAutoDetection,
                    liveSessionStartMs = liveSessionStartMs,
                    liveOffStateMs = liveOffStateMs,
                    liveDistanceKm = tripDistanceKm,
                    liveOdometerDistanceKm = liveOdometerDistanceKm,
                    liveAccumulatedKwh = liveAccumulatedKwh,
                    unitSystem = unitSystem,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    } else {
    // Full-landscape (and narrow while editing): the fixed wide layout. When editing in
    // a narrow window we still shrink the fonts so the wide grid fits for the drag UI.
    val widthScale = if (ratio > 0.85f) 1f else ratio.coerceIn(0.6f, 1f)
    CompositionLocalProvider(LocalDensity provides Density(d.density, d.fontScale * widthScale)) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        PowerMetricsRow(
            telemetry = telemetry,
            vehicleSnapshot = vehicleSnapshot,
            tripDataPoints = tripDataPoints,
            sessionDistanceKm = sessionDistanceKm,
            tripDistanceKm = tripDistanceKm,
            distanceSinceChargeKm = distanceSinceChargeKm,
            socSource = socSource,
            editMode = editMode,
            onRangeClick = { showChart = true },
            modifier = Modifier.fillMaxWidth()
        )

        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            val chartContent: @Composable (Modifier, Boolean, () -> Unit) -> Unit = { m, editing, onToggle ->
                ChartTile(
                    telemetry = telemetry,
                    tripDataPoints = tripDataPoints,
                    activeRangeModel = activeRangeModel,
                    socSource = socSource,
                    unitSystem = unitSystem,
                    editMode = editing,
                    hidden = workingChartHidden,
                    onClick = { showChart = true },
                    onToggleHidden = onToggle,
                    modifier = m
                )
            }

            when {
                editMode -> CompositionLocalProvider(LocalDashboardCardCompact provides true) {
                    SplitEditGrid(
                        order = workingOrder,
                        hidden = workingHidden,
                        telemetry = telemetry,
                        vehicleSnapshot = vehicleSnapshot,
                        tyreUnit = tyreUnit,
                        onReorder = { newOrder ->
                            workingOrder = newOrder
                            persistCards(workingOrder, workingHidden)
                        },
                        onToggleHidden = { id ->
                            workingHidden = if (id in workingHidden) workingHidden - id else workingHidden + id
                            persistCards(workingOrder, workingHidden)
                        },
                        chart = { m -> chartContent(m, true) {
                            workingChartHidden = !workingChartHidden
                            scope.launch { prefs.saveDashboardChartHidden(workingChartHidden) }
                        } }
                    )
                }

                !persistedChartHidden -> CompositionLocalProvider(LocalDashboardCardCompact provides true) {
                    val visible = persistedOrder.filter { it !in persistedHidden }
                    SplitViewGrid(
                        visible = visible,
                        telemetry = telemetry,
                        vehicleSnapshot = vehicleSnapshot,
                        tyreUnit = tyreUnit,
                        onNavigateToBatteryDegradation = onNavigateToBatteryDegradation,
                        onShowBattery12vHistory = onShowBattery12vHistory,
                        onShowTyreDialog = onShowTyreDialog,
                        chart = { m -> chartContent(m, false) {} }
                    )
                }

                else -> {
                    val visible = persistedOrder.filter { it !in persistedHidden }
                    ModularCardGrid(
                        visible = visible,
                        telemetry = telemetry,
                        vehicleSnapshot = vehicleSnapshot,
                        tyreUnit = tyreUnit,
                        onNavigateToBatteryDegradation = onNavigateToBatteryDegradation,
                        onShowBattery12vHistory = onShowBattery12vHistory,
                        onShowTyreDialog = onShowTyreDialog
                    )
                }
            }
        }

        TripControls(
            telemetry = telemetry,
            liveGear = vehicleSnapshot?.gear,
            isInTrip = isInTrip,
            autoTripDetection = autoTripDetection,
            onStartTrip = onStartTrip,
            onEndTrip = onEndTrip,
            onToggleAutoDetection = onToggleAutoDetection,
            liveSessionStartMs = liveSessionStartMs,
            liveOffStateMs = liveOffStateMs,
            liveDistanceKm = tripDistanceKm,
            liveOdometerDistanceKm = liveOdometerDistanceKm,
            liveAccumulatedKwh = liveAccumulatedKwh,
            unitSystem = unitSystem,
            modifier = Modifier.fillMaxWidth()
        )
    }
    }
    }
    }

    if (showChart) {
        Dialog(
            onDismissRequest = { showChart = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(0.92f).fillMaxHeight(0.82f),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.range_projection_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        TextButton(onClick = { showChart = false }) {
                            Text(stringResource(R.string.close))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    RangeProjectionChart(
                        dataPoints = tripDataPoints,
                        activeRangeModel = activeRangeModel,
                        liveSoc = if (socSource == SocSource.PANEL) telemetry.socPanel.toDouble() else telemetry.soc,
                        liveElectricRangeKm = telemetry.electricDrivingRangeKm,
                        useImperial = unitSystem.isImperial,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

/** Small pinned chart tile; tap to open the full-size chart. Hideable in edit mode. */
@Composable
private fun ChartTile(
    telemetry: VehicleTelemetry,
    tripDataPoints: List<RangeDataPoint>,
    activeRangeModel: DashboardViewModel.RangeModel,
    socSource: SocSource,
    unitSystem: UnitSystem,
    editMode: Boolean,
    hidden: Boolean,
    onClick: () -> Unit,
    onToggleHidden: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val neon = MaterialTheme.isNeon
    Box(modifier = modifier) {
        Card(
            modifier = Modifier
                .fillMaxSize()
                .alpha(if (editMode && hidden) 0.35f else 1f)
                .border(
                    width = if (neon) 1.5.dp else 1.dp,
                    color = if (neon) MaterialTheme.extendedColors.range.copy(alpha = 0.45f)
                    else MaterialTheme.colorScheme.outlineVariant,
                    shape = RoundedCornerShape(14.dp)
                )
                .then(if (!editMode) Modifier.clickable { onClick() } else Modifier),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(if (compact) 6.dp else 12.dp)) {
                // In split-screen the title + all chart chrome are dropped so the plot
                // gets the full height; the full view (tap Range) keeps everything.
                if (!compact) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ShowChart,
                            contentDescription = null,
                            tint = MaterialTheme.extendedColors.range,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.range_projection_title),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                }
                RangeProjectionChart(
                    dataPoints = tripDataPoints,
                    activeRangeModel = activeRangeModel,
                    liveSoc = if (socSource == SocSource.PANEL) telemetry.socPanel.toDouble() else telemetry.soc,
                    liveElectricRangeKm = telemetry.electricDrivingRangeKm,
                    useImperial = unitSystem.isImperial,
                    compact = compact,
                    modifier = Modifier.fillMaxWidth().weight(1f)
                )
            }
        }
        if (editMode) {
            HideBadge(
                hidden = hidden,
                onToggle = onToggleHidden,
                modifier = Modifier.align(Alignment.TopEnd)
            )
        }
    }
}

/** Circular corner badge: remove (hide) when visible, add (restore) when hidden. */
@Composable
private fun HideBadge(hidden: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onToggle,
        shape = CircleShape,
        color = if (hidden) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.errorContainer,
        modifier = modifier.padding(6.dp).size(28.dp)
    ) {
        Icon(
            imageVector = if (hidden) Icons.Filled.Add else Icons.Filled.Remove,
            contentDescription = null,
            tint = if (hidden) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(4.dp)
        )
    }
}

/** A vertically-stacked 2-column grid of cards, filling its space (used for each flank). */
@Composable
private fun SideGrid(
    cards: List<DashboardCardId>,
    telemetry: VehicleTelemetry,
    vehicleSnapshot: VehicleTelemetrySnapshot?,
    tyreUnit: TyrePressureUnit,
    onNavigateToBatteryDegradation: () -> Unit,
    onShowBattery12vHistory: () -> Unit,
    onShowTyreDialog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        cards.chunked(2).forEach { pair ->
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                pair.forEach { id ->
                    DashboardStatCard(
                        id = id,
                        telemetry = telemetry,
                        vehicleSnapshot = vehicleSnapshot,
                        tyreUnit = tyreUnit,
                        onNavigateToBatteryDegradation = onNavigateToBatteryDegradation,
                        onShowBattery12vHistory = onShowBattery12vHistory,
                        onShowTyreDialog = onShowTyreDialog,
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** View mode with the chart visible: [left cards] · [chart] · [right cards], each a third. */
@Composable
private fun SplitViewGrid(
    visible: List<DashboardCardId>,
    telemetry: VehicleTelemetry,
    vehicleSnapshot: VehicleTelemetrySnapshot?,
    tyreUnit: TyrePressureUnit,
    onNavigateToBatteryDegradation: () -> Unit,
    onShowBattery12vHistory: () -> Unit,
    onShowTyreDialog: () -> Unit,
    chart: @Composable (Modifier) -> Unit,
) {
    val half = (visible.size + 1) / 2
    val left = visible.take(half)
    val right = visible.drop(half)

    Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SideGrid(
            cards = left,
            telemetry = telemetry,
            vehicleSnapshot = vehicleSnapshot,
            tyreUnit = tyreUnit,
            onNavigateToBatteryDegradation = onNavigateToBatteryDegradation,
            onShowBattery12vHistory = onShowBattery12vHistory,
            onShowTyreDialog = onShowTyreDialog,
            modifier = Modifier.weight(1f).fillMaxHeight()
        )
        chart(Modifier.weight(1f).fillMaxHeight())
        SideGrid(
            cards = right,
            telemetry = telemetry,
            vehicleSnapshot = vehicleSnapshot,
            tyreUnit = tyreUnit,
            onNavigateToBatteryDegradation = onNavigateToBatteryDegradation,
            onShowBattery12vHistory = onShowBattery12vHistory,
            onShowTyreDialog = onShowTyreDialog,
            modifier = Modifier.weight(1f).fillMaxHeight()
        )
    }
}

/** View mode with the chart hidden: cards reclaim the full width in two balanced rows. */
@Composable
private fun ModularCardGrid(
    visible: List<DashboardCardId>,
    telemetry: VehicleTelemetry,
    vehicleSnapshot: VehicleTelemetrySnapshot?,
    tyreUnit: TyrePressureUnit,
    onNavigateToBatteryDegradation: () -> Unit,
    onShowBattery12vHistory: () -> Unit,
    onShowTyreDialog: () -> Unit,
) {
    if (visible.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.dashboard_no_cards_hint),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    val topCount = (visible.size + 1) / 2
    val topRow = visible.take(topCount)
    val bottomRow = visible.drop(topCount)

    @Composable
    fun CardRow(ids: List<DashboardCardId>, modifier: Modifier) {
        Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ids.forEach { id ->
                DashboardStatCard(
                    id = id,
                    telemetry = telemetry,
                    vehicleSnapshot = vehicleSnapshot,
                    tyreUnit = tyreUnit,
                    onNavigateToBatteryDegradation = onNavigateToBatteryDegradation,
                    onShowBattery12vHistory = onShowBattery12vHistory,
                    onShowTyreDialog = onShowTyreDialog,
                    modifier = Modifier.weight(1f).fillMaxHeight()
                )
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CardRow(topRow, Modifier.weight(1f))
        if (bottomRow.isNotEmpty()) CardRow(bottomRow, Modifier.weight(1f))
    }
}

/**
 * Edit mode: the chart is pinned in the centre (spanning both rows); the eight cards
 * occupy two 2×2 flanks (indices 0–3 left, 4–7 right) and are drag-reorderable. Target
 * slot is computed from the finger's absolute position — snapping to the nearest flank
 * column and skipping the chart band — so drags across the centre land correctly.
 */
@Composable
private fun SplitEditGrid(
    order: List<DashboardCardId>,
    hidden: Set<DashboardCardId>,
    telemetry: VehicleTelemetry,
    vehicleSnapshot: VehicleTelemetrySnapshot?,
    tyreUnit: TyrePressureUnit,
    onReorder: (List<DashboardCardId>) -> Unit,
    onToggleHidden: (DashboardCardId) -> Unit,
    chart: @Composable (Modifier) -> Unit,
) {
    val density = LocalDensity.current
    val gapPx = with(density) { 12.dp.toPx() }

    var size by remember { mutableStateOf(IntSize.Zero) }
    var dragId by remember { mutableStateOf<DashboardCardId?>(null) }
    var dragTopLeft by remember { mutableStateOf(Offset.Zero) }
    val latestOrder by rememberUpdatedState(order)

    val n = order.size
    val w = size.width.toFloat()
    val h = size.height.toFloat()
    val sideW = if (w > 0) (w - 2 * gapPx) / 3f else 0f
    val chartX = sideW + gapPx
    val chartW = sideW
    val rightBaseX = 2 * sideW + 2 * gapPx
    val cardW = if (sideW > 0) (sideW - gapPx) / 2f else 0f
    val rowH = if (h > 0) (h - gapPx) / 2f else 0f

    fun slot(index: Int): Offset {
        val li = index % 4
        val col = li % 2
        val row = li / 2
        val baseX = if (index < 4) 0f else rightBaseX
        return Offset(baseX + col * (cardW + gapPx), row * (rowH + gapPx))
    }

    fun cardIndexAt(p: Offset): Int {
        val left = p.x < w / 2f
        val baseX = if (left) 0f else rightBaseX
        val regionBase = if (left) 0 else 4
        val col = if ((p.x - baseX) < cardW + gapPx / 2f) 0 else 1
        val row = if (p.y < h / 2f) 0 else 1
        return (regionBase + row * 2 + col).coerceIn(0, n - 1)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .pointerInput(size, n) {
                var grab = Offset.Zero
                detectDragGestures(
                    onDragStart = { start ->
                        // Ignore drags that begin on the pinned centre chart.
                        if (cardW > 0f && rowH > 0f && (start.x < chartX || start.x > chartX + chartW)) {
                            val idx = cardIndexAt(start)
                            dragId = latestOrder.getOrNull(idx)
                            grab = start - slot(idx)
                        }
                    },
                    onDragEnd = { dragId = null },
                    onDragCancel = { dragId = null },
                    onDrag = { change, _ ->
                        val id = dragId ?: return@detectDragGestures
                        change.consume()
                        dragTopLeft = (change.position - grab)
                        val center = dragTopLeft + Offset(cardW / 2f, rowH / 2f)
                        val target = cardIndexAt(center)
                        val cur = latestOrder.indexOf(id)
                        if (cur >= 0 && cur != target) {
                            onReorder(latestOrder.toMutableList().apply { add(target, removeAt(cur)) })
                        }
                    }
                )
            }
    ) {
        if (cardW > 0f && rowH > 0f) {
            val cardWDp = with(density) { cardW.toDp() }
            val rowHDp = with(density) { rowH.toDp() }
            val chartWDp = with(density) { chartW.toDp() }
            val fullHDp = with(density) { h.toDp() }

            // Pinned centre chart.
            chart(
                Modifier
                    .offset { IntOffset(chartX.roundToInt(), 0) }
                    .size(chartWDp, fullHDp)
            )

            order.forEachIndexed { index, id ->
                key(id) {
                    val isDragging = id == dragId
                    val pos by animateOffsetAsState(
                        targetValue = if (isDragging) dragTopLeft else slot(index),
                        animationSpec = if (isDragging) snap()
                        else spring(stiffness = Spring.StiffnessMediumLow),
                        label = "tilePos"
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
                            .offset { IntOffset(pos.x.roundToInt(), pos.y.roundToInt()) }
                            .size(cardWDp, rowHDp)
                            .zIndex(if (isDragging) 1f else 0f)
                            .graphicsLayer {
                                if (isDragging) {
                                    scaleX = 1.05f
                                    scaleY = 1.05f
                                } else {
                                    rotationZ = angle
                                }
                            }
                    ) {
                        DashboardStatCard(
                            id = id,
                            telemetry = telemetry,
                            vehicleSnapshot = vehicleSnapshot,
                            tyreUnit = tyreUnit,
                            interactive = false,
                            modifier = Modifier.fillMaxSize().alpha(if (id in hidden) 0.35f else 1f)
                        )
                        HideBadge(
                            hidden = id in hidden,
                            onToggle = { onToggleHidden(id) },
                            modifier = Modifier.align(Alignment.TopEnd)
                        )
                    }
                }
            }
        }
    }
}
