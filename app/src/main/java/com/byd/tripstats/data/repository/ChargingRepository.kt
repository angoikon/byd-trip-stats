package com.byd.tripstats.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.room.withTransaction
import com.byd.tripstats.data.config.CarCatalog
import com.byd.tripstats.data.config.CarConfig
import com.byd.tripstats.data.local.BydStatsDatabase
import com.byd.tripstats.data.local.entity.ChargingDataPointEntity
import com.byd.tripstats.data.local.entity.ChargingSessionEntity
import com.byd.tripstats.data.model.VehicleTelemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Hybrid charging session management:
 *
 * ── Car ON / awake / ready (carOn > 0) ─────────────────────────────────────
 *   First packet after service start: SoC-delta reconstruction check.
 *   If SoC increased since last shutdown → synthetic session created for the
 *   car-off charging period (overnight, remote charging, etc.).
 *
 *   chargingPower > 0 → open / continue a real-time session, record data points.
 *   chargingPower = 0 → close active session immediately (no debounce needed —
 *   if the car is on and power dropped to 0, charging definitively stopped).
 *
 * ── Car OFF (carOn = 0) ────────────────────────────────────────────────────
 *   Close any active real-time session immediately.
 *   Continue persisting SoC + timestamp so the next wake-up can reconstruct.
 *
 * This means live charts are available for sessions recorded while the car is on,
 * and synthetic sessions (peakKw = avgKw = 0, no data points) represent car-off
 * charging reconstructed from the SoC delta on wake-up.
 */
class ChargingRepository private constructor(context: Context) {

    private val TAG = "ChargingRepository"

    private val database   = BydStatsDatabase.getDatabase(context)
    private val sessionDao = database.chargingSessionDao()
    private val scope      = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val prefs: SharedPreferences =
        context.getSharedPreferences("charging_shutdown_state", Context.MODE_PRIVATE)

    // ── Runtime state ─────────────────────────────────────────────────────────

    /** True only during a live car-on charging session. */
    private val _isCharging = MutableStateFlow(false)
    val isCharging: StateFlow<Boolean> = _isCharging.asStateFlow()

    private val _activeSessionId = MutableStateFlow<Long?>(null)
    val activeSessionId: StateFlow<Long?> = _activeSessionId.asStateFlow()

    private var activeSession: ChargingSessionEntity? = null
    private var lastChargingSeenAtMs: Long = 0L
    // Post-charge settle top-up: after a near-full off-state session closes, the BMS keeps
    // recomputing SoC upward for a few minutes (e.g. 98.5% → 100%). While settleUntilMs is
    // in the future, applyPostChargeSettle() revises the already-closed session's end SoC.
    private var settleSessionId: Long? = null
    private var settleBaselineSoc: Double = 0.0
    private var settleUntilMs: Long = 0L

    /** Prevents reconstruction from running more than once per car wake-up cycle. */
    private var reconstructionAttempted = false
    private var activeSessionRecoveryAttempted = false
    private var wasCarOn = false

    private data class ShutdownState(
        val soc: Double,
        val timestamp: Long,
        val tempAvg: Double,
        val voltage: Int,
        val socPanel: Int = 0,
    )

    // ── Public API ────────────────────────────────────────────────────────────

    fun getAllSessions(): Flow<List<ChargingSessionEntity>> =
        sessionDao.getAllSessions()

    fun getDataPointsForSession(sessionId: Long): Flow<List<ChargingDataPointEntity>> =
        sessionDao.getDataPointsForSession(sessionId)

    suspend fun getSessionById(sessionId: Long): ChargingSessionEntity? =
        sessionDao.getSessionById(sessionId)

    suspend fun getDataPointsForSessionSync(sessionId: Long): List<ChargingDataPointEntity> =
        sessionDao.getDataPointsForSessionSync(sessionId)

