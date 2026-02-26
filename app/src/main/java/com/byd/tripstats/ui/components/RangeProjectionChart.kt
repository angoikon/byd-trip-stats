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
import androidx.compose.ui.unit.sp
import com.byd.tripstats.ui.theme.BatteryBlue
import com.byd.tripstats.ui.theme.RegenGreen
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A single telemetry snapshot recorded during a trip.
 *
 * @param distanceKm  Cumulative km driven since trip start
 * @param soc         Battery state-of-charge at this point (0..100)
 */
data class RangeDataPoint(
    val distanceKm: Double,
    val soc: Double
)

/**
 * Range Projection Chart
 *
 * X-axis : Distance driven during the trip (km)
 * Y-axis : Estimated remaining range (km)
 *
 * Two curves are drawn:
 *  • Rated  (gray)   – straight line assuming perfect WLTP efficiency (1 km driven = 1 km lost)
 *  • Actual (orange) – real remaining range derived from SOC × wltpRangeKm
 *
 * The area between them is filled green when actual > rated (efficient driving)
 * and orange/amber when actual < rated (inefficient driving).
 *
 * A dot marks the most recent point on the actual curve.
 *
 * @param dataPoints   List of telemetry snapshots for this trip (can be empty).
 *                     When empty the chart gracefully shows "no data yet".
 * @param startSoc     Battery % at trip start (used to anchor both curves at x=0).
 * @param wltpRangeKm  Full-charge rated range. Default 520 km (BYD Seal AWD WLTP).
 */
