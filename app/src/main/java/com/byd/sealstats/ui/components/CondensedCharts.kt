package com.byd.sealstats.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.byd.sealstats.data.local.entity.TripDataPointEntity

/**
 * Condensed versions of charts for overview display
 * These charts:
 * - Sample data to ~50 points max
 * - Disable scrolling/zooming
 * - Fit entire trip in viewport
 */

@Composable
fun CondensedEnergyChart(
    dataPoints: List<TripDataPointEntity>,
    modifier: Modifier = Modifier
) {
    val condensed = remember(dataPoints) { condenseData(dataPoints) }
    EnergyConsumptionChart(dataPoints = condensed, modifier = modifier)
}

@Composable
fun CondensedSpeedChart(
    dataPoints: List<TripDataPointEntity>,
    modifier: Modifier = Modifier
) {
    val condensed = remember(dataPoints) { condenseData(dataPoints) }
    SpeedChart(dataPoints = condensed, modifier = modifier)
}

@Composable
fun CondensedMotorRpmChart(
    dataPoints: List<TripDataPointEntity>,
    modifier: Modifier = Modifier
) {
    val condensed = remember(dataPoints) { condenseData(dataPoints) }
    MotorRpmChart(dataPoints = condensed, modifier = modifier)
}

@Composable
fun CondensedAltitudeChart(
    dataPoints: List<TripDataPointEntity>,
    modifier: Modifier = Modifier
) {
    val condensed = remember(dataPoints) { condenseData(dataPoints) }
    AltitudeChart(dataPoints = condensed, modifier = modifier)
}

/**
 * Condense data points to max 45 for overview display
 * Ensures full trip fits in viewport without scrolling
 */
fun condenseData(
    dataPoints: List<TripDataPointEntity>,
    maxPoints: Int = 45  // Default
): List<TripDataPointEntity> {
    if (dataPoints.size <= maxPoints) return dataPoints

    val step = dataPoints.size / maxPoints
    return dataPoints.filterIndexed { index, _ -> index % step == 0 }
}