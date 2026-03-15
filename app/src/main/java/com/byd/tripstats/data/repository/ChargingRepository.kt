package com.byd.tripstats.data.repository

import android.content.Context
import android.util.Log
import com.byd.tripstats.data.config.CarConfig
import com.byd.tripstats.data.local.BydStatsDatabase
import com.byd.tripstats.data.local.entity.ChargingDataPointEntity
import com.byd.tripstats.data.local.entity.ChargingSessionEntity
import com.byd.tripstats.data.model.VehicleTelemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Manages charging session detection, recording, and persistence.
 *
 * Detection logic mirrors TripRepository:
 *   - isCharging flips true  → open a new session
 *   - isCharging flips false → close the active session (with a debounce
 *     to survive brief telemetry gaps without prematurely ending a session)
 *
 * Call [onTelemetry] from DashboardViewModel for every incoming MQTT packet.
 * All DB writes run on the IO dispatcher; the caller never needs to worry
 * about threading.
 */
class ChargingRepository private constructor(context: Context) {

    private val TAG = "ChargingRepository"

    private val database   = BydStatsDatabase.getDatabase(context)
    private val sessionDao = database.chargingSessionDao()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── Public state ──────────────────────────────────────────────────────────

    private val _isCharging = MutableStateFlow(false)
    val isCharging: StateFlow<Boolean> = _isCharging.asStateFlow()

    private val _activeSessionId = MutableStateFlow<Long?>(null)
    val activeSessionId: StateFlow<Long?> = _activeSessionId.asStateFlow()

    // ── Internal state ────────────────────────────────────────────────────────

    /**
     * Job-based debounce timer — fires automatically, no incoming packet required.
     *
     * Two timeouts depending on whether the car is on or off:
     *
     *   DC fast charging (car_on = 1, Electro publishes every 1 s):
     *     60 s — closes the session roughly one minute after unplugging.
     *
     *   AC home charging (car_on = 0, Electro publishes every 5–10 min):
     *     3 min — safely beyond the 1-min maximum publish interval, so a brief
     *     gap between packets never prematurely closes an ongoing overnight session.
     *     The session closes automatically once the charger is disconnected and the
     *     next slow packet (with chargingPower = 0) triggers a timer that fires
     *     12 minutes later.
     *
     * The timer is cancelled and restarted on every incoming charging packet,
     * so it only fires if the car genuinely stops sending charging data.
     */
    private val DC_DEBOUNCE_MS  = 60_000L          // 1 min  — car on
    private val AC_DEBOUNCE_MS  = 3 * 60_000L     // 3 min — car off

    private var closeSessionJob: Job? = null

    /**
     * The last telemetry packet where chargingPower > 0.
     * Used to anchor endTime to the actual last known charging moment
     * rather than the debounce fire time, which can be up to one publish
     * interval + debounce window later.
     */
    private var lastChargingTelemetry: VehicleTelemetry? = null

    private var activeSession: ChargingSessionEntity? = null
    private val pendingDataPoints = mutableListOf<ChargingDataPointEntity>()

    // ── Public API ────────────────────────────────────────────────────────────

    fun getAllSessions(): Flow<List<ChargingSessionEntity>> =
        sessionDao.getAllSessions()

    fun getDataPointsForSession(sessionId: Long): Flow<List<ChargingDataPointEntity>> =
        sessionDao.getDataPointsForSession(sessionId)

    suspend fun getSessionById(sessionId: Long): ChargingSessionEntity? =
        sessionDao.getSessionById(sessionId)

    suspend fun getDataPointsForSessionSync(sessionId: Long): List<ChargingDataPointEntity> =
        sessionDao.getDataPointsForSessionSync(sessionId)

    /**
     * Feed each incoming telemetry packet here.
     * [carConfig] must be the currently selected car — used to compute kwhAdded on close.
     */
    fun onTelemetry(telemetry: VehicleTelemetry, carConfig: CarConfig?) {
        scope.launch {
            if (telemetry.isCharging) {
                // Cancel any pending close — we are definitely still charging
                closeSessionJob?.cancel()
                closeSessionJob = null

                // Track the last packet where we confirmed charging was active.
                // Used to set endTime accurately regardless of when the debounce fires.
                lastChargingTelemetry = telemetry

                if (activeSession == null) {
                    startSession(telemetry, carConfig)
                } else {
                    recordDataPoint(telemetry)
                }

                // Schedule automatic close in case packets stop arriving
                // (e.g. car goes to sleep mid-charge, Electro crashes, phone loses
                // connection). The job is cancelled and rescheduled on every charging
                // packet so it only fires if no charging packet arrives within the window.
                //
                // Distinguish DC vs AC by chargingPower magnitude — not car_on, which
                // is 0 whenever the car is in accessory/sleep mode during charging:
                //   ≥ 20 kW → DC fast charge → 60 s debounce (car is nearby, quick closure)
                //   <  20 kW → AC home charge → 12 min debounce (safe beyond 10-min interval)
                val debounceMs = if (telemetry.chargingPower >= 20.0) DC_DEBOUNCE_MS else AC_DEBOUNCE_MS
                closeSessionJob = scope.launch {
                    delay(debounceMs)
                    Log.i(TAG, "No charging packet for ${debounceMs / 1000}s — auto-closing session")
                    closeSession(lastChargingTelemetry ?: telemetry, carConfig)
                }
            } else {
                if (activeSession != null) {
                    // Non-charging packet — start/reset debounce timer.
                    // Same DC/AC distinction applies: if the last known charging power
                    // was DC, use the short timeout; if AC, use the long one.
                    closeSessionJob?.cancel()
                    val lastKw = lastChargingTelemetry?.chargingPower ?: 0.0
                    val debounceMs = if (lastKw >= 20.0) DC_DEBOUNCE_MS else AC_DEBOUNCE_MS
                    closeSessionJob = scope.launch {
                        delay(debounceMs)
                        Log.i(TAG, "Charging stopped confirmed after ${debounceMs / 1000}s — closing session")
                        closeSession(lastChargingTelemetry ?: telemetry, carConfig)
                    }
                }
            }
        }
    }

