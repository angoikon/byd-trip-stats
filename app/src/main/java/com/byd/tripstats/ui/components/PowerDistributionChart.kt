package com.byd.tripstats.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import com.byd.tripstats.ui.theme.*

/**
 * Power distribution bar chart.
 * Color per bucket communicates the driving mode at a glance:
 *   Regen buckets → RegenGreen (recovering energy)
 *   Cruising       → BydElectricAzure (neutral efficient state)
 *   Accel buckets → AccelerationOrange (consuming energy)
 */
@Composable
fun PowerDistributionChart(
    powerDistribution: Map<String, Double>,
    modifier: Modifier = Modifier
) {
    if (powerDistribution.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text("No data available", style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    data class Bucket(val key: String, val label: String)

    val buckets = remember {
        listOf(
            Bucket("regen_strong",       "Strong regen"),
            Bucket("regen_medium",       "Medium regen"),
            Bucket("regen_light",        "Light regen"),
            Bucket("cruising",           "Cruise"),
            Bucket("acceleration",       "Accel"),
            Bucket("hard_acceleration",  "Drive like you stole it"),
        )
    }

    val barColors = listOf(
        RegenGreen,             // strong regen
        RegenGreen,             // medium regen
        RegenGreen.copy(alpha = 0.7f), // light regen — slightly muted
        BydElectricAzure,          // cruising
        AccelerationOrange.copy(alpha = 0.75f), // acceleration
        BydErrorRed,     // hard acceleration
    )

    val values = remember(powerDistribution) {
        buckets.map { powerDistribution[it.key]?.toFloat() ?: 0f }
    }

    if (values.all { it == 0f }) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text("Insufficient data", style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }

    val textColor  = MaterialTheme.colorScheme.onSurface
    val gridColor  = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)
    val axisColor  = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width; val h = size.height
        val padL = 80f; val padR = 16f; val padT = 16f; val padB = 40f  // single-line x labels
        val chartW = w - padL - padR; val chartH = h - padT - padB
        val nc = drawContext.canvas.nativeCanvas

        val maxVal = values.max().coerceAtLeast(1f)
        val yStep = when {
            maxVal < 50f   -> 10.0; maxVal < 200f  -> 50.0
            maxVal < 500f  -> 100.0; else           -> 200.0
        }
        val yMax = (maxVal / yStep).toInt() * yStep + yStep

        fun yOf(v: Float): Float {
            val frac = v / yMax
            return (padT + chartH * (1.0 - frac)).toFloat()
        }

        val labelPaint = android.graphics.Paint().apply {
            color = textColor.copy(alpha = 0.7f).toArgb(); textSize = 22f; isAntiAlias = true
        }

        // Y grid + labels
        var yTick = 0.0
        while (yTick <= yMax + 0.01) {
            val y = yOf(yTick.toFloat())
            drawLine(gridColor, Offset(padL, y), Offset(w - padR, y), 1f)
            labelPaint.textAlign = android.graphics.Paint.Align.RIGHT
            nc.drawText("%.0f".format(yTick), padL - 6f, y + 8f, labelPaint)
            yTick += yStep
        }

        // X axis
        drawLine(axisColor, Offset(padL, padT + chartH), Offset(w - padR, padT + chartH), 1.5f)

        // Bars
        val n = buckets.size
        val totalGap = chartW * 0.35f          // 35% of width is gaps
        val barW = (chartW - totalGap) / n
        val gapW = totalGap / (n + 1)          // equal gap before/between/after bars

        buckets.forEachIndexed { i, bucket ->
            val barX = padL + gapW * (i + 1) + barW * i
            val barTop = yOf(values[i])
            val barH = (padT + chartH) - barTop

            if (barH > 0f) {
                // Bar fill
                drawRoundRect(
                    color = barColors[i],
                    topLeft = Offset(barX, barTop),
                    size = Size(barW, barH),
                    cornerRadius = CornerRadius(6f, 6f)
                )
                // Value label above bar
                labelPaint.textAlign = android.graphics.Paint.Align.CENTER
                labelPaint.color = barColors[i].copy(alpha = 0.9f).toArgb()
                labelPaint.textSize = 20f
                nc.drawText("${values[i].toInt()}", barX + barW / 2f, barTop - 6f, labelPaint)
            }

            // X axis label — single line, smaller font so all 6 fit without wrapping
            val xLabelPaint = android.graphics.Paint().apply {
                color = textColor.copy(alpha = 0.65f).toArgb(); textSize = 16f
                textAlign = android.graphics.Paint.Align.CENTER; isAntiAlias = true
            }
            nc.drawText(bucket.label, barX + barW / 2f, padT + chartH + 22f, xLabelPaint)
        }
    }
}