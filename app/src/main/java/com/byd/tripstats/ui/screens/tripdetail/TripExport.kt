package com.byd.tripstats.ui.screens.tripdetail

import android.util.Log
import com.byd.tripstats.data.backup.TelegramManager
import com.byd.tripstats.data.local.entity.TripDataPointEntity
import com.byd.tripstats.data.local.entity.TripEntity
import com.byd.tripstats.data.preferences.SocSource
import com.byd.tripstats.data.preferences.UnitSystem
import com.byd.tripstats.data.preferences.consumptionUnit
import com.byd.tripstats.data.preferences.convertDistance
import com.byd.tripstats.data.preferences.convertEfficiency
import com.byd.tripstats.data.preferences.convertSpeed
import com.byd.tripstats.data.preferences.distanceUnit
import com.byd.tripstats.data.preferences.speedUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.abs

// Copy summary to clipboard
fun copyTripSummaryToClipboard(
    context: android.content.Context,
    trip: TripEntity,
    unitSystem: UnitSystem = UnitSystem.METRIC,
    socSource: SocSource = SocSource.PANEL
) {
    val summary = buildString {
        appendLine("🚗 BYD Trip Stats")
        appendLine("")
        appendLine("📅 Date: ${formatTimestampExport(trip.startTime)}")
        appendLine("🛣️ Distance: ${String.format("%.1f", unitSystem.convertDistance(trip.distance ?: 0.0))} ${unitSystem.distanceUnit}")
        appendLine("⏱️ Duration: ${formatDurationExport(trip.duration ?: 0)}")
        appendLine("⚡ Energy: ${String.format("%.2f", trip.energyConsumed ?: 0.0)} kWh")
        appendLine("🌿 Consumption: ${String.format("%.1f", unitSystem.convertEfficiency(trip.efficiency ?: 0.0))} ${unitSystem.consumptionUnit}")
        if (socSource == SocSource.PANEL) {
            appendLine("🔋 SoC (Panel): ${trip.startSocPanel.toInt()}% → ${trip.endSocPanel?.toInt() ?: 0}%")
        } else {
            appendLine("🔋 SoC (BMS): ${String.format("%.1f", trip.startSoc)}% → ${String.format("%.1f", trip.endSoc ?: 0.0)}%")
        }
        appendLine("⚡ Max Power: ${trip.maxPower.toInt()} kW")
        appendLine("🔋 Max Regen: ${abs(trip.maxRegenPower).toInt()} kW")
        appendLine("🏎️ Max Speed: ${unitSystem.convertSpeed(trip.maxSpeed).toInt()} ${unitSystem.speedUnit}")
    }

    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    val clip = android.content.ClipData.newPlainText("Trip Summary", summary)
    clipboard.setPrimaryClip(clip)

    android.widget.Toast.makeText(
        context,
        "Trip summary copied to clipboard!",
        android.widget.Toast.LENGTH_SHORT
    ).show()
}

internal fun csvEscape(value: Any?): String {
    val s = value?.toString() ?: ""
    return if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
        "\"${s.replace("\"", "\"\"")}\""
    } else s
}

internal fun jsonEscape(s: String): String = buildString {
    for (c in s) when (c) {
        '\\' -> append("\\\\")
        '"'  -> append("\\\"")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        '\b' -> append("\\b")
        '\u000C' -> append("\\f")
        else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
    }
}

internal fun TripDataPointEntity.exportValues(): List<Any> = listOf(
    timestamp, latitude, longitude, altitude,
    speed, power, soc,
    odometer, batteryTemp, totalDischarge,
    gear, isRegenerating,
    engineSpeedFront, engineSpeedRear,
    electricDrivingRangeKm,
    tyrePressureLF, tyrePressureRF, tyrePressureLR, tyrePressureRR,
    soh,
    batteryTotalVoltage, battery12vVoltage,
    batteryCellVoltageMax, batteryCellVoltageMin,
    socPanel,
    tyreTempLF, tyreTempRF, tyreTempLR, tyreTempRR,
    rawJson,
)

private val tripExportColumns = listOf(
    "timestamp", "latitude", "longitude", "altitude",
    "speed", "power", "soc",
    "odometer", "batteryTemp", "totalDischarge",
    "gear", "isRegenerating",
    "engineSpeedFront", "engineSpeedRear",
    "electricDrivingRangeKm",
    "tyrePressureLF", "tyrePressureRF", "tyrePressureLR", "tyrePressureRR",
    "soh",
    "batteryTotalVoltage", "battery12vVoltage",
    "batteryCellVoltageMax", "batteryCellVoltageMin",
    "socPanel",
    "tyreTempLF", "tyreTempRF", "tyreTempLR", "tyreTempRR",
    "rawJson",
)

