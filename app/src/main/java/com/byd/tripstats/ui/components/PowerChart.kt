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
import androidx.compose.ui.graphics.Color
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
import com.byd.tripstats.ui.theme.*
import kotlin.math.roundToInt

@Composable
fun PowerChart(
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

    val accelColor = AccelerationOrange
    val regenColor = RegenGreen
    val textColor  = MaterialTheme.colorScheme.onSurface
    val gridColor  = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)
    val axisColor  = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
    val zeroColor  = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
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
        val values = dataPoints.map { it.power.toFloat() }
        val rawMin = values.min().coerceAtMost(0f)
        val rawMax = values.max().coerceAtLeast(0f)
        val yStep = when {
            rawMax - rawMin < 20f -> 5.0; rawMax - rawMin < 60f -> 10.0
            rawMax - rawMin < 150f -> 25.0; else -> 50.0
        }
        val yMin = (rawMin / yStep).toInt() * yStep - yStep
        val yMax = (rawMax / yStep).toInt() * yStep + yStep
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
            nc.drawText("%.0f".format(yTick), padL - 6f, y + 8f, labelPaint)
            yTick += yStep
        }
        val yAxisPaint = android.graphics.Paint().apply {
            color = textColor.copy(alpha = 0.55f).toArgb(); textSize = 19f
            textAlign = android.graphics.Paint.Align.CENTER; isAntiAlias = true
        }
        nc.save(); nc.rotate(-90f, 18f, padT + chartH / 2f)
        nc.drawText("kW", 18f, padT + chartH / 2f, yAxisPaint); nc.restore()
        drawLine(axisColor, Offset(padL, padT + chartH), Offset(w - padR, padT + chartH), 1.5f)
        val zeroY = yOf(0f)
        if (zeroY > padT && zeroY < padT + chartH)
            drawLine(zeroColor, Offset(padL, zeroY), Offset(w - padR, zeroY), 1.5f)
        val labelEvery = when {
            dataPoints.size > 200 -> 40; dataPoints.size > 100 -> 20; dataPoints.size > 50 -> 10; else -> 5
        }
        val minLabelGap = 72f
        var lastLabelX = -minLabelGap
        dataPoints.forEachIndexed { i, _ ->
            if (i % labelEvery == 0 || i == dataPoints.size - 1) {
                val x = xOf(i)
                if (x - lastLabelX >= minLabelGap) {
                    val secs = if (dataPoints.size > 1) (i / (dataPoints.size - 1).toFloat()) * totalDuration else 0.0
                    nc.drawText("%d:%02d".format((secs / 60).toInt(), (secs % 60).toInt()), x, h - 8f, xLabelPaint)
                    lastLabelX = x
                }
            }
        }
        if (dataPoints.size >= 2) {
            val posPath = Path().apply {
                var started = false
                values.forEachIndexed { i, v ->
                    val x = xOf(i); val y = yOf(v.coerceAtLeast(0f))
                    if (!started) { moveTo(x, zeroY); started = true }; lineTo(x, y)
                }
                lineTo(xOf(values.size - 1), zeroY); close()
            }
            drawPath(posPath, Brush.verticalGradient(
                colors = listOf(accelColor.copy(alpha = 0.40f), accelColor.copy(alpha = 0f)),
                startY = padT, endY = zeroY
            ))
            val negPath = Path().apply {
                var started = false
                values.forEachIndexed { i, v ->
                    val x = xOf(i); val y = yOf(v.coerceAtMost(0f))
                    if (!started) { moveTo(x, zeroY); started = true }; lineTo(x, y)
                }
                lineTo(xOf(values.size - 1), zeroY); close()
            }
            drawPath(negPath, Brush.verticalGradient(
                colors = listOf(regenColor.copy(alpha = 0f), regenColor.copy(alpha = 0.40f)),
                startY = zeroY, endY = padT + chartH
            ))
            // Draw line segment-by-segment so color switches exactly at the zero crossing.
            // When the sign changes between two points, interpolate the precise zero-crossing
            // X position and split the segment there so the color join is clean.
            val stroke = Stroke(width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            var segPath = Path()
            var segColor = if (values[0] >= 0f) accelColor else regenColor
            segPath.moveTo(xOf(0), yOf(values[0]))

            for (i in 1 until values.size) {
                val v0 = values[i - 1]; val v1 = values[i]
                val x0 = xOf(i - 1);   val x1 = xOf(i)
                val y1 = yOf(v1)

                if ((v0 >= 0f) == (v1 >= 0f)) {
                    // Same side — just extend the current segment
                    segPath.lineTo(x1, y1)
                } else {
                    // Zero crossing — interpolate the exact X where value == 0
                    val t       = v0 / (v0 - v1)            // fraction along the segment
                    val xCross  = x0 + t * (x1 - x0)
                    val yCross  = yOf(0f)

                    // Close the current segment at the crossing point and draw it
                    segPath.lineTo(xCross, yCross)
                    drawPath(segPath, segColor.copy(alpha = 0.9f), style = stroke)

                    // Start a new segment from the crossing point in the new color
                    segColor = if (v1 >= 0f) accelColor else regenColor
                    segPath = Path()
                    segPath.moveTo(xCross, yCross)
                    segPath.lineTo(x1, y1)
                }
            }
            // Draw the final segment
            drawPath(segPath, segColor.copy(alpha = 0.9f), style = stroke)
        }
        touchPos?.let { tp ->
            if (tp.x in padL..(w - padR) && dataPoints.size > 1) {
                val idx = ((tp.x - padL) / chartW * (dataPoints.size - 1)).roundToInt().coerceIn(0, dataPoints.size - 1)
                val v = values[idx]
                val secs = (idx / (dataPoints.size - 1).toFloat()) * totalDuration
                val realTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(dataPoints[idx].timestamp))
                val durationStr = "+%d:%02d into trip".format((secs / 60).toInt(), (secs % 60).toInt())
                val modeLabel = when { v > 5f -> "Accel"; v < -5f -> "Regen"; else -> "Cruise" }
                val dotColor = when { v < -5f -> regenColor; else -> accelColor }
                drawCrosshair(
                    cx = xOf(idx), cy = yOf(v), w = w,
                    padL = padL, padR = padR, padT = padT, chartH = chartH,
                    line1 = "%.1f kW  ($modeLabel)".format(v),
                    line2 = realTime,
                    line3 = durationStr,
                    accentColor = dotColor, textColor = textColor
                )
            }
        }
    }
}