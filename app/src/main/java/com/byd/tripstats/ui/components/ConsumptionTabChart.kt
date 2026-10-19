package com.byd.tripstats.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DateRangePickerDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.byd.tripstats.R
import com.byd.tripstats.data.preferences.PreferencesManager
import com.byd.tripstats.data.preferences.UnitSystem
import com.byd.tripstats.data.preferences.consumptionUnit
import com.byd.tripstats.data.preferences.convertDistance
import com.byd.tripstats.data.preferences.convertEfficiency
import com.byd.tripstats.data.preferences.distanceUnit
import com.byd.tripstats.ui.theme.*
import com.byd.tripstats.ui.viewmodel.DashboardViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

private const val METRIC_TO_IMPERIAL_EFFICIENCY = 1.0 / 0.621371

/** The period's consumption: each bucket weighted by its distance, i.e. total energy over total distance. */
private fun List<DashboardViewModel.UsageBucket>.periodKwhPer100km(): Double? {
    val counted = filter { it.consumptionKwhPer100km != null }
    val distanceKm = counted.sumOf { it.consumptionDistanceKm }
    if (distanceKm <= 0.0) return null
    return counted.sumOf { it.consumptionKwhPer100km!! * it.consumptionDistanceKm } / distanceKm
}

/** (label, kWh/100 km) for the buckets that have a consumption figure. */
private fun List<DashboardViewModel.UsageBucket>.consumptionPoints(): List<Pair<String, Double>> =
    mapNotNull { b -> b.consumptionKwhPer100km?.let { b.dateLabel to it } }

// ── Thumbnail ─────────────────────────────────────────────────────────────────

/**
 * Sparkline thumbnail showing a compact consumption trend preview.
 * Tapping opens ConsumptionChartExpanded.
 */
