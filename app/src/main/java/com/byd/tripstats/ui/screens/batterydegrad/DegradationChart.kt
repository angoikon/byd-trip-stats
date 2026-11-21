package com.byd.tripstats.ui.screens.batterydegrad

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.material3.MaterialTheme
import com.byd.tripstats.ui.theme.AccelerationOrange
import com.byd.tripstats.ui.theme.BatteryBlue
import com.byd.tripstats.ui.viewmodel.DashboardViewModel
import android.text.format.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.*

// ── Regression data class ─────────────────────────────────────────────────────

internal data class RegressionResult(
    val slope          : Double,  // SoH % per millisecond
    val intercept      : Double,
    val projectedAt80Ms: Long?    // null if trend is flat or going up
) {
    fun predict(ms: Double): Double = slope * ms + intercept
}

// ── Chart ─────────────────────────────────────────────────────────────────────

@Composable
internal fun DegradationChart(
    data       : List<DashboardViewModel.SohDataPoint>,
    regression : RegressionResult,
    modifier   : Modifier = Modifier
) {
    val lineColor       = BatteryBlue
    val dotFill         = BatteryBlue
    val dotGlow         = BatteryBlue.copy(alpha = 0.18f)
    val trendColor      = AccelerationOrange
    val projectedColor  = AccelerationOrange.copy(alpha = 0.45f)
    val gridColor       = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.10f)
    val textColor       = MaterialTheme.colorScheme.onSurface

    Canvas(modifier = modifier) {
        val w = size.width; val h = size.height
        val padL = 52f; val padR = 16f; val padT = 12f; val padB = 32f
        val chartW = w - padL - padR; val chartH = h - padT - padB
        // Keeps the first and last dots clear of the y labels and the right edge.
        val inset = 14f
        val plotL = padL + inset; val plotW = chartW - 2 * inset

        val firstMs = data.first().timestamp
        val lastMs  = data.last().timestamp
        val (xMinMs, xMaxMs) = degradationXRange(firstMs, lastMs)
        val xRange = xMaxMs - xMinMs
        val (yMin, yMax) = degradationYRange(
            minSoh = data.minOf { it.avgSoh },
            maxSoh = data.maxOf { it.avgSoh },
            trendStart = regression.predict(xMinMs),
            trendEnd = regression.predict(xMaxMs)
        )
        val yRange = yMax - yMin

        fun xOf(ms: Double) = (plotL + ((ms - xMinMs) / xRange) * plotW).toFloat()
        fun yOf(soh: Double) = (padT + chartH * (1.0 - (soh - yMin) / yRange)).toFloat()

        val nc = drawContext.canvas.nativeCanvas

        // Y grid + labels
        val yLabelPaint = android.graphics.Paint().apply {
            color     = textColor.copy(alpha = 0.65f).toArgb()
            textSize  = 22f
            textAlign = android.graphics.Paint.Align.RIGHT
            isAntiAlias = true
        }
        val yStep = if (yRange <= 8) 1.0 else if (yRange <= 15) 2.0 else 5.0
        var yTick = ceil(yMin / yStep) * yStep
        while (yTick <= yMax + 0.01) {
            val y = yOf(yTick)
            drawLine(gridColor, Offset(padL, y), Offset(w - padR, y), 1f)
            nc.drawText("%.0f%%".format(yTick), padL - 6f, y + 8f, yLabelPaint)
            yTick += yStep
        }

        // X grid + labels at calendar boundaries, as fine as the labels fit without touching.
        // (A label at every trip whose SoH dipped below the one before printed hundreds of
        // overlapping dates once per-trip SoH carried decimals.)
        val xLabelPaint = android.graphics.Paint().apply {
            color     = textColor.copy(alpha = 0.65f).toArgb()
            textSize  = 20f
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
        }
        val locale = Locale.getDefault()
        val formats = HashMap<String, SimpleDateFormat>()
        fun formatFor(step: DateTickStep) = formats.getOrPut(step.skeleton) {
            SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, step.skeleton), locale)
        }
        // Width of the widest label a step prints: the 28th of each month (month names differ).
        val sampleDates = Calendar.getInstance().let { cal ->
            cal.timeInMillis = xMaxMs.toLong()
            (0 until 12).map { m -> cal.set(Calendar.MONTH, m); cal.set(Calendar.DAY_OF_MONTH, 28); cal.time }
        }
        val step = pickDateTickStep(xRange, plotW) { s ->
            val f = formatFor(s)
            sampleDates.maxOf { xLabelPaint.measureText(f.format(it)) }
        }
        val tickFormat = formatFor(step)
        dateTicks(xMinMs.toLong(), xMaxMs.toLong(), step).forEach { t ->
            val x = xOf(t.toDouble())
            drawLine(gridColor, Offset(x, padT), Offset(x, padT + chartH), 1f)
            val label = tickFormat.format(Date(t))
            val half = xLabelPaint.measureText(label) / 2f
            if (x - half >= 0f && x + half <= w) nc.drawText(label, x, h - 4f, xLabelPaint)
        }

        // ── Trend line (solid orange) ──────────────────────────────────────────
        if (data.size >= 2) {
            val trendPath = Path().apply {
                moveTo(xOf(xMinMs), yOf(regression.predict(xMinMs)).coerceIn(padT, padT + chartH))
                lineTo(xOf(lastMs.toDouble()),
                    yOf(regression.predict(lastMs.toDouble()))
                        .coerceIn(padT, padT + chartH))
            }
            drawPath(trendPath, trendColor,
                style = Stroke(width = 2.5f, cap = StrokeCap.Round))

            // ── Projected line (dashed, faded) ────────────────────────────────
            val projPath = Path().apply {
                moveTo(xOf(lastMs.toDouble()),
                    yOf(regression.predict(lastMs.toDouble()))
                        .coerceIn(padT, padT + chartH))
                lineTo(xOf(xMaxMs),
                    yOf(regression.predict(xMaxMs)).coerceIn(padT, padT + chartH))
            }
            drawPath(projPath, projectedColor,
                style = Stroke(width = 2f, cap = StrokeCap.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))))
        }

        // ── Actual data line ───────────────────────────────────────────────────
        if (data.size >= 2) {
            val linePath = Path().apply {
                moveTo(xOf(data[0].timestamp.toDouble()), yOf(data[0].avgSoh))
                data.drop(1).forEach { pt ->
                    lineTo(xOf(pt.timestamp.toDouble()), yOf(pt.avgSoh))
                }
            }
            drawPath(linePath, lineColor,
                style = Stroke(width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }

        // ── Data dots ─────────────────────────────────────────────────────────
        // One per trip only while they stay apart: hundreds of trips drew overlapping halos
        // that merged into a solid bar. The newest trip always gets one.
        val dotSpacing = (xOf(lastMs.toDouble()) - xOf(firstMs.toDouble())) /
            (data.size - 1).coerceAtLeast(1)
        val dotted = if (dotSpacing >= 18f) data else listOf(data.last())
        dotted.forEach { pt ->
            val x = xOf(pt.timestamp.toDouble())
            val y = yOf(pt.avgSoh)
            drawCircle(dotGlow, 14f, Offset(x, y))
            drawCircle(dotFill,  6f, Offset(x, y))
            drawCircle(Color.White, 2.5f, Offset(x, y))
        }
    }
}

