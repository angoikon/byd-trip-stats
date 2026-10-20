package com.byd.tripstats.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.byd.tripstats.R
import com.byd.tripstats.data.preferences.PreferencesManager
import com.byd.tripstats.data.preferences.UnitSystem
import com.byd.tripstats.data.preferences.displayLabel
import com.byd.tripstats.ui.theme.*
import com.byd.tripstats.ui.viewmodel.DashboardViewModel
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

// ── Holographic palette ───────────────────────────────────────────────────────
// Variant B (Holographic Sweep) from the design canvas: phosphor cyan for the
// BMS "ghost" + grid, dark navy panel, neon green/amber accent stays driven by
// the existing app theme (RegenGreen / AccelerationOrange) so it stays in sync
// with the rest of the UI's good/bad colour language.
private val HoloCyan       = Color(0xFF00E1FF)
private val HoloPanelBg    = Color(0xFF0B1730)
private val HoloPanelStroke = Color(0x14FFFFFF)   // inset hairline

/**
 * The BMS range at each of [points]' distances, linearly interpolated along [bmsPoints].
 * Both series come from the same distance-sorted points, so the bracketing index only moves
 * forward and one pass covers them all. Interpolating each point on its own rescanned the BMS
 * series every time, which made each draw quadratic in trip length: with a point every 100 m,
 * about a million comparisons per draw at 100 km.
 */
internal fun bmsAtDistances(
    points: List<Pair<Double, Double>>,
    bmsPoints: List<Pair<Double, Double>>
): DoubleArray {
    val out = DoubleArray(points.size)
    if (bmsPoints.isEmpty()) return out
    val first = bmsPoints.first()
    val last = bmsPoints.last()
    var hi = 0
    for (i in points.indices) {
        val d = points[i].first
        // First BMS point at or past d.
        while (hi < bmsPoints.size && bmsPoints[hi].first < d) hi++
        out[i] = when {
            d <= first.first -> first.second
            d >= last.first  -> last.second
            hi <= 0          -> first.second
            else -> {
                val (d0, r0) = bmsPoints[hi - 1]
                val (d1, r1) = bmsPoints[hi]
                val t = if (d1 > d0) (d - d0) / (d1 - d0) else 0.0
                r0 + t * (r1 - r0)
            }
        }
    }
    return out
}

/**
 * What [RangeChartCanvas] draws, built once per change of the points. @Immutable and remembered,
 * so the canvas can tell an unchanged series by its identity and skip.
 */
@Immutable
private class RangeChartSeries(
    val projected: List<Pair<Double, Double>>,
    val bms: List<Pair<Double, Double>>,
    /** The BMS range at each projected point's distance. */
    val bmsAtProjected: DoubleArray
)

/**
 * A single telemetry snapshot recorded during a trip.
 *
 * @param distanceKm             Cumulative km driven since trip start
 * @param soc                    Battery state-of-charge at this point (0..100)
 * @param electricDrivingRangeKm Car's BMS-reported remaining range (secondary reference line)
 * @param projectedRangeKm       Projection computed in the ViewModel from the consumption over
 *                               the last RangeWindow.km of driving. Null when the remaining-energy
 *                               read was unusable.
 * @param isStabilised           False while the projection is still on the catalog rate (no
 *                               driving history yet) — the chart says so and leans on the BMS line.
 */