@Composable
fun ConsumptionThumbnail(
    data: List<DashboardViewModel.UsageBucket>,
    modifier: Modifier = Modifier
) {
    val lineColor = BydElectricAzure.copy(alpha = 0.8f)
    val values = remember(data) { data.consumptionPoints().map { it.second.toFloat() } }

    // clipToBounds prevents the line from bleeding outside the parent container
    Canvas(modifier = modifier.clipToBounds()) {
        if (values.size < 2) return@Canvas

        val padding = 8.dp.toPx() // Keep the line away from the actual edges
        val w = size.width - (padding * 2)
        val h = size.height - (padding * 2)

        val vMin = values.minOrNull() ?: 0f
        val vMax = values.maxOrNull() ?: 1f
        val range = (vMax - vMin).coerceAtLeast(1f) // Avoid div by zero

        // Offset the entire drawing by the padding
        translate(left = padding, top = padding) {
            fun xOf(i: Int) = i / (values.size - 1).toFloat() * w
            fun yOf(v: Float) = h - (v - vMin) / range * h

            val path = Path().apply {
                val firstY = yOf(values[0])
                moveTo(xOf(0), firstY)
                values.indices.drop(1).forEach { i ->
                    lineTo(xOf(i), yOf(values[i]))
                }
            }

            drawPath(
                path = path,
                color = lineColor,
                style = Stroke(
                    width = 2.5f,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
            )
        }
    }
}

// ── Unified expanded chart ────────────────────────────────────────────────────

private enum class ConsumptionTab {
    WEEK, MONTH, YEAR, CUSTOM;
}

/** What the chart plots: consumption as a line, or distance / energy per bucket as bars. */
private enum class UsageMetric {
    CONSUMPTION, DISTANCE, ENERGY;
}

/**
 * Full-size consumption chart: a 4-tab period control at the top (this week, this month, this year,
 * and a custom date range) and a metric control under it (consumption, distance, energy).
 *
 * Tab design mirrors the BYD DiLink segment button style:
 *   - Selected:   filled cobalt pill, white bold text
 *   - Unselected: transparent pill with outline border, muted text
 * This gives clearly distinct visual states at a glance.
 */
@Composable
fun ConsumptionChartExpanded(
    usage: DashboardViewModel.UsageCharts,
    onCustomRange: (DashboardViewModel.UsageRange) -> Unit,
    onClose: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val prefs = remember { PreferencesManager(context.applicationContext) }
    val selectedCar by prefs.selectedCarConfig.collectAsState(initial = null)
    val unitSystem  by prefs.unitSystem.collectAsState(initial = prefs.getCachedUnitSystem())
    val useImperial = unitSystem == UnitSystem.IMPERIAL
    val efficiencyFactor = if (useImperial) METRIC_TO_IMPERIAL_EFFICIENCY else 1.0

    val referenceConsumptionKwhPer100km = selectedCar?.referenceConsumptionKwhPer100km

    var selectedTab by remember { mutableStateOf(ConsumptionTab.WEEK) }
    var selectedMetric by remember { mutableStateOf(UsageMetric.CONSUMPTION) }
    var showRangePicker by remember { mutableStateOf(false) }
    // A custom tab with no range yet falls back to the week until one is picked.
    val shownTab = if (selectedTab == ConsumptionTab.CUSTOM && usage.customRange == null) ConsumptionTab.WEEK else selectedTab

    val activeData = when (shownTab) {
        ConsumptionTab.WEEK   -> usage.week
        ConsumptionTab.MONTH  -> usage.month
        ConsumptionTab.YEAR   -> usage.year
        ConsumptionTab.CUSTOM -> usage.custom
    }
    val bucketSize = when (shownTab) {
        ConsumptionTab.WEEK, ConsumptionTab.MONTH -> DashboardViewModel.UsageBucketSize.DAY
        ConsumptionTab.YEAR   -> DashboardViewModel.UsageBucketSize.MONTH
        ConsumptionTab.CUSTOM -> usage.customRange?.bucketSize ?: DashboardViewModel.UsageBucketSize.DAY
    }
    val selectedDurationAverage = activeData.periodKwhPer100km()?.times(efficiencyFactor)
    val labelEvery = when (shownTab) {
        ConsumptionTab.WEEK   -> 1
        ConsumptionTab.MONTH  -> 5
        ConsumptionTab.YEAR   -> 1
        ConsumptionTab.CUSTOM -> ((activeData.size + 7) / 8).coerceAtLeast(1)
    }

    // Distance / energy bars, in display units.
    val barUnit = if (selectedMetric == UsageMetric.DISTANCE) unitSystem.distanceUnit else "kWh"
    val barPoints = activeData.map {
        it.dateLabel to if (selectedMetric == UsageMetric.DISTANCE) unitSystem.convertDistance(it.distanceKm) else it.energyKwh
    }
    val barTotal = barPoints.sumOf { it.second }
    val barAverage = if (barPoints.isEmpty()) 0.0 else barTotal / barPoints.size
    fun formatBar(v: Double) = if (selectedMetric == UsageMetric.ENERGY && v < 100.0) "%.1f".format(v) else "%.0f".format(v)

    Column(modifier = modifier.fillMaxSize()) {

        // ── Tab navbar + close button ─────────────────────────────────────────
        // Close button lives OUTSIDE the clipped pill container so the last
        // pill's right outline never gets cropped by the parent clip boundary.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            SegmentedPills(
                entries = ConsumptionTab.entries,
                selected = shownTab,
                label = { tab ->
                    when (tab) {
                        ConsumptionTab.WEEK   -> stringResource(R.string.chart_weekly_consumption)
                        ConsumptionTab.MONTH  -> stringResource(R.string.chart_monthly_consumption)
                        ConsumptionTab.YEAR   -> stringResource(R.string.chart_yearly_consumption)
                        ConsumptionTab.CUSTOM -> usage.customRange?.let { rangeLabel(it) }
                            ?: stringResource(R.string.chart_custom_range)
                    }
                },
                onSelect = { tab ->
                    // The custom tab always opens the picker, so a second tap changes the range.
                    if (tab == ConsumptionTab.CUSTOM) showRangePicker = true else selectedTab = tab
                },
                height = 36.dp,
                modifier = Modifier.weight(1f)
            )

            Spacer(Modifier.width(8.dp))

            // Close button — outside the clip so it never interferes with pill borders.
            // 48 dp is the Material minimum recommended touch target.
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.chart_close),
                    tint = BydErrorRedLight,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        // ── Metric control ────────────────────────────────────────────────────
        // Same pills as the period row, aligned under them (the trailing spacer stands in for
        // the close button), so the two controls read as one block at any width.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 4.dp)
        ) {
            SegmentedPills(
                entries = UsageMetric.entries,
                selected = selectedMetric,
                label = { metric ->
                    when (metric) {
                        UsageMetric.CONSUMPTION -> stringResource(R.string.stat_avg_consumption)
                        UsageMetric.DISTANCE    -> stringResource(R.string.stat_distance)
                        UsageMetric.ENERGY      -> stringResource(R.string.section_energy)
                    }
                },
                onSelect = { selectedMetric = it },
                height = 36.dp,
                // The consumption label is the longest; at split-screen width an equal share
                // ellipsized it, while Distance / Energy fit in less.
                weightOf = { if (it == UsageMetric.CONSUMPTION) 1.5f else 1f },
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp + 48.dp))
        }

        // ── Legend ────────────────────────────────────────────────────────────
        // Centered on its own line, as before the metric row existed. In split-screen (half the
        // width) the items stack instead of sharing a line, and each stays on one line, so the
        // legend can never grow tall enough to squeeze the chart.
        val legendItems = buildList<Pair<Color, String>> {
            if (selectedMetric == UsageMetric.CONSUMPTION) {
                selectedDurationAverage?.let { avg ->
                    add(RegenGreen.copy(alpha = 0.9f) to stringResource(R.string.chart_your_duration_avg, String.format("%.1f", avg), unitSystem.consumptionUnit))
                }
                referenceConsumptionKwhPer100km?.let { avg ->
                    add(AccelerationOrange.copy(alpha = 0.9f) to stringResource(R.string.chart_selected_car_avg, String.format("%.1f", avg * efficiencyFactor), unitSystem.consumptionUnit))
                }
            } else if (barTotal > 0.0) {
                add(
                    RegenGreen.copy(alpha = 0.9f) to stringResource(
                        when (bucketSize) {
                            DashboardViewModel.UsageBucketSize.DAY   -> R.string.chart_total_per_day
                            DashboardViewModel.UsageBucketSize.WEEK  -> R.string.chart_total_per_week
                            DashboardViewModel.UsageBucketSize.MONTH -> R.string.chart_total_per_month
                        },
                        "${formatBar(barTotal)} $barUnit",
                        "${formatBar(barAverage)} $barUnit"
                    )
                )
            }
        }
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 2.dp)
        ) {
            if (maxWidth < LEGEND_ONE_LINE_MIN_WIDTH) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    legendItems.forEach { (color, label) -> ConsumptionLegendItem(color = color, label = label) }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    legendItems.forEach { (color, label) -> ConsumptionLegendItem(color = color, label = label) }
                }
            }
        }

        // ── Chart canvas ──────────────────────────────────────────────────────
        val canvasModifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .padding(horizontal = 8.dp, vertical = 4.dp)
        if (selectedMetric == UsageMetric.CONSUMPTION) {
            ConsumptionCanvas(
                points = activeData.consumptionPoints(),
                durationAverageKwhPer100km = activeData.periodKwhPer100km(),
                labelEvery = labelEvery,
                referenceConsumptionKwhPer100km = referenceConsumptionKwhPer100km,
                useImperial = useImperial,
                modifier = canvasModifier
            )
        } else {
            UsageBarCanvas(
                points = barPoints,
                average = barAverage,
                unitLabel = barUnit,
                labelEvery = labelEvery,
                formatValue = ::formatBar,
                modifier = canvasModifier
            )
        }
    }

    if (showRangePicker) {
        UsageRangeSheet(
            initial = usage.customRange,
            onDismiss = { showRangePicker = false },
            onPick = { range ->
                showRangePicker = false
                onCustomRange(range)
                selectedTab = ConsumptionTab.CUSTOM
            }
        )
    }
}