    fun onTelemetry(telemetry: VehicleTelemetry, carConfig: CarConfig?) {
        // Detect off→on transition. When the service survives across a car sleep/wake
        // cycle (keepServiceAliveWhenOff=true), reconstructionAttempted stays true from
        // the previous wake-up. Reset it here so every car wake-up gets one attempt,
        // which is required to catch off-state charging that happened while the service
        // was continuously alive.
        if (telemetry.isCarOn && !wasCarOn && reconstructionAttempted) {
            Log.i(TAG, "Car transitioned off→on — resetting reconstruction guard for new wake-up")
            reconstructionAttempted = false
        }
        wasCarOn = telemetry.isCarOn

        val previousShutdownState =
            if (telemetry.isCarOn && !reconstructionAttempted) readShutdownState() else null

        // Persist only while the car is on. Off-state packets would overwrite the
        // pre-sleep baseline as SoC climbs during an off-state charge, making the
        // subsequent wake-up delta zero and dropping the reconstructed session.
        //
        // On the very first car-on packet of each wake cycle (reconstructionAttempted = false),
        // we defer persisting until AFTER the reconstruction coroutine runs. Persisting
        // immediately would overwrite the pre-charge SoC with the post-charge value if the
        // process is killed mid-reconstruction (e.g. an app update installed while the car is
        // waking up), making the next start's SoC delta zero and silently dropping the session.
        // Subsequent packets (reconstructionAttempted = true) persist synchronously as before.
        if (telemetry.isCarOn && reconstructionAttempted) {
            persistShutdownState(telemetry)
        }

        scope.launch {
            if (!activeSessionRecoveryAttempted) {
                activeSessionRecoveryAttempted = true
                recoverOrphanActiveSessions(telemetry, carConfig)
            }

            if (telemetry.isCarOn) {
                // First carOn packet after service start → attempt reconstruction
                // only if no data points were recorded while car was off
                // (process survived → real-time data exists → no reconstruction needed).
                if (!reconstructionAttempted) {
                    reconstructionAttempted = true
                    val hasLiveData = activeSession?.id?.let {
                        sessionDao.getDataPointsForSessionSync(it).isNotEmpty()
                    } ?: false
                    if (!hasLiveData) {
                        tryReconstructChargingSession(previousShutdownState, telemetry, carConfig)
                    } else {
                        Log.i(TAG, "Live car-off charging data exists — skipping reconstruction")
                    }
                    // Persist after reconstruction so a mid-reconstruction process restart
                    // (e.g. app update) can still reconstruct from the original pre-charge SoC.
                    persistShutdownState(telemetry)
                }
                handleRealTimeCharging(telemetry, carConfig)
            } else if (telemetry.isCharging) {
                // Car is off but actively charging — process is alive (survived ACC_OFF).
                // Record data points in real-time exactly like car-on charging.
                // Do NOT close the session — it will be closed when charging stops.
                handleRealTimeCharging(telemetry, carConfig)
            } else {
                // Car is off and not charging — close any open session, then keep revising
                // a just-closed near-full session's end SoC while the BMS settles to 100%.
                closeActiveSessionIfAny(carConfig, telemetry)
                applyPostChargeSettle(telemetry)
            }
        }
    }

    private suspend fun recoverOrphanActiveSessions(
        telemetry: VehicleTelemetry,
        carConfig: CarConfig?
    ) {
        val activeSessions = sessionDao.getAllActiveSessions()
        if (activeSessions.isEmpty()) return

        val latestActive = activeSessions.maxByOrNull { it.startTime }
        val now = System.currentTimeMillis()
        // 15 min — must exceed OffStateKeepaliveReceiver.INTERVAL_MS (4 min) plus
        // the UID-2000 daemon's 60s revival cadence by a healthy margin so that
        // a missed wake-up doesn't fragment a single overnight charge into
        // multiple rows. 15 is also short enough that a genuine plug/unplug/
        // replug cycle with meaningful downtime between still produces separate
        // sessions.
        val resumeWindowMs = 15 * 60 * 1000L

        activeSessions.forEach { session ->
            val points = sessionDao.getDataPointsForSessionSync(session.id)
            val lastPoint = points.lastOrNull()
            val shouldResume =
                telemetry.isCharging &&
                session.id == latestActive?.id &&
                lastPoint != null &&
                now - lastPoint.timestamp <= resumeWindowMs

            if (shouldResume) {
                val positivePowers = points.map { it.chargingPower }.filter { it > 0.1 }
                activeSession = session.copy(
                    socEnd = requireNotNull(lastPoint).soc,
                    peakKw = maxOf(session.peakKw, positivePowers.maxOrNull() ?: session.peakKw)
                )
                lastChargingSeenAtMs = now
                _activeSessionId.value = session.id
                _isCharging.value = true
                Log.i(TAG, "Resumed active charging session ${session.id}")
            } else {
                closePersistedActiveSession(session, points, carConfig)
            }
        }
    }

    // ── Real-time session management (car ON only) ────────────────────────────

