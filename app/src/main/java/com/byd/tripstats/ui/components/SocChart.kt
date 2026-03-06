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
import com.byd.tripstats.ui.theme.BydElectricBlue
import kotlin.math.roundToInt

@Composable
fun SocChart(
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

    val lineColor = BydElectricBlue
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
        val values = dataPoints.map { it.soc.toFloat() }
        val yMin = 0.0; val yMax = 100.0; val yStep = 20.0
        fun xOf(i: Int) = if (dataPoints.size == 1) padL + chartW / 2f
                          else padL + i / (dataPoints.size - 1).toFloat() * chartW
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
            nc.drawText("${yTick.toInt()}%", padL - 6f, y + 8f, labelPaint)
            yTick += yStep
        }
        val yAxisPaint = android.graphics.Paint().apply {
            color = textColor.copy(alpha = 0.55f).toArgb(); textSize = 19f
            textAlign = android.graphics.Paint.Align.CENTER; isAntiAlias = true
        }
        nc.save(); nc.rotate(-90f, 18f, padT + chartH / 2f)
        nc.drawText("SoC %", 18f, padT + chartH / 2f, yAxisPaint); nc.restore()
        drawLine(axisColor, Offset(padL, padT + chartH), Offset(w - padR, padT + chartH), 1.5f)
        // Draw x-axis time labels with a pixel-gap guard so they never overlap.
        // labelEvery limits how many candidates we consider (cheap), then the
        // minLabelGap check rejects any candidate whose left edge would land too
        // close to the previous drawn label — including the unconditional last one.
        val labelEvery = when {
            dataPoints.size > 200 -> 40; dataPoints.size > 100 -> 20; dataPoints.size > 50 -> 10; else -> 5
        }
        val minLabelGap = 72f   // px — enough for a "999m" label at textSize 20
        var lastLabelX = -minLabelGap   // sentinel: allow the very first label
        dataPoints.forEachIndexed { i, _ ->
            if (i % labelEvery == 0 || i == dataPoints.size - 1) {
                val x = xOf(i)
                if (x - lastLabelX >= minLabelGap) {
                    val secs = if (dataPoints.size > 1) (i / (dataPoints.size - 1).toFloat()) * totalDuration else 0.0
                    nc.drawText("${(secs / 60).toInt()}m", x, h - 8f, xLabelPaint)
                    lastLabelX = x
                }
            }
        }
        if (dataPoints.size >= 2) {
            val areaPath = Path().apply {
                moveTo(xOf(0), yOf(values[0]))
                values.drop(1).forEachIndexed { i, v -> lineTo(xOf(i + 1), yOf(v)) }
                lineTo(xOf(dataPoints.size - 1), padT + chartH); lineTo(xOf(0), padT + chartH); close()
            }
            drawPath(areaPath, Brush.verticalGradient(
                colors = listOf(BydElectricBlue.copy(alpha = 0.40f), BydElectricBlue.copy(alpha = 0f)),
                startY = yOf(values.max()), endY = padT + chartH
            ))
            val linePath = Path().apply {
                moveTo(xOf(0), yOf(values[0]))
                values.drop(1).forEachIndexed { i, v -> lineTo(xOf(i + 1), yOf(v)) }
            }
            drawPath(linePath, lineColor, style = Stroke(width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        touchPos?.let { tp ->
            if (tp.x in padL..(w - padR) && dataPoints.size > 1) {
                val idx = ((tp.x - padL) / chartW * (dataPoints.size - 1)).roundToInt().coerceIn(0, dataPoints.size - 1)
                val secs = (idx / (dataPoints.size - 1).toFloat()) * totalDuration
                val clockTime = java.time.Instant.ofEpochMilli(dataPoints[idx].timestamp)
                    .atZone(java.time.ZoneId.systemDefault())
                    .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))
                drawCrosshair(
                    cx = xOf(idx), cy = yOf(values[idx]), w = w,
                    padL = padL, padR = padR, padT = padT, chartH = chartH,
                    line1 = "%.1f%%".format(values[idx]),
                    line2 = "${(secs / 60).toInt()}m ${(secs % 60).toInt()}s",
                    line3 = clockTime,
                    accentColor = lineColor, textColor = textColor
                )
            }
        }
    }
}