package com.byd.tripstats.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.byd.tripstats.ui.viewmodel.DashboardViewModel
import kotlin.math.roundToInt

// BYD Seal average consumption reference line
private const val SEAL_AVERAGE_KWH = 19.0

/**
 * Thumbnail version — plain sparkline, no axes/labels/legend.
 * Intended to sit as a small preview in the EnergyFlow box.
 */
@Composable
fun WeeklyEnergyThumbnail(
    data: List<DashboardViewModel.DailyEfficiency>,
    modifier: Modifier = Modifier
) {
    val lineColor = Color(0xFF64B5F6)  // light blue

    Canvas(modifier = modifier) {
        if (data.size < 2) return@Canvas

        val w = size.width
        val h = size.height
        val values = data.map { it.avgKwhPer100km.toFloat() }
        val vMin = values.min() * 0.9f
        val vMax = values.max() * 1.1f

        fun xOf(i: Int) = i / (data.size - 1).toFloat() * w
        fun yOf(v: Float) = h - (v - vMin) / (vMax - vMin) * h

        val path = Path().apply {
            moveTo(xOf(0), yOf(values[0]))
            values.drop(1).forEachIndexed { i, v -> lineTo(xOf(i + 1), yOf(v)) }
        }
        drawPath(path, lineColor, style = Stroke(width = 2.5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/**
 * Expanded full chart with:
 *  • Y-axis: kWh/100km with labels
 *  • X-axis: DD-MM date labels
 *  • Data points (circles) for each day
 *  • Dotted reference line at 19 kWh/100km labeled "BYD Seal average"
 */
@Composable
fun WeeklyEnergyChartExpanded(
    data: List<DashboardViewModel.DailyEfficiency>,
    modifier: Modifier = Modifier
) {
    val lineColor      = Color(0xFF64B5F6)
    val pointColor     = Color(0xFF1E88E5)
    val sealLineColor  = Color(0xFFF5A623).copy(alpha = 0.85f)
    val textColor      = MaterialTheme.colorScheme.onSurface
    val gridColor      = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f)
    val axisColor      = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)

    Column(modifier = modifier.fillMaxSize()) {

        Text(
            text = "Energy Consumption — Last 7 Days",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp)
        )

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 8.dp, vertical = 8.dp)
        ) {
            val w = size.width
            val h = size.height

            val padL = 64f   // Y labels
            val padR = 16f
            val padT = 16f
            val padB = 40f   // X labels

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
            // Round to clean step boundaries
            val yStep = when {
                rawMax - rawMin < 5  -> 1.0
                rawMax - rawMin < 15 -> 2.0
                else                 -> 5.0
            }
            val yMin = (rawMin / yStep).toInt() * yStep - yStep
            val yMax = (rawMax / yStep).toInt() * yStep + yStep

            fun xOf(i: Int): Float {
                return if (data.size == 1) padL + chartW / 2f
                else padL + i / (data.size - 1).toFloat() * chartW
            }
            fun yOf(v: Double): Float {
                val frac = (v - yMin) / (yMax - yMin)
                return (padT + chartH * (1.0 - frac)).toFloat()
            }

            val labelPaint = android.graphics.Paint().apply {
                color       = textColor.copy(alpha = 0.7f).toArgb()
                textSize    = 22f
                isAntiAlias = true
            }
            val xLabelPaint = android.graphics.Paint().apply {
                color       = textColor.copy(alpha = 0.7f).toArgb()
                textSize    = 20f
                textAlign   = android.graphics.Paint.Align.CENTER
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

            // Y axis label ("kWh/100km") rotated vertically
            val yAxisPaint = android.graphics.Paint().apply {
                color       = textColor.copy(alpha = 0.55f).toArgb()
                textSize    = 19f
                textAlign   = android.graphics.Paint.Align.CENTER
                isAntiAlias = true
            }
            nc.save()
            nc.rotate(-90f, 18f, padT + chartH / 2f)
            nc.drawText("kWh/100km", 18f, padT + chartH / 2f, yAxisPaint)
            nc.restore()

            // X axis line
            drawLine(
                axisColor,
                Offset(padL, padT + chartH), Offset(w - padR, padT + chartH), 1.5f
            )

            // X labels
            data.forEachIndexed { i, d ->
                nc.drawText(d.dateLabel, xOf(i), h - 8f, xLabelPaint)
            }

            // BYD Seal average reference line (dotted)
            val sealY = yOf(SEAL_AVERAGE_KWH)
            drawLine(
                color       = sealLineColor,
                start       = Offset(padL, sealY),
                end         = Offset(w - padR, sealY),
                strokeWidth = 2f,
                pathEffect  = PathEffect.dashPathEffect(floatArrayOf(10f, 7f))
            )
            // Seal label at right end of reference line
            labelPaint.color     = sealLineColor.toArgb()
            labelPaint.textSize  = 19f
            labelPaint.textAlign = android.graphics.Paint.Align.RIGHT
            nc.drawText("BYD Seal avg (${SEAL_AVERAGE_KWH.toInt()} kWh)", w - padR - 4f, sealY - 6f, labelPaint)

            if (data.size >= 2) {
                // Line connecting daily points
                val linePath = Path().apply {
                    moveTo(xOf(0), yOf(values[0]))
                    values.drop(1).forEachIndexed { i, v -> lineTo(xOf(i + 1), yOf(v)) }
                }
                drawPath(linePath, lineColor, style = Stroke(width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }

            // Data points
            data.forEachIndexed { i, d ->
                val x = xOf(i)
                val y = yOf(d.avgKwhPer100km)
                drawCircle(pointColor.copy(alpha = 0.25f), 14f, Offset(x, y))
                drawCircle(pointColor,                      6f,  Offset(x, y))
                drawCircle(Color.White,                     2.5f, Offset(x, y))
                // Value label above point
                labelPaint.color     = textColor.toArgb()
                labelPaint.textSize  = 20f
                labelPaint.textAlign = android.graphics.Paint.Align.CENTER
                nc.drawText("%.1f".format(d.avgKwhPer100km), x, y - 18f, labelPaint)
            }
        }
    }
}