    private suspend fun handleRealTimeCharging(
        telemetry : VehicleTelemetry,
        carConfig : CarConfig?
    ) {
        if (telemetry.isCharging) {
            lastChargingSeenAtMs = System.currentTimeMillis()
            settleSessionId = null; settleUntilMs = 0L   // active charging — cancel any pending settle top-up
            if (activeSession == null) {
                startSession(telemetry, carConfig)
            } else {
                recordDataPoint(telemetry)
            }
        } else {
            // Car is on but the incoming packet says charging has stopped.
            // Integration tests and downstream logic expect this to close
            // immediately rather than waiting for a grace window.
            closeActiveSessionIfAny(carConfig, telemetry, force = true)
        }
    }

    private suspend fun startSession(telemetry: VehicleTelemetry, carConfig: CarConfig?) {
        Log.i(TAG, "Real-time charging session started — SoC: ${telemetry.soc}%")
        val session = ChargingSessionEntity(
            startTime        = System.currentTimeMillis(),
            socStart         = telemetry.soc,
            socStartPanel    = telemetry.socPanel.toDouble(),
            batteryTempStart = telemetry.batteryTempAvg,
            voltageStart     = telemetry.batteryTotalVoltage,
            batteryKwh       = carConfig?.batteryKwh ?: FALLBACK_BATTERY_KWH,
            carConfigId      = carConfig?.id ?: "",
            isActive         = true,
            // Odometer at plug-in — anchors "distance since the previous charge".
            // The car is stationary while charging, so this single reading is the
            // charge's odometer. Guarded to null when the getter reads 0 (offline).
            startOdometer    = telemetry.odometer.takeIf { it > 0.0 }
        )
        val id = sessionDao.insertSession(session)
        activeSession = session.copy(id = id)
        lastChargingSeenAtMs = System.currentTimeMillis()
        recordDataPoint(telemetry)
        // Signal observers only after the first data point is committed,
        // so any close triggered immediately after open sees non-empty data.
        _activeSessionId.value = id
        _isCharging.value = true
    }

    private suspend fun recordDataPoint(telemetry: VehicleTelemetry) {
        val session = activeSession ?: return
        val point = ChargingDataPointEntity(
            sessionId             = session.id,
            timestamp             = System.currentTimeMillis(),
            soc                   = telemetry.soc,
            socPanel              = telemetry.socPanel,
            chargingPower         = telemetry.chargingPower,
            batteryTotalVoltage   = telemetry.batteryTotalVoltage,
            battery12vVoltage     = telemetry.battery12vVoltage,
            batteryTempAvg        = telemetry.batteryTempAvg,
            batteryCellTempMin    = telemetry.batteryCellTempMin,
            batteryCellTempMax    = telemetry.batteryCellTempMax,
            batteryCellVoltageMin = telemetry.batteryCellVoltageMin,
            batteryCellVoltageMax = telemetry.batteryCellVoltageMax,
            rawJson               = telemetry.toSchemaJson(
                isPhev = CarCatalog.fromId(activeSession?.carConfigId)?.isPhev ?: false
            )
        )
        sessionDao.insertDataPoint(point)

        // Keep peak kW and real-time SoC up to date on the session row
        val needsPeakUpdate = telemetry.chargingPower > (activeSession?.peakKw ?: 0.0)
        val needsSocUpdate  = telemetry.soc != activeSession?.socEnd
        if (needsPeakUpdate || needsSocUpdate) {
            val updated = activeSession!!.copy(
                peakKw      = if (needsPeakUpdate) telemetry.chargingPower else activeSession!!.peakKw,
                socEnd      = telemetry.soc,
                socEndPanel = telemetry.socPanel.toDouble()
            )
            activeSession = updated
            sessionDao.updateSession(updated)
        }
    }