@Serializable
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
 *  • Projected range (colored, main) — computed in the ViewModel from the battery's
 *    discharge over the last RangeWindow.km of driving (this trip and the ones before it):
 *    projectedKm = (BATTERY_KWH × 1000 × soc/100) / consumptionWhPerKm
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
    useImperial: Boolean = false,
    compact: Boolean = false,
    modifier: Modifier = Modifier
) {
    // ── Config values ────────────────────────────────────────────────────────
    val context = LocalContext.current
    val prefs = remember { PreferencesManager(context.applicationContext) }
    val selectedCar by prefs.selectedCarConfig.collectAsState(initial = null)
    val rangeWindow by prefs.rangeWindow.collectAsState(initial = prefs.getCachedRangeWindow())

    selectedCar?.let { car ->
        // ── Derived values ────────────────────────────────────────────────────────

        // ViewModel already gates emission to SAMPLE_INTERVAL_KM resolution,
        // so distinctBy is redundant. Keep sortedBy as defensive safety only.
        val points = remember(dataPoints) {
            dataPoints.sortedBy { it.distanceKm }
        }

        // BMS range at trip start — used as Y-axis scale anchor. Each candidate is
        // guarded against 0 because if the very first stored data point was captured
        // before the BMS finished its cold-boot wake-up it can carry
        // electricDrivingRangeKm == 0; without a guard yMax would be 0 too and
        // yOf would produce a divide-by-zero / NaN, leaving a degenerate chart.
        // Final fallback uses SoC-scaled WLTP (and a small floor) instead of the
        // earlier magic 400.0 so it tracks the selected car.
        val startBmsRange = (
            points.firstOrNull()?.electricDrivingRangeKm?.toDouble()?.takeIf { it > 0 }
                ?: liveElectricRangeKm.toDouble().takeIf { it > 0 }
                ?: (car.wltpKm.toDouble() * (liveSoc / 100.0)).takeIf { it > 0 }
                ?: 50.0
            ).coerceAtLeast(50.0)

        // The projection is EV-only (electric range from your actual EV consumption), so the ceiling
        // is the EV WLTP. For PHEVs allow the trip-start BMS EV range to raise it if that already
        // reads above WLTP (a fresh/efficient pack can). BEVs use the catalog wltpKm.
        val wltpKm = if (car.isPhev)
            startBmsRange.coerceAtLeast(car.wltpKm.toDouble()).toInt()
        else
            car.wltpKm

        val maxDistanceKm = points.lastOrNull()?.distanceKm?.coerceAtLeast(1.0) ?: 1.0

        // Include every point that has a non-null projectedRangeKm — both the
        // RECENT_DRIVING ones and any catalog-rate (BASELINE) ones at the very start
        // of a fresh install's first drive — so the line touches the origin instead
        // of starting at the first stabilised point.
        val projectedPoints: List<Pair<Double, Double>> = remember(points) {
            points.mapNotNull { p -> p.projectedRangeKm?.let { p.distanceKm to it } }
        }

        // BMS series — secondary reference line
        val bmsPoints: List<Pair<Double, Double>> = remember(points) {
            points.map { it.distanceKm to it.electricDrivingRangeKm.toDouble() }
        }

        val series = remember(projectedPoints, bmsPoints) {
            RangeChartSeries(projectedPoints, bmsPoints, bmsAtDistances(projectedPoints, bmsPoints))
        }

        // Whether we have enough data to show the real projection yet
        val isStabilised       = points.lastOrNull()?.isStabilised ?: false
        val currentBms         = points.lastOrNull()?.electricDrivingRangeKm?.toDouble() ?: startBmsRange
        val rawProjected       = projectedPoints.lastOrNull()?.second ?: currentBms
        // Cap display at WLTP — projections above this indicate calibration is still settling
        val isSaturated        = rawProjected > wltpKm
        val currentProjected   = rawProjected.coerceAtMost(wltpKm.toDouble())

        // Positive delta = our projection is higher than BMS (you're beating expectations)
        val deltaKm     = currentProjected - currentBms
        val beating     = deltaKm >= 0
        val accentColor = if (beating) RegenGreen else AccelerationOrange

        // Anchor Y-max to trip-start BMS range + 15% headroom.
        // Using live values caused the axis to rescale as the projection converged,
        // producing distracting visual jumps. A fixed ceiling keeps the chart stable.
        val yMax = startBmsRange * 1.15

        // ── Theme-aware holographic palette ───────────────────────────────────────
        // The neon / phosphor look only reads well on a dark background. In
        // light theme we degrade gracefully: blend the panel with the parent
        // surface, swap cyan for the theme primary, drop the wide halo passes,
        // and flip the chip / label backgrounds light. The plot's own colours
        // live in RangeChartCanvas.
        val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f

        val textColor      = MaterialTheme.colorScheme.onSurface
        val bmsAccent      = if (isDark) HoloCyan
                             else        MaterialTheme.colorScheme.primary
        val chipSurface    = if (isDark) Color.Black.copy(alpha = 0.6f)
                             else        MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)

        // ── Header ────────────────────────────────────────────────────────────────
        Column(modifier = modifier.fillMaxSize()) {
            val modelLabel = when (activeRangeModel) {
                DashboardViewModel.RangeModel.RECENT_DRIVING -> stringResource(
                    R.string.legend_recent_driving,
                    rangeWindow.displayLabel(if (useImperial) UnitSystem.IMPERIAL else UnitSystem.METRIC)
                )
                DashboardViewModel.RangeModel.BASELINE       -> stringResource(R.string.legend_baseline)
            }
            val modelColor = when (activeRangeModel) {
                DashboardViewModel.RangeModel.RECENT_DRIVING -> RegenGreen
                DashboardViewModel.RangeModel.BASELINE       -> MaterialTheme.colorScheme.onSurfaceVariant
            }

            if (!compact) Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                Column {
                    // BMS-sourced values (currentBms, currentProjected, deltaKm) already
                    // arrive in the car's native unit — UK-market BMSes report in miles
                    // natively, so no conversion is applied here, only the unit label.
                    // wltpKm is from the catalog which is always metric — convert it.
                    val distUnit = if (useImperial) "mi" else "km"
                    val wltpDisplay = if (useImperial) (wltpKm * 0.621371).toInt() else wltpKm.toInt()
                    if (!isStabilised && activeRangeModel == DashboardViewModel.RangeModel.BASELINE) {
                        Text(
                            text  = stringResource(R.string.proj_bms_low_cold, "${"%.0f".format(currentBms)} $distUnit"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else if (isSaturated) {
                        Text(
                            text  = stringResource(R.string.proj_wltp_limit, "$wltpDisplay $distUnit"),
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = textColor.copy(alpha = 0.7f)
                        )
                        val satSubtitle = when (activeRangeModel) {
                            DashboardViewModel.RangeModel.BASELINE ->
                                stringResource(R.string.chart_low_speed_cap)
                            else ->
                                stringResource(R.string.chart_above_wltp_cap)
                        }
                        Text(
                            text  = satSubtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            text  = stringResource(R.string.proj_range, "${"%.0f".format(currentProjected)} $distUnit"),
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = textColor
                        )
                        val sign = if (beating) "+" else ""
                        Text(
                            text  = stringResource(R.string.proj_vs_bms, "$sign${"%.0f".format(deltaKm)} $distUnit"),
                            style = MaterialTheme.typography.bodySmall,
                            color = accentColor
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                // Right rail: holographic Δ chip + model badge
                Column(horizontalAlignment = Alignment.End) {
                    if (isStabilised && !isSaturated && projectedPoints.isNotEmpty()) {
                        val sign = if (beating) "+" else ""
                        val distUnit = if (useImperial) "mi" else "km"
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(chipSurface)
                                .border(1.dp, accentColor, RoundedCornerShape(4.dp))
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text  = "Δ $sign${"%.1f".format(deltaKm)} $distUnit",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.SemiBold,
                                    letterSpacing = 0.5.sp
                                ),
                                color = accentColor
                            )
                        }
                        Spacer(Modifier.height(3.dp))
                    }
                    Text(
                        text  = modelLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = modelColor
                    )
                }
            }

            // ── Canvas ────────────────────────────────────────────────────────────
            RangeChartCanvas(
                series        = series,
                maxDistanceKm = maxDistanceKm,
                startBmsRange = startBmsRange,
                yMax          = yMax,
                wltpKm        = wltpKm,
                useImperial   = useImperial,
                accentColor   = accentColor,
                bmsAccent     = bmsAccent,
                modifier      = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 12.dp)
            )

            // ── Legend ────────────────────────────────────────────────────────────
            if (!compact) Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Canvas(modifier = Modifier.size(20.dp, 3.dp)) {
                    drawLine(
                        color = bmsAccent, start = Offset(0f, size.height / 2),
                        end = Offset(size.width, size.height / 2), strokeWidth = 3f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f))
                    )
                }
                Spacer(Modifier.width(5.dp))
                Text(
                    text  = stringResource(R.string.chart_bms_estimate),
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
                    text  = stringResource(R.string.chart_projected_actual),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(4.dp))
        }
    }
}

