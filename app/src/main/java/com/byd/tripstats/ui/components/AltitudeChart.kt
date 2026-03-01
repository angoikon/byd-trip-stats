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
import com.byd.tripstats.ui.theme.BydArcticBlue
import kotlin.math.roundToInt

@Composable
fun AltitudeChart(
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

    val lineColor = BydArcticBlue
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
        val altitudes = dataPoints.map { it.altitude.toFloat() }
        val rawMin = altitudes.min(); val rawMax = altitudes.max()
        val range = (rawMax - rawMin).coerceAtLeast(10f)
        val yStep = when {
            range < 50f -> 10.0; range < 200f -> 25.0; range < 500f -> 50.0; else -> 100.0
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
        nc.drawText("m", 18f, padT + chartH / 2f, yAxisPaint); nc.restore()
        drawLine(axisColor, Offset(padL, padT + chartH), Offset(w - padR, padT + chartH), 1.5f)
        val labelEvery = when {
            dataPoints.size > 200 -> 40; dataPoints.size > 100 -> 20; dataPoints.size > 50 -> 10; else -> 5
        }
        dataPoints.forEachIndexed { i, _ ->
            if (i % labelEvery == 0 || i == dataPoints.size - 1) {
                val secs = if (dataPoints.size > 1) (i / (dataPoints.size - 1).toFloat()) * totalDuration else 0.0
                nc.drawText("${(secs / 60).toInt()}m", xOf(i), h - 8f, xLabelPaint)
            }
        }
        if (dataPoints.size >= 2) {
            val areaPath = Path().apply {
                moveTo(xOf(0), yOf(altitudes[0]))
                altitudes.drop(1).forEachIndexed { i, v -> lineTo(xOf(i + 1), yOf(v)) }
                lineTo(xOf(dataPoints.size - 1), padT + chartH); lineTo(xOf(0), padT + chartH); close()
            }
            drawPath(areaPath, Brush.verticalGradient(
                colors = listOf(BydArcticBlue.copy(alpha = 0.45f), BydArcticBlue.copy(alpha = 0f)),
                startY = yOf(altitudes.max()), endY = padT + chartH
            ))
            val linePath = Path().apply {
                moveTo(xOf(0), yOf(altitudes[0]))
                altitudes.drop(1).forEachIndexed { i, v -> lineTo(xOf(i + 1), yOf(v)) }
            }
            drawPath(linePath, lineColor, style = Stroke(width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        } else {
            drawCircle(lineColor, 6f, Offset(xOf(0), yOf(altitudes[0])))
        }
        touchPos?.let { tp ->
            if (tp.x in padL..(w - padR) && dataPoints.size > 1) {
                val idx = ((tp.x - padL) / chartW * (dataPoints.size - 1)).roundToInt().coerceIn(0, dataPoints.size - 1)
                val secs = (idx / (dataPoints.size - 1).toFloat()) * totalDuration
                drawCrosshair(
                    cx = xOf(idx), cy = yOf(altitudes[idx]), w = w,
                    padL = padL, padR = padR, padT = padT, chartH = chartH,
                    line1 = "%.0f m".format(altitudes[idx]),
                    line2 = "${(secs / 60).toInt()}m ${(secs % 60).toInt()}s",
                    accentColor = lineColor, textColor = textColor
                )
            }
        }
    }
}