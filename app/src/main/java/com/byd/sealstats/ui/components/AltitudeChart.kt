package com.byd.sealstats.ui.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.byd.sealstats.data.local.entity.TripDataPointEntity
import com.patrykandpatrick.vico.compose.axis.horizontal.rememberBottomAxis
import com.patrykandpatrick.vico.compose.axis.vertical.rememberStartAxis
import com.patrykandpatrick.vico.compose.chart.Chart
import com.patrykandpatrick.vico.compose.chart.line.lineChart
import com.patrykandpatrick.vico.compose.style.ProvideChartStyle
import com.patrykandpatrick.vico.core.chart.values.AxisValuesOverrider
import com.patrykandpatrick.vico.core.entry.FloatEntry
import com.patrykandpatrick.vico.core.entry.entryModelOf

/**
 * Altitude/Elevation chart showing trip elevation profile over time
 * Y-axis: Altitude in meters
 * X-axis: Time (trip duration)
 */
@Composable
fun AltitudeChart(
    dataPoints: List<TripDataPointEntity>,
    modifier: Modifier = Modifier
) {
    val chartColor = MaterialTheme.colorScheme.tertiary
    
    if (dataPoints.isEmpty()) {
        return
    }
    
    // Create chart entries from altitude data
    val chartEntries = remember(dataPoints) {
        dataPoints.mapIndexed { index, point ->
            FloatEntry(
                x = index.toFloat(),
                y = point.altitude.toFloat()
            )
        }
    }
    
    val chartModel = remember(chartEntries) {
        entryModelOf(chartEntries)
    }
    
    ProvideChartStyle {
        Chart(
            chart = lineChart(
                axisValuesOverrider = AxisValuesOverrider.fixed(
                    minY = (dataPoints.minOfOrNull { it.altitude }?.toFloat() ?: 0f) - 10f,
                    maxY = (dataPoints.maxOfOrNull { it.altitude }?.toFloat() ?: 100f) + 10f
                )
            ),
            model = chartModel,
            startAxis = rememberStartAxis(
                title = "Altitude (m)"
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
            modifier = modifier.fillMaxSize()
        )
    }
}
