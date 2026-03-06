package com.byd.tripstats.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.byd.tripstats.data.local.entity.TripDataPointEntity
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max

// ── Entry point ───────────────────────────────────────────────────────────────

@Composable
fun TripHeatmapsTab(dataPoints: List<TripDataPointEntity>) {
    if (dataPoints.size < 30) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "Not enough data points for heatmaps.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // 1. Power vs Speed — the classic EV motor operating map
        HeatmapCard(
            title    = "Power vs Speed",
            subtitle = "Motor output at each speed — shows where the car actually operates"
        ) {
            PowerVsSpeedHeatmap(dataPoints, Modifier.fillMaxSize())
        }

        // 2. Instantaneous consumption vs speed — where efficiency is good or poor
        HeatmapCard(
            title    = "Consumption vs Speed",
            subtitle = "Instantaneous efficiency (kWh/100km) across the speed range"
        ) {
            ConsumptionVsSpeedHeatmap(dataPoints, Modifier.fillMaxSize())
        }

        // 3. Regen power vs speed — how much energy is recovered during braking
        HeatmapCard(
            title    = "Regen Power vs Speed",
            subtitle = "Regenerative braking strength by speed band"
        ) {
            RegenVsSpeedHeatmap(dataPoints, Modifier.fillMaxSize())
        }

        // 4. Motor RPM vs speed — powertrain operating envelope
        HeatmapCard(
            title    = "Motor RPM vs Speed",
            subtitle = "Powertrain operating map — near-linear for a direct-drive EV"
        ) {
            RpmVsSpeedHeatmap(dataPoints, Modifier.fillMaxSize())
        }

        // 5. Battery temperature vs power — thermal behaviour profile
        HeatmapCard(
            title    = "Battery Temp vs Power",
            subtitle = "Thermal operating window — higher power available as pack warms up"
        ) {
            BatteryTempVsPowerHeatmap(dataPoints, Modifier.fillMaxSize())
        }

        // 6. Acceleration vs Speed — driving style map
        HeatmapCard(
            title    = "Acceleration vs Speed",
            subtitle = "Where the driver accelerates hard or coasts — lower is more efficient"
        ) {
            AccelerationVsSpeedHeatmap(dataPoints, Modifier.fillMaxSize())
        }

        // 7. SOC vs Instantaneous Consumption — battery state effect on efficiency
        HeatmapCard(
            title    = "SOC vs Consumption",
            subtitle = "Whether efficiency changes at low or high charge states"
        ) {
            SocVsConsumptionHeatmap(dataPoints, Modifier.fillMaxSize())
        }

        // 8. Time of Day vs Speed — traffic pattern map
        HeatmapCard(
            title    = "Time of Day vs Speed",
            subtitle = "When and how fast you drive — reveals rush-hour congestion patterns"
        ) {
            TimeOfDayVsSpeedHeatmap(dataPoints, Modifier.fillMaxSize())
        }

        // 9. Altitude Gradient vs Consumption — topography cost
        HeatmapCard(
            title    = "Gradient vs Consumption",
            subtitle = "How slope affects energy — regen visible in negative-gradient band"
        ) {
            GradientVsConsumptionHeatmap(dataPoints, Modifier.fillMaxSize())
        }

        // 10. Front RPM vs Rear RPM — AWD torque split
        HeatmapCard(
            title    = "Front vs Rear Motor RPM",
            subtitle = "AWD torque split — diagonal = equal share, off-diagonal = one motor dominant"
        ) {
            FrontVsRearRpmHeatmap(dataPoints, Modifier.fillMaxSize())
        }
    }
}

// ── Individual heatmaps ───────────────────────────────────────────────────────

@Composable
private fun PowerVsSpeedHeatmap(
    dataPoints: List<TripDataPointEntity>,
    modifier: Modifier = Modifier
) {
    val xBins = 16; val yBins = 14
    val xMin  = 0f;   val xMax = 160f   // km/h
    val yMin  = -100f; val yMax = 300f  // kW  (negative = regen)

    val cells = remember(dataPoints) {
        buildGrid(
            points = dataPoints.mapNotNull { p ->
                val spd = p.speed.toFloat().takeIf { it >= 0f } ?: return@mapNotNull null
                val pwr = p.power?.toFloat()                    ?: return@mapNotNull null
                spd to pwr
            },
            xMin = xMin, xMax = xMax, xBins = xBins,
            yMin = yMin, yMax = yMax, yBins = yBins
        )
    }

    Heatmap2D(
        cells      = cells,
        xLabels    = axisLabels(xMin, xMax, xBins),
        yLabels    = axisLabels(yMin, yMax, yBins),
        xAxisLabel = "Speed (km/h)",
        yAxisLabel = "Power (kW)",
        modifier   = modifier
    )
}