/** The DiLink-style pill row shared by the period and metric controls. */
@Composable
private fun <T> SegmentedPills(
    entries: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    height: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    weightOf: (T) -> Float = { 1f }
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(4.dp)
    ) {
        entries.forEachIndexed { index, entry ->
            val isSelected = entry == selected
            Box(
                modifier = Modifier
                    .weight(weightOf(entry))
                    .height(height)
                    .clip(RoundedCornerShape(7.dp))
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primary
                        else Color.Transparent
                    )
                    .then(
                        if (!isSelected) Modifier.outline(
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                            cornerRadius = 7.dp,
                            strokeWidth = 1.dp
                        ) else Modifier
                    )
                    .clickable { onSelect(entry) }
                    .padding(vertical = 4.dp, horizontal = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label(entry),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    color = if (isSelected)
                        MaterialTheme.colorScheme.onPrimary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (index != entries.lastIndex) {
                Spacer(Modifier.width(4.dp))
            }
        }
    }
}

/** "3 Mar – 21 Jun", with the year when the range isn't all this year. */
private fun rangeLabel(range: DashboardViewModel.UsageRange): String {
    val zone = ZoneId.systemDefault()
    val first = Instant.ofEpochMilli(range.firstDayMs).atZone(zone).toLocalDate()
    val last = Instant.ofEpochMilli(range.lastDayMs).atZone(zone).toLocalDate()
    val thisYear = LocalDate.now(zone).year
    val pattern = if (first.year == thisYear && last.year == thisYear) "d MMM" else "d MMM yy"
    val formatter = java.time.format.DateTimeFormatter.ofPattern(pattern, java.util.Locale.getDefault())
    return if (first == last) first.format(formatter) else "${first.format(formatter)} – ${last.format(formatter)}"
}