    private suspend fun closeActiveSessionIfAny(
        carConfig : CarConfig?,
        telemetry : VehicleTelemetry,
        force: Boolean = true
    ) {
        val session = activeSession ?: return
        val now = System.currentTimeMillis()
        if (!force && now - lastChargingSeenAtMs < ACTIVE_SESSION_CLOSE_GRACE_MS) {
            _isCharging.value = true
            return
        }
        val dataPoints = sessionDao.getDataPointsForSessionSync(session.id)
        closePersistedActiveSession(session, dataPoints, carConfig)

        // Arm post-charge settle top-up for a near-full off-state charge: the session is
        // already closed (activeSessionId clears immediately, as callers expect), but the
        // BMS keeps recomputing SoC upward for a few minutes after current stops
        // (e.g. 98.5% → 100%). applyPostChargeSettle() revises the closed row's end SoC
        // during this window. Bounded + capped so it can never absorb a drive or a
        // separate charge. Car-off only (off-state charging completion).
        val closedEndSoc = maxOf(dataPoints.lastOrNull()?.soc ?: 0.0, session.socEnd ?: 0.0)
        if (!telemetry.isCarOn && dataPoints.isNotEmpty() && closedEndSoc >= SETTLE_NEAR_FULL_PCT) {
            settleSessionId   = session.id
            settleBaselineSoc = closedEndSoc
            settleUntilMs     = now + SETTLE_GRACE_MS
        } else {
            settleSessionId = null
            settleUntilMs   = 0L
        }

        activeSession = null
        lastChargingSeenAtMs = 0L
        _activeSessionId.value = null
        _isCharging.value = false
    }

    /**
     * After a near-full off-state session closes, the BMS keeps recomputing SoC upward for
     * a few minutes (the CV/settle phase, e.g. 98.5% → 100%). While within the settle
     * window and the car stays parked, revise the closed session's end SoC (and energy)
     * to the higher value. Capped at [SETTLE_MAX_RISE_PCT] and gated so it can never absorb
     * a drive or a separate charge.
     */
    private suspend fun applyPostChargeSettle(telemetry: VehicleTelemetry) {
        val sid = settleSessionId ?: return
        val now = System.currentTimeMillis()
        val carMoving = telemetry.speed > 1.0 || telemetry.gear in setOf("D", "R")
        if (now >= settleUntilMs || telemetry.isCharging || telemetry.isCarOn || carMoving) {
            settleSessionId = null
            settleUntilMs   = 0L
            return
        }
        val cur = telemetry.soc
        if (cur <= settleBaselineSoc || cur - settleBaselineSoc > SETTLE_MAX_RISE_PCT || cur > 100.5) return

        val session = sessionDao.getSessionById(sid) ?: run { settleSessionId = null; return }
        val newDelta = (cur - session.socStart).coerceAtLeast(0.0)
        val updated = session.copy(
            socEnd      = cur,
            socEndPanel = maxOf(session.socEndPanel ?: 0.0, telemetry.socPanel.toDouble()),
            kwhAdded    = (newDelta / 100.0) * session.batteryKwh,
        )
        sessionDao.updateSession(updated)
        settleBaselineSoc = cur
        Log.i(TAG, "Post-charge settle: session $sid end SoC revised to ${"%.1f".format(cur)}%")
    }

    private suspend fun closePersistedActiveSession(
        session: ChargingSessionEntity,
        dataPoints: List<ChargingDataPointEntity>,
        carConfig: CarConfig?
    ) {
        if (dataPoints.isEmpty()) {
            sessionDao.deleteSession(session)
            Log.i(TAG, "Phantom session ${session.id} deleted (0 data points)")
            return
        }

        val lastPoint = dataPoints.last()
        // End SoC includes any post-charge settle captured on the session row (socEnd may
        // exceed the last recorded data point's SoC by the CV-phase top-off, e.g. 100% vs 98.5%).
        val endSoc      = maxOf(lastPoint.soc, session.socEnd ?: lastPoint.soc)
        val endSocPanel = maxOf(lastPoint.socPanel.toDouble(), session.socEndPanel ?: 0.0)
        val batteryKwh = carConfig?.batteryKwh ?: session.batteryKwh
        val socDelta = (endSoc - session.socStart).coerceAtLeast(0.0)
        val kwhAdded = (socDelta / 100.0) * batteryKwh
        val durationMs = lastPoint.timestamp - session.startTime
        val positivePowers = dataPoints.map { it.chargingPower }.filter { it > 0.1 }
        val avgKw = positivePowers.average().takeIf { positivePowers.isNotEmpty() } ?: 0.0
        val peakKw = maxOf(session.peakKw, positivePowers.maxOrNull() ?: 0.0)

        // peakKw is deliberately excluded: a DC charger handshake can briefly report
        // high power (5-60 kW) via capacity-activity inference before actual charging
        // starts, creating ghost sessions with real-looking peakKw but zero energy.
        val isMicroSession = durationMs in 0 until MIN_COMPLETED_SESSION_DURATION_MS &&
            socDelta < MIN_COMPLETED_SESSION_SOC_DELTA_PCT &&
            kwhAdded < MIN_COMPLETED_SESSION_KWH

        if (isMicroSession) {
            sessionDao.deleteDataPointsForSession(session.id)
            sessionDao.deleteSession(session)
            Log.i(TAG, "Transient charging flap ${session.id} deleted (${dataPoints.size} points, ${durationMs}ms)")
            return
        }

        val closed = session.copy(
            endTime      = lastPoint.timestamp,
            socEnd       = endSoc,
            socEndPanel  = endSocPanel,
            kwhAdded     = kwhAdded,
            peakKw       = peakKw,
            avgKw        = avgKw,
            batteryTempEnd = lastPoint.batteryTempAvg,
            voltageEnd   = lastPoint.batteryTotalVoltage,
            isActive     = false
        )
        sessionDao.updateSession(closed)
        Log.i(TAG, "Charging session ${session.id} closed — ${session.socStart}% → $endSoc%  ${dataPoints.size} points")
    }