@Composable
private fun ConsumptionVsSpeedHeatmap(
    dataPoints: List<TripDataPointEntity>,
    modifier: Modifier = Modifier
) {
    // Instantaneous consumption = power (kW) / speed (km/h) × 100 → kWh/100km
    val xBins = 14; val yBins = 14
    val xMin  = 5f;   val xMax = 160f  // km/h  (skip near-zero to avoid artefacts)
    val yMin  = -60f; val yMax = 80f   // kWh/100km  (negative = regen)

    val cells = remember(dataPoints) {
        buildGrid(
            points = dataPoints.mapNotNull { p ->
                val spd = p.speed.toFloat().takeIf { it >= 5f } ?: return@mapNotNull null
                val pwr = p.power?.toFloat()                    ?: return@mapNotNull null
                val consumption = pwr / spd * 100f
                spd to consumption
            },
            xMin = xMin, xMax = xMax, xBins = xBins,
            yMin = yMin, yMax = yMax, yBins = yBins
        )
    }

    Heatmap2D(
        cells      = cells,
        xLabels    = axisLabels(xMin, xMax, xBins),
        yLabels    = axisLabels(yMin, yMax, yBins),
        xAxisLabel = "Speed (km/h)",
        yAxisLabel = "kWh / 100 km",
        modifier   = modifier
    )
}

@Composable
private fun RegenVsSpeedHeatmap(
    dataPoints: List<TripDataPointEntity>,
    modifier: Modifier = Modifier
) {
    val xBins = 14; val yBins = 12
    val xMin  = 0f; val xMax = 140f   // km/h
    val yMin  = 0f; val yMax = 120f   // kW (magnitude — we only keep regen samples)

    val regenPoints = remember(dataPoints) {
        dataPoints.mapNotNull { p ->
            val spd = p.speed.toFloat().takeIf { it >= 0f } ?: return@mapNotNull null
            val pwr = p.power?.toFloat()?.takeIf { it < 0f } ?: return@mapNotNull null
            spd to abs(pwr)
        }
    }

    if (regenPoints.size < 10) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(
                "No regenerative braking data recorded on this trip.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    val cells = remember(regenPoints) {
        buildGrid(regenPoints, xMin, xMax, xBins, yMin, yMax, yBins)
    }

    Heatmap2D(
        cells      = cells,
        xLabels    = axisLabels(xMin, xMax, xBins),
        yLabels    = axisLabels(yMin, yMax, yBins),
        xAxisLabel = "Speed (km/h)",
        yAxisLabel = "Regen Power (kW)",
        modifier   = modifier
    )
}

@Composable
private fun RpmVsSpeedHeatmap(
    dataPoints: List<TripDataPointEntity>,
    modifier: Modifier = Modifier
) {
    val xBins = 14; val yBins = 12
    val xMin  = 0f;    val xMax = 160f    // km/h
    val yMin  = 0f;    val yMax = 14000f  // RPM

    val rpmPoints = remember(dataPoints) {
        dataPoints.mapNotNull { p ->
            val spd      = p.speed.toFloat().takeIf { it >= 0f } ?: return@mapNotNull null
            val frontRpm = p.engineSpeedFront?.toFloat() ?: 0f
            val rearRpm  = p.engineSpeedRear?.toFloat()  ?: 0f
            val rpm      = max(frontRpm, rearRpm).takeIf { it > 0f } ?: return@mapNotNull null
            spd to rpm
        }
    }

    if (rpmPoints.size < 10) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(
                "No motor RPM data recorded on this trip.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    val cells = remember(rpmPoints) {
        buildGrid(rpmPoints, xMin, xMax, xBins, yMin, yMax, yBins)
    }

    Heatmap2D(
        cells      = cells,
        xLabels    = axisLabels(xMin, xMax, xBins),
        yLabels    = axisLabels(yMin, yMax, yBins, fmt = "%.0f"),
        xAxisLabel = "Speed (km/h)",
        yAxisLabel = "Motor RPM",
        modifier   = modifier
    )
}

