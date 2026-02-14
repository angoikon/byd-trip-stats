package com.byd.sealstats.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import com.byd.sealstats.data.local.entity.TripDataPointEntity
import com.byd.sealstats.ui.theme.AccelerationOrange
import com.patrykandpatrick.vico.compose.axis.horizontal.rememberBottomAxis
import com.patrykandpatrick.vico.compose.axis.vertical.rememberStartAxis
import com.patrykandpatrick.vico.compose.chart.Chart
import com.patrykandpatrick.vico.compose.chart.line.lineChart
import com.patrykandpatrick.vico.compose.style.ProvideChartStyle
import com.patrykandpatrick.vico.core.entry.entryModelOf
import com.patrykandpatrick.vico.compose.m3.style.m3ChartStyle

@Composable
fun EnergyConsumptionChart(
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
    
    // Calculate cumulative energy consumption
    val energyData = remember(dataPoints) {
        var cumulativeEnergy = 0.0
        dataPoints.mapIndexed { index, point ->
            if (index > 0) {
                val prevPoint = dataPoints[index - 1]
                val deltaDischarge = point.totalDischarge - prevPoint.totalDischarge
                cumulativeEnergy += deltaDischarge
            }
            cumulativeEnergy.toFloat()
        }
    }
    
    if (energyData.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                text = "Insufficient data",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }
    
    val chartEntryModel = remember(energyData) {
        entryModelOf(*energyData.toTypedArray())
    }
    
    ProvideChartStyle(m3ChartStyle()) {
        Chart(
            chart = lineChart(),
            model = chartEntryModel,
            startAxis = rememberStartAxis(
                title = "Energy (kWh)"
            ),
            bottomAxis = rememberBottomAxis(
                title = "Trip Progress"
            ),
            modifier = modifier
        )
    }
}
