package com.byd.tripstats.data.repository

import android.content.Context
import android.util.Log
import com.byd.tripstats.data.model.BatteryVoltageHistoryPoint
import com.byd.tripstats.data.model.VehicleTelemetry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class BatteryVoltageHistoryRepository private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val historyFile = File(appContext.filesDir, HISTORY_FILE_NAME)
    private val json = Json { ignoreUnknownKeys = true }
    private val _history = MutableStateFlow(loadHistory())
    val history: StateFlow<List<BatteryVoltageHistoryPoint>> = _history.asStateFlow()

    fun onTelemetry(telemetry: VehicleTelemetry) {
        val voltage12v = telemetry.battery12vVoltage
        if (voltage12v <= 0.0) return

        val point = BatteryVoltageHistoryPoint(
            timestamp = System.currentTimeMillis(),
            battery12vVoltage = voltage12v,
            batteryTotalVoltage = telemetry.batteryTotalVoltage.coerceAtLeast(0),
            isChargingSample = telemetry.isCharging,
            soc = telemetry.soc,
            socPanel = telemetry.socPanel
        )
        val current = _history.value
        val last = current.lastOrNull()
        val shouldAppend = when {
            last == null -> true
            point.timestamp - last.timestamp >= MIN_SAMPLE_INTERVAL_MS -> true
            kotlin.math.abs(point.battery12vVoltage - last.battery12vVoltage) >= MIN_VOLTAGE_DELTA_V -> true
            point.isChargingSample != last.isChargingSample -> true
            else -> false
        }
        if (!shouldAppend) return

        val trimmed = (current + point)
            .filter { it.timestamp >= point.timestamp - HISTORY_WINDOW_MS }
            .sortedBy { it.timestamp }
        _history.value = trimmed
        persist(trimmed)
    }

    private fun loadHistory(): List<BatteryVoltageHistoryPoint> {
        if (!historyFile.exists()) return emptyList()
        return runCatching {
            json.decodeFromString<List<BatteryVoltageHistoryPoint>>(historyFile.readText())
                .filter { it.timestamp >= System.currentTimeMillis() - HISTORY_WINDOW_MS }
                .sortedBy { it.timestamp }
        }.onFailure {
            Log.w(TAG, "Failed to load battery voltage history", it)
        }.getOrDefault(emptyList())
    }

    private fun persist(points: List<BatteryVoltageHistoryPoint>) {
        runCatching {
            historyFile.writeText(json.encodeToString(points))
        }.onFailure {
            Log.w(TAG, "Failed to persist battery voltage history", it)
        }
    }

    companion object {
        private const val TAG = "BatteryVoltageHistory"
        private const val HISTORY_FILE_NAME = "battery_voltage_history.json"
        const val HISTORY_WINDOW_MS = 48L * 60L * 60L * 1000L
        private const val MIN_SAMPLE_INTERVAL_MS = 60_000L
        private const val MIN_VOLTAGE_DELTA_V = 0.02

        @Volatile
        private var INSTANCE: BatteryVoltageHistoryRepository? = null

        fun getInstance(context: Context): BatteryVoltageHistoryRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: BatteryVoltageHistoryRepository(context).also { INSTANCE = it }
            }
        }
    }
}
