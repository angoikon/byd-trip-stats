package com.byd.sealstats.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.patrykandpatrick.vico.compose.axis.horizontal.rememberBottomAxis
import com.patrykandpatrick.vico.compose.axis.vertical.rememberStartAxis
import com.patrykandpatrick.vico.compose.chart.Chart
import com.patrykandpatrick.vico.compose.chart.column.columnChart
import com.patrykandpatrick.vico.compose.style.ProvideChartStyle
import com.patrykandpatrick.vico.core.entry.entryModelOf
import com.patrykandpatrick.vico.compose.m3.style.m3ChartStyle

@Composable
fun SpeedDistributionChart(
    speedDistribution: Map<String, Double>,
    modifier: Modifier = Modifier
) {
    if (speedDistribution.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                text = "No data available",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }
    
    // Convert speed distribution to chart data
    val chartData = remember(speedDistribution) {
        val orderedKeys = listOf(
            "0-30",
            "30-70",
            "70-100",
            "100-130",
            "130+"
        )
        
        orderedKeys.mapNotNull { key ->
            speedDistribution[key]?.toFloat()
        }
    }
    
    if (chartData.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                text = "Insufficient data",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }
    
    val chartEntryModel = remember(chartData) {
        entryModelOf(*chartData.toTypedArray())
    }
    
    ProvideChartStyle(m3ChartStyle()) {
        Chart(
            chart = columnChart(),
            model = chartEntryModel,
            startAxis = rememberStartAxis(
                title = "Count"
            ),
            bottomAxis = rememberBottomAxis(
                title = "Speed Range (km/h)",
                valueFormatter = { value, _ ->
                    when (value.toInt()) {
                        0 -> "0-30"
                        1 -> "30-70"
                        2 -> "70-100"
                        3 -> "100-130"
                        4 -> "130+"
                        else -> ""
                    }
                }
            ),
            modifier = modifier
        )
    }
}