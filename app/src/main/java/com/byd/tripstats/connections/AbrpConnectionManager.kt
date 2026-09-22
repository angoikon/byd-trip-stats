package com.byd.tripstats.connections

import android.content.Context
import android.net.Uri
import com.byd.tripstats.data.config.CarConfig
import com.byd.tripstats.data.model.VehicleTelemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import android.util.Log
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

class AbrpConnectionManager(context: Context) {
    private val appContext = context.applicationContext
    private val inFlight = AtomicBoolean(false)
    @Volatile private var lastUploadAtMs: Long = 0L

    fun onTelemetry(
        telemetry: VehicleTelemetry,
        carConfig: CarConfig?,
        scope: CoroutineScope
    ) {
        val config = AbrpConnectionStore.load(appContext)
        if (!config.enabled || config.userToken.isBlank()) return
        // Skip uploads while the car is turned off and not charging — ABRP only cares about
        // active driving/charging telemetry, and the foreground service stays alive 24/7
        // (keepServiceAliveWhenOff defaults to true), so without this gate the loop
        // would burn ~140 MB/day on idle uploads (a fresh TLS handshake per request
        // dominates the byte count) even when nothing about the car is changing.
        if (!telemetry.isCarOn && !telemetry.isCharging) return
        val intervalMs = config.uploadIntervalSeconds.coerceIn(5, 120) * 1000L
        val now = System.currentTimeMillis()
        if (lastUploadAtMs > 0L && now - lastUploadAtMs < intervalMs) return
        if (!inFlight.compareAndSet(false, true)) return

        scope.launch(Dispatchers.IO) {
            try {
                val payload = buildPayload(telemetry, carConfig)
                val ok = upload(payload, config)
                val status = if (ok) "ABRP upload ok" else "ABRP upload failed"
                lastUploadAtMs = now
                AbrpConnectionStore.updateStatus(appContext, status, if (ok) now else 0L)
            } catch (e: Exception) {
                Log.w("AbrpConnectionManager", "upload error: ${e.message}")
            } finally {
                inFlight.set(false)
            }
        }
    }

    fun testUpload(
        telemetry: VehicleTelemetry,
        carConfig: CarConfig?
    ): Pair<Boolean, String> {
        val config = AbrpConnectionStore.load(appContext)
        if (!config.enabled || config.userToken.isBlank()) {
            return false to "ABRP is not configured"
        }
        val payload = buildPayload(telemetry, carConfig)
        val ok = upload(payload, config)
        val status = if (ok) "ABRP test upload ok" else "ABRP test upload failed"
        AbrpConnectionStore.updateStatus(appContext, status, if (ok) System.currentTimeMillis() else 0L)
        return ok to status
    }

    fun testUpload(
        telemetry: VehicleTelemetry,
        carConfig: CarConfig?,
        configOverride: AbrpConnectionConfig
    ): Pair<Boolean, String> {
        if (!configOverride.enabled || configOverride.userToken.isBlank()) {
            return false to "ABRP is not configured"
        }
        val payload = buildPayload(telemetry, carConfig)
        val ok = upload(payload, configOverride)
        val status = if (ok) "ABRP test upload ok" else "ABRP test upload failed"
        AbrpConnectionStore.save(
            appContext,
            configOverride.copy(
                lastStatus = status,
                lastUploadAtMs = if (ok) System.currentTimeMillis() else 0L
            )
        )
        return ok to status
    }

    private fun buildPayload(telemetry: VehicleTelemetry, carConfig: CarConfig?): JSONObject {
        val payload = JSONObject()
        val utcSeconds = System.currentTimeMillis() / 1000
        payload.put("utc", utcSeconds)
        payload.put("soc", telemetry.soc)
        payload.put("power", when {
            telemetry.isCharging && telemetry.chargingPower > 0.0 -> -telemetry.chargingPower
            telemetry.enginePower != 0 -> telemetry.enginePower.toDouble()
            else -> 0.0
        })
        payload.put("speed", telemetry.locationGpsSpeed?.takeIf { it > 0.1 } ?: telemetry.speed)
        telemetry.locationLatitude.takeIf { it != 0.0 }?.let { payload.put("lat", it) }
        telemetry.locationLongitude.takeIf { it != 0.0 }?.let { payload.put("lon", it) }
        telemetry.locationAltitude.takeIf { it > 0.0 }?.let { payload.put("elevation", it) }
        telemetry.locationOrientation?.takeIf { it.isFinite() }?.let { payload.put("heading", it) }
        telemetry.instrumentOutCarTemperature?.let { payload.put("ext_temp", it) }
        // HV pack voltage + signed current (positive = discharge, negative = regen/charge) — matches
        // ABRP's convention. Available on DiLink-5 via the collectdata events.
        telemetry.batteryTotalVoltage.takeIf { it > 0 }?.let { payload.put("voltage", it) }
        telemetry.batteryTotalCurrent?.let { payload.put("current", it) }
        payload.put("is_charging", if (telemetry.isCharging) 1 else 0)
        payload.put("is_parked", if (telemetry.gear == "P") 1 else 0)
        payload.put("odometer", telemetry.odometer)
        // Car's OEM estimated remaining range (statisticElecDrivingRangeValue, confirmed accurate
        // vs the cluster). Without this ABRP keeps showing the last range from another source
        // (e.g. a removed Enode link), so send it whenever valid.
        telemetry.electricDrivingRangeKm.takeIf { it > 0 }?.let { payload.put("est_battery_range", it) }
        telemetry.soh.takeIf { it > 0 }?.let { payload.put("soh", it) }
        val capacity = carConfig?.batteryKwh?.takeIf { it > 0.0 }
            ?: run {
                val soc = telemetry.soc.takeIf { it > 0.0 } ?: 0.0
                val remain = telemetry.batteryRemainPowerEV?.takeIf { it > 0.0 } ?: 0.0
                if (soc > 0.0 && remain > 0.0) remain / (soc / 100.0) else null
            }
        capacity?.let { payload.put("capacity", it) }
        return payload
    }

    private fun upload(payload: JSONObject, config: AbrpConnectionConfig): Boolean {
        val url = Uri.parse("https://api.iternio.com/1/tlm/send").buildUpon()
            .appendQueryParameter("token", config.userToken.trim())
            .appendQueryParameter("api_key", config.apiKey.trim().ifBlank { AbrpConnectionStore.DEFAULT_PUBLIC_API_KEY })
            .build().toString()
        val body = "tlm=" + URLEncoder.encode(payload.toString(), "UTF-8")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 10_000
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
        }
        // Note: we deliberately do NOT call conn.disconnect() on the success path.
        // HttpURLConnection's connection pool keeps the underlying TCP/TLS socket
        // alive for reuse once the response stream has been fully drained, which
        // is what we do below (~5–8 KB TLS handshake saved per request). disconnect()
        // forces the socket closed and disables that reuse. We only force-close on
        // exception paths where the connection is in an unknown state.
        return try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val responseCode = conn.responseCode
            val stream = if (responseCode in 200..299) conn.inputStream else conn.errorStream
            stream?.use { input ->
                BufferedReader(InputStreamReader(input)).use { reader -> reader.readText() }
            }
            responseCode in 200..299
        } catch (e: IOException) {
            Log.w("AbrpConnectionManager", "upload failed (network): ${e.message}")
            runCatching { conn.disconnect() }
            false
        }
    }
}