fun buildTripCsv(
    dataPoints: List<TripDataPointEntity>
): String = buildString {
    appendLine(tripExportColumns.joinToString(","))
    dataPoints.forEach { point ->
        appendLine(point.exportValues().joinToString(",") { csvEscape(it) })
    }
}

fun saveTripAsCSV(
    context: android.content.Context,
    trip: TripEntity,
    dataPoints: List<TripDataPointEntity>
) {
    try {
        val fileName = "trip_${trip.id}_${System.currentTimeMillis()}.csv"
        saveToDownloads(context, fileName, "text/csv", buildTripCsv(dataPoints))
    } catch (e: Exception) {
        Log.e("TripDetailScreen", "Save CSV failed", e)
        android.widget.Toast.makeText(context, "Save failed: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
    }
}

internal fun Any?.toJsonLiteral(): String = when (this) {
    null       -> "null"
    is Boolean -> toString()
    is Number  -> toString()
    is String  -> {
        val trimmed = trim()
        if ((trimmed.startsWith("{") && trimmed.endsWith("}")) ||
            (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
            trimmed
        } else {
            "\"${jsonEscape(this)}\""
        }
    }
    else -> "\"${jsonEscape(toString())}\""
}

fun buildTripJson(
    trip: TripEntity,
    dataPoints: List<TripDataPointEntity>
): String = buildString {
    appendLine("{")
    appendLine("  \"tripId\": ${trip.id},")
    appendLine("  \"startTime\": ${trip.startTime},")
    appendLine("  \"endTime\": ${trip.endTime},")
    appendLine("  \"distance\": ${trip.distance},")
    appendLine("  \"startOdometer\": ${trip.startOdometer},")
    appendLine("  \"endOdometer\": ${trip.endOdometer},")
    appendLine("  \"duration\": ${trip.duration},")
    appendLine("  \"consumption\": ${trip.efficiency},")
    appendLine("  \"energyConsumed\": ${trip.energyConsumed},")
    appendLine("  \"maxSpeed\": ${trip.maxSpeed},")
    appendLine("  \"maxPower\": ${trip.maxPower},")
    appendLine("  \"dataPoints\": [")
    val exportable = dataPoints.filter { it.latitude != 0.0 || it.longitude != 0.0 }
    exportable.forEachIndexed { index, point ->
        val values = point.exportValues()
        appendLine("    {")
        tripExportColumns.forEachIndexed { i, col ->
            val raw = values[i]
            val literal = when {
                col == "gear" && raw is String -> "\"${jsonEscape(raw)}\""
                else -> raw.toJsonLiteral()
            }
            val sep = if (i < tripExportColumns.lastIndex) "," else ""
            appendLine("      \"$col\": $literal$sep")
        }
        appendLine("    }${if (index < exportable.size - 1) "," else ""}")
    }
    appendLine("  ]")
    appendLine("}")
}

fun saveTripAsJSON(
    context: android.content.Context,
    trip: TripEntity,
    dataPoints: List<TripDataPointEntity>
) {
    try {
        val fileName = "trip_${trip.id}_${System.currentTimeMillis()}.json"
        saveToDownloads(context, fileName, "application/json", buildTripJson(trip, dataPoints))
    } catch (e: Exception) {
        Log.e("TripDetailScreen", "Save JSON failed", e)
        android.widget.Toast.makeText(context, "Save failed: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
    }
}

/**
 * Uploads the trip's JSON export to litterbox (temporary public host) and returns the
 * public download URL. Mirrors the vehicle-compatibility-probe QR share: the head unit
 * has no mail app and no easy file transfer, so a tester can hand a full trip back to
 * the developer by scanning a QR that opens a pre-filled email carrying this link. The
 * file self-deletes after [retention] (default 72h — more slack than the probe's 24h
 * since a tester may not act immediately).
 *
 * Blocking network call — invoke from a background dispatcher. Throws on any failure so
 * the caller can surface a message.
 */
fun uploadTripJson(
    trip: TripEntity,
    dataPoints: List<TripDataPointEntity>,
    retention: String = "72h",
): String = com.byd.tripstats.util.LitterboxUploader.upload(
    fileName = "trip_${trip.id}.json",
    content = buildTripJson(trip, dataPoints),
    retention = retention,
)

fun buildTripEmbeddedHtml(
    context: android.content.Context,
    trip: TripEntity,
    dataPoints: List<TripDataPointEntity>
): String {
    val viewerTemplate = context.assets.open("trip-viewer.html")
        .bufferedReader(Charsets.UTF_8)
        .use { it.readText() }
    val tripJson = buildTripJson(trip, dataPoints)
    val safeJson = tripJson.replace("</", "<\\/")
    val embedTag = "<script>window.__embeddedTrip = $safeJson;</script>\n</head>"
    return viewerTemplate.replaceFirst("</head>", embedTag)
}

/**
 * Application-wide scope for trip-export Telegram uploads. Independent of any
 * Composable's lifecycle so dismissing the export dialog doesn't cancel the
 * upload mid-flight.
 */
private val TripExportTelegramScope: CoroutineScope =
    CoroutineScope(SupervisorJob() + Dispatchers.IO)

fun saveTripAsHtml(
    context: android.content.Context,
    trip: TripEntity,
    dataPoints: List<TripDataPointEntity>
) {
    try {
        val fileName = "trip_${trip.id}_${System.currentTimeMillis()}.html"
        saveToDownloads(context, fileName, "text/html", buildTripEmbeddedHtml(context, trip, dataPoints))
    } catch (e: Exception) {
        Log.e("TripDetailScreen", "Save HTML failed", e)
        android.widget.Toast.makeText(context, "Save failed: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
    }
}

@Suppress("UNUSED_PARAMETER")
fun sendTripExportToTelegram(
    context: android.content.Context,
    telegram: TelegramManager,
    scope: CoroutineScope,
    trip: TripEntity,
    format: String,
    content: String,
) {
    val fileName = "trip_${trip.id}_${System.currentTimeMillis()}.$format"
    android.widget.Toast.makeText(
        context, "Sending ${format.uppercase()} to Telegram…",
        android.widget.Toast.LENGTH_SHORT
    ).show()
    TripExportTelegramScope.launch(Dispatchers.IO) {
        val tempFile = java.io.File(context.cacheDir, fileName)
        try {
            tempFile.writeText(content, Charsets.UTF_8)
            telegram.sendFile(
                tempFile,
                caption = "BYD Trip Stats — trip #${trip.id} (${format.uppercase()})"
            )
            val finalState = telegram.state.value
            val msg = when (finalState) {
                is TelegramManager.TelegramState.Success -> "Sent to Telegram ✓"
                is TelegramManager.TelegramState.Error   -> "Telegram send failed: ${finalState.message}"
                else                                     -> "Telegram send finished."
            }
            launch(Dispatchers.Main) {
                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Log.e("TripDetailScreen", "Telegram trip send failed", e)
            launch(Dispatchers.Main) {
                android.widget.Toast.makeText(
                    context, "Telegram send failed: ${e.message}",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        } finally {
            tempFile.delete()
        }
    }
}

internal fun saveToDownloads(
    context: android.content.Context,
    fileName: String,
    mimeType: String,
    content: String
) {
    val values = android.content.ContentValues().apply {
        put(android.provider.MediaStore.Downloads.DISPLAY_NAME, fileName)
        put(android.provider.MediaStore.Downloads.MIME_TYPE, mimeType)
        put(android.provider.MediaStore.Downloads.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
        put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
    }

    val resolver = context.contentResolver
    val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        ?: throw Exception("Could not create file in Download")

    resolver.openOutputStream(uri)?.use { stream ->
        stream.write(content.toByteArray(Charsets.UTF_8))
    } ?: throw Exception("Could not open output stream")

    values.clear()
    values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
    resolver.update(uri, values, null, null)

    android.widget.Toast.makeText(
        context,
        "Saved to Download: $fileName",
        android.widget.Toast.LENGTH_LONG
    ).show()
}

// ── Private helpers used only within this file ────────────────────────────────

private fun formatDurationExport(milliseconds: Long): String {
    val seconds = milliseconds / 1000
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    return if (hours > 0) String.format("%dh %dmin", hours, minutes)
    else String.format("%dmin", minutes)
}

private fun formatTimestampExport(timestamp: Long): String {
    val instant = java.time.Instant.ofEpochMilli(timestamp)
    val formatter = java.time.format.DateTimeFormatter
        .ofPattern("MMM dd, yyyy HH:mm")
        .withZone(java.time.ZoneId.systemDefault())
    return formatter.format(instant)
}
