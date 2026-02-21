package com.byd.sealstats.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.byd.sealstats.data.local.entity.TripDataPointEntity
import com.patrykandpatrick.vico.compose.axis.horizontal.rememberBottomAxis
import com.patrykandpatrick.vico.compose.axis.vertical.rememberStartAxis
import com.patrykandpatrick.vico.compose.chart.Chart
import com.patrykandpatrick.vico.compose.chart.line.lineChart
import com.patrykandpatrick.vico.compose.style.ProvideChartStyle
import com.patrykandpatrick.vico.core.entry.entryModelOf
import com.patrykandpatrick.vico.compose.m3.style.m3ChartStyle

@Composable
fun PowerChart(
    dataPoints: List<TripDataPointEntity>,
    modifier: Modifier = Modifier
) {
    if (dataPoints.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                text = "No data available",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }
    
    val powerData = remember(dataPoints) {
        dataPoints.map { it.power.toFloat() }
    }
    
    val chartEntryModel = remember(powerData) {
        entryModelOf(*powerData.toTypedArray())
    }
    
    ProvideChartStyle(m3ChartStyle()) {
        Chart(
            chart = lineChart(),
            model = chartEntryModel,
            startAxis = rememberStartAxis(
                title = "Power (kW)"
            ),
            bottomAxis = rememberBottomAxis(
                title = "Time (min)",
                valueFormatter = { value, _ ->
                    if (dataPoints.isEmpty()) return@rememberBottomAxis "0m"
                    val totalDuration = (dataPoints.last().timestamp - dataPoints.first().timestamp) / 1000.0
                    val seconds = (value / (dataPoints.size - 1)) * totalDuration
                    val minutes = (seconds / 60.0).toInt()
                    "${minutes}m"
                }
            ),
            modifier = modifier
        )
    }
}