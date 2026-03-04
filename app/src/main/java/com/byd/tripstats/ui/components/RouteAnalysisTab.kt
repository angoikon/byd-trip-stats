package com.byd.tripstats.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.byd.tripstats.data.local.entity.TripDataPointEntity
import com.byd.tripstats.ui.theme.AccelerationOrange
import com.byd.tripstats.ui.theme.BydErrorRed
import com.byd.tripstats.ui.theme.RegenGreen
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs

/**
 * Route Analysis tab — detailed trip statistics and timeline.
 * Shows waypoints, segments, energy hotspots, and timeline.
 */
@Composable
fun RouteAnalysisTab(
    dataPoints: List<TripDataPointEntity>,
    modifier: Modifier = Modifier
) {
    if (dataPoints.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "No route data available",
                style = MaterialTheme.typography.titleMedium
            )
        }
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        WaypointsCard(dataPoints)
        RouteSegmentsCard(dataPoints)
        EnergyHeatmapCard(dataPoints)
        TripTimelineCard(dataPoints)
    }
}

// ── Shared card border modifier ───────────────────────────────────────────────

private val cardBorder: Modifier
    @Composable get() = Modifier.border(
        width = 1.dp,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f),
        shape = MaterialTheme.shapes.medium
    )

private val cardColors: CardColors
    @Composable get() = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
    )

// ── Helpers ───────────────────────────────────────────────────────────────────

private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
private fun fmt(ts: Long) = timeFormat.format(Date(ts))

// ── Waypoints ─────────────────────────────────────────────────────────────────

@Composable
private fun WaypointsCard(dataPoints: List<TripDataPointEntity>) {
    val startPoint = dataPoints.first()
    val endPoint   = dataPoints.last()

    Card(
        modifier = Modifier.fillMaxWidth().then(cardBorder),
        colors = cardColors
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Waypoints",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))
            WaypointItem(
                icon    = Icons.Filled.FlagCircle,
                label   = "Start",
                time    = fmt(startPoint.timestamp),
                soc     = "${startPoint.soc.toInt()}%",
                color   = RegenGreen
            )
            Spacer(modifier = Modifier.height(8.dp))
            WaypointItem(
                icon    = Icons.Filled.LocationOn,
                label   = "End",
                time    = fmt(endPoint.timestamp),
                soc     = "${endPoint.soc.toInt()}%",
                color   = BydErrorRed
            )
        }
    }
}

