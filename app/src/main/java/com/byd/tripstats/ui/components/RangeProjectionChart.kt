package com.byd.tripstats.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.byd.tripstats.ui.theme.*
import kotlin.math.roundToInt

/**
 * A single telemetry snapshot recorded during a trip.
 *
 * @param distanceKm             Cumulative km driven since trip start
 * @param soc                    Battery state-of-charge at this point (0..100)
 * @param electricDrivingRangeKm Car's BMS-reported remaining range (used as secondary reference)
 */
data class RangeDataPoint(
    val distanceKm: Double,
    val soc: Double,
    val electricDrivingRangeKm: Int
)

// Both conditions must be met before trusting the observed efficiency formula.
// On a short trip with a small SoC drop, even 0.5% sensor noise causes
// hundreds of km of error in (kmDriven / socDrop) × remainingSoc.
// 8 km + 3% drop gives a noise-to-signal ratio below ~15%, which is acceptable.
private const val MIN_KM_FOR_OBSERVED   = 8.0   // km driven since trip start
private const val MIN_SOC_DROP_FOR_OBS  = 3.0   // absolute SoC % drop

/**
 * Range Projection Chart
 *
 * X-axis : Distance driven during the trip (km)
 * Y-axis : Estimated remaining range (km)
 *
 * Two curves are drawn:
 *  • Observed projection (colored, main) — "given how efficiently I've actually been
 *    driving this trip, how far will I realistically get?"
 *    Formula: (kmDriven / socDrop) × currentSoc
 *    Below MIN_KM_FOR_OBSERVED it falls back to (soc / startSoc) × startBmsRange
 *    to avoid instability from tiny early SOC noise.
 *
 *  • BMS estimate (gray dashed, secondary) — the car's own electricDrivingRangeKm.
 *    Shown for reference only. With aggressive driving this is typically optimistic.
 *
 * Area fill:
 *  • Green  → observed > BMS  (you're doing better than the car expects)
 *  • Orange → observed < BMS  (you're burning more than the car expects)
 */