@Composable
private fun BatteryTempVsPowerHeatmap(
    dataPoints: List<TripDataPointEntity>,
    modifier: Modifier = Modifier
) {
    val xBins = 12; val yBins = 12
    val xMin  = 10f;   val xMax = 50f    // °C
    val yMin  = -100f; val yMax = 300f   // kW

    val tempPoints = remember(dataPoints) {
        dataPoints.mapNotNull { p ->
            val temp = p.batteryTemp?.toFloat()?.takeIf { it > 0f } ?: return@mapNotNull null
            val pwr  = p.power?.toFloat()                           ?: return@mapNotNull null
            temp to pwr
        }
    }

    if (tempPoints.size < 10) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(
                "No battery temperature data recorded on this trip.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    val cells = remember(tempPoints) {
        buildGrid(tempPoints, xMin, xMax, xBins, yMin, yMax, yBins)
    }

    Heatmap2D(
        cells      = cells,
        xLabels    = axisLabels(xMin, xMax, xBins),
        yLabels    = axisLabels(yMin, yMax, yBins),
        xAxisLabel = "Battery Temp (°C)",
        yAxisLabel = "Power (kW)",
        modifier   = modifier
    )
}

// ── Generic 2D heatmap renderer ───────────────────────────────────────────────

/**
 * Renders a [cells] grid as a colour-mapped 2D heatmap with axis labels.
 *
 * - [cells] is indexed [xBin][yBin]; yBin 0 is drawn at the bottom.
 * - Colour intensity uses a log scale so sparse outlier bins don't wash out
 *   the dense core of the distribution.
 * - Every other tick label is blanked when the bin count is high (> 8) to
 *   prevent crowding on the car's display.
 */
@Composable
private fun Heatmap2D(
    cells      : Array<IntArray>,
    xLabels    : List<String>,
    yLabels    : List<String>,
    xAxisLabel : String,
    yAxisLabel : String,
    modifier   : Modifier = Modifier
) {
    val labelArgb   = MaterialTheme.colorScheme.onSurface.toArgb()
    val outlineColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)

    Canvas(modifier = modifier) {
        val xBins = cells.size
        if (xBins == 0) return@Canvas
        val yBins = cells[0].size
        if (yBins == 0) return@Canvas

        // ── Layout margins (px) ───────────────────────────────────────────────
        val yAxisLabelStrip = 22f   // rotated y-axis label
        val yTickStrip      = 50f   // y tick label column
        val xTickStrip      = 30f   // x tick label row
        val xAxisLabelStrip = 26f   // x axis label row
        val topPad          = 8f
        val rightPad        = 8f

        val left   = yAxisLabelStrip + yTickStrip
        val right  = size.width - rightPad
        val top    = topPad
        val bottom = size.height - xTickStrip - xAxisLabelStrip

        val gridW  = right - left
        val gridH  = bottom - top
        val cellW  = gridW / xBins
        val cellH  = gridH / yBins

        // ── Normalisation ─────────────────────────────────────────────────────
        var maxCount = 1
        for (col in cells) for (v in col) if (v > maxCount) maxCount = v

        // ── Draw cells ────────────────────────────────────────────────────────
        for (xi in 0 until xBins) {
            for (yi in 0 until yBins) {
                val count = cells[xi][yi]
                if (count == 0) continue
                val norm = (log10(count + 1f) / log10(maxCount + 1f)).coerceIn(0f, 1f)
                drawRect(
                    color   = heatmapColor(norm),
                    topLeft = Offset(left + xi * cellW + 1f, bottom - (yi + 1) * cellH + 1f),
                    size    = Size(cellW - 2f, cellH - 2f)
                )
            }
        }

        // ── Grid border ───────────────────────────────────────────────────────
        drawRect(
            color   = outlineColor,
            topLeft = Offset(left, top),
            size    = Size(gridW, gridH),
            style   = Stroke(width = 1f)
        )

        // ── Text labels via nativeCanvas ──────────────────────────────────────
        drawIntoCanvas { canvas ->
            val nc = canvas.nativeCanvas

            val tickPaint = android.graphics.Paint().apply {
                isAntiAlias = true
                textSize    = 22f
                color       = labelArgb
            }
            val axisPaint = android.graphics.Paint().apply {
                isAntiAlias    = true
                textSize       = 24f
                isFakeBoldText = true
                color          = labelArgb
            }

            // X tick labels (centred under each column)
            tickPaint.textAlign = android.graphics.Paint.Align.CENTER
            xLabels.forEachIndexed { i, lbl ->
                if (lbl.isEmpty()) return@forEachIndexed
                nc.drawText(lbl, left + (i + 0.5f) * cellW, bottom + xTickStrip * 0.85f, tickPaint)
            }

            // X axis label
            axisPaint.textAlign = android.graphics.Paint.Align.CENTER
            nc.drawText(xAxisLabel, left + gridW / 2f, size.height - 2f, axisPaint)

            // Y tick labels (right-aligned, centred vertically per row)
            tickPaint.textAlign = android.graphics.Paint.Align.RIGHT
            yLabels.forEachIndexed { i, lbl ->
                if (lbl.isEmpty()) return@forEachIndexed
                val y = bottom - (i + 0.5f) * cellH + tickPaint.textSize / 3f
                nc.drawText(lbl, left - 6f, y, tickPaint)
            }

            // Y axis label (rotated 90° counter-clockwise)
            val cx = yAxisLabelStrip / 2f
            val cy = top + gridH / 2f
            axisPaint.textAlign = android.graphics.Paint.Align.CENTER
            nc.save()
            nc.rotate(-90f, cx, cy)
            nc.drawText(yAxisLabel, cx, cy + axisPaint.textSize / 3f, axisPaint)
            nc.restore()
        }
    }
}