/**
 * Material date-range picker for the custom tab, in the same bottom sheet as Trip History's
 * filter (surfaceVariant, Cancel / Apply). Its colors are set here because the theme defines no
 * surfaceContainer roles, so Material's defaults came out lavender-pink. The picker works in
 * UTC-midnight days; the range is handed back as local-midnight days. Future days can't be picked.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UsageRangeSheet(
    initial: DashboardViewModel.UsageRange?,
    onDismiss: () -> Unit,
    onPick: (DashboardViewModel.UsageRange) -> Unit
) {
    val zone = ZoneId.systemDefault()
    fun toPickerDay(localMs: Long) =
        Instant.ofEpochMilli(localMs).atZone(zone).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    fun fromPickerDay(utcMs: Long) =
        Instant.ofEpochMilli(utcMs).atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
    val todayUtc = LocalDate.now(zone).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    val state = rememberDateRangePickerState(
        initialSelectedStartDateMillis = initial?.firstDayMs?.let(::toPickerDay),
        initialSelectedEndDateMillis = initial?.lastDayMs?.let(::toPickerDay),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= todayUtc
            override fun isSelectableYear(year: Int) = year <= LocalDate.now(zone).year
        }
    )
    val dateFormatter = remember { DatePickerDefaults.dateFormatter() }
    val sheetColor = MaterialTheme.colorScheme.surfaceVariant
    val start = state.selectedStartDateMillis

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = sheetColor
    ) {
        DateRangePicker(
            state = state,
            // Measured after the button row and capped, so the sheet stops short of the status
            // bar on the head unit instead of filling the screen (its month list is lazy and
            // would otherwise take every pixel it is offered).
            modifier = Modifier.weight(1f, fill = false).heightIn(max = 480.dp),
            dateFormatter = dateFormatter,
            title = {
                Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                    DateRangePickerDefaults.DateRangePickerTitle(displayMode = state.displayMode)
                }
            },
            headline = {
                Box(Modifier.fillMaxWidth().padding(bottom = 12.dp), contentAlignment = Alignment.Center) {
                    DateRangePickerDefaults.DateRangePickerHeadline(
                        selectedStartDateMillis = state.selectedStartDateMillis,
                        selectedEndDateMillis = state.selectedEndDateMillis,
                        displayMode = state.displayMode,
                        dateFormatter = dateFormatter
                    )
                }
            },
            colors = DatePickerDefaults.colors(
                containerColor = sheetColor,
                selectedDayContainerColor = MaterialTheme.colorScheme.primary,
                selectedDayContentColor = MaterialTheme.colorScheme.onPrimary,
                dayInSelectionRangeContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
                dayInSelectionRangeContentColor = MaterialTheme.colorScheme.onSurface,
                todayDateBorderColor = MaterialTheme.colorScheme.primary,
                todayContentColor = MaterialTheme.colorScheme.primary
            )
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(top = 8.dp, bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.cancel))
            }
            Button(
                enabled = start != null,
                onClick = {
                    if (start != null) {
                        // A single tapped day is a one-day range.
                        val end = state.selectedEndDateMillis ?: start
                        onPick(DashboardViewModel.UsageRange(fromPickerDay(start), fromPickerDay(end)))
                    }
                },
                modifier = Modifier.weight(1f)
            ) { Text(stringResource(R.string.apply)) }
        }
    }
}

// ── Outline modifier helper ───────────────────────────────────────────────────
// Draws a border inside the composable bounds without affecting layout size.
private fun Modifier.outline(
    color: androidx.compose.ui.graphics.Color,
    cornerRadius: androidx.compose.ui.unit.Dp,
    strokeWidth: androidx.compose.ui.unit.Dp
): Modifier = this.then(
    Modifier.drawWithContent {
        drawContent()
        val stroke = strokeWidth.toPx()
        val radius = cornerRadius.toPx()
        drawRoundRect(
            color = color,
            size = size,
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius),
            style = Stroke(width = stroke)
        )
    }
)

// ── Chart canvas ──────────────────────────────────────────────────────────────

/** Consumption per bucket as a line. [points] are (label, kWh/100 km), metric; drawn in display units. */
@Composable
private fun ConsumptionCanvas(
    points: List<Pair<String, Double>>,
    durationAverageKwhPer100km: Double?,
    labelEvery: Int,
    referenceConsumptionKwhPer100km: Double?,
    useImperial: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    // Resolve all theme-aware colors before entering DrawScope
    val lineColor     = BydElectricAzure.copy(alpha = 0.9f)
    val pointFill     = BydElectricAzure
    val pointGlow     = BydElectricAzure.copy(alpha = 0.20f)
    val sealLineColor = AccelerationOrange.copy(alpha = 0.9f)
    val durationAvgLineColor = RegenGreen.copy(alpha = 0.9f)
    val textColor     = MaterialTheme.colorScheme.onSurface
    val gridColor     = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)
    val axisColor     = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        val padL = 80f
        val padR = 16f
        val padT = 16f
        val padB = 40f

        val chartW = w - padL - padR
        val chartH = h - padT - padB
        if (chartH < MIN_PLOT_HEIGHT_PX || chartW <= 0f) return@Canvas

        val nc = drawContext.canvas.nativeCanvas

        if (points.isEmpty()) {
            nc.drawText(
                context.getString(R.string.chart_no_data_yet),
                padL + chartW / 2f,
                padT + chartH / 2f,
                android.graphics.Paint().apply {
                    color = textColor.copy(alpha = 0.35f).toArgb()
                    textSize = 30f
                    textAlign = android.graphics.Paint.Align.CENTER
                    isAntiAlias = true
                }
            )
            return@Canvas
        }

        val factor = if (useImperial) METRIC_TO_IMPERIAL_EFFICIENCY else 1.0
        val values = points.map { it.second * factor }
        // Same figure as the legend above the chart.
        val durationAverage = durationAverageKwhPer100km?.times(factor) ?: values.average()
        val refConverted = referenceConsumptionKwhPer100km?.times(factor)
        val allVals = if (refConverted != null) {
            values + listOf(refConverted, durationAverage)
        } else {
            values + listOf(durationAverage)
        }
        val rawMin = allVals.min()
        val rawMax = allVals.max()
        val yStep = when {
            rawMax - rawMin < 5  -> 1.0
            rawMax - rawMin < 15 -> 2.0
            else                 -> 5.0
        }
        val yMin = (rawMin / yStep).toInt() * yStep - yStep
        val yMax = (rawMax / yStep).toInt() * yStep + yStep

        fun xOf(i: Int): Float =
            if (points.size == 1) padL + chartW / 2f
            else padL + i / (points.size - 1).toFloat() * chartW

        fun yOf(v: Double): Float {
            val frac = (v - yMin) / (yMax - yMin)
            return (padT + chartH * (1.0 - frac)).toFloat()
        }

        val labelPaint = android.graphics.Paint().apply {
            color = textColor.copy(alpha = 0.7f).toArgb()
            textSize = 22f
            isAntiAlias = true
        }
        val xLabelPaint = android.graphics.Paint().apply {
            color = textColor.copy(alpha = 0.7f).toArgb()
            textSize = 20f
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
        }

        // Y grid lines + labels
        var yTick = yMin
        while (yTick <= yMax + 0.01) {
            val y = yOf(yTick)
            drawLine(gridColor, Offset(padL, y), Offset(w - padR, y), 1f)
            labelPaint.textAlign = android.graphics.Paint.Align.RIGHT
            nc.drawText("%.0f".format(yTick), padL - 6f, y + 8f, labelPaint)
            yTick += yStep
        }

        // Y axis label rotated vertically
        val yAxisPaint = android.graphics.Paint().apply {
            color = textColor.copy(alpha = 0.55f).toArgb()
            textSize = 19f
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
        }
        nc.save()
        nc.rotate(-90f, 18f, padT + chartH / 2f)
        nc.drawText(if (useImperial) "kWh/100mi" else "kWh/100km", 18f, padT + chartH / 2f, yAxisPaint)
        nc.restore()

        // X axis line
        drawLine(axisColor, Offset(padL, padT + chartH), Offset(w - padR, padT + chartH), 1.5f)

        // X labels
        points.forEachIndexed { i, (label, _) ->
            if (i % labelEvery == 0 || i == points.size - 1) {
                nc.drawText(label, xOf(i), h - 8f, xLabelPaint)
            }
        }

        // Selected duration average line
        val durationAverageY = yOf(durationAverage)
        drawLine(
            color = durationAvgLineColor,
            start = Offset(padL, durationAverageY),
            end = Offset(w - padR, durationAverageY),
            strokeWidth = 2f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))
        )

        // Selected car average reference line (dashed orange — distinct from cobalt line)
        refConverted?.let { referenceValue ->
            val referenceY = yOf(referenceValue)

            drawLine(
                color = sealLineColor,
                start = Offset(padL, referenceY),
                end = Offset(w - padR, referenceY),
                strokeWidth = 2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 7f))
            )
        }

        // Area fill under the data line — vertical gradient matching other charts
        if (points.size >= 2) {
            val areaPath = Path().apply {
                moveTo(xOf(0), yOf(values[0]))
                values.drop(1).forEachIndexed { i, v -> lineTo(xOf(i + 1), yOf(v)) }
                lineTo(xOf(points.size - 1), padT + chartH)
                lineTo(xOf(0), padT + chartH)
                close()
            }
            drawPath(areaPath, Brush.verticalGradient(
                colors = listOf(BydElectricAzure.copy(alpha = 0.35f), BydElectricAzure.copy(alpha = 0f)),
                startY = yOf(values.max()), endY = padT + chartH
            ))
        }

        // Data line
        if (points.size >= 2) {
            val linePath = Path().apply {
                moveTo(xOf(0), yOf(values[0]))
                values.drop(1).forEachIndexed { i, v -> lineTo(xOf(i + 1), yOf(v)) }
            }
            drawPath(
                linePath, lineColor,
                style = Stroke(width = 3.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }

        // Data points — always draw glow+dot+pin on every point.
        // Value label above the dot: shown on every point up to a month's worth, beyond
        // which they would overlap. X date label density is controlled separately by
        // labelEvery (passed in from the tab).
        points.forEachIndexed { i, (_, kwhPer100km) ->
            val x = xOf(i)
            val y = yOf(kwhPer100km * factor)

            drawCircle(pointGlow,  16f, Offset(x, y))
            drawCircle(pointFill,   7f, Offset(x, y))
            drawCircle(Color.White, 3f, Offset(x, y))

            // Value label always shown above every dot.
            // First point: LEFT-align so it doesn't bleed into Y-axis labels.
            // All others: CENTER.
            // Vertical: draw above dot normally, but if that would go above padT
            // (i.e. top of chart), draw below the dot instead.
            if (points.size <= MAX_VALUE_LABELS) {
                labelPaint.color     = textColor.toArgb()
                labelPaint.textSize  = 20f
                labelPaint.textAlign = if (i == 0)
                    android.graphics.Paint.Align.LEFT
                else
                    android.graphics.Paint.Align.CENTER
                val labelY = if (y - 20f < padT + 20f) y + 32f else y - 20f
                nc.drawText("%.1f".format(kwhPer100km * factor), x, labelY, labelPaint)
            }
        }
    }
}

