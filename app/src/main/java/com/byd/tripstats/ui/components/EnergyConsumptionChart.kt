package com.byd.tripstats.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import com.byd.tripstats.data.local.entity.TripDataPointEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.byd.tripstats.ui.theme.ChargingYellow
import kotlin.math.roundToInt

@Composable
fun EnergyConsumptionChart(
    dataPoints: List<TripDataPointEntity>,
    modifier: Modifier = Modifier
) {
    if (dataPoints.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text("No data available", style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    val values = remember(dataPoints) {
        var cumulative = 0.0
        dataPoints.mapIndexed { i, point ->
            if (i > 0) cumulative += (point.totalDischarge - dataPoints[i - 1].totalDischarge)
            cumulative.toFloat()
        }
    }

    if (values.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text("Insufficient data", style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    val lineColor = ChargingYellow
    val textColor = MaterialTheme.colorScheme.onSurface
    val gridColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)
    val axisColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
    var touchPos by remember { mutableStateOf<Offset?>(null) }

    Canvas(modifier = modifier.fillMaxSize().pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown()
            touchPos = down.position
            drag(down.id) { change -> touchPos = change.position }
            touchPos = null
        }
    }) {
        val w = size.width; val h = size.height
        val padL = 80f; val padR = 16f; val padT = 16f; val padB = 40f
        val chartW = w - padL - padR; val chartH = h - padT - padB
        val nc = drawContext.canvas.nativeCanvas
        val rawMax = values.max().coerceAtLeast(1f)
        val yStep = when {
            rawMax < 5f -> 1.0; rawMax < 20f -> 2.0; rawMax < 50f -> 5.0; rawMax < 100f -> 10.0; else -> 20.0
        }
        val yMin = 0.0; val yMax = (rawMax / yStep).toInt() * yStep + yStep
        fun xOf(i: Int) = if (values.size == 1) padL + chartW / 2f
                          else padL + i / (values.size - 1).toFloat() * chartW
        fun yOf(v: Float): Float {
            return (padT + chartH * (1.0 - (v - yMin) / (yMax - yMin))).toFloat()
        }
        val totalDuration = if (dataPoints.size > 1)
            (dataPoints.last().timestamp - dataPoints.first().timestamp) / 1000.0 else 0.0
        val labelPaint = android.graphics.Paint().apply {
            color = textColor.copy(alpha = 0.7f).toArgb(); textSize = 22f; isAntiAlias = true
        }
        val xLabelPaint = android.graphics.Paint().apply {
            color = textColor.copy(alpha = 0.7f).toArgb(); textSize = 20f
            textAlign = android.graphics.Paint.Align.CENTER; isAntiAlias = true
        }
        var yTick = yMin
        while (yTick <= yMax + 0.01) {
            val y = yOf(yTick.toFloat())
            drawLine(gridColor, Offset(padL, y), Offset(w - padR, y), 1f)
            labelPaint.textAlign = android.graphics.Paint.Align.RIGHT
            nc.drawText("%.1f".format(yTick), padL - 6f, y + 8f, labelPaint)
            yTick += yStep
        }
        val yAxisPaint = android.graphics.Paint().apply {
            color = textColor.copy(alpha = 0.55f).toArgb(); textSize = 19f
            textAlign = android.graphics.Paint.Align.CENTER; isAntiAlias = true
        }
        nc.save(); nc.rotate(-90f, 18f, padT + chartH / 2f)
        nc.drawText("kWh", 18f, padT + chartH / 2f, yAxisPaint); nc.restore()
        drawLine(axisColor, Offset(padL, padT + chartH), Offset(w - padR, padT + chartH), 1.5f)
        val labelEvery = when {
            dataPoints.size > 200 -> 40; dataPoints.size > 100 -> 20; dataPoints.size > 50 -> 10; else -> 5
        }
        dataPoints.forEachIndexed { i, _ ->
            if (i % labelEvery == 0 || i == dataPoints.size - 1) {
                val secs = if (dataPoints.size > 1) (i / (dataPoints.size - 1).toFloat()) * totalDuration else 0.0
                nc.drawText("%d:%02d".format((secs / 60).toInt(), (secs % 60).toInt()), xOf(i), h - 8f, xLabelPaint)
            }
        }
        if (values.size >= 2) {
            val areaPath = Path().apply {
                moveTo(xOf(0), yOf(values[0]))
                values.drop(1).forEachIndexed { i, v -> lineTo(xOf(i + 1), yOf(v)) }
                lineTo(xOf(values.size - 1), padT + chartH); lineTo(xOf(0), padT + chartH); close()
            }
            drawPath(areaPath, Brush.verticalGradient(
                colors = listOf(ChargingYellow.copy(alpha = 0.40f), ChargingYellow.copy(alpha = 0f)),
                startY = yOf(values.max()), endY = padT + chartH
            ))
            val linePath = Path().apply {
                moveTo(xOf(0), yOf(values[0]))
                values.drop(1).forEachIndexed { i, v -> lineTo(xOf(i + 1), yOf(v)) }
            }
            drawPath(linePath, lineColor, style = Stroke(width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        touchPos?.let { tp ->
            if (tp.x in padL..(w - padR) && values.size > 1) {
                val idx = ((tp.x - padL) / chartW * (values.size - 1)).roundToInt().coerceIn(0, values.size - 1)
                val secs = (idx / (values.size - 1).toFloat()) * totalDuration
                val realTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(dataPoints[idx].timestamp))
                val durationStr = "+%d:%02d into trip".format((secs / 60).toInt(), (secs % 60).toInt())
                drawCrosshair(
                    cx = xOf(idx), cy = yOf(values[idx]), w = w,
                    padL = padL, padR = padR, padT = padT, chartH = chartH,
                    line1 = "%.2f kWh".format(values[idx]),
                    line2 = realTime,
                    line3 = durationStr,
                    accentColor = lineColor, textColor = textColor
                )
            }
        }
    }
}