// ── Heatmap 6: Acceleration vs Speed ─────────────────────────────────────────

@Composable
private fun AccelerationVsSpeedHeatmap(
    dataPoints: List<TripDataPointEntity>,
    modifier: Modifier = Modifier
) {
    val xBins = 16; val yBins = 16
    val xMin  = 0f;  val xMax = 160f   // km/h
    val yMin  = -8f; val yMax =   8f   // km/h per second

    // Derive acceleration from consecutive point pairs; skip telemetry gaps > 30 s
    val accelPoints = remember(dataPoints) {
        dataPoints.zipWithNext { a, b ->
            val dtSec = (b.timestamp - a.timestamp) / 1000.0
            if (dtSec < 0.5 || dtSec > 30.0) return@zipWithNext null
            val accel    = ((b.speed - a.speed) / dtSec).toFloat()
            val midSpeed = ((a.speed + b.speed) / 2.0).toFloat()
            midSpeed to accel
        }.filterNotNull()
    }

    if (accelPoints.size < 10) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(
                "Not enough speed transitions for this heatmap.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    val cells = remember(accelPoints) {
        buildGrid(accelPoints, xMin, xMax, xBins, yMin, yMax, yBins)
    }

    Heatmap2D(
        cells      = cells,
        xLabels    = axisLabels(xMin, xMax, xBins),
        yLabels    = axisLabels(yMin, yMax, yBins, "%.1f"),
        xAxisLabel = "Speed (km/h)",
        yAxisLabel = "Accel (km/h/s)",
        modifier   = modifier
    )
}

// ── Heatmap 7: SOC vs Instantaneous Consumption ───────────────────────────────

@Composable
private fun SocVsConsumptionHeatmap(
    dataPoints: List<TripDataPointEntity>,
    modifier: Modifier = Modifier
) {
    val xBins = 20; val yBins = 16
    val xMin  = 0f;  val xMax = 100f   // SOC %
    val yMin  = 0f;  val yMax =  80f   // kWh/100km (traction only)

    // Only count forward-drive samples (speed > 10, positive power) to avoid
    // regen and idle noise skewing the distribution
    val consPoints = remember(dataPoints) {
        dataPoints.mapNotNull { p ->
            val spd = p.speed.toFloat().takeIf { it > 10f } ?: return@mapNotNull null
            val pwr = p.power?.toFloat()?.takeIf { it > 0f } ?: return@mapNotNull null
            val cons = pwr / spd * 100f
            if (cons > yMax) return@mapNotNull null
            p.soc.toFloat() to cons
        }
    }

    if (consPoints.size < 10) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(
                "Not enough driving samples for this heatmap.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    val cells = remember(consPoints) {
        buildGrid(consPoints, xMin, xMax, xBins, yMin, yMax, yBins)
    }

    Heatmap2D(
        cells      = cells,
        xLabels    = axisLabels(xMin, xMax, xBins),
        yLabels    = axisLabels(yMin, yMax, yBins),
        xAxisLabel = "SOC (%)",
        yAxisLabel = "kWh / 100 km",
        modifier   = modifier
    )
}