@Composable
fun RangeProjectionChart(
    dataPoints: List<RangeDataPoint>,
    liveSoc: Double = 100.0,
    liveElectricRangeKm: Int = 0,
    modifier: Modifier = Modifier
) {
    // ── Derived values ────────────────────────────────────────────────────────

    val points = remember(dataPoints) {
        dataPoints
            .sortedBy { it.distanceKm }
            .distinctBy { (it.distanceKm * 10).roundToInt() }
    }

    val startSoc      = points.firstOrNull()?.soc ?: liveSoc
    // BMS range at trip start — used as the fallback anchor and Y-axis scale
    val startBmsRange = points.firstOrNull()?.electricDrivingRangeKm?.toDouble()
        ?: liveElectricRangeKm.toDouble().takeIf { it > 0 }
        ?: (liveSoc / 100.0 * 400.0)   // last-resort rough fallback

    val maxDistanceKm = points.lastOrNull()?.distanceKm?.coerceAtLeast(1.0) ?: 1.0
    val currentSoc    = points.lastOrNull()?.soc ?: startSoc

    // Observed efficiency projection for a single point
    fun observedRange(distDriven: Double, soc: Double): Double {
        val socDrop = startSoc - soc
        return if (distDriven >= MIN_KM_FOR_OBSERVED && socDrop >= MIN_SOC_DROP_FOR_OBS) {
            (distDriven / socDrop) * soc            // observed km/SOC% × remaining SOC
        } else {
            (soc / startSoc) * startBmsRange        // stable fallback: scale by SOC ratio
        }
    }

    // Per-point observed projection series (drives the main curve)
    val observedPoints: List<Pair<Double, Double>> = remember(points) {
        points.map { p -> p.distanceKm to observedRange(p.distanceKm, p.soc) }
    }

    // Current observed projection — the headline number
    val currentObserved = observedRange(
        points.lastOrNull()?.distanceKm ?: 0.0,
        currentSoc
    )

    // BMS series — secondary reference line
    val bmsPoints: List<Pair<Double, Double>> = remember(points) {
        points.map { it.distanceKm to it.electricDrivingRangeKm.toDouble() }
    }
    val currentBms = points.lastOrNull()?.electricDrivingRangeKm?.toDouble() ?: startBmsRange

    // Positive delta = our observed projection is higher than BMS (beating expectations)
    val deltaKm     = currentObserved - currentBms
    val beating     = deltaKm >= 0
    val accentColor = if (beating) RegenGreen else AccelerationOrange

    val yMax = maxOf(startBmsRange, currentObserved, currentBms) * 1.08
    val yMin = 0.0

    // ── Theme colors ──────────────────────────────────────────────────────────
    val bmsLineColor  = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
    val gridLineColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f)
    val axisColor     = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
    val textColor     = MaterialTheme.colorScheme.onSurface

    // ── Header ────────────────────────────────────────────────────────────────
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, start = 16.dp, end = 16.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            Column {
                Text(
                    text  = "%.0f km projected range".format(currentObserved),
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = textColor
                )
                val sign = if (beating) "+" else ""
                Text(
                    text  = "$sign${"%.0f".format(deltaKm)} km vs BMS estimate",
                    style = MaterialTheme.typography.bodySmall,
                    color = accentColor
                )
            }
            Spacer(Modifier.weight(1f))
        }

        // ── Canvas ────────────────────────────────────────────────────────────
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 12.dp)
        ) {
            val w = size.width
            val h = size.height

            val padL = 80f
            val padR = 12f
            val padT = 12f
            val padB = 36f

            val chartW = w - padL - padR
            val chartH = h - padT - padB

            val nc = drawContext.canvas.nativeCanvas

            fun xOf(distKm: Double) = padL + (distKm / maxDistanceKm * chartW).toFloat()
            fun yOf(rangeKm: Double): Float {
                val fraction = (rangeKm - yMin) / (yMax - yMin)
                return (padT + chartH * (1f - fraction)).toFloat()
            }

            // Paint objects — created once, reused across all draw calls
            val labelPaint = android.graphics.Paint().apply {
                color = textColor.copy(alpha = 0.7f).toArgb()
                textSize = 20f; textAlign = android.graphics.Paint.Align.RIGHT; isAntiAlias = true
            }
            val xLabelPaint = android.graphics.Paint().apply {
                color = textColor.copy(alpha = 0.6f).toArgb()
                textSize = 19f; textAlign = android.graphics.Paint.Align.CENTER; isAntiAlias = true
            }
            val yAxisPaint = android.graphics.Paint().apply {
                color = textColor.copy(alpha = 0.55f).toArgb()
                textSize = 19f; textAlign = android.graphics.Paint.Align.CENTER; isAntiAlias = true
            }

            // Rotated Y-axis label
            nc.save()
            nc.rotate(-90f, 18f, padT + chartH / 2f)
            nc.drawText("km", 18f, padT + chartH / 2f, yAxisPaint)
            nc.restore()

            // Y grid lines + labels
            val yStepKm = when {
                startBmsRange > 300 -> 100.0
                startBmsRange > 150 -> 50.0
                startBmsRange > 75  -> 25.0
                else                -> 10.0
            }
            var yTick = (yMin / yStepKm).toInt() * yStepKm
            while (yTick <= yMax) {
                val y = yOf(yTick)
                drawLine(gridLineColor, Offset(padL, y), Offset(w - padR, y), 1f)
                nc.drawText("${yTick.roundToInt()}", padL - 6f, y + 5f, labelPaint)
                yTick += yStepKm
            }

            // X axis
            drawLine(axisColor, Offset(padL, padT + chartH), Offset(w - padR, padT + chartH), 1.5f)

            // X ticks — 5 evenly spaced labels
            val xStep = maxDistanceKm / 4.0
            for (i in 0..4) {
                val dist = i * xStep
                val x = xOf(dist)
                drawLine(axisColor, Offset(x, padT + chartH), Offset(x, padT + chartH + 5f), 1.5f)
                nc.drawText("%.1f".format(dist), x, h - 4f, xLabelPaint)
            }

            if (observedPoints.isNotEmpty()) {

                // ── Fill between observed and BMS curves ──────────────────────
                // Walk observed forward, then BMS backward to close the polygon
                if (bmsPoints.isNotEmpty()) {
                    val fillPath = Path().apply {
                        moveTo(xOf(observedPoints.first().first), yOf(observedPoints.first().second))
                        observedPoints.drop(1).forEach { (d, r) -> lineTo(xOf(d), yOf(r)) }
                        bmsPoints.reversed().forEach { (d, r) -> lineTo(xOf(d), yOf(r)) }
                        close()
                    }
                    val fillColor = if (beating) RegenGreen.copy(alpha = 0.15f)
                                    else         AccelerationOrange.copy(alpha = 0.12f)
                    drawPath(fillPath, fillColor)
                }

                // ── BMS reference line (gray dashed, secondary) ───────────────
                if (bmsPoints.size >= 2) {
                    val bmsPath = Path().apply {
                        moveTo(xOf(bmsPoints.first().first), yOf(bmsPoints.first().second))
                        bmsPoints.drop(1).forEach { (d, r) -> lineTo(xOf(d), yOf(r)) }
                    }
                    drawPath(
                        path  = bmsPath,
                        color = bmsLineColor,
                        style = Stroke(
                            width      = 2f,
                            cap        = StrokeCap.Round,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))
                        )
                    )
                }

                // ── Observed projection line (main, colored) ──────────────────
                val observedPath = Path().apply {
                    moveTo(xOf(observedPoints.first().first), yOf(observedPoints.first().second))
                    observedPoints.drop(1).forEach { (d, r) -> lineTo(xOf(d), yOf(r)) }
                }
                drawPath(
                    path  = observedPath,
                    color = accentColor,
                    style = Stroke(width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )

                // Current position dot on observed line
                val last = observedPoints.last()
                val dotX = xOf(last.first); val dotY = yOf(last.second)
                drawCircle(accentColor.copy(alpha = 0.25f), 18f, Offset(dotX, dotY))
                drawCircle(accentColor,                      8f,  Offset(dotX, dotY))
                drawCircle(Color.White,                      3f,  Offset(dotX, dotY))

            } else {
                nc.drawText(
                    "No trip data yet",
                    padL + chartW / 2f,
                    padT + chartH / 2f,
                    android.graphics.Paint().apply {
                        color = textColor.copy(alpha = 0.3f).toArgb()
                        textSize = 28f; textAlign = android.graphics.Paint.Align.CENTER; isAntiAlias = true
                    }
                )
            }
        }

        // ── Legend ────────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Canvas(modifier = Modifier.size(20.dp, 3.dp)) {
                drawLine(
                    color = bmsLineColor, start = Offset(0f, size.height / 2),
                    end = Offset(size.width, size.height / 2), strokeWidth = 3f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f))
                )
            }
            Spacer(Modifier.width(5.dp))
            Text(
                text  = "BMS estimate",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(20.dp))
            Canvas(modifier = Modifier.size(20.dp, 3.dp)) {
                drawLine(
                    color = accentColor, start = Offset(0f, size.height / 2),
                    end = Offset(size.width, size.height / 2), strokeWidth = 3f
                )
            }
            Spacer(Modifier.width(5.dp))
            Text(
                text  = "Observed projection",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(4.dp))
    }
}