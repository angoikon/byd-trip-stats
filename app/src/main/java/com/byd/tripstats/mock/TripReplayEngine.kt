package com.byd.tripstats.mock

import com.byd.tripstats.data.model.VehicleTelemetry
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Dev-only tool: replays an exported trip JSON (the "Save as JSON" format from
 * TripExport.buildTripJson) through the app as if the car were producing the
 * telemetry live.
 *
 * Purpose: develop and regression-test telemetry-consuming features (PHEV stats,
 * range projection, trip recording) against *real recorded drives* — e.g. a JSON a
 * PHEV owner exported and shared — without access to the car. One good recorded
 * drive becomes a permanent fixture.
 *
 * The caller feeds the emitted packets into TripRepository.processTelemetry, so the
 * FULL production pipeline runs: auto trip detection, DB writes, live projection.
 * Only use on a dev device/emulator — replayed trips are recorded into the real
 * database (that is the point, but you don't want them on your car).
 *
 * Timestamps are rebased so the replayed trip *ends* roughly when the replay ends,
 * i.e. it appears in History as a just-finished trip. Packet-to-packet time deltas
 * are preserved in the telemetry timestamps (time-based logic like the 90 s
 * segment-reset threshold sees the original drive's timing) while the emission
 * pace is accelerated by [speedMultiplier].
 */
object TripReplayEngine {

    /** One replayable packet: original epoch millis + the reconstructed telemetry. */
    data class ReplayPoint(val originalMs: Long, val telemetry: VehicleTelemetry)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Parses an exported trip JSON into replayable telemetry packets.
     * Throws IllegalArgumentException on a file that isn't a trip export.
     */
    fun parse(tripJson: String): List<ReplayPoint> {
        val root = runCatching { json.parseToJsonElement(tripJson).jsonObject }
            .getOrElse { throw IllegalArgumentException("Not valid JSON: ${it.message}") }
        val dataPoints = root["dataPoints"]?.jsonArray
            ?: throw IllegalArgumentException("No dataPoints array — not a trip export")
        if (dataPoints.isEmpty()) throw IllegalArgumentException("Trip export has no data points")

        return dataPoints.mapNotNull { element ->
            val p = element.jsonObject
            val ts = p.longOrNull("timestamp") ?: return@mapNotNull null
            val telemetryJson = buildTelemetryJson(p, ts)
            val telemetry = runCatching {
                json.decodeFromJsonElement<VehicleTelemetry>(telemetryJson)
            }.getOrNull() ?: return@mapNotNull null
            ReplayPoint(ts, telemetry)
        }.sortedBy { it.originalMs }
    }

    /**
     * Emits the parsed points with rebased timestamps, pacing packet delays by
     * 1/[speedMultiplier] (capped at [maxTickDelayMs] so recording gaps don't
     * stall the replay).
     */
    fun replayFlow(
        points: List<ReplayPoint>,
        speedMultiplier: Double = 10.0,
        maxTickDelayMs: Long = 2_000L,
    ): Flow<VehicleTelemetry> = flow {
        if (points.isEmpty()) return@flow
        val firstMs = points.first().originalMs
        val durationMs = points.last().originalMs - firstMs
        val replayWallMs = (durationMs / speedMultiplier).toLong()
        // Rebase so the last packet is stamped ≈ when the replay actually finishes:
        // the trip lands in History as a just-completed drive, not a future one.
        val rebasedStartMs = System.currentTimeMillis() - durationMs + replayWallMs

        var prevMs: Long? = null
        for (point in points) {
            prevMs?.let { prev ->
                val tickDelay = ((point.originalMs - prev) / speedMultiplier).toLong()
                delay(tickDelay.coerceIn(0L, maxTickDelayMs))
            }
            prevMs = point.originalMs
            val rebasedMs = rebasedStartMs + (point.originalMs - firstMs)
            emit(
                point.telemetry.copy(
                    currentDatetime = Instant.ofEpochMilli(rebasedMs).toString()
                )
            )
        }
    }

    // ── Column → VehicleTelemetry serial-name mapping ─────────────────────────

    /**
     * Rebuilds the full VehicleTelemetry JSON for one exported data point: the
     * rawJson escape-hatch blob already uses VehicleTelemetry serial names, so it
     * seeds the object and the first-class export columns are layered on top.
     */
    private fun buildTelemetryJson(p: JsonObject, timestampMs: Long): JsonObject {
        val raw = when (val r = p["rawJson"]) {
            is JsonObject -> r
            is JsonPrimitive ->
                runCatching { json.parseToJsonElement(r.content).jsonObject }
                    .getOrElse { JsonObject(emptyMap()) }
            else -> JsonObject(emptyMap())
        }
        return buildJsonObject {
            raw.forEach { (k, v) -> put(k, v) }

            put("current_datetime", JsonPrimitive(Instant.ofEpochMilli(timestampMs).toString()))
            put("soc", JsonPrimitive(p.doubleOr("soc", 0.0)))
            put("speed", JsonPrimitive(p.doubleOr("speed", 0.0)))
            put("gear", JsonPrimitive(p.stringOr("gear", "P")))
            put("odometer", JsonPrimitive(p.doubleOr("odometer", 0.0)))
            put("total_discharge", JsonPrimitive(p.doubleOr("totalDischarge", 0.0)))
            put("engine_power", JsonPrimitive(p.doubleOr("power", 0.0).toInt()))
            put("location_latitude", JsonPrimitive(p.doubleOr("latitude", 0.0)))
            put("location_longitude", JsonPrimitive(p.doubleOr("longitude", 0.0)))
            put("location_altitude", JsonPrimitive(p.doubleOr("altitude", 0.0)))
            put("engine_speed_front", JsonPrimitive(p.intOr("engineSpeedFront", 0)))
            put("engine_speed_rear", JsonPrimitive(p.intOr("engineSpeedRear", 0)))
            put("electric_driving_range_km", JsonPrimitive(p.intOr("electricDrivingRangeKm", 0)))
            put("soh", JsonPrimitive(p.intOr("soh", 0)))
            put("battery_total_voltage", JsonPrimitive(p.intOr("batteryTotalVoltage", 0)))
            put("battery_12v_voltage", JsonPrimitive(p.doubleOr("battery12vVoltage", 0.0)))
            put("battery_cell_voltage_max", JsonPrimitive(p.doubleOr("batteryCellVoltageMax", 0.0)))
            put("battery_cell_voltage_min", JsonPrimitive(p.doubleOr("batteryCellVoltageMin", 0.0)))
            put("soc_panel", JsonPrimitive(p.intOr("socPanel", 0)))
            put("tyre_pressure_left_front_psi", JsonPrimitive(p.doubleOr("tyrePressureLF", 0.0)))
            put("tyre_pressure_right_front_psi", JsonPrimitive(p.doubleOr("tyrePressureRF", 0.0)))
            put("tyre_pressure_left_rear_psi", JsonPrimitive(p.doubleOr("tyrePressureLR", 0.0)))
            put("tyre_pressure_right_rear_psi", JsonPrimitive(p.doubleOr("tyrePressureRR", 0.0)))
            put("tyre_temperature_left_front_c", JsonPrimitive(p.intOr("tyreTempLF", 0)))
            put("tyre_temperature_right_front_c", JsonPrimitive(p.intOr("tyreTempRF", 0)))
            put("tyre_temperature_left_rear_c", JsonPrimitive(p.intOr("tyreTempLR", 0)))
            put("tyre_temperature_right_rear_c", JsonPrimitive(p.intOr("tyreTempRR", 0)))
            // The per-point batteryTemp column is the displayed average; feeding it
            // to both cell extremes reproduces batteryTempAvg on the model.
            p.doubleOrNull("batteryTemp")?.let { avg ->
                put("battery_cell_temp_max", JsonPrimitive(avg.toInt()))
                put("battery_cell_temp_min", JsonPrimitive(avg.toInt()))
            }
            // car_on isn't exported per point; a recorded trip point is by
            // definition from a powered car unless the raw blob says otherwise.
            if (!raw.containsKey("car_on")) put("car_on", JsonPrimitive(2))
        }
    }

    /** The value as a primitive, treating a JSON `null` literal as absent. */
    private fun JsonObject.prim(key: String): JsonPrimitive? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString || it.content != "null" }

    private fun JsonObject.longOrNull(key: String): Long? = prim(key)?.content?.toLongOrNull()
    private fun JsonObject.doubleOrNull(key: String): Double? = prim(key)?.content?.toDoubleOrNull()
    private fun JsonObject.doubleOr(key: String, def: Double): Double = doubleOrNull(key) ?: def
    private fun JsonObject.intOr(key: String, def: Int): Int =
        prim(key)?.content?.toDoubleOrNull()?.toInt() ?: def
    private fun JsonObject.stringOr(key: String, def: String): String =
        prim(key)?.takeIf { it.isString || it.content.isNotBlank() }?.content ?: def
}