// ── Heatmap 8: Time of Day vs Speed ──────────────────────────────────────────

@Composable
private fun TimeOfDayVsSpeedHeatmap(
    dataPoints: List<TripDataPointEntity>,
    modifier: Modifier = Modifier
) {
    val xBins = 24; val yBins = 16
    val xMin  = 0f;  val xMax =  24f   // hour 0–23
    val yMin  = 0f;  val yMax = 160f   // km/h

    val timePoints = remember(dataPoints) {
        val cal = java.util.Calendar.getInstance()
        dataPoints.mapNotNull { dp ->
            if (dp.speed <= 0.0) return@mapNotNull null
            cal.timeInMillis = dp.timestamp
            val hour = cal.get(java.util.Calendar.HOUR_OF_DAY).toFloat()
            hour to dp.speed.toFloat()
        }
    }

    if (timePoints.size < 10) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(
                "Not enough data for time-of-day heatmap.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    val cells = remember(timePoints) {
        buildGrid(timePoints, xMin, xMax, xBins, yMin, yMax, yBins)
    }

    Heatmap2D(
        cells      = cells,
        xLabels    = axisLabels(xMin, xMax, xBins),
        yLabels    = axisLabels(yMin, yMax, yBins),
        xAxisLabel = "Hour of day",
        yAxisLabel = "Speed (km/h)",
        modifier   = modifier
    )
}

// ── Heatmap 9: Altitude Gradient vs Consumption ───────────────────────────────

@Composable
private fun GradientVsConsumptionHeatmap(
    dataPoints: List<TripDataPointEntity>,
    modifier: Modifier = Modifier
) {
    val xBins = 20; val yBins = 20
    val xMin  = -10f; val xMax = 10f   // road gradient %
    val yMin  = -30f; val yMax = 80f   // kWh/100km (negative = net regen)

    val gradPoints = remember(dataPoints) {
        dataPoints.zipWithNext { a, b ->
            val dtSec = (b.timestamp - a.timestamp) / 1000.0
            if (dtSec < 0.5 || dtSec > 30.0) return@zipWithNext null
            if (a.speed < 5.0) return@zipWithNext null
            val dOdo = (b.odometer - a.odometer).toFloat()       // km
            if (dOdo < 0.001f) return@zipWithNext null
            val dAlt     = (b.altitude - a.altitude).toFloat()   // metres
            val gradient = (dAlt / (dOdo * 1000f)) * 100f        // rise/run %
            val cons     = (a.power / a.speed * 100.0).toFloat() // kWh/100km
            if (gradient < xMin || gradient > xMax) return@zipWithNext null
            if (cons     < yMin || cons     > yMax) return@zipWithNext null
            gradient to cons
        }.filterNotNull()
    }

    if (gradPoints.size < 10) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(
                "Not enough altitude/odometer data for this heatmap.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    val cells = remember(gradPoints) {
        buildGrid(gradPoints, xMin, xMax, xBins, yMin, yMax, yBins)
    }

    Heatmap2D(
        cells      = cells,
        xLabels    = axisLabels(xMin, xMax, xBins, "%.0f"),
        yLabels    = axisLabels(yMin, yMax, yBins),
        xAxisLabel = "Gradient (%)",
        yAxisLabel = "kWh / 100 km",
        modifier   = modifier
    )
}

// ── Heatmap 10: Front RPM vs Rear RPM (AWD torque split) ─────────────────────

@Composable
private fun FrontVsRearRpmHeatmap(
    dataPoints: List<TripDataPointEntity>,
    modifier: Modifier = Modifier
) {
    val xBins = 14; val yBins = 14
    val xMin  = 0f; val xMax = 14000f   // front motor RPM
    val yMin  = 0f; val yMax = 14000f   // rear  motor RPM

    val rpmPoints = remember(dataPoints) {
        dataPoints.mapNotNull { dp ->
            val front = dp.engineSpeedFront.toFloat()
            val rear  = dp.engineSpeedRear.toFloat()
            if (front <= 0f && rear <= 0f) return@mapNotNull null
            front to rear
        }
    }

    if (rpmPoints.size < 10) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(
                "No dual-motor RPM data recorded on this trip.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    val cells = remember(rpmPoints) {
        buildGrid(rpmPoints, xMin, xMax, xBins, yMin, yMax, yBins)
    }

    Heatmap2D(
        cells      = cells,
        xLabels    = axisLabels(xMin, xMax, xBins),
        yLabels    = axisLabels(yMin, yMax, yBins),
        xAxisLabel = "Front RPM",
        yAxisLabel = "Rear RPM",
        modifier   = modifier
    )
}