@Composable
private fun WaypointItem(
    icon: ImageVector,
    label: String,
    time: String,
    soc: String,
    color: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(imageVector = icon, contentDescription = label, tint = color, modifier = Modifier.size(32.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(text = time, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(text = "SOC: $soc", style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ── Route Segments ────────────────────────────────────────────────────────────

@Composable
private fun RouteSegmentsCard(dataPoints: List<TripDataPointEntity>) {
    val segmentSize = (dataPoints.size / 5).coerceAtLeast(1)
    val segments    = dataPoints.chunked(segmentSize).take(5)

    Card(
        modifier = Modifier.fillMaxWidth().then(cardBorder),
        colors = cardColors
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Route Segments",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))

            segments.forEachIndexed { index, segment ->
                val avgSpeed  = segment.map { it.speed }.average()
                val avgPower  = segment.map { it.power }.average()
                val socChange = segment.first().soc - segment.last().soc
                val startTime = fmt(segment.first().timestamp)
                val endTime   = fmt(segment.last().timestamp)

                SegmentItem(
                    segmentNumber = index + 1,
                    timeRange     = "$startTime – $endTime",
                    avgSpeed      = avgSpeed.toInt(),
                    avgPower      = avgPower.toInt(),
                    socChange     = socChange
                )

                if (index < segments.size - 1) {
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun SegmentItem(
    segmentNumber: Int,
    timeRange: String,
    avgSpeed: Int,
    avgPower: Int,
    socChange: Double
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.05f),
                shape = RoundedCornerShape(8.dp)
            )
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = "Segment $segmentNumber",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = timeRange,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(text = "$avgSpeed km/h", style = MaterialTheme.typography.bodySmall)
            Text(
                text = "${abs(avgPower)} kW",
                style = MaterialTheme.typography.bodySmall,
                color = if (avgPower < 0) RegenGreen else AccelerationOrange
            )
            Text(
                text = "${String.format("%.1f", abs(socChange))}% SoC",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

// ── Energy Hotspots ───────────────────────────────────────────────────────────

@Composable
private fun EnergyHeatmapCard(dataPoints: List<TripDataPointEntity>) {
    // Chunk into groups of 10 points and calculate energy using actual time deltas.
    // E (kWh) = Σ power_kW × Δt_seconds / 3600
    data class EnergySegment(val startTs: Long, val endTs: Long, val energyKwh: Double)

    val segments = dataPoints.chunked(10).map { chunk ->
        var energy = 0.0
        for (i in 1 until chunk.size) {
            val dtSeconds = (chunk[i].timestamp - chunk[i - 1].timestamp) / 1000.0
            energy += abs(chunk[i].power) * dtSeconds / 3600.0
        }
        EnergySegment(
            startTs   = chunk.first().timestamp,
            endTs     = chunk.last().timestamp,
            energyKwh = energy
        )
    }.sortedByDescending { it.energyKwh }.take(5)

    Card(
        modifier = Modifier.fillMaxWidth().then(cardBorder),
        colors = cardColors
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Energy Hotspots",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Segments with highest energy usage",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))

            segments.forEachIndexed { index, seg ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${fmt(seg.startTs)} – ${fmt(seg.endTs)}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "${String.format("%.3f", seg.energyKwh)} kWh",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = AccelerationOrange
                    )
                }
                if (index < segments.size - 1) {
                    HorizontalDivider(
                        modifier  = Modifier.padding(vertical = 2.dp),
                        thickness = 0.5.dp,
                        color     = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
                    )
                }
            }
        }
    }
}

// ── Trip Timeline ─────────────────────────────────────────────────────────────

@Composable
private fun TripTimelineCard(dataPoints: List<TripDataPointEntity>) {
    val events = mutableListOf<TimelineEvent>()

    events.add(TimelineEvent(fmt(dataPoints.first().timestamp), "Trip Started",  Icons.Filled.FlagCircle,  RegenGreen))

    // Debounce acceleration/braking events: use a 5-point rolling average of power
    // to suppress noise, and enforce a minimum 10-second gap between events.
    val window = 5
    var lastEventTs = dataPoints.first().timestamp

    for (i in window until dataPoints.size) {
        val curr = dataPoints[i]
        val gapSeconds = (curr.timestamp - lastEventTs) / 1000.0
        if (gapSeconds < 10.0) continue // too soon after last event

        val avgBefore = dataPoints.subList(i - window, i).map { it.power }.average()
        val avgAfter  = dataPoints.subList(i, (i + window).coerceAtMost(dataPoints.size)).map { it.power }.average()
        val delta     = avgAfter - avgBefore

        if (abs(delta) > 30) { // significant smoothed power change
            events.add(TimelineEvent(
                time  = fmt(curr.timestamp),
                title = if (delta > 0) "Hard Acceleration" else "Hard Braking",
                icon  = if (delta > 0) Icons.Filled.TrendingUp else Icons.Filled.TrendingDown,
                color = if (delta > 0) AccelerationOrange else RegenGreen
            ))
            lastEventTs = curr.timestamp
        }
    }

    events.add(TimelineEvent(fmt(dataPoints.last().timestamp), "Trip Ended", Icons.Filled.LocationOn, BydErrorRed))

    Card(
        modifier = Modifier.fillMaxWidth().then(cardBorder),
        colors = cardColors
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Trip Timeline",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))

            events.take(15).forEachIndexed { index, event ->
                TimelineEventItem(event)
                if (index < (events.take(15).size - 1)) {
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun TimelineEventItem(event: TimelineEvent) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(imageVector = event.icon, contentDescription = event.title, tint = event.color, modifier = Modifier.size(24.dp))
        Column {
            Text(text = event.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(text = event.time, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private data class TimelineEvent(
    val time:  String,
    val title: String,
    val icon:  ImageVector,
    val color: Color
)