    // ── SoC-delta reconstruction (car-off charging) ───────────────────────────

    private suspend fun tryReconstructChargingSession(
        previousShutdownState: ShutdownState?,
        telemetry : VehicleTelemetry,
        carConfig : CarConfig?
    ) {
        val now = System.currentTimeMillis()
        val lastSoc       = previousShutdownState?.soc ?: -1.0
        val lastTimestamp = previousShutdownState?.timestamp ?: -1L
        val lastTempAvg   = previousShutdownState?.tempAvg ?: 0.0
        val lastVoltage   = previousShutdownState?.voltage ?: 0

        if (lastSoc < 0 || lastTimestamp < 0) {
            Log.i(TAG, "No shutdown state — skipping reconstruction (first ever run)")
            return
        }

        val elapsedSinceLastStateMs = now - lastTimestamp
        if (elapsedSinceLastStateMs in 0 until MIN_RECONSTRUCTION_GAP_MS) {
            Log.i(TAG, "Saved state is too recent (${elapsedSinceLastStateMs}ms) — skipping reconstruction")
            return
        }

        val socDelta   = telemetry.soc - lastSoc
        val batteryKwh = carConfig?.batteryKwh ?: FALLBACK_BATTERY_KWH
        val kwhAdded   = (socDelta / 100.0) * batteryKwh

        Log.i(TAG, "Wake-up SoC: last=${"%.1f".format(lastSoc)}% → " +
            "now=${"%.1f".format(telemetry.soc)}%  Δ=${"%.1f".format(socDelta)}%  " +
            "≈${"%.2f".format(kwhAdded)} kWh")

        if (socDelta < MIN_SOC_DELTA_PCT || kwhAdded < MIN_KWH_ADDED) {
            Log.i(TAG, "Delta below threshold — no car-off session to reconstruct")
            return
        }

        // Duplicate guard
        val recent = sessionDao.getMostRecentSession()
        if (recent != null && recent.startTime >= lastTimestamp - OVERLAP_GUARD_MS) {
            Log.i(TAG, "Recent session (id=${recent.id}) covers this window — skipping")
            return
        }

        val session = ChargingSessionEntity(
            startTime        = lastTimestamp,
            endTime          = now,
            socStart         = lastSoc,
            socEnd           = telemetry.soc,
            socStartPanel    = (previousShutdownState?.socPanel ?: 0).toDouble(),
            socEndPanel      = telemetry.socPanel.toDouble(),
            kwhAdded         = kwhAdded,
            peakKw           = 0.0,   // not available — car was off
            avgKw            = 0.0,   // not available — car was off
            batteryTempStart = lastTempAvg,
            batteryTempEnd   = telemetry.batteryTempAvg,
            voltageStart     = lastVoltage,
            voltageEnd       = telemetry.batteryTotalVoltage,
            batteryKwh       = batteryKwh,
            carConfigId      = carConfig?.id ?: "",
            isActive         = false,
            // The car was parked throughout an off-state charge, so the odometer
            // never moved — the wake-up reading is the charge's odometer anchor.
            startOdometer    = telemetry.odometer.takeIf { it > 0.0 }
        )
        val id = sessionDao.insertSession(session)
        Log.i(TAG, "✅ Synthetic session created (id=$id  " +
            "${"%.1f".format(socDelta)}%  ${"%.2f".format(kwhAdded)} kWh)")
    }

