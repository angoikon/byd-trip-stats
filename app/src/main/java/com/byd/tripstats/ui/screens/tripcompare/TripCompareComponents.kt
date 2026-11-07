package com.byd.tripstats.ui.screens.tripcompare

import androidx.compose.ui.graphics.Color
import com.byd.tripstats.data.local.entity.TripDataPointEntity
import com.byd.tripstats.data.local.entity.TripEntity
import com.byd.tripstats.data.preferences.SocSource
import com.byd.tripstats.ui.theme.AccelerationOrange
import com.byd.tripstats.ui.theme.BydElectricAzure
import com.byd.tripstats.ui.theme.RegenGreen
import com.byd.tripstats.util.altitudeAboveSeaLevel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

// ── Per-trip color palette ────────────────────────────────────────────────────

private val TRIP_COLORS = listOf(BydElectricAzure, AccelerationOrange, RegenGreen)

internal fun tripColor(index: Int): Color = TRIP_COLORS[index % TRIP_COLORS.size]

internal fun tripColorArgb(index: Int): Int = when (index % 3) {
    0    -> 0xFF2979FF.toInt()
    1    -> 0xFFFF6D00.toInt()
    else -> 0xFF00C853.toInt()
}

internal fun tripShortLabel(trip: TripEntity): String =
    DateTimeFormatter.ofPattern("dd/MM HH:mm")
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(trip.startTime))

internal fun formatDurationCompare(ms: Long): String {
    val h = TimeUnit.MILLISECONDS.toHours(ms)
    val m = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
    return if (h > 0) "${h}h ${m}min" else "${m}min"
}

internal fun niceStepCompare(range: Double): Double = when {
    range <= 5    -> 1.0
    range <= 20   -> 5.0
    range <= 50   -> 10.0
    range <= 200  -> 25.0
    range <= 500  -> 50.0
    else          -> 100.0
}

// ── Normalised point ──────────────────────────────────────────────────────────

internal data class NormalisedPoint(
    val distPct       : Double,
    val avgSpeed      : Double,
    val avgPower      : Double,
    val avgConsumption: Double,
    val avgSoc        : Double,
    val avgElevation  : Double
)

internal fun normaliseTripData(
    points   : List<TripDataPointEntity>,
    buckets  : Int = 100,
    socSource: SocSource = SocSource.PANEL,
): List<NormalisedPoint> {
    if (points.size < 2) return emptyList()
    val startOdo  = points.first().odometer
    val totalDist = (points.last().odometer - startOdo).coerceAtLeast(0.001)

    val bucketLists = Array(buckets) { mutableListOf<TripDataPointEntity>() }
    for (pt in points) {
        val idx = ((pt.odometer - startOdo) / totalDist * buckets)
            .toInt().coerceIn(0, buckets - 1)
        bucketLists[idx].add(pt)
    }

    return bucketLists.mapIndexedNotNull { i, pts ->
        if (pts.isEmpty()) return@mapIndexedNotNull null
        val consPts = pts.filter { it.speed > 5.0 && it.power > 0.0 }
        NormalisedPoint(
            distPct        = i / buckets.toDouble() * 100.0,
            avgSpeed       = pts.map { it.speed }.average(),
            avgPower       = pts.map { it.power }.average(),
            avgConsumption = if (consPts.isNotEmpty())
                consPts.map { it.power / it.speed * 100.0 }.average()
            else 0.0,
            avgSoc         = pts.map { p ->
                if (socSource == SocSource.PANEL && p.socPanel > 0) p.socPanel.toDouble()
                else p.soc
            }.average(),
            avgElevation   = pts.map { it.altitudeAboveSeaLevel }.average()
        )
    }
}
