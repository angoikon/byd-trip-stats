package com.byd.tripstats.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import com.byd.tripstats.data.config.Drivetrain
import com.byd.tripstats.data.local.entity.TripDataPointEntity
import com.byd.tripstats.data.preferences.PreferencesManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.byd.tripstats.ui.theme.*
import kotlin.math.roundToInt

@Composable
fun MotorRpmChart(
    dataPoints: List<TripDataPointEntity>,
    modifier: Modifier = Modifier
) {
    if (dataPoints.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                "No motor data available",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    val context = LocalContext.current
    val prefs = remember { PreferencesManager(context.applicationContext) }
    val selectedCar by prefs.selectedCarConfig.collectAsState(initial = null)
    val car = selectedCar ?: return

    val rearColor  = BydElectricAzure
    val frontColor = MotorViolet
    val textColor  = MaterialTheme.colorScheme.onSurface
    val gridColor  = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)
    val axisColor  = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
    var touchPos by remember { mutableStateOf<Offset?>(null) }

    val showFront = car.drivetrain == Drivetrain.FWD || car.drivetrain == Drivetrain.AWD
    val showRear  = car.drivetrain == Drivetrain.RWD || car.drivetrain == Drivetrain.AWD

    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (showRear) {
                LegendDot(rearColor, "Rear motor")
            }

            if (showRear && showFront) {
                Spacer(Modifier.width(20.dp))
            }

            if (showFront) {
                LegendDot(frontColor, "Front motor")
            }
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        touchPos = down.position
                        drag(down.id) { change -> touchPos = change.position }
                        touchPos = null
                    }
                }
        ) {
            val w = size.width
            val h = size.height
            val padL = 80f
            val padR = 16f
            val padT = 16f
            val padB = 40f
            val chartW = w - padL - padR
            val chartH = h - padT - padB
            val nc = drawContext.canvas.nativeCanvas

            val rearValues  = dataPoints.map { it.engineSpeedRear.toFloat() }
            val frontValues = dataPoints.map { it.engineSpeedFront.toFloat() }

            val allValues = buildList {
                if (showRear) addAll(rearValues)
                if (showFront) addAll(frontValues)
            }

            val rawMax = allValues.maxOrNull()?.coerceAtLeast(100f) ?: 1000f
            val yStep = when {
                rawMax < 1000f -> 200.0
                rawMax < 3000f -> 500.0
                rawMax < 6000f -> 1000.0
                else -> 2000.0
            }
            val yMin = 0.0
            val yMax = (rawMax / yStep).toInt() * yStep + yStep

            fun xOf(i: Int) =
                if (dataPoints.size == 1) padL + chartW / 2f
                else padL + i / (dataPoints.size - 1).toFloat() * chartW

            fun yOf(v: Float): Float {
                return (padT + chartH * (1.0 - (v - yMin) / (yMax - yMin))).toFloat()
            }

            val totalDuration = if (dataPoints.size > 1) {
                (dataPoints.last().timestamp - dataPoints.first().timestamp) / 1000.0
            } else {
                0.0
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

            var yTick = yMin
            while (yTick <= yMax + 0.01) {
                val y = yOf(yTick.toFloat())
                drawLine(gridColor, Offset(padL, y), Offset(w - padR, y), 1f)
                labelPaint.textAlign = android.graphics.Paint.Align.RIGHT
                val rpmLabel =
                    if (yTick >= 1000.0) "${"%.0f".format(yTick / 1000.0)}K"
                    else "%.0f".format(yTick)
                nc.drawText(rpmLabel, padL - 6f, y + 8f, labelPaint)
                yTick += yStep
            }

            val yAxisPaint = android.graphics.Paint().apply {
                color = textColor.copy(alpha = 0.55f).toArgb()
                textSize = 19f
                textAlign = android.graphics.Paint.Align.CENTER
                isAntiAlias = true
            }

            nc.save()
            nc.rotate(-90f, 18f, padT + chartH / 2f)
            nc.drawText("RPM", 18f, padT + chartH / 2f, yAxisPaint)
            nc.restore()

            drawLine(axisColor, Offset(padL, padT + chartH), Offset(w - padR, padT + chartH), 1.5f)

            val labelEvery = when {
                dataPoints.size > 200 -> 40
                dataPoints.size > 100 -> 20
                dataPoints.size > 50 -> 10
                else -> 5
            }

            val minLabelGap = 72f
            var lastLabelX = -minLabelGap
            dataPoints.forEachIndexed { i, _ ->
                if (i % labelEvery == 0 || i == dataPoints.size - 1) {
                    val x = xOf(i)
                    if (x - lastLabelX >= minLabelGap) {
                        val secs =
                            if (dataPoints.size > 1) (i / (dataPoints.size - 1).toFloat()) * totalDuration
                            else 0.0
                        nc.drawText(
                            "%d:%02d".format((secs / 60).toInt(), (secs % 60).toInt()),
                            x,
                            h - 8f,
                            xLabelPaint
                        )
                        lastLabelX = x
                    }
                }
            }

            if (dataPoints.size >= 2) {
                if (showRear) {
                    val rearArea = Path().apply {
                        moveTo(xOf(0), yOf(rearValues[0]))
                        rearValues.drop(1).forEachIndexed { i, v -> lineTo(xOf(i + 1), yOf(v)) }
                        lineTo(xOf(rearValues.size - 1), padT + chartH)
                        lineTo(xOf(0), padT + chartH)
                        close()
                    }
                    drawPath(
                        rearArea,
                        Brush.verticalGradient(
                            colors = listOf(rearColor.copy(alpha = 0.35f), rearColor.copy(alpha = 0f)),
                            startY = yOf(rearValues.max()),
                            endY = padT + chartH
                        )
                    )
                    val rearLine = Path().apply {
                        moveTo(xOf(0), yOf(rearValues[0]))
                        rearValues.drop(1).forEachIndexed { i, v -> lineTo(xOf(i + 1), yOf(v)) }
                    }
                    drawPath(
                        rearLine,
                        rearColor.copy(alpha = 0.9f),
                        style = Stroke(width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                }

                if (showFront) {
                    val frontArea = Path().apply {
                        moveTo(xOf(0), yOf(frontValues[0]))
                        frontValues.drop(1).forEachIndexed { i, v -> lineTo(xOf(i + 1), yOf(v)) }
                        lineTo(xOf(frontValues.size - 1), padT + chartH)
                        lineTo(xOf(0), padT + chartH)
                        close()
                    }
                    drawPath(
                        frontArea,
                        Brush.verticalGradient(
                            colors = listOf(frontColor.copy(alpha = 0.30f), frontColor.copy(alpha = 0f)),
                            startY = yOf(frontValues.max()),
                            endY = padT + chartH
                        )
                    )
                    val frontLine = Path().apply {
                        moveTo(xOf(0), yOf(frontValues[0]))
                        frontValues.drop(1).forEachIndexed { i, v -> lineTo(xOf(i + 1), yOf(v)) }
                    }
                    drawPath(
                        frontLine,
                        MotorViolet.copy(alpha = 0.9f),
                        style = Stroke(width = 2.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                }
            }

            // Crosshair — shows only the motors available on the selected car
            touchPos?.let { tp ->
                if (tp.x in padL..(w - padR) && dataPoints.size > 1) {
                    val idx = ((tp.x - padL) / chartW * (dataPoints.size - 1))
                        .roundToInt()
                        .coerceIn(0, dataPoints.size - 1)

                    val secs = (idx / (dataPoints.size - 1).toFloat()) * totalDuration
                    val realTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                        .format(Date(dataPoints[idx].timestamp))
                    val durationStr = "+%d:%02d into trip".format(
                        (secs / 60).toInt(),
                        (secs % 60).toInt()
                    )

                    val rRpm = rearValues[idx]
                    val fRpm = frontValues[idx]

                    val anchorRpm = when (car.drivetrain) {
                        Drivetrain.FWD -> fRpm
                        Drivetrain.RWD -> rRpm
                        Drivetrain.AWD -> rRpm
                    }

                    val line1 = when (car.drivetrain) {
                        Drivetrain.FWD -> "F: ${formatRpm(fRpm)}"
                        Drivetrain.RWD -> "R: ${formatRpm(rRpm)}"
                        Drivetrain.AWD -> "R: ${formatRpm(rRpm)}  F: ${formatRpm(fRpm)}"
                    }

                    val accentColor = when (car.drivetrain) {
                        Drivetrain.FWD -> frontColor
                        Drivetrain.RWD -> rearColor
                        Drivetrain.AWD -> rearColor
                    }

                    drawCrosshair(
                        cx = xOf(idx),
                        cy = yOf(anchorRpm),
                        w = w,
                        padL = padL,
                        padR = padR,
                        padT = padT,
                        chartH = chartH,
                        line1 = line1,
                        line2 = realTime,
                        line3 = durationStr,
                        accentColor = accentColor,
                        textColor = textColor
                    )
                }
            }
        }
    }
}

private fun formatRpm(rpm: Float): String =
    if (rpm >= 1000f) "${"%.1f".format(rpm / 1000f)}K" else "%.0f".format(rpm)

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}