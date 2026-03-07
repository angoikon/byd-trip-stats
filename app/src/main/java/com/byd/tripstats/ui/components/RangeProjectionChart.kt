package com.byd.tripstats.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButtonDefaults.Icon
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
import com.byd.tripstats.ui.viewmodel.DashboardViewModel
import kotlin.math.roundToInt

/**
 * A single telemetry snapshot recorded during a trip.
 *
 * @param distanceKm             Cumulative km driven since trip start
 * @param soc                    Battery state-of-charge at this point (0..100)
 * @param electricDrivingRangeKm Car's BMS-reported remaining range (secondary reference line)
 * @param projectedRangeKm       Power-integrated realistic projection computed in ViewModel.
 *                               Null during the stabilisation window (first STABILISATION_KM).
 * @param isStabilised           False while the projection is still warming up — chart shows
 *                               a "Calibrating…" badge and falls back to BMS line.
 */
data class RangeDataPoint(
    val distanceKm:             Double,
    val soc:                    Double,
    val electricDrivingRangeKm: Int,
    val projectedRangeKm:       Double?  = null,
    val isStabilised:           Boolean  = false
)

/**
 * Range Projection Chart
 *
 * X-axis : Distance driven during the trip (km)
 * Y-axis : Estimated remaining range (km)
 *
 * Two curves are drawn:
 *  • Projected range (colored, main) — computed in the ViewModel by integrating
 *    engine_power (kW) over time to get real Wh/km consumption, then:
 *    projectedKm = (BATTERY_KWH × 1000 × soc/100) / consumptionWhPerKm
 *    Shown only after the STABILISATION_KM warmup window; "Calibrating…" before that.
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
    activeRangeModel: DashboardViewModel.RangeModel = DashboardViewModel.RangeModel.BASELINE,
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

    // BMS range at trip start — used as Y-axis scale anchor
    val startBmsRange = points.firstOrNull()?.electricDrivingRangeKm?.toDouble()
        ?: liveElectricRangeKm.toDouble().takeIf { it > 0 }
        ?: (liveSoc / 100.0 * 400.0)   // last-resort rough fallback

    val maxDistanceKm = points.lastOrNull()?.distanceKm?.coerceAtLeast(1.0) ?: 1.0

    // The projection is pre-computed in the ViewModel via power integration.
    // Only points where isStabilised=true carry a valid projectedRangeKm.
    val projectedPoints: List<Pair<Double, Double>> = remember(points) {
        points.mapNotNull { p ->
            p.projectedRangeKm?.let { p.distanceKm to it }
        }
    }

    // BMS series — secondary reference line
    val bmsPoints: List<Pair<Double, Double>> = remember(points) {
        points.map { it.distanceKm to it.electricDrivingRangeKm.toDouble() }
    }

    // Whether we have enough data to show the real projection yet
    val isStabilised    = points.lastOrNull()?.isStabilised ?: false
    val currentBms      = points.lastOrNull()?.electricDrivingRangeKm?.toDouble() ?: startBmsRange
    val currentProjected = projectedPoints.lastOrNull()?.second ?: currentBms

    // Positive delta = our projection is higher than BMS (you're beating expectations)
    val deltaKm     = currentProjected - currentBms
    val beating     = deltaKm >= 0
    val accentColor = if (beating) RegenGreen else AccelerationOrange

    // Anchor Y-max to trip-start BMS range + 15% headroom.
    // Using live values caused the axis to rescale as the projection converged,
    // producing distracting visual jumps. A fixed ceiling keeps the chart stable.
    val yMax = startBmsRange * 1.15
    val yMin = 0.0

    // ── Theme colors ──────────────────────────────────────────────────────────
    val bmsLineColor  = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
    val gridLineColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f)
    val axisColor     = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
    val textColor     = MaterialTheme.colorScheme.onSurface

    // ── Header ────────────────────────────────────────────────────────────────
    Column(modifier = modifier.fillMaxSize()) {
        val modelLabel = when (activeRangeModel) {
            DashboardViewModel.RangeModel.LIVE_TRIP        -> "● Live trip"
            DashboardViewModel.RangeModel.HISTORICAL_BINS  -> "● Speed bins"
            DashboardViewModel.RangeModel.LIFETIME_AVERAGE -> "● Lifetime avg"
            DashboardViewModel.RangeModel.BASELINE         -> "● Baseline"
        }
        val modelColor = when (activeRangeModel) {
            DashboardViewModel.RangeModel.LIVE_TRIP       -> RegenGreen
            DashboardViewModel.RangeModel.HISTORICAL_BINS -> AccelerationOrange
            else                                          -> MaterialTheme.colorScheme.onSurfaceVariant
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            Column {
                if (!isStabilised && activeRangeModel == DashboardViewModel.RangeModel.BASELINE) {
                    Text(
                        text  = "Calibrating…",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = textColor.copy(alpha = 0.5f)
                    )
                    Text(
                        text  = "BMS: ${"%.0f".format(currentBms)} km (collecting data)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text  = "%.0f km projected range".format(currentProjected),
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
            }
            Spacer(Modifier.weight(1f))
            // Model badge stacked, right-aligned
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text  = modelLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = modelColor
                )
            }
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

            if (bmsPoints.isNotEmpty()) {

                // ── BMS reference line (gray dashed, always visible) ──────────
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

                // ── Projected line — only drawn once stabilised ───────────────
                if (projectedPoints.size >= 2) {

                    // Fill between projected and BMS curves.
                    // BMS is linearly interpolated at each projected X so the fill
                    // is correct even when the two series have slightly different
                    // sample positions (which happens after the stabilisation window
                    // starts mid-trip and projected points begin later than BMS points).
                    fun interpolateBmsAt(targetDist: Double): Double {
                        if (bmsPoints.isEmpty()) return 0.0
                        if (targetDist <= bmsPoints.first().first) return bmsPoints.first().second
                        if (targetDist >= bmsPoints.last().first)  return bmsPoints.last().second
                        val hi = bmsPoints.indexOfFirst { it.first >= targetDist }
                        if (hi <= 0) return bmsPoints.first().second
                        val lo = hi - 1
                        val (d0, r0) = bmsPoints[lo]
                        val (d1, r1) = bmsPoints[hi]
                        val t = if (d1 > d0) (targetDist - d0) / (d1 - d0) else 0.0
                        return r0 + t * (r1 - r0)
                    }

                    val fillPath = Path().apply {
                        moveTo(xOf(projectedPoints.first().first), yOf(projectedPoints.first().second))
                        projectedPoints.drop(1).forEach { (d, r) -> lineTo(xOf(d), yOf(r)) }
                        // Walk BMS backwards using interpolated Y at each projected X
                        projectedPoints.reversed().forEach { (d, _) ->
                            lineTo(xOf(d), yOf(interpolateBmsAt(d)))
                        }
                        close()
                    }
                    val fillColor = if (beating) RegenGreen.copy(alpha = 0.15f)
                                    else         AccelerationOrange.copy(alpha = 0.12f)
                    drawPath(fillPath, fillColor)

                    // Projected line (main, colored)
                    val projPath = Path().apply {
                        moveTo(xOf(projectedPoints.first().first), yOf(projectedPoints.first().second))
                        projectedPoints.drop(1).forEach { (d, r) -> lineTo(xOf(d), yOf(r)) }
                    }
                    drawPath(
                        path  = projPath,
                        color = accentColor,
                        style = Stroke(width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )

                    // Current position dot on projected line
                    val last = projectedPoints.last()
                    val dotX = xOf(last.first); val dotY = yOf(last.second)
                    drawCircle(accentColor.copy(alpha = 0.25f), 18f, Offset(dotX, dotY))
                    drawCircle(accentColor,                      8f,  Offset(dotX, dotY))
                    drawCircle(Color.White,                      3f,  Offset(dotX, dotY))
                }

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
                text  = "Projected (actual)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(4.dp))
    }
}