    // ── Session lifecycle ─────────────────────────────────────────────────────

    private suspend fun startSession(telemetry: VehicleTelemetry, carConfig: CarConfig?) {
        Log.i(TAG, "Charging session started — SoC: ${telemetry.soc}%")

        val session = ChargingSessionEntity(
            startTime       = System.currentTimeMillis(),
            socStart        = telemetry.soc,
            batteryTempStart = telemetry.batteryTempAvg,
            voltageStart    = telemetry.batteryTotalVoltage,
            batteryKwh      = carConfig?.batteryKwh ?: 0.0,
            carConfigId     = carConfig?.id ?: "",
            isActive        = true
        )

        val id = sessionDao.insertSession(session)
        activeSession = session.copy(id = id)
        _activeSessionId.value = id
        _isCharging.value = true

        pendingDataPoints.clear()
        recordDataPoint(telemetry)
    }

    private suspend fun recordDataPoint(telemetry: VehicleTelemetry) {
        val session = activeSession ?: return

        val point = ChargingDataPointEntity(
            sessionId            = session.id,
            timestamp            = System.currentTimeMillis(),
            soc                  = telemetry.soc,
            socPanel             = telemetry.socPanel,
            chargingPower        = telemetry.chargingPower,
            batteryTotalVoltage  = telemetry.batteryTotalVoltage,
            battery12vVoltage    = telemetry.battery12vVoltage,
            batteryTempAvg       = telemetry.batteryTempAvg,
            batteryCellTempMin   = telemetry.batteryCellTempMin,
            batteryCellTempMax   = telemetry.batteryCellTempMax,
            batteryCellVoltageMin = telemetry.batteryCellVoltageMin,
            batteryCellVoltageMax = telemetry.batteryCellVoltageMax
        )

        sessionDao.insertDataPoint(point)
        pendingDataPoints.add(point)

        // Keep peak kW up to date on the session row
        if (telemetry.chargingPower > (activeSession?.peakKw ?: 0.0)) {
            activeSession = activeSession!!.copy(peakKw = telemetry.chargingPower)
            sessionDao.updateSession(activeSession!!)
        }
    }

    private suspend fun closeSession(telemetry: VehicleTelemetry, carConfig: CarConfig?) {
        val session = activeSession ?: return

        // Use lastChargingTelemetry as the source of truth for all closing metrics.
        // This anchors endTime, socEnd, and temperature to the last confirmed charging
        // packet rather than the debounce fire time or the first non-charging packet,
        // either of which can be minutes later than actual charging stopped.
        val last = lastChargingTelemetry ?: telemetry

        val endMs = runCatching {
            java.time.Instant.parse(last.currentDatetime).toEpochMilli()
        }.getOrDefault(System.currentTimeMillis())

        Log.i(TAG, "Charging session closed — SoC: ${session.socStart}% → ${last.soc}%  endTime anchored to last charging packet")

        val dataPoints = sessionDao.getDataPointsForSessionSync(session.id)
        val avgKw = if (dataPoints.isNotEmpty())
            dataPoints.map { it.chargingPower }.average()
        else 0.0

        val batteryKwh = carConfig?.batteryKwh ?: session.batteryKwh
        val socDelta   = (last.soc - session.socStart).coerceAtLeast(0.0)
        val kwhAdded   = (socDelta / 100.0) * batteryKwh

        val closed = session.copy(
            endTime        = endMs,
            socEnd         = last.soc,
            kwhAdded       = kwhAdded,
            avgKw          = avgKw,
            batteryTempEnd = last.batteryTempAvg,
            voltageEnd     = last.batteryTotalVoltage,
            isActive       = false
        )

        sessionDao.updateSession(closed)
        activeSession = null
        lastChargingTelemetry = null
        closeSessionJob?.cancel()
        closeSessionJob = null
        pendingDataPoints.clear()
        _activeSessionId.value = null
        _isCharging.value = false
    }

    // ── Singleton ─────────────────────────────────────────────────────────────

    companion object {
        @Volatile private var INSTANCE: ChargingRepository? = null

        fun getInstance(context: Context): ChargingRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: ChargingRepository(context.applicationContext).also { INSTANCE = it }
            }
    }
}