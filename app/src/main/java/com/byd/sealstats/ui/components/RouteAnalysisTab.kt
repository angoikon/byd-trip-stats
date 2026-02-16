package com.byd.sealstats.ui.components

import androidx.compose.foundation.background
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
import com.byd.sealstats.data.local.entity.TripDataPointEntity
import com.byd.sealstats.ui.theme.AccelerationOrange
import com.byd.sealstats.ui.theme.RegenGreen
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs

/**
 * Route Analysis tab - detailed trip statistics and timeline
 * Shows waypoints, segments, energy usage, and timeline
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
        // Waypoints Section
        WaypointsCard(dataPoints)
        
        // Route Segments
        RouteSegmentsCard(dataPoints)
        
        // Energy Heatmap
        EnergyHeatmapCard(dataPoints)
        
        // Timeline
        TripTimelineCard(dataPoints)
    }
}

@Composable
private fun WaypointsCard(dataPoints: List<TripDataPointEntity>) {
    val startPoint = dataPoints.first()
    val endPoint = dataPoints.last()
    val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Waypoints",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Start point
            WaypointItem(
                icon = Icons.Filled.PlayArrow,
                label = "Start",
                time = timeFormat.format(Date(startPoint.timestamp)),
                location = "${String.format("%.6f", startPoint.latitude)}, ${String.format("%.6f", startPoint.longitude)}",
                speed = "${startPoint.speed.toInt()} km/h",
                soc = "${startPoint.soc.toInt()}%",
                color = Color.Green
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // End point
            WaypointItem(
                icon = Icons.Filled.Stop,
                label = "End",
                time = timeFormat.format(Date(endPoint.timestamp)),
                location = "${String.format("%.6f", endPoint.latitude)}, ${String.format("%.6f", endPoint.longitude)}",
                speed = "${endPoint.speed.toInt()} km/h",
                soc = "${endPoint.soc.toInt()}%",
                color = Color.Red
            )
        }
    }
}

@Composable
private fun WaypointItem(
    icon: ImageVector,
    label: String,
    time: String,
    location: String,
    speed: String,
    soc: String,
    color: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = color,
            modifier = Modifier.size(32.dp)
        )
        
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Time: $time",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = location,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Speed: $speed",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "SOC: $soc",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun RouteSegmentsCard(dataPoints: List<TripDataPointEntity>) {
    // Split trip into segments (every 20% of points)
    val segmentSize = (dataPoints.size / 5).coerceAtLeast(1)
    val segments = dataPoints.chunked(segmentSize).take(5)
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Route Segments",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            segments.forEachIndexed { index, segment ->
                val avgSpeed = segment.map { it.speed }.average()
                val avgPower = segment.map { it.power }.average()
                val socChange = segment.first().soc - segment.last().soc
                
                SegmentItem(
                    segmentNumber = index + 1,
                    avgSpeed = avgSpeed.toInt(),
                    avgPower = avgPower.toInt(),
                    socChange = socChange
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
    avgSpeed: Int,
    avgPower: Int,
    socChange: Double
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(8.dp)
            )
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Segment $segmentNumber",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = "$avgSpeed km/h",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "${abs(avgPower)} kW",
                style = MaterialTheme.typography.bodySmall,
                color = if (avgPower < 0) RegenGreen else AccelerationOrange
            )
            Text(
                text = "${String.format("%.1f", abs(socChange))}% ${if (socChange > 0) "used" else "gained"}",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun EnergyHeatmapCard(dataPoints: List<TripDataPointEntity>) {
    // Find segments with highest energy consumption
    val segmentSize = 10
    val energySegments = dataPoints.chunked(segmentSize).mapIndexed { index, segment ->
        val totalEnergy = segment.sumOf { abs(it.power) }
        index to totalEnergy
    }.sortedByDescending { it.second }.take(5)
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
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
            
            energySegments.forEach { (segmentIndex, energy) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Time: ${segmentIndex * segmentSize}s - ${(segmentIndex + 1) * segmentSize}s",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "${String.format("%.1f", energy)} kW",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = AccelerationOrange
                    )
                }
            }
        }
    }
}

@Composable
private fun TripTimelineCard(dataPoints: List<TripDataPointEntity>) {
    // Create timeline with major events
    val events = mutableListOf<TimelineEvent>()
    val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    
    // Start
    events.add(TimelineEvent(
        time = timeFormat.format(Date(dataPoints.first().timestamp)),
        title = "Trip Started",
        icon = Icons.Filled.PlayArrow,
        color = Color.Green
    ))
    
    // Find acceleration/braking events
    for (i in 1 until dataPoints.size) {
        val prev = dataPoints[i - 1]
        val curr = dataPoints[i]
        val powerChange = curr.power - prev.power
        
        if (abs(powerChange) > 20) { // Significant power change
            events.add(TimelineEvent(
                time = timeFormat.format(Date(curr.timestamp)),
                title = if (powerChange > 0) "Hard Acceleration" else "Hard Braking",
                icon = if (powerChange > 0) Icons.Filled.TrendingUp else Icons.Filled.TrendingDown,
                color = if (powerChange > 0) AccelerationOrange else RegenGreen
            ))
        }
    }
    
    // End
    events.add(TimelineEvent(
        time = timeFormat.format(Date(dataPoints.last().timestamp)),
        title = "Trip Ended",
        icon = Icons.Filled.Stop,
        color = Color.Red
    ))
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Trip Timeline",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            events.take(10).forEach { event ->
                TimelineEventItem(event)
                Spacer(modifier = Modifier.height(8.dp))
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
        Icon(
            imageVector = event.icon,
            contentDescription = event.title,
            tint = event.color,
            modifier = Modifier.size(24.dp)
        )
        
        Column {
            Text(
                text = event.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = event.time,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private data class TimelineEvent(
    val time: String,
    val title: String,
    val icon: ImageVector,
    val color: Color
)