// ── Axis helpers ──────────────────────────────────────────────────────────────

private const val DAY_MS  = 86_400_000.0
private const val YEAR_MS = 365.25 * DAY_MS

/**
 * The chart's x span: the recorded trips, then a projected tail a quarter as long (at least a
 * day), so the trips fill ~80 % of the width. The year SoH reaches 80 % is on the stat card;
 * stretching the axis toward it (up to 5 years) squeezed months of trips into a sliver.
 */
internal fun degradationXRange(firstMs: Long, lastMs: Long): Pair<Double, Double> {
    val span = (lastMs - firstMs).toDouble().coerceAtLeast(0.0)
    return firstMs.toDouble() to lastMs + max(span * 0.25, DAY_MS)
}

/**
 * The chart's y span in whole percent: the recorded SoH and the trend across the x span with a
 * 1 % margin, at least 3 % tall, and no headroom above 100 % unless a trip read above it. The
 * trend widens it by at most 3 % past the data, so a steep fit over a short history can't
 * flatten the trips. (A fixed 100 % top left a fraction-of-a-percent history as a thin band.)
 */
internal fun degradationYRange(
    minSoh: Double,
    maxSoh: Double,
    trendStart: Double,
    trendEnd: Double
): Pair<Double, Double> {
    val lo = min(minSoh, min(trendStart, trendEnd).coerceAtLeast(minSoh - 3.0))
    val hi = max(maxSoh, max(trendStart, trendEnd).coerceAtMost(maxSoh + 3.0))
    var yMax = ceil(hi) + 1.0
    if (maxSoh <= 100.0) yMax = min(yMax, 100.0)
    var yMin = floor(lo) - 1.0
    if (yMax - yMin < 3.0) yMin = yMax - 3.0
    return yMin to yMax
}

/** A calendar step for the date axis, and the date skeleton its labels print. */
internal data class DateTickStep(
    val field: Int,          // Calendar field the step adds
    val count: Int,
    val approxMs: Double,    // average length, for spacing
    val skeleton: String
)

internal val DATE_TICK_STEPS = listOf(
    DateTickStep(Calendar.DAY_OF_MONTH, 1, DAY_MS, "dMMM"),
    DateTickStep(Calendar.DAY_OF_MONTH, 2, 2 * DAY_MS, "dMMM"),
    DateTickStep(Calendar.WEEK_OF_YEAR, 1, 7 * DAY_MS, "dMMM"),
    DateTickStep(Calendar.WEEK_OF_YEAR, 2, 14 * DAY_MS, "dMMM"),
    DateTickStep(Calendar.MONTH, 1, YEAR_MS / 12, "MMMyy"),
    DateTickStep(Calendar.MONTH, 2, YEAR_MS / 6, "MMMyy"),
    DateTickStep(Calendar.MONTH, 3, YEAR_MS / 4, "MMMyy"),
    DateTickStep(Calendar.MONTH, 6, YEAR_MS / 2, "MMMyy"),
    DateTickStep(Calendar.YEAR, 1, YEAR_MS, "yyyy"),
    DateTickStep(Calendar.YEAR, 2, 2 * YEAR_MS, "yyyy"),
    DateTickStep(Calendar.YEAR, 5, 5 * YEAR_MS, "yyyy"),
)

/**
 * The finest step whose labels ([labelWidthPx] wide, half that again as a gap) fit [plotWidthPx]
 * across [rangeMs]; the coarsest when none does.
 */
internal fun pickDateTickStep(
    rangeMs: Double,
    plotWidthPx: Float,
    labelWidthPx: (DateTickStep) -> Float
): DateTickStep =
    DATE_TICK_STEPS.firstOrNull { step ->
        plotWidthPx * (step.approxMs / rangeMs) >= labelWidthPx(step) * 1.5f
    } ?: DATE_TICK_STEPS.last()

/**
 * Tick times for [step] on calendar boundaries (local midnight; the locale's week start; the
 * first of a month whose index is a multiple of the count, e.g. Jan/Apr/Jul/Oct for 3 months;
 * New Year of a year divisible by the count) inside [fromMs]..[toMs].
 */
internal fun dateTicks(
    fromMs: Long,
    toMs: Long,
    step: DateTickStep,
    zone: TimeZone = TimeZone.getDefault(),
    locale: Locale = Locale.getDefault()
): List<Long> {
    val cal = Calendar.getInstance(zone, locale).apply {
        timeInMillis = fromMs
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        when (step.field) {
            Calendar.WEEK_OF_YEAR ->
                while (get(Calendar.DAY_OF_WEEK) != firstDayOfWeek) add(Calendar.DAY_OF_MONTH, -1)
            Calendar.MONTH -> {
                set(Calendar.DAY_OF_MONTH, 1)
                while (get(Calendar.MONTH) % step.count != 0) add(Calendar.MONTH, -1)
            }
            Calendar.YEAR -> {
                set(Calendar.DAY_OF_YEAR, 1)
                while (get(Calendar.YEAR) % step.count != 0) add(Calendar.YEAR, -1)
            }
        }
    }
    val ticks = mutableListOf<Long>()
    while (cal.timeInMillis <= toMs && ticks.size < 500) {
        if (cal.timeInMillis >= fromMs) ticks += cal.timeInMillis
        cal.add(step.field, step.count)
    }
    return ticks
}

// ── Regression helpers ────────────────────────────────────────────────────────

private fun linearRegression(data: List<DashboardViewModel.SohDataPoint>): RegressionResult {
    val xs  = data.map { it.timestamp.toDouble() }
    val ys  = data.map { it.avgSoh }
    val xMean = xs.average()
    val yMean = ys.average()
    val num   = xs.zip(ys).sumOf { (x, y) -> (x - xMean) * (y - yMean) }
    val den   = xs.sumOf { x -> (x - xMean).pow(2) }
    val slope = if (den == 0.0) 0.0 else num / den
    val intercept = yMean - slope * xMean

    // Project when SoH hits 80% — only meaningful if trend is declining
    val proj80Ms: Long? = if (slope < 0) {
        val ms = (80.0 - intercept) / slope
        if (ms > data.last().timestamp) ms.toLong() else null
    } else null

    return RegressionResult(slope, intercept, proj80Ms)
}

private data class DegradationStats(
    val declinePerYear   : Double,
    val projectedAt80Label: String
)

private fun degradationStats(
    regression: RegressionResult
): DegradationStats {
    // slope is % per ms → convert to % per year
    val msPerYear      = 365.25 * 24 * 3_600_000.0
    val declinePerYear = -regression.slope * msPerYear  // positive = declining

    val maxReasonableMs = System.currentTimeMillis() + 100L * 365 * 24 * 3600 * 1000

    val projLabel = when {
        regression.slope >= 0 -> "Not declining"
        regression.projectedAt80Ms == null -> "Far future"
        regression.projectedAt80Ms > maxReasonableMs -> "Far future"
        else -> {
            val calendar = java.util.Calendar.getInstance().apply {
                timeInMillis = regression.projectedAt80Ms
            }
            "${calendar.get(java.util.Calendar.YEAR)}"
        }
    }
    return DegradationStats(declinePerYear.coerceAtLeast(0.0), projLabel)
}

// ── Package-internal entry points for the screen ──────────────────────────────

internal fun computeLinearRegression(data: List<DashboardViewModel.SohDataPoint>): RegressionResult =
    linearRegression(data)

internal data class DegradationScreenStats(
    val declinePerYear    : Double,
    val projectedAt80Label: String
)

internal fun buildDegradationScreenStats(regression: RegressionResult): DegradationScreenStats {
    val stats = degradationStats(regression)
    return DegradationScreenStats(stats.declinePerYear, stats.projectedAt80Label)
}