@Composable
fun RangeProjectionChart(
    dataPoints: List<RangeDataPoint>,
    liveSoc: Double = 100.0, // Current SOC from telemetry, used when no trip data yet
    wltpRangeKm: Int = 520, // TODO: make this dynamic based on car model via config
    modifier: Modifier = Modifier
) {
    // ── Compute derived values ────────────────────────────────────────────────

    // When no trip has started yet use live SOC so the header shows real remaining range
    val startSoc = dataPoints.firstOrNull()?.soc ?: liveSoc
    val startRangeKm = startSoc / 100.0 * wltpRangeKm   // km at trip start (rated anchor)

    // Normalise & sort data; guard against duplicate x values
    val points = remember(dataPoints) {
        dataPoints
            .sortedBy { it.distanceKm }
            .distinctBy { (it.distanceKm * 10).roundToInt() }
    }

    val maxDistanceKm = points.lastOrNull()?.distanceKm?.coerceAtLeast(1.0) ?: 1.0
    val currentSoc    = points.lastOrNull()?.soc ?: startSoc

    // ── Observed consumption-based range projection ───────────────────────────
    // Once we have enough distance (≥3 km), compute km-per-soc-percent directly
    // from trip data rather than relying on WLTP. Falls back to WLTP × SOC below
    // the threshold to avoid division instability from tiny SOC changes.
    val MIN_DISTANCE_FOR_OBSERVED_KM = 3.0  // km before switching to data-driven

    val currentRange: Double = run {
        val distDriven = points.lastOrNull()?.distanceKm ?: 0.0
        val socDropPct = startSoc - currentSoc

        if (distDriven >= MIN_DISTANCE_FOR_OBSERVED_KM && socDropPct > 0.5) {
            // Observed: how many km per SOC percent consumed so far
            val kmPerSocPct = distDriven / socDropPct
            currentSoc * kmPerSocPct   // project remaining range
        } else {
            // Not enough data yet — fall back to WLTP ratio
            currentSoc / 100.0 * wltpRangeKm
        }
    }

    // Build a per-point actual range series using the same logic so the curve
    // reflects the evolving observed efficiency, not a fixed WLTP constant.
    val actualRangePoints: List<Pair<Double, Double>> = remember(points) {
        points.mapIndexedNotNull { index, p ->
            if (index == 0) return@mapIndexedNotNull p.distanceKm to (startSoc / 100.0 * wltpRangeKm)
            val distDriven = p.distanceKm
            val socDrop    = startSoc - p.soc
            val range = if (distDriven >= MIN_DISTANCE_FOR_OBSERVED_KM && socDrop > 0.5) {
                val kmPerSocPct = distDriven / socDrop
                p.soc * kmPerSocPct
            } else {
                p.soc / 100.0 * wltpRangeKm
            }
            p.distanceKm to range
        }
    }

    // Rated range at the current distance (straight line: startRange - distanceDriven)
    val ratedRangeNow = (startRangeKm - maxDistanceKm).coerceAtLeast(0.0)

    val deltaKm  = currentRange - ratedRangeNow
    val beating  = deltaKm >= 0
    val accentColor = if (beating) RegenGreen else Color(0xFFF5A623)

    // Y-axis: headroom above whichever is higher — rated start or actual projection
    val yMax = maxOf(startRangeKm, currentRange) * 1.05
    val yMin = 0.0

    // ── Theme colours (must be extracted outside Canvas) ─────────────────────
    val ratedLineColor   = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
    val gridLineColor    = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f)
    val axisColor        = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
    val textColor        = MaterialTheme.colorScheme.onSurface

    // ── Header ────────────────────────────────────────────────────────────────
    Column(modifier = modifier.fillMaxSize()) {
        // Summary row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            Column {
                Text(
                    text = "%.1f km remaining".format(currentRange),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = textColor
                )
                val sign  = if (beating) "+" else ""
                val label = if (beating) "ahead of rated" else "behind rated"
                Text(
                    text = "$sign${"%.1f".format(deltaKm)} km $label",
                    style = MaterialTheme.typography.bodySmall,
                    color = accentColor
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = "WLTP $wltpRangeKm km",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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

            val padL = 52f   // left  – Y labels
            val padR = 12f   // right
            val padT = 12f   // top
            val padB = 36f   // bottom – X labels

            val chartW = w - padL - padR
            val chartH = h - padT - padB

            // Helper: data → canvas coordinates
            fun xOf(distKm: Double) = padL + (distKm / maxDistanceKm * chartW).toFloat()
            fun yOf(rangeKm: Double): Float {
                val fraction = (rangeKm - yMin) / (yMax - yMin)
                return (padT + chartH * (1f - fraction)).toFloat()
            }

            // ── Grid & axes ───────────────────────────────────────────────────

            // Y grid lines — max ~5 labels regardless of range
            val yStepKm = when {
                startRangeKm > 300 -> 100.0
                startRangeKm > 150 -> 50.0
                startRangeKm > 75  -> 25.0
                else               -> 10.0
            }
            var yTick = (yMin / yStepKm).toInt() * yStepKm
            while (yTick <= yMax) {
                val y = yOf(yTick)
                drawLine(
                    color = gridLineColor,
                    start = Offset(padL, y),
                    end   = Offset(w - padR, y),
                    strokeWidth = 1f
                )
                drawContext.canvas.nativeCanvas.drawText(
                    "${yTick.roundToInt()}",
                    padL - 6f,
                    y + 5f,
                    android.graphics.Paint().apply {
                        color     = textColor.copy(alpha = 0.7f).toArgb()
                        textSize  = 20f
                        textAlign = android.graphics.Paint.Align.RIGHT
                    }
                )
                yTick += yStepKm
            }

            // X axis line
            drawLine(
                color       = axisColor,
                start       = Offset(padL, padT + chartH),
                end         = Offset(w - padR, padT + chartH),
                strokeWidth = 1.5f
            )

            // X ticks — 5 even labels
            val xStep = maxDistanceKm / 4.0
            for (i in 0..4) {
                val dist = i * xStep
                val x    = xOf(dist)
                drawLine(
                    color       = axisColor,
                    start       = Offset(x, padT + chartH),
                    end         = Offset(x, padT + chartH + 5f),
                    strokeWidth = 1.5f
                )
                drawContext.canvas.nativeCanvas.drawText(
                    "%.1f".format(dist),
                    x,
                    h - 4f,
                    android.graphics.Paint().apply {
                        color     = textColor.copy(alpha = 0.6f).toArgb()
                        textSize  = 19f
                        textAlign = android.graphics.Paint.Align.CENTER
                    }
                )
            }

            // ── Rated line (gray, dashed) ─────────────────────────────────────
            val ratedPath = Path().apply {
                moveTo(xOf(0.0), yOf(startRangeKm))
                // Rated line ends when range hits 0 or at max trip distance
                val ratedEndDist = startRangeKm.coerceAtMost(maxDistanceKm)
                lineTo(xOf(ratedEndDist), yOf((startRangeKm - ratedEndDist).coerceAtLeast(0.0)))
            }
            drawPath(
                path  = ratedPath,
                color = ratedLineColor,
                style = Stroke(
                    width      = 2.5f,
                    cap        = StrokeCap.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))
                )
            )

            // ── Actual path and fill ──────────────────────────────────────────
            if (actualRangePoints.isNotEmpty()) {
                // Build the actual curve path
                val actualPath = Path().apply {
                    val first = actualRangePoints.first()
                    moveTo(xOf(first.first), yOf(first.second))
                    actualRangePoints.drop(1).forEach { (dist, range) ->
                        lineTo(xOf(dist), yOf(range))
                    }
                }

                // Build fill polygon: actual curve + rated curve reversed
                val fillPath = Path().apply {
                    val first = actualRangePoints.first()
                    moveTo(xOf(first.first), yOf(first.second))
                    actualRangePoints.drop(1).forEach { (dist, range) ->
                        lineTo(xOf(dist), yOf(range))
                    }
                    // Back along rated
                    val lastDist = actualRangePoints.last().first
                    lineTo(xOf(lastDist), yOf((startRangeKm - lastDist).coerceAtLeast(0.0)))
                    lineTo(xOf(actualRangePoints.first().first), yOf(startRangeKm))
                    close()
                }

                val fillColor = if (beating) RegenGreen.copy(alpha = 0.18f)
                                else         Color(0xFFF5A623).copy(alpha = 0.15f)
                drawPath(path = fillPath, color = fillColor)

                // Actual curve stroke
                drawPath(
                    path  = actualPath,
                    color = accentColor,
                    style = Stroke(width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )

                // Current position dot
                val lastPoint = actualRangePoints.last()
                val dotX = xOf(lastPoint.first)
                val dotY = yOf(lastPoint.second)
                drawCircle(color = accentColor.copy(alpha = 0.28f), radius = 18f, center = Offset(dotX, dotY))
                drawCircle(color = accentColor,                      radius = 8f,  center = Offset(dotX, dotY))
                drawCircle(color = Color.White,                      radius = 3f,  center = Offset(dotX, dotY))
            } else {
                // No telemetry yet – show faded message
                drawContext.canvas.nativeCanvas.drawText(
                    "No trip data yet",
                    padL + chartW / 2f,
                    padT + chartH / 2f,
                    android.graphics.Paint().apply {
                        color     = textColor.copy(alpha = 0.3f).toArgb()
                        textSize  = 28f
                        textAlign = android.graphics.Paint.Align.CENTER
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
            // Rated indicator
            Canvas(modifier = Modifier.size(20.dp, 3.dp)) {
                drawLine(
                    color       = ratedLineColor,
                    start       = Offset(0f, size.height / 2),
                    end         = Offset(size.width, size.height / 2),
                    strokeWidth = 3f,
                    pathEffect  = PathEffect.dashPathEffect(floatArrayOf(6f, 4f))
                )
            }
            Spacer(Modifier.width(5.dp))
            Text(
                text  = "Rated",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(16.dp))
            Canvas(modifier = Modifier.size(20.dp, 3.dp)) {
                drawLine(
                    color       = accentColor,
                    start       = Offset(0f, size.height / 2),
                    end         = Offset(size.width, size.height / 2),
                    strokeWidth = 3f
                )
            }
            Spacer(Modifier.width(5.dp))
            Text(
                text  = "Actual",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text  = "(km driven →)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
        }

        Spacer(Modifier.height(4.dp))
    }
}