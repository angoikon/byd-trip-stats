package com.byd.tripstats.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.byd.tripstats.ui.theme.*
import com.byd.tripstats.ui.viewmodel.DashboardViewModel

// BYD Seal average consumption reference line in kWh / 100km — used in ConsumptionCanvas as a horizontal benchmark line.
// Taken by ev-database.org real-world tests, not the official WLTP rating. See https://ev-database.org/car/2002/BYD-SEAL-825-kWh-AWD-Excellence for details.
private const val SEAL_AVERAGE_KWH = 18.5

// ── Thumbnail ─────────────────────────────────────────────────────────────────

/**
 * Sparkline thumbnail showing the 7-day consumption trend.
 * Tapping opens ConsumptionChartExpanded.
 */
@Composable
fun WeeklyEnergyThumbnail(
    data: List<DashboardViewModel.DailyEfficiency>,
    modifier: Modifier = Modifier
) {
    val lineColor = BydElectricAzure.copy(alpha = 0.8f)

    // clipToBounds prevents the line from bleeding outside the parent container
    Canvas(modifier = modifier.clipToBounds()) {
        if (data.size < 2) return@Canvas

        val padding = 8.dp.toPx() // Keep the line away from the actual edges
        val w = size.width - (padding * 2)
        val h = size.height - (padding * 2)
        
        val values = data.map { it.avgKwhPer100km.toFloat() }
        val vMin = values.minOrNull() ?: 0f
        val vMax = values.maxOrNull() ?: 1f
        val range = (vMax - vMin).coerceAtLeast(1f) // Avoid div by zero

        // Offset the entire drawing by the padding
        translate(left = padding, top = padding) {
            fun xOf(i: Int) = i / (data.size - 1).toFloat() * w
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

private enum class ConsumptionTab(val label: String) {
    WEEK("Daily consumption"),
    MONTH("Monthly consumption"),
    YEAR("Yearly consumption");
}

/**
 * Full-size consumption chart with a 3-tab segment control at the top.
 *
 * Tab design mirrors the BYD DiLink segment button style:
 *   - Selected:   filled cobalt pill, white bold text
 *   - Unselected: transparent pill with outline border, muted text
 * This gives three clearly distinct visual states at a glance.
 */
@Composable
fun ConsumptionChartExpanded(
    weeklyData: List<DashboardViewModel.DailyEfficiency>,
    monthlyData: List<DashboardViewModel.DailyEfficiency>,
    yearlyData: List<DashboardViewModel.DailyEfficiency>,
    onClose: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableStateOf(ConsumptionTab.WEEK) }

    val activeData = when (selectedTab) {
        ConsumptionTab.WEEK  -> weeklyData
        ConsumptionTab.MONTH -> monthlyData
        ConsumptionTab.YEAR  -> yearlyData
    }
    val labelEvery = when (selectedTab) {
        ConsumptionTab.WEEK  -> 1
        ConsumptionTab.MONTH -> 5
        ConsumptionTab.YEAR  -> 1
    }

    Column(modifier = modifier.fillMaxSize()) {

        // ── Tab navbar + close button ─────────────────────────────────────────
        // Close button lives OUTSIDE the clipped pill container so the Yearly
        // pill's right outline never gets cropped by the parent clip boundary.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // Pill container — only the three tabs live inside the clip
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(4.dp)
            ) {
                ConsumptionTab.entries.forEach { tab ->
                    val isSelected = tab == selectedTab
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(36.dp)
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
                            .clickable { selectedTab = tab }
                            .padding(vertical = 6.dp, horizontal = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = tab.label,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected)
                                MaterialTheme.colorScheme.onPrimary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (tab != ConsumptionTab.entries.last()) {
                        Spacer(Modifier.width(4.dp))
                    }
                }
            }

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
                    contentDescription = "Close chart",
                    tint = BydErrorRedLight,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        // ── Chart canvas ──────────────────────────────────────────────────────
        ConsumptionCanvas(
            data = activeData,
            labelEvery = labelEvery,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 8.dp, vertical = 4.dp)
        )
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

@Composable
private fun ConsumptionCanvas(
    data: List<DashboardViewModel.DailyEfficiency>,
    labelEvery: Int,
    modifier: Modifier = Modifier
) {
    // Resolve all theme-aware colors before entering DrawScope
    val lineColor     = BydElectricAzure.copy(alpha = 0.9f)
    val pointFill     = BydElectricAzure
    val pointGlow     = BydElectricAzure.copy(alpha = 0.20f)
    val sealLineColor = AccelerationOrange.copy(alpha = 0.9f)
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

        val nc = drawContext.canvas.nativeCanvas

        if (data.isEmpty()) {
            nc.drawText(
                "No data yet",
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

        val values  = data.map { it.avgKwhPer100km }
        val allVals = values + listOf(SEAL_AVERAGE_KWH)
        val rawMin  = allVals.min()
        val rawMax  = allVals.max()
        val yStep = when {
            rawMax - rawMin < 5  -> 1.0
            rawMax - rawMin < 15 -> 2.0
            else                 -> 5.0
        }
        val yMin = (rawMin / yStep).toInt() * yStep - yStep
        val yMax = (rawMax / yStep).toInt() * yStep + yStep

        fun xOf(i: Int): Float =
            if (data.size == 1) padL + chartW / 2f
            else padL + i / (data.size - 1).toFloat() * chartW

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
        nc.drawText("kWh/100km", 18f, padT + chartH / 2f, yAxisPaint)
        nc.restore()

        // X axis line
        drawLine(axisColor, Offset(padL, padT + chartH), Offset(w - padR, padT + chartH), 1.5f)

        // X labels
        data.forEachIndexed { i, d ->
            if (i % labelEvery == 0 || i == data.size - 1) {
                nc.drawText(d.dateLabel, xOf(i), h - 8f, xLabelPaint)
            }
        }

        // BYD Seal average reference line (dashed orange — distinct from cobalt line)
        val sealY = yOf(SEAL_AVERAGE_KWH)
        drawLine(
            color = sealLineColor,
            start = Offset(padL, sealY),
            end = Offset(w - padR, sealY),
            strokeWidth = 2f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 7f))
        )
        labelPaint.color     = sealLineColor.toArgb()
        labelPaint.textSize  = 19f
        labelPaint.textAlign = android.graphics.Paint.Align.LEFT
        nc.drawText(
            "BYD Seal avg (${SEAL_AVERAGE_KWH.toInt()} kWh)",
            padL + 6f,
            sealY - 6f,
            labelPaint
        )

        // Area fill under the data line — vertical gradient matching other charts
        if (data.size >= 2) {
            val areaPath = Path().apply {
                moveTo(xOf(0), yOf(values[0]))
                values.drop(1).forEachIndexed { i, v -> lineTo(xOf(i + 1), yOf(v)) }
                lineTo(xOf(data.size - 1), padT + chartH)
                lineTo(xOf(0), padT + chartH)
                close()
            }
            drawPath(areaPath, Brush.verticalGradient(
                colors = listOf(BydElectricAzure.copy(alpha = 0.35f), BydElectricAzure.copy(alpha = 0f)),
                startY = yOf(values.max()), endY = padT + chartH
            ))
        }

        // Data line
        if (data.size >= 2) {
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
        // Value label above the dot: always shown on all points.
        // X date label density is controlled separately by labelEvery (passed in from the tab).
        data.forEachIndexed { i, d ->
            val x = xOf(i)
            val y = yOf(d.avgKwhPer100km)

            drawCircle(pointGlow,  16f, Offset(x, y))
            drawCircle(pointFill,   7f, Offset(x, y))
            drawCircle(Color.White, 3f, Offset(x, y))

            // Value label always shown above every dot.
            // First point: LEFT-align so it doesn't bleed into Y-axis labels.
            // All others: CENTER.
            // Vertical: draw above dot normally, but if that would go above padT
            // (i.e. top of chart), draw below the dot instead.
            labelPaint.color     = textColor.toArgb()
            labelPaint.textSize  = 20f
            labelPaint.textAlign = if (i == 0)
                android.graphics.Paint.Align.LEFT
            else
                android.graphics.Paint.Align.CENTER
            val labelY = if (y - 20f < padT + 20f) y + 32f else y - 20f
            nc.drawText("%.1f".format(d.avgKwhPer100km), x, labelY, labelPaint)
        }
    }
}