package com.byd.tripstats.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.byd.tripstats.data.local.entity.TripDataPointEntity
import com.patrykandpatrick.vico.compose.axis.horizontal.rememberBottomAxis
import com.patrykandpatrick.vico.compose.axis.vertical.rememberStartAxis
import com.patrykandpatrick.vico.compose.chart.Chart
import com.patrykandpatrick.vico.compose.chart.line.lineChart
import com.patrykandpatrick.vico.compose.chart.line.lineSpec
import com.patrykandpatrick.vico.compose.m3.style.m3ChartStyle
import com.patrykandpatrick.vico.compose.style.ProvideChartStyle
import com.patrykandpatrick.vico.core.entry.entryModelOf
import com.patrykandpatrick.vico.core.entry.entryOf

@Composable
fun MotorRpmChart(
    dataPoints: List<TripDataPointEntity>,
    modifier: Modifier = Modifier
) {
    if (dataPoints.isEmpty()) {
        Box(
            modifier = modifier,
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "No motor data available",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    // Create separate entry lists for front and rear motors
    val rearMotorEntries = dataPoints.mapIndexed { index, point ->
        entryOf(index, point.engineSpeedRear)
    }

    val frontMotorEntries = dataPoints.mapIndexed { index, point ->
        entryOf(index, point.engineSpeedFront)
    }

    val chartEntryModel = entryModelOf(
        rearMotorEntries,
        frontMotorEntries
    )

    Column(modifier = modifier) {
        // Legend - ALWAYS VISIBLE
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            LegendItem(color = Color(0xFF1976D2), label = "Rear Motor")
            Spacer(modifier = Modifier.width(24.dp))
            LegendItem(color = Color(0xFF7821F3), label = "Front Motor")
        }
        
        // Chart
        ProvideChartStyle(m3ChartStyle()) {
            Chart(
                chart = lineChart(
                    lines = listOf(
                        lineSpec(lineColor = Color(0xFF1976D2)), // Dark blue for rear motor
                        lineSpec(lineColor = Color(0xFF7821F3)) // Magenta for front motor
                    )
                ),
                model = chartEntryModel,
                startAxis = rememberStartAxis(
                    title = "RPM"
                ),
                bottomAxis = rememberBottomAxis(
                    title = "Time (min)",
                    valueFormatter = { value, _ ->
                        if (dataPoints.isEmpty()) return@rememberBottomAxis "0m"
                        val totalDuration = (dataPoints.last().timestamp - dataPoints.first().timestamp) / 1000.0 // seconds
                        val seconds = (value / (dataPoints.size - 1)) * totalDuration
                        val minutes = (seconds / 60.0).toInt()
                        "${minutes}m"
                    }
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
        }
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .background(color)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}