// Most buckets that still get a value label above each point / bar.
private const val MAX_VALUE_LABELS = 31

// Narrower than this (the head unit's split-screen), the legend stacks its items.
private val LEGEND_ONE_LINE_MIN_WIDTH = 560.dp

// Below this plot height (px) a canvas draws nothing rather than overlapping its own labels.
private const val MIN_PLOT_HEIGHT_PX = 60f

/**
 * Distance or energy per bucket as bars, from zero, with the period's per-bucket [average] as a
 * dashed line. [points] are (label, value) already in display units; empty buckets are zero bars.
 */
@Composable
private fun UsageBarCanvas(
    points: List<Pair<String, Double>>,
    average: Double,
    unitLabel: String,
    labelEvery: Int,
    formatValue: (Double) -> String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val barColor   = BydElectricAzure.copy(alpha = 0.85f)
    val avgColor   = RegenGreen.copy(alpha = 0.9f)
    val textColor  = MaterialTheme.colorScheme.onSurface
    val gridColor  = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)
    val axisColor  = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val padL = 80f
        val padR = 16f
        val padT = 28f
        val padB = 40f
        val chartW = w - padL - padR
        val chartH = h - padT - padB
        if (chartH < MIN_PLOT_HEIGHT_PX || chartW <= 0f) return@Canvas
        val nc = drawContext.canvas.nativeCanvas

        val maxValue = points.maxOfOrNull { it.second } ?: 0.0
        if (maxValue <= 0.0) {
            nc.drawText(
                context.getString(R.string.chart_no_data_yet),
                padL + chartW / 2f,
                padT + chartH / 2f,
                android.graphics.Paint().apply {
                    color = textColor.copy(alpha = 0.35f).toArgb()
                    textSize = 30f
                    textAlign = android.graphics.Paint.Align.CENTER
                    isAntiAlias = true
                }
            )
            return@Canvas
        }

        // A 1-2-5 step giving about five gridlines.
        val rough = maxValue / 5.0
        val magnitude = Math.pow(10.0, kotlin.math.floor(kotlin.math.log10(rough)))
        val yStep = listOf(1.0, 2.0, 5.0, 10.0).map { it * magnitude }.first { it >= rough }
        val yMax = kotlin.math.ceil(maxValue / yStep) * yStep
        fun yOf(v: Double): Float = (padT + chartH * (1.0 - v / yMax)).toFloat()

        val labelPaint = android.graphics.Paint().apply {
            color = textColor.copy(alpha = 0.7f).toArgb()
            textSize = 22f
            textAlign = android.graphics.Paint.Align.RIGHT
            isAntiAlias = true
        }
        val xLabelPaint = android.graphics.Paint().apply {
            color = textColor.copy(alpha = 0.7f).toArgb()
            textSize = 20f
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
        }
        val valuePaint = android.graphics.Paint().apply {
            color = textColor.toArgb()
            textSize = 20f
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
        }

        // Y grid lines + labels
        var yTick = 0.0
        while (yTick <= yMax + yStep * 0.01) {
            val y = yOf(yTick)
            drawLine(gridColor, Offset(padL, y), Offset(w - padR, y), 1f)
            nc.drawText(if (yStep < 1.0) "%.1f".format(yTick) else "%.0f".format(yTick), padL - 6f, y + 8f, labelPaint)
            yTick += yStep
        }

        // Y axis label rotated vertically
        val yAxisPaint = android.graphics.Paint().apply {
            color = textColor.copy(alpha = 0.55f).toArgb()
            textSize = 19f
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
        }
        nc.save()
        nc.rotate(-90f, 18f, padT + chartH / 2f)
        nc.drawText(unitLabel, 18f, padT + chartH / 2f, yAxisPaint)
        nc.restore()

        drawLine(axisColor, Offset(padL, padT + chartH), Offset(w - padR, padT + chartH), 1.5f)

        val slot = chartW / points.size
        val barW = (slot * 0.62f).coerceAtMost(48f)
        points.forEachIndexed { i, (label, value) ->
            val cx = padL + slot * (i + 0.5f)
            if (value > 0.0) {
                val top = yOf(value)
                drawRoundRect(
                    color = barColor,
                    topLeft = Offset(cx - barW / 2f, top),
                    size = androidx.compose.ui.geometry.Size(barW, padT + chartH - top),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f)
                )
                if (points.size <= MAX_VALUE_LABELS) {
                    nc.drawText(formatValue(value), cx, top - 8f, valuePaint)
                }
            }
            if (i % labelEvery == 0 || i == points.size - 1) {
                nc.drawText(label, cx, h - 8f, xLabelPaint)
            }
        }

        // Per-bucket average across the period
        if (average > 0.0) {
            val y = yOf(average)
            drawLine(
                color = avgColor,
                start = Offset(padL, y),
                end = Offset(w - padR, y),
                strokeWidth = 2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))
            )
        }
    }
}

@Composable
private fun ConsumptionLegendItem(
    color: Color,
    label: String
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(modifier = Modifier.size(width = 18.dp, height = 8.dp)) {
            drawLine(
                color = color,
                start = Offset(0f, size.height / 2f),
                end = Offset(size.width, size.height / 2f),
                strokeWidth = 3f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 7f))
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