/**
 * The chart itself. Every parameter is stable, so this skips the recompositions the dashboard
 * runs for its live readouts (up to 5 a second while driving) and redraws only when the points
 * (every 100 m), the scale or the theme change. Inline in RangeProjectionChart, whose point list
 * Compose can't compare, it rebuilt every path on each of them.
 */
@Composable
private fun RangeChartCanvas(
    series: RangeChartSeries,
    maxDistanceKm: Double,
    startBmsRange: Double,
    yMax: Double,
    wltpKm: Int,
    useImperial: Boolean,
    accentColor: Color,
    bmsAccent: Color,
    modifier: Modifier = Modifier
) {
    val yMin = 0.0

    // Theme-aware holographic palette — see RangeProjectionChart.
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f

    val textColor      = MaterialTheme.colorScheme.onSurface
    val panelBg        = if (isDark) HoloPanelBg
                         else        Color.Transparent
    val panelStroke    = if (isDark) HoloPanelStroke
                         else        MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)
    val dotGridColor   = if (isDark) HoloCyan.copy(alpha = 0.18f)
                         else        MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
    val gridLineColor  = if (isDark) Color.White.copy(alpha = 0.06f)
                         else        MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)
    val axisColor      = if (isDark) Color.White.copy(alpha = 0.45f)
                         else        textColor.copy(alpha = 0.45f)
    val labelTextColor = if (isDark) Color.White.copy(alpha = 0.7f)
                         else        textColor.copy(alpha = 0.7f)
    val xLabelColor    = if (isDark) Color.White.copy(alpha = 0.6f)
                         else        textColor.copy(alpha = 0.6f)
    val yAxisLabelColor= if (isDark) Color.White.copy(alpha = 0.55f)
                         else        textColor.copy(alpha = 0.55f)
    val centerCoreColor= if (isDark) Color.White.copy(alpha = 0.85f)
                         else        Color.Transparent       // white core would vanish on light bg
    val surfaceColor   = MaterialTheme.colorScheme.surface
    val scanningText   = stringResource(R.string.chart_scanning)
    val acquiringText  = stringResource(R.string.chart_acquiring)

    Canvas(
        modifier = modifier
            .clipToBounds()   // prevent projected line from escaping the card
    ) {
        val projectedPoints = series.projected
        val bmsPoints       = series.bms
        val bmsAtProjected  = series.bmsAtProjected

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

        // y-position for a projected value. For unsaturated (≤ WLTP)
        // values it's the normal yOf mapping. For saturated values
        // (raw projection > WLTP), the line is rendered at the chart's
        // visual ceiling with a small asymptotic lift into the top
        // padding — bounded by padT so it can never escape the panel.
        //
        // Why anchor saturated at yMax (chart ceiling) rather than at
        // the WLTP km mark: when the trip starts with low SoC,
        // yMax = startBmsRange × 1.15 can be smaller than WLTP, which
        // put yOf(WLTP) above the canvas's top edge and got eaten by
        // clipToBounds — saturated segments became invisible. Anchoring
        // at yMax keeps them on-screen in every case.
        //
        // Purely cosmetic: the displayed projected-range text and the
        // deltaKm math both still cap at WLTP, so semantics elsewhere
        // are unchanged.
        fun projectedY(rangeKm: Double): Float {
            if (rangeKm <= wltpKm) return yOf(rangeKm)
            val ceilingY = yOf(yMax)
            val overshootKm = rangeKm - wltpKm
            val liftPx = (overshootKm / (overshootKm + 30.0)) * 10f
            return ceilingY - liftPx.toFloat()
        }

        // ── Holographic panel: theme-aware ground + inset hairline ────
        if (panelBg != Color.Transparent) {
            drawRoundRect(
                color        = panelBg,
                topLeft      = Offset(padL, padT),
                size         = Size(chartW, chartH),
                cornerRadius = CornerRadius(6f, 6f)
            )
        }
        drawRoundRect(
            color        = panelStroke,
            topLeft      = Offset(padL, padT),
            size         = Size(chartW, chartH),
            cornerRadius = CornerRadius(6f, 6f),
            style        = Stroke(width = 1f)
        )

        // ── Phosphor dot grid — fills plot area ───────────────────────
        val dotSpacing = 14f
        run {
            var dy = padT + dotSpacing / 2f
            while (dy < padT + chartH) {
                var dx = padL + dotSpacing / 2f
                while (dx < padL + chartW) {
                    drawCircle(dotGridColor, 0.9f, Offset(dx, dy))
                    dx += dotSpacing
                }
                dy += dotSpacing
            }
        }

        // Paint objects — created once, reused across all draw calls
        val labelPaint = android.graphics.Paint().apply {
            color = labelTextColor.toArgb()
            textSize = 20f; textAlign = android.graphics.Paint.Align.RIGHT; isAntiAlias = true
        }
        val xLabelPaint = android.graphics.Paint().apply {
            color = xLabelColor.toArgb()
            textSize = 19f; textAlign = android.graphics.Paint.Align.CENTER; isAntiAlias = true
        }
        val yAxisPaint = android.graphics.Paint().apply {
            color = yAxisLabelColor.toArgb()
            textSize = 19f; textAlign = android.graphics.Paint.Align.CENTER; isAntiAlias = true
        }

        // Rotated Y-axis label
        nc.save()
        nc.rotate(-90f, 18f, padT + chartH / 2f)
        nc.drawText(if (useImperial) "mi" else "km", 18f, padT + chartH / 2f, yAxisPaint)
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

        // X ticks — 5 evenly spaced labels. The first/last labels sit on the chart edges,
        // so anchor them LEFT/RIGHT (not CENTER) or half the text spills past the canvas
        // and gets clipped (the trailing digit of e.g. "12.6" was being cut off).
        val xStep = maxDistanceKm / 4.0
        for (i in 0..4) {
            val dist = i * xStep
            val x = xOf(dist)
            drawLine(axisColor, Offset(x, padT + chartH), Offset(x, padT + chartH + 5f), 1.5f)
            xLabelPaint.textAlign = when (i) {
                0    -> android.graphics.Paint.Align.LEFT
                4    -> android.graphics.Paint.Align.RIGHT
                else -> android.graphics.Paint.Align.CENTER
            }
            nc.drawText("%.1f".format(dist), x, h - 4f, xLabelPaint)
        }

        if (bmsPoints.isNotEmpty() && bmsPoints.size >= 2) {

            // ── Confidence cone around BMS estimate ───────────────────
            // Widens with trip progress: ±4km at start, growing to ±32km
            // at trip end. Faint translucent cyan fill + dashed outline.
            run {
                val cone = Path()
                val firstD = bmsPoints.first().first
                val lastD  = bmsPoints.last().first.coerceAtLeast(firstD + 0.001)
                fun spreadAt(d: Double): Double {
                    val frac = ((d - firstD) / (lastD - firstD)).coerceIn(0.0, 1.0)
                    // Spread in km, scaled relative to the Y range so the cone
                    // visually widens proportionally to the chart's vertical extent.
                    return 4.0 + frac * 28.0
                }
                cone.moveTo(xOf(firstD), yOf(bmsPoints.first().second + spreadAt(firstD)))
                for (i in 1 until bmsPoints.size) {
                    val (d, r) = bmsPoints[i]
                    cone.lineTo(xOf(d), yOf(r + spreadAt(d)))
                }
                for (i in bmsPoints.size - 1 downTo 0) {
                    val (d, r) = bmsPoints[i]
                    cone.lineTo(xOf(d), yOf(r - spreadAt(d)))
                }
                cone.close()
                drawPath(cone, bmsAccent.copy(alpha = if (isDark) 0.07f else 0.05f))
                drawPath(
                    cone,
                    color = bmsAccent.copy(alpha = if (isDark) 0.35f else 0.30f),
                    style = Stroke(
                        width = 0.8f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 4f))
                    )
                )
            }

            // ── BMS reference line: phosphor ghost ────────────────────
            // Soft glow underneath (dark theme only — would muddy a
            // light surface), dashed bright stroke always.
            val bmsPath = Path().apply {
                moveTo(xOf(bmsPoints.first().first), yOf(bmsPoints.first().second))
                for (i in 1 until bmsPoints.size) {
                    val (d, r) = bmsPoints[i]
                    lineTo(xOf(d), yOf(r))
                }
            }
            if (isDark) {
                drawPath(
                    bmsPath,
                    color = bmsAccent.copy(alpha = 0.25f),
                    style = Stroke(width = 6f, cap = StrokeCap.Round)
                )
            }
            drawPath(
                bmsPath,
                color = bmsAccent.copy(alpha = if (isDark) 0.75f else 0.85f),
                style = Stroke(
                    width      = if (isDark) 1.4f else 1.8f,
                    cap        = StrokeCap.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
                )
            )

            // ── Projected line — only drawn once stabilised ───────────
            if (projectedPoints.size >= 2) {

                // Per-segment classification. Every consecutive pair of
                // projected points becomes one mini-segment, colored by
                // its starting point's relationship to the BMS line at
                // the same x: above BMS → RegenGreen, below → Acceleration-
                // Orange. A segment whose start exceeds WLTP is pinned to
                // the WLTP ceiling and drawn as dash-dot (calibration-
                // saturated). We collect into four shared Paths so the
                // multi-pass glow still runs only twice per color rather
                // than once per micro-segment.
                val greenLine  = Path()
                val orangeLine = Path()
                val greenFill  = Path()
                val orangeFill = Path()
                val saturatedLine = Path()
                var anyGreenLine = false
                var anyOrangeLine = false
                var anyGreenFill = false
                var anyOrangeFill = false
                var anySaturated = false

                for (i in 0 until projectedPoints.size - 1) {
                    val (da, ra) = projectedPoints[i]
                    val (db, rb) = projectedPoints[i + 1]
                    val xa = xOf(da); val xb = xOf(db)

                    val saturatedA = ra > wltpKm
                    val saturatedB = rb > wltpKm

                    // projectedY lifts saturated values asymptotically above the
                    // WLTP ceiling so the line bulges instead of clipping flat.
                    val yPa = projectedY(ra); val yPb = projectedY(rb)
                    val bmsA = bmsAtProjected[i]
                    val bmsB = bmsAtProjected[i + 1]
                    val yBa = yOf(bmsA);  val yBb = yOf(bmsB)

                    if (saturatedA && saturatedB) {
                        saturatedLine.moveTo(xa, yPa)
                        saturatedLine.lineTo(xb, yPb)
                        anySaturated = true
                        continue
                    }

                    // Segment colour is decided on the raw projected value vs BMS at
                    // the same x, not the capped one — a 600 km projection that's >
                    // BMS still counts as "beating" even though we render it capped.
                    val isGreen = ra >= bmsA
                    val linePath = if (isGreen) greenLine else orangeLine
                    val fillPath = if (isGreen) greenFill else orangeFill
                    linePath.moveTo(xa, yPa)
                    linePath.lineTo(xb, yPb)
                    if (isGreen) anyGreenLine = true else anyOrangeLine = true

                    // Quad fill between the projected polyline and the BMS line
                    // for this segment. Avoids the previous behaviour where the
                    // whole area between projected and BMS was painted in a
                    // single colour driven by only the latest point's comparison.
                    fillPath.moveTo(xa, yPa)
                    fillPath.lineTo(xb, yPb)
                    fillPath.lineTo(xb, yBb)
                    fillPath.lineTo(xa, yBa)
                    fillPath.close()
                    if (isGreen) anyGreenFill = true else anyOrangeFill = true
                }

                if (anyGreenFill)  drawPath(greenFill,  RegenGreen.copy(alpha = 0.18f))
                if (anyOrangeFill) drawPath(orangeFill, AccelerationOrange.copy(alpha = 0.18f))

                // Multi-pass glow per color. In light theme the wide outer
                // halos are skipped (they muddy on a light surface) and the
                // white centerline is skipped (invisible on light bg).
                fun drawGlowLine(path: Path, color: Color, hasContent: Boolean) {
                    if (!hasContent) return
                    if (isDark) {
                        drawPath(path, color = color.copy(alpha = 0.18f),
                            style = Stroke(width = 12f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                        drawPath(path, color = color.copy(alpha = 0.45f),
                            style = Stroke(width = 6f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                    drawPath(path, color = color,
                        style = Stroke(width = if (isDark) 2.6f else 3f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    if (centerCoreColor.alpha > 0f) {
                        drawPath(path, color = centerCoreColor,
                            style = Stroke(width = 0.9f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                }
                drawGlowLine(greenLine,  RegenGreen,           anyGreenLine)
                drawGlowLine(orangeLine, AccelerationOrange,   anyOrangeLine)

                // Saturated (> WLTP) — dash-dot ceiling. A saturated
                // projection is by definition above the BMS estimate, so
                // the colour follows the "green = beating" rule.
                if (anySaturated) {
                    if (isDark) {
                        drawPath(saturatedLine, color = RegenGreen.copy(alpha = 0.25f),
                            style = Stroke(width = 8f, cap = StrokeCap.Round))
                    }
                    drawPath(
                        saturatedLine,
                        color = RegenGreen.copy(alpha = if (isDark) 0.55f else 0.7f),
                        style = Stroke(
                            width      = 2f,
                            cap        = StrokeCap.Round,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 4f, 2f, 4f))
                        )
                    )
                }

                // ── Head node + tape-measure delta callout ───────────
                // The leading edge of the projected curve is the hero:
                // a vertical "tape" between projected and BMS at that
                // x, with tick caps and a delta label box that floats
                // alongside.
                val last  = projectedPoints.last()
                val leadX = xOf(last.first)
                val leadYa = projectedY(last.second)
                val leadYb = yOf(bmsAtProjected.last())

                // Tape line + caps
                if (isDark) {
                    drawLine(
                        color = accentColor.copy(alpha = 0.35f),
                        start = Offset(leadX, leadYa), end = Offset(leadX, leadYb),
                        strokeWidth = 6f, cap = StrokeCap.Round
                    )
                }
                drawLine(
                    color = accentColor,
                    start = Offset(leadX, leadYa), end = Offset(leadX, leadYb),
                    strokeWidth = 1.5f, cap = StrokeCap.Round
                )
                drawLine(accentColor,
                    start = Offset(leadX - 6f, leadYa), end = Offset(leadX + 6f, leadYa),
                    strokeWidth = 1.5f)
                drawLine(bmsAccent,
                    start = Offset(leadX - 6f, leadYb), end = Offset(leadX + 6f, leadYb),
                    strokeWidth = 1.5f)
                // (The in-chart "Δ" text used to live here as a label box at
                // the leading edge, but it duplicated the chip rendered in the
                // top-right of the chart frame. Removed; the vertical tape
                // line + tick caps remain as the visual delta indicator.)

                // Head node: colored ring + concentric pulse rings
                // (static — animation would burn battery in-car; the
                // layered rings still read as "live"). Centre fill is
                // light-theme aware so the dot stays visible.
                if (isDark) {
                    drawCircle(accentColor.copy(alpha = 0.25f), 14f, Offset(leadX, leadYa))
                    drawCircle(accentColor.copy(alpha = 0.45f), 9f,  Offset(leadX, leadYa))
                } else {
                    drawCircle(accentColor.copy(alpha = 0.20f), 11f, Offset(leadX, leadYa))
                }
                drawCircle(
                    if (isDark) Color.White else surfaceColor,
                    3.5f, Offset(leadX, leadYa)
                )
                drawCircle(accentColor, 3.5f, Offset(leadX, leadYa),
                    style = Stroke(width = 1.4f))
            }

        } else {
            // ── Empty state: corner brackets + SCANNING callout ───────
            val bracketLen = 14f
            val bracketColor = bmsAccent.copy(alpha = 0.7f)
            val corners = listOf(
                Triple(padL,          padT,          Pair( 1f,  1f)),
                Triple(padL + chartW, padT,          Pair(-1f,  1f)),
                Triple(padL,          padT + chartH, Pair( 1f, -1f)),
                Triple(padL + chartW, padT + chartH, Pair(-1f, -1f))
            )
            corners.forEach { (cx, cy, signs) ->
                val (sx, sy) = signs
                drawLine(bracketColor,
                    start = Offset(cx, cy), end = Offset(cx + bracketLen * sx, cy),
                    strokeWidth = 1.4f)
                drawLine(bracketColor,
                    start = Offset(cx, cy), end = Offset(cx, cy + bracketLen * sy),
                    strokeWidth = 1.4f)
            }
            val scanPaint = android.graphics.Paint().apply {
                color = bmsAccent.toArgb()
                textSize = 30f
                textAlign = android.graphics.Paint.Align.CENTER
                isAntiAlias = true
                letterSpacing = 0.3f
                typeface = android.graphics.Typeface.MONOSPACE
            }
            val subPaint = android.graphics.Paint().apply {
                color = textColor.copy(alpha = 0.45f).toArgb()
                textSize = 18f
                textAlign = android.graphics.Paint.Align.CENTER
                isAntiAlias = true
                typeface = android.graphics.Typeface.MONOSPACE
            }
            nc.drawText(scanningText,
                padL + chartW / 2f, padT + chartH / 2f - 4f, scanPaint)
            nc.drawText(acquiringText,
                padL + chartW / 2f, padT + chartH / 2f + 22f, subPaint)
        }
    }
}