    private fun persistShutdownState(telemetry: VehicleTelemetry) {
        prefs.edit()
            .putFloat(KEY_LAST_SOC,       telemetry.soc.toFloat())
            .putLong(KEY_LAST_TIMESTAMP,  System.currentTimeMillis())
            .putFloat(KEY_LAST_TEMP_AVG,  telemetry.batteryTempAvg.toFloat())
            .putInt(KEY_LAST_VOLTAGE,     telemetry.batteryTotalVoltage)
            .putInt(KEY_LAST_SOC_PANEL,   telemetry.socPanel)
            .commit()
    }

    private fun readShutdownState(): ShutdownState? {
        val lastSoc = prefs.getFloat(KEY_LAST_SOC, -1f).toDouble()
        val lastTimestamp = prefs.getLong(KEY_LAST_TIMESTAMP, -1L)
        if (lastSoc < 0 || lastTimestamp < 0) return null
        return ShutdownState(
            soc       = lastSoc,
            timestamp = lastTimestamp,
            tempAvg   = prefs.getFloat(KEY_LAST_TEMP_AVG, 0f).toDouble(),
            voltage   = prefs.getInt(KEY_LAST_VOLTAGE, 0),
            socPanel  = prefs.getInt(KEY_LAST_SOC_PANEL, 0)
        )
    }

    // ── Deletion ──────────────────────────────────────────────────────────────

    suspend fun deleteSession(sessionId: Long) {
        database.withTransaction {
            sessionDao.deleteDataPointsForSession(sessionId)
            sessionDao.deleteSessionById(sessionId)
        }
    }

    suspend fun deleteSessions(sessionIds: List<Long>) {
        database.withTransaction {
            sessionIds.forEach { id ->
                sessionDao.deleteDataPointsForSession(id)
                sessionDao.deleteSessionById(id)
            }
        }
    }

    /** Flags/unflags a charging session as favourite — favourites are exempt from trimming. */
    suspend fun setFavourite(sessionId: Long, favourite: Boolean) {
        sessionDao.setFavourite(sessionId, favourite)
    }

    /** Sets (or clears, when [price] is null) the per-charge electricity price in currency/kWh.
     *  0.0 is a valid value meaning a free charge; null reverts the charge to the global tariff. */
    suspend fun setSessionPrice(sessionId: Long, price: Double?) {
        sessionDao.setSessionPrice(sessionId, price)
    }

    // ── Singleton ─────────────────────────────────────────────────────────────

    /** Cancels the coroutine scope. Must be called in tests to prevent
     *  stale coroutines from a previous instance writing to shared prefs
     *  after the singleton is reset between test runs. */
    fun close() {
        scope.cancel()
    }

    companion object {
        @Volatile private var INSTANCE: ChargingRepository? = null

        fun getInstance(context: Context): ChargingRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: ChargingRepository(context.applicationContext).also { INSTANCE = it }
            }

        private const val KEY_LAST_SOC        = "last_soc"
        private const val KEY_LAST_TIMESTAMP  = "last_timestamp"
        private const val KEY_LAST_TEMP_AVG   = "last_temp_avg"
        private const val KEY_LAST_VOLTAGE    = "last_voltage"
        private const val KEY_LAST_SOC_PANEL  = "last_soc_panel"

        private const val MIN_SOC_DELTA_PCT   = 1.0
        private const val MIN_KWH_ADDED       = 0.3
        private const val MIN_RECONSTRUCTION_GAP_MS = 30_000L
        private const val OVERLAP_GUARD_MS    = 5 * 60 * 1000L
        private const val FALLBACK_BATTERY_KWH = 82.5
        private const val ACTIVE_SESSION_CLOSE_GRACE_MS = 20_000L
        // Post-charge settle: hold a near-full off-state session open this long after charge
        // current stops so the BMS's SoC recalibration to 100% is captured. Comfortably
        // within the service's 5-min car-off self-stop window.
        private const val SETTLE_GRACE_MS = 4 * 60_000L
        private const val SETTLE_NEAR_FULL_PCT = 90.0
        private const val SETTLE_MAX_RISE_PCT = 4.0
        private const val MIN_COMPLETED_SESSION_DURATION_MS = 90_000L
        private const val MIN_COMPLETED_SESSION_SOC_DELTA_PCT = 0.05
        private const val MIN_COMPLETED_SESSION_KWH = 0.05
    }
}