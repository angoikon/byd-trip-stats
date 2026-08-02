package com.byd.tripstats.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import com.byd.tripstats.data.local.entity.TripDataPointEntity
import com.byd.tripstats.ui.theme.AccelerationOrange
import com.byd.tripstats.ui.theme.BatteryBlue
import com.byd.tripstats.ui.theme.BydErrorRed
import com.byd.tripstats.ui.theme.ChargingYellow
import com.byd.tripstats.ui.theme.RegenGreen
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val tripModeJson = Json { ignoreUnknownKeys = true }

data class TripPointModes(
    val driveMode: Int,
    val regenMode: Int,
    /** PHEV energy/powertrain source mode (1=EV, 2=Force EV, 3=HEV, 4=Fuel, 5=Keep); 0 on BEVs. */
    val energyMode: Int = 0
)

enum class DriveModeFilter(val modeValue: Int?, val label: String) {
    ALL(null, "All"),
    ECO(1, "Eco"),
    SPORT(2, "Sport"),
    NORMAL(3, "Normal")
}

enum class RegenModeFilter(val modeValue: Int?, val label: String) {
    ALL(null, "All"),
    STANDARD(1, "Standard"),
    HIGH(2, "High")
}

internal fun String.toTripModeJsonObjectOrNull(): JsonObject? {
    if (isBlank() || this == "{}") return null
    return runCatching { tripModeJson.parseToJsonElement(this).jsonObject }.getOrNull()
}

fun TripDataPointEntity.extractTripModes(): TripPointModes {
    val json = rawJson.toTripModeJsonObjectOrNull()
    return TripPointModes(
        driveMode = json.intOrZero("drive_mode"),
        regenMode = json.intOrZero("regen_mode"),
        energyMode = json.intOrZero("energy_mode")
    )
}

private fun JsonObject?.intOrZero(key: String): Int =
    this?.get(key)?.jsonPrimitive?.intOrNull ?: 0

fun driveModeLabel(mode: Int): String = when (mode) {
    1 -> "Eco"
    2 -> "Sport"
    3 -> "Normal"
    else -> "Unknown"
}

fun regenModeLabel(mode: Int): String = when (mode) {
    1 -> "Standard"
    2 -> "High"
    else -> "Unknown"
}

fun driveModeColor(mode: Int): Color = when (mode) {
    1 -> RegenGreen
    2 -> BydErrorRed
    3 -> BatteryBlue
    else -> Color.Gray.copy(alpha = 0.45f)
}

fun regenModeColor(mode: Int): Color = when (mode) {
    1 -> ChargingYellow
    2 -> AccelerationOrange
    else -> Color.Gray.copy(alpha = 0.45f)
}

// ── PHEV energy/powertrain source mode (product terms, untranslated) ──────────

fun energyModeLabel(mode: Int): String = when (mode) {
    1 -> "EV"
    2 -> "Force EV"
    3 -> "HEV"
    4 -> "Fuel"
    5 -> "Keep"
    else -> "Unknown"
}

fun energyModeColor(mode: Int): Color = when (mode) {
    1 -> RegenGreen                 // pure electric
    2 -> BatteryBlue                // forced electric
    3 -> AccelerationOrange         // hybrid — ICE assisting
    4 -> BydErrorRed                // pure combustion
    5 -> ChargingYellow             // hold/keep SoC
    else -> Color.Gray.copy(alpha = 0.45f)
}

fun hasEnergyModeData(dataPoints: List<TripDataPointEntity>): Boolean =
    dataPoints.any { it.extractTripModes().energyMode != 0 }

fun filterTripPointsByModes(
    dataPoints: List<TripDataPointEntity>,
    driveMode: DriveModeFilter,
    regenMode: RegenModeFilter
): List<TripDataPointEntity> = dataPoints.filter { point ->
    val modes = point.extractTripModes()
    (driveMode.modeValue == null || modes.driveMode == driveMode.modeValue) &&
        (regenMode.modeValue == null || modes.regenMode == regenMode.modeValue)
}

fun hasTripModeData(dataPoints: List<TripDataPointEntity>): Boolean =
    dataPoints.any {
        val modes = it.extractTripModes()
        modes.driveMode != 0 || modes.regenMode != 0
    }

/** Returns the single drive mode value if the whole trip used exactly one mode; null otherwise. */
fun List<TripPointModes>.singleDriveModeOrNull(): Int? =
    mapNotNull { it.driveMode.takeIf { m -> m != 0 } }.toSet().singleOrNull()

/**
 * Draws a small drive-mode label in the top-right corner of the chart.
 * Only call when there is exactly one drive mode for the whole trip —
 * multi-mode trips are self-describing via colour segments.
 */
fun DrawScope.drawDriveModeLabel(singleDriveMode: Int?, padR: Float, padT: Float) {
    singleDriveMode ?: return
    val color = driveModeColor(singleDriveMode)
    val label = "\"${driveModeLabel(singleDriveMode)}\""
    val paint = android.graphics.Paint().apply {
        this.color = color.copy(alpha = 0.85f).toArgb()
        textSize = 24f
        textAlign = android.graphics.Paint.Align.RIGHT
        isAntiAlias = true
        isFakeBoldText = true
    }
    drawIntoCanvas { canvas ->
        canvas.nativeCanvas.drawText(label, size.width - padR - 18f, padT + 34f, paint)
    }
}
