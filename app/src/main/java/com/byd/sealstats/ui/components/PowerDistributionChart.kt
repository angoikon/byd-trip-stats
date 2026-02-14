package com.byd.sealstats.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import com.byd.sealstats.ui.theme.AccelerationOrange
import com.byd.sealstats.ui.theme.RegenGreen
import com.patrykandpatrick.vico.compose.axis.horizontal.rememberBottomAxis
import com.patrykandpatrick.vico.compose.axis.vertical.rememberStartAxis
import com.patrykandpatrick.vico.compose.chart.Chart
import com.patrykandpatrick.vico.compose.chart.column.columnChart
import com.patrykandpatrick.vico.compose.style.ProvideChartStyle
import com.patrykandpatrick.vico.core.entry.entryModelOf
import com.patrykandpatrick.vico.compose.m3.style.m3ChartStyle

@Composable
fun PowerDistributionChart(
    powerDistribution: Map<String, Double>,
    modifier: Modifier = Modifier
) {
    if (powerDistribution.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                text = "No data available",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }
    
    // Convert power distribution to chart data
    val chartData = remember(powerDistribution) {
        val orderedKeys = listOf(
            "regen_strong",
            "regen_medium", 
            "regen_light",
            "cruising",
            "acceleration",
            "hard_acceleration"
        )
        
        orderedKeys.mapNotNull { key ->
            powerDistribution[key]?.toFloat()
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
                title = "Power Range"
            ),
            modifier = modifier
        )
    }
}