// ── Card wrapper ──────────────────────────────────────────────────────────────

@Composable
private fun HeatmapCard(
    title   : String,
    subtitle: String,
    content : @Composable BoxScope.() -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(340.dp)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f),
                shape = MaterialTheme.shapes.medium
            ),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                content  = content
            )
            Spacer(Modifier.height(6.dp))
            HeatmapLegend()
        }
    }
}

/**
 * Horizontal colour-ramp strip spanning the Viridis palette used for heatmap cells,
 * with "Few samples" on the left and "Many samples" on the right.
 */
@Composable
private fun HeatmapLegend() {
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier          = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            "Few",
            style    = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
            color    = labelColor
        )
        // Gradient ramp canvas
        Canvas(
            modifier = Modifier
                .weight(1f)
                .height(10.dp)
        ) {
            val steps = 64
            val w = size.width / steps
            for (i in 0 until steps) {
                val t = i / (steps - 1f)
                drawRect(
                    color   = heatmapColor(t),
                    topLeft = androidx.compose.ui.geometry.Offset(i * w, 0f),
                    size    = androidx.compose.ui.geometry.Size(w + 1f, size.height)
                )
            }
        }
        Text(
            "Many",
            style    = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
            color    = labelColor
        )
    }
    Text(
        "Colour intensity = sample density (log scale)",
        style    = MaterialTheme.typography.labelSmall,
        fontSize = 10.sp,
        color    = labelColor,
        modifier = Modifier.fillMaxWidth()
    )
}

// ── Helpers ───────────────────────────────────────────────────────────────────

/**
 * Bins a list of (x, y) pairs into an [xBins] × [yBins] count grid.
 * Points outside the [xMin]..[xMax] / [yMin]..[yMax] range are discarded.
 */
private fun buildGrid(
    points: List<Pair<Float, Float>>,
    xMin: Float, xMax: Float, xBins: Int,
    yMin: Float, yMax: Float, yBins: Int
): Array<IntArray> {
    val grid   = Array(xBins) { IntArray(yBins) }
    val xRange = xMax - xMin
    val yRange = yMax - yMin
    for ((x, y) in points) {
        if (x < xMin || x > xMax || y < yMin || y > yMax) continue
        val xi = ((x - xMin) / xRange * xBins).toInt().coerceIn(0, xBins - 1)
        val yi = ((y - yMin) / yRange * yBins).toInt().coerceIn(0, yBins - 1)
        grid[xi][yi]++
    }
    return grid
}

/**
 * Produces [bins] axis tick labels spanning [min]..[max].
 * When bins > 8, every other label is blanked to avoid crowding on the car display.
 */
private fun axisLabels(
    min : Float,
    max : Float,
    bins: Int,
    fmt : String = "%.0f"
): List<String> {
    val step = (max - min) / bins
    return List(bins) { i ->
        if (bins <= 8 || i % 2 == 0) String.format(fmt, min + i * step)
        else ""
    }
}

/**
 * Viridis-inspired perceptual colour scale.
 * [t] = 0 → deep indigo (sparse / cold)
 * [t] = 1 → bright yellow (dense / hot)
 *
 * Renders well on the BYD DiLink dark background without relying on red,
 * which is already used for error states throughout the app.
 */
private fun heatmapColor(t: Float): Color {
    val stops = arrayOf(
        0.000f to Color(0.050f, 0.031f, 0.529f),  // deep indigo
        0.250f to Color(0.416f, 0.000f, 0.655f),  // purple
        0.500f to Color(0.694f, 0.165f, 0.565f),  // rose
        0.750f to Color(0.988f, 0.651f, 0.212f),  // amber
        1.000f to Color(0.941f, 0.976f, 0.129f),  // yellow
    )
    val clamped = t.coerceIn(0f, 1f)
    for (i in 0 until stops.size - 1) {
        val (t0, c0) = stops[i]
        val (t1, c1) = stops[i + 1]
        if (clamped <= t1) return lerp(c0, c1, (clamped - t0) / (t1 - t0))
    }
    return stops.last().second
}