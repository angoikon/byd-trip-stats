package com.byd.tripstats.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.byd.tripstats.data.local.entity.ChargingDataPointEntity
import com.byd.tripstats.data.local.entity.ChargingSessionEntity
import com.byd.tripstats.ui.theme.*
import com.byd.tripstats.ui.viewmodel.DashboardViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChargingDetailScreen(
    sessionId: Long,
    viewModel: DashboardViewModel,
    onNavigateBack: () -> Unit
) {
    LaunchedEffect(sessionId) { viewModel.selectSession(sessionId) }
    DisposableEffect(Unit) { onDispose { viewModel.clearSelectedSession() } }

    val session    by viewModel.selectedSession.collectAsState()
    val dataPoints by viewModel.selectedSessionDataPoints.collectAsState()

    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("Overview", "Power", "SoC", "Voltage", "Temperature")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Charging Detail", fontSize = 24.sp, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", modifier = Modifier.size(28.dp))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { paddingValues ->
        if (session == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(paddingValues),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator(modifier = Modifier.size(60.dp)) }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            TabRow(selectedTabIndex = selectedTab, modifier = Modifier.fillMaxWidth()) {
                tabs.forEachIndexed { i, title ->
                    Tab(
                        selected = selectedTab == i,
                        onClick  = { selectedTab = i },
                        text = {
                            Text(
                                title,
                                fontSize   = 14.sp,
                                fontWeight = if (selectedTab == i) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    )
                }
            }

            when (selectedTab) {
                0 -> ChargingOverviewTab(session!!, dataPoints)
                1 -> ChargingChartTab(
                    dataPoints   = dataPoints,
                    title        = "Charge Power",
                    yAxisLabel   = "kW",
                    lineColor    = AccelerationOrange,
                    valueSelector = { it.chargingPower }
                )
                2 -> ChargingChartTab(
                    dataPoints   = dataPoints,
                    title        = "State of Charge",
                    yAxisLabel   = "%",
                    lineColor    = BatteryBlue,
                    valueSelector = { it.soc }
                )
                3 -> ChargingChartTab(
                    dataPoints   = dataPoints,
                    title        = "HV Battery Voltage",
                    yAxisLabel   = "V",
                    lineColor    = BydEcoTealDim,
                    valueSelector = { it.batteryTotalVoltage.toDouble() }
                )
                4 -> ChargingTempTab(dataPoints)
            }
        }
    }
}

// ── Tab: Overview ─────────────────────────────────────────────────────────────

@Composable
private fun ChargingOverviewTab(
    session   : ChargingSessionEntity,
    dataPoints: List<ChargingDataPointEntity>
) {
    val dateFmt = remember { SimpleDateFormat("dd MMM yyyy  HH:mm", Locale.getDefault()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Date & duration
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors   = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OverviewRow("Started",  dateFmt.format(Date(session.startTime)))
                session.endTime?.let {
                    OverviewRow("Ended",  dateFmt.format(Date(it)))
                }
                session.durationSeconds?.let {
                    OverviewRow("Duration", formatDurationLong(it))
                }
                OverviewRow("Car",      session.carConfigId.replace("_", " ").lowercase()
                    .replaceFirstChar { it.uppercase() })
            }
        }

        // Energy & SoC
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors   = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Energy", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                OverviewRow("SoC start",    "%.1f%%".format(session.socStart))
                session.socEnd?.let {
                    OverviewRow("SoC end",  "%.1f%%".format(it))
                    OverviewRow("SoC added","%.1f%%".format(it - session.socStart))
                }
                session.kwhAdded?.let {
                    OverviewRow("kWh added", "%.2f kWh".format(it), valueColor = RegenGreen)
                }
                OverviewRow("Battery (car)", "%.1f kWh".format(session.batteryKwh))
            }
        }

        // Power
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors   = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Power", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                if (session.peakKw > 0) {
                    OverviewRow("Peak power", "%.0f kW".format(session.peakKw), valueColor = AccelerationOrange)
                }
                if (session.avgKw > 0) {
                    OverviewRow("Average power", "%.1f kW".format(session.avgKw))
                }
                // Average charge rate (kWh/h) from peak and duration
                session.durationSeconds?.let { secs ->
                    if (session.kwhAdded != null && secs > 0) {
                        val rate = session.kwhAdded!! / (secs / 3600.0)
                        OverviewRow("Charge rate", "%.1f kW (avg)".format(rate))
                    }
                }
            }
        }

        // Thermal
        if (session.batteryTempStart > 0) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors   = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Battery Temperature", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    OverviewRow("At start", "%.1f °C".format(session.batteryTempStart))
                    session.batteryTempEnd?.let {
                        OverviewRow("At end",   "%.1f °C".format(it))
                        val delta = it - session.batteryTempStart
                        val sign  = if (delta >= 0) "+" else ""
                        OverviewRow("Rise",     "$sign%.1f °C".format(delta),
                            valueColor = if (delta > 10) BydErrorRed else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }

        // Data points count
        if (dataPoints.isNotEmpty()) {
            Text(
                "${dataPoints.size} telemetry points recorded",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
}

@Composable
private fun OverviewRow(
    label     : String,
    value     : String,
    valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium, color = valueColor)
    }
}

// ── Tab: Generic single-series chart ─────────────────────────────────────────

@Composable
private fun ChargingChartTab(
    dataPoints   : List<ChargingDataPointEntity>,
    title        : String,
    yAxisLabel   : String,
    lineColor    : androidx.compose.ui.graphics.Color,
    valueSelector: (ChargingDataPointEntity) -> Double
) {
    if (dataPoints.size < 2) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Not enough data", style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    val textColor  = MaterialTheme.colorScheme.onSurface
    val gridColor  = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)
    val axisColor  = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
    val areaColor  = lineColor.copy(alpha = 0.20f)

    Column(modifier = Modifier.fillMaxSize().padding(top = 8.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 8.dp, vertical = 8.dp)
        ) {
            val w = size.width; val h = size.height
            val padL = 80f; val padR = 16f; val padT = 16f; val padB = 40f
            val chartW = w - padL - padR; val chartH = h - padT - padB

            val values  = dataPoints.map(valueSelector)
            val rawMin  = values.minOrNull() ?: 0.0
            val rawMax  = values.maxOrNull()?.coerceAtLeast(rawMin + 1.0) ?: 1.0
            val yStep   = niceStep(rawMax - rawMin)
            val yMin    = (rawMin / yStep).toInt() * yStep
            val yMax    = (rawMax / yStep).toInt() * yStep + yStep

            val totalMs  = dataPoints.last().timestamp - dataPoints.first().timestamp
            val startMs  = dataPoints.first().timestamp

            fun xOf(ts: Long)  = padL + ((ts - startMs).toFloat() / totalMs.coerceAtLeast(1L)) * chartW
            fun yOf(v: Double) = (padT + chartH * (1.0 - (v - yMin) / (yMax - yMin))).toFloat()

            val nc = drawContext.canvas.nativeCanvas
            val labelPaint = android.graphics.Paint().apply {
                color = textColor.copy(alpha = 0.7f).toArgb(); textSize = 22f; isAntiAlias = true
            }
            val xLabelPaint = android.graphics.Paint().apply {
                color = textColor.copy(alpha = 0.6f).toArgb(); textSize = 20f
                textAlign = android.graphics.Paint.Align.CENTER; isAntiAlias = true
            }

            // Y grid + labels
            var yTick = yMin
            while (yTick <= yMax + 0.01) {
                val y = yOf(yTick)
                drawLine(gridColor, Offset(padL, y), Offset(w - padR, y), 1f)
                labelPaint.textAlign = android.graphics.Paint.Align.RIGHT
                nc.drawText("%.0f".format(yTick), padL - 6f, y + 8f, labelPaint)
                yTick += yStep
            }

            // Y axis label
            val yAxisPaint = android.graphics.Paint().apply {
                color = textColor.copy(alpha = 0.55f).toArgb(); textSize = 19f
                textAlign = android.graphics.Paint.Align.CENTER; isAntiAlias = true
            }
            nc.save(); nc.rotate(-90f, 18f, padT + chartH / 2f)
            nc.drawText(yAxisLabel, 18f, padT + chartH / 2f, yAxisPaint); nc.restore()

            // X axis
            drawLine(axisColor, Offset(padL, padT + chartH), Offset(w - padR, padT + chartH), 1.5f)

            // X tick labels — 5 evenly spaced time markers
            for (i in 0..4) {
                val frac = i / 4.0
                val ts   = startMs + (totalMs * frac).toLong()
                val x    = xOf(ts)
                val mins = ((ts - startMs) / 60_000.0).roundToInt()
                nc.drawText("+${mins}m", x, h - 4f, xLabelPaint)
            }

            // Area fill
            val areaPath = Path().apply {
                moveTo(xOf(dataPoints.first().timestamp), yOf(values.first()))
                dataPoints.drop(1).forEachIndexed { i, p -> lineTo(xOf(p.timestamp), yOf(values[i + 1])) }
                lineTo(xOf(dataPoints.last().timestamp), padT + chartH)
                lineTo(xOf(dataPoints.first().timestamp), padT + chartH)
                close()
            }
            drawPath(areaPath, Brush.verticalGradient(
                listOf(lineColor.copy(alpha = 0.30f), lineColor.copy(alpha = 0f)),
                startY = yOf(values.max()), endY = padT + chartH
            ))

            // Line
            val linePath = Path().apply {
                moveTo(xOf(dataPoints.first().timestamp), yOf(values.first()))
                dataPoints.drop(1).forEachIndexed { i, p -> lineTo(xOf(p.timestamp), yOf(values[i + 1])) }
            }
            drawPath(linePath, lineColor, style = Stroke(width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

// ── Tab: Temperature (multi-series) ──────────────────────────────────────────

@Composable
private fun ChargingTempTab(dataPoints: List<ChargingDataPointEntity>) {
    if (dataPoints.size < 2) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Not enough data", style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    val textColor    = MaterialTheme.colorScheme.onSurface
    val gridColor    = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)
    val axisColor    = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
    val avgColor     = BydErrorRed
    val minColor     = BydElectricAzure
    val maxColor     = AccelerationOrange

    Column(modifier = Modifier.fillMaxSize().padding(top = 8.dp)) {
        Text(
            "Battery Temperature",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )

        // Legend
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ChartLegendItem(avgColor, "Avg")
            ChartLegendItem(minColor, "Cell min")
            ChartLegendItem(maxColor, "Cell max")
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 8.dp, vertical = 8.dp)
        ) {
            val w = size.width; val h = size.height
            val padL = 80f; val padR = 16f; val padT = 16f; val padB = 40f
            val chartW = w - padL - padR; val chartH = h - padT - padB

            val avgVals = dataPoints.map { it.batteryTempAvg }
            val minVals = dataPoints.map { it.batteryCellTempMin.toDouble() }
            val maxVals = dataPoints.map { it.batteryCellTempMax.toDouble() }
            val allVals = avgVals + minVals + maxVals
            val rawMin  = allVals.minOrNull() ?: 0.0
            val rawMax  = allVals.maxOrNull()?.coerceAtLeast(rawMin + 1.0) ?: 1.0
            val yStep   = niceStep(rawMax - rawMin)
            val yMin    = (rawMin / yStep).toInt() * yStep
            val yMax    = (rawMax / yStep).toInt() * yStep + yStep

            val totalMs  = dataPoints.last().timestamp - dataPoints.first().timestamp
            val startMs  = dataPoints.first().timestamp

            fun xOf(ts: Long)  = padL + ((ts - startMs).toFloat() / totalMs.coerceAtLeast(1L)) * chartW
            fun yOf(v: Double) = (padT + chartH * (1.0 - (v - yMin) / (yMax - yMin))).toFloat()

            val nc = drawContext.canvas.nativeCanvas
            val labelPaint = android.graphics.Paint().apply {
                color = textColor.copy(alpha = 0.7f).toArgb(); textSize = 22f; isAntiAlias = true
            }
            val xLabelPaint = android.graphics.Paint().apply {
                color = textColor.copy(alpha = 0.6f).toArgb(); textSize = 20f
                textAlign = android.graphics.Paint.Align.CENTER; isAntiAlias = true
            }

            // Y grid + labels
            var yTick = yMin
            while (yTick <= yMax + 0.01) {
                val y = yOf(yTick)
                drawLine(gridColor, Offset(padL, y), Offset(w - padR, y), 1f)
                labelPaint.textAlign = android.graphics.Paint.Align.RIGHT
                nc.drawText("%.0f".format(yTick), padL - 6f, y + 8f, labelPaint)
                yTick += yStep
            }

            // Y axis label
            val yAxisPaint = android.graphics.Paint().apply {
                color = textColor.copy(alpha = 0.55f).toArgb(); textSize = 19f
                textAlign = android.graphics.Paint.Align.CENTER; isAntiAlias = true
            }
            nc.save(); nc.rotate(-90f, 18f, padT + chartH / 2f)
            nc.drawText("°C", 18f, padT + chartH / 2f, yAxisPaint); nc.restore()

            // X axis
            drawLine(axisColor, Offset(padL, padT + chartH), Offset(w - padR, padT + chartH), 1.5f)
            for (i in 0..4) {
                val frac = i / 4.0
                val ts   = startMs + (totalMs * frac).toLong()
                val mins = ((ts - startMs) / 60_000.0).roundToInt()
                nc.drawText("+${mins}m", xOf(ts), h - 4f, xLabelPaint)
            }

            // Draw three series
            fun drawSeries(vals: List<Double>, color: androidx.compose.ui.graphics.Color, width: Float = 2.5f) {
                if (vals.size < 2) return
                val path = Path().apply {
                    moveTo(xOf(dataPoints.first().timestamp), yOf(vals.first()))
                    vals.drop(1).forEachIndexed { i, v -> lineTo(xOf(dataPoints[i + 1].timestamp), yOf(v)) }
                }
                drawPath(path, color, style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }

            drawSeries(minVals, minColor, 2f)
            drawSeries(maxVals, maxColor, 2f)
            drawSeries(avgVals, avgColor, 3f)  // avg on top
        }
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

@Composable
private fun ChartLegendItem(color: androidx.compose.ui.graphics.Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(modifier = Modifier.size(20.dp, 3.dp)) {
            drawLine(color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 3f)
        }
        Spacer(Modifier.width(5.dp))
        Text(label, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun niceStep(range: Double): Double = when {
    range <= 5    -> 1.0
    range <= 20   -> 5.0
    range <= 50   -> 10.0
    range <= 200  -> 25.0
    range <= 500  -> 50.0
    else          -> 100.0
}

private fun formatDurationLong(seconds: Long): String {
    val h = TimeUnit.SECONDS.toHours(seconds)
    val m = TimeUnit.SECONDS.toMinutes(seconds) % 60
    val s = seconds % 60
    return if (h > 0) "${h}h ${m}m ${s}s" else "${m}m ${s}s"
}