package com.byd.tripstats.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Log
import androidx.room.withTransaction
import com.byd.tripstats.data.analysis.ChargingEnergy
import com.byd.tripstats.data.config.CarCatalog
import com.byd.tripstats.data.config.CarConfig
import com.byd.tripstats.data.local.BydStatsDatabase
import com.byd.tripstats.data.local.entity.ChargingDataPointEntity
import com.byd.tripstats.data.local.entity.ChargingSessionEntity
import com.byd.tripstats.data.model.VehicleTelemetry
import com.byd.tripstats.data.notify.ChargeOrigin
import com.byd.tripstats.data.notify.VehicleEvents
import com.byd.tripstats.data.preferences.PreferencesManager
import com.byd.tripstats.sdk.DiLink5Platform
import com.byd.tripstats.util.DiagLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * Hybrid charging session management:
 *
 * ── Car ON / awake / ready (carOn > 0) ─────────────────────────────────────
 *   First packet after service start: SoC-delta reconstruction check.
 *   If SoC increased since last shutdown → synthetic session created for the
 *   car-off charging period (overnight, remote charging, etc.).
 *
 *   chargingPower > 0 → open / continue a real-time session, record data points.
 *   chargingPower = 0 → close the active session, unless the car is still out of D/R: then
 *   the session is held for CHARGE_PAUSE_HOLD_MS first, because an AC charge pauses for
 *   seconds when the car is switched on or unlocked (see endOrHoldActiveSession).
 *
 * ── Car OFF (carOn = 0) ────────────────────────────────────────────────────
 *   Charging → keep recording; not charging → close (after the same pause hold).
 *   SoC + timestamp are persisted only while the car is on, so the next wake-up can
 *   reconstruct, from there or from the end of a session recorded since.
 *
 * This means live charts are available for sessions recorded while the car is on,
 * and synthetic sessions (peakKw = avgKw = 0, no data points) represent car-off
 * charging reconstructed from the SoC delta on wake-up.
 */
class ChargingRepository private constructor(context: Context) {

    private val TAG = "ChargingRepository"

    private val appContext = context.applicationContext
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
    /** elapsedRealtime of the last charging packet — what [CHARGE_PAUSE_HOLD_MS] is measured from. */
    private var lastChargingSeenElapsedMs: Long = 0L
    /** The session a pause is holding open, so the pause and its end are logged once each. */
    private var pausedSessionId: Long? = null
    // Post-charge settle top-up: after a near-full off-state session closes, the BMS keeps
    // recomputing SoC upward for a few minutes (e.g. 98.5% → 100%). While settleUntilMs is
    // in the future, applyPostChargeSettle() revises the already-closed session's end SoC.
    private var settleSessionId: Long? = null
    private var settleBaselineSoc: Double = 0.0
    private var settleUntilMs: Long = 0L

    /**
     * Prevents reconstruction from running more than once per car wake-up cycle, and orphan
     * recovery from running more than once per process. Both are read on the caller's thread
     * and claimed inside a coroutine on [Dispatchers.IO], so two packets arriving together at
     * cold start would both pass a plain check-then-set. The claim is the compareAndSet, which
     * one of them loses.
     */
    private val reconstructionAttempted = AtomicBoolean(false)
    private val activeSessionRecoveryAttempted = AtomicBoolean(false)
    private var wasCarOn = false

    internal data class ShutdownState(
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
        if (telemetry.isCarOn && !wasCarOn && reconstructionAttempted.get()) {
            Log.i(TAG, "Car transitioned off→on — resetting reconstruction guard for new wake-up")
            reconstructionAttempted.set(false)
        }
        wasCarOn = telemetry.isCarOn

        val previousShutdownState =
            if (telemetry.isCarOn && !reconstructionAttempted.get()) readShutdownState() else null

        // Persist only while the car is on. Off-state packets would overwrite the
        // pre-sleep baseline as SoC climbs during an off-state charge, making the
        // subsequent wake-up delta zero and dropping the reconstructed session.
        //
        // On the very first car-on packet of each wake cycle (reconstructionAttempted still false),
        // we defer persisting until AFTER the reconstruction coroutine runs. Persisting
        // immediately would overwrite the pre-charge SoC with the post-charge value if the
        // process is killed mid-reconstruction (e.g. an app update installed while the car is
        // waking up), making the next start's SoC delta zero and silently dropping the session.
        // Subsequent packets (reconstructionAttempted already true) persist synchronously as before.
        if (telemetry.isCarOn && reconstructionAttempted.get()) {
            persistShutdownState(telemetry)
        }

        scope.launch {
            if (activeSessionRecoveryAttempted.compareAndSet(false, true)) {
                recoverOrphanActiveSessions(telemetry, carConfig)
            }

            if (telemetry.isCarOn) {
                // First carOn packet after service start → attempt reconstruction
                // only if no data points were recorded while car was off
                // (process survived → real-time data exists → no reconstruction needed).
                if (reconstructionAttempted.compareAndSet(false, true)) {
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
                // Car is off and not charging — close any open session (unless a pause holds it),
                // then keep revising a just-closed near-full session's end SoC while the BMS
                // settles to 100%.
                endOrHoldActiveSession(carConfig, telemetry)
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
                lastChargingSeenElapsedMs = SystemClock.elapsedRealtime()
                _activeSessionId.value = session.id
                _isCharging.value = true
                Log.i(TAG, "Resumed active charging session ${session.id}")
            } else {
                // The app was killed mid-charge, so this row is finalised long after the fact.
                closePersistedActiveSession(session, points, carConfig)
                    ?.let { notifyChargingFinished(it.id, settleWaitMs = 0L, origin = ChargeOrigin.RECOVERED) }
            }
        }
    }

    // ── Real-time session management (car ON only) ────────────────────────────

    private suspend fun handleRealTimeCharging(
        telemetry : VehicleTelemetry,
        carConfig : CarConfig?
    ) {
        if (telemetry.isCharging) {
            val nowElapsed = SystemClock.elapsedRealtime()
            val held = activeSession?.id?.takeIf { it == pausedSessionId }
            if (held != null) {
                DiagLog.event(appContext, TAG,
                    "🔌 charge resumed after ${(nowElapsed - lastChargingSeenElapsedMs) / 1000}s — session $held continues")
            }
            pausedSessionId = null
            lastChargingSeenElapsedMs = nowElapsed
            settleSessionId = null; settleUntilMs = 0L   // active charging — cancel any pending settle top-up
            if (activeSession == null) {
                startSession(telemetry, carConfig)
            } else if (DiLink5Platform.isDiLink5 && telemetry.chargingPower <= 0.1) {
                // DiLink-5 holds a charge through a pause with the cable in and reports 0 kW
                // meanwhile (DI5_CHARGING_PAUSE_HOLD_MS). No point is stored, so a charge that
                // finished with the cable left in ends at its last real reading, not when the
                // hold runs out. DiLink-3 keeps recording: there, a 0 kW tick can be a real
                // off-state AC charge with the power reading absent.
            } else {
                recordDataPoint(telemetry)
            }
        } else {
            // Car is on but the incoming packet says charging has stopped.
            endOrHoldActiveSession(carConfig, telemetry)
        }
    }

    /**
     * Ends the open session on a not-charging packet, unless the charge has only paused.
     *
     * An AC charge pauses for a few seconds when the car is switched on or unlocked, and on
     * DiLink-3 every charging signal drops with it. On the dev Seal (2026-11-12, 2.1 kW AC, cable
     * in throughout) switching the car on mid-charge took pack power to 0 for ~10 s: the session
     * closed, "charging finished" went out, and the charge carried on as a new session, so one
     * charge became #331 + #332. While the car stays out of D/R, the session is held for
     * [CHARGE_PAUSE_HOLD_MS] from the last charging packet; a charge that comes back in that time
     * continues it, and one that doesn't ends at its last data point, as it would have anyway.
     * DiLink-5 holds its pauses in the data source instead ([com.byd.tripstats.sdk.DI5_CHARGING_PAUSE_HOLD_MS]),
     * where the cable state is known, so it closes here at once as before.
     */
    private suspend fun endOrHoldActiveSession(carConfig: CarConfig?, telemetry: VehicleTelemetry) {
        val session = activeSession ?: return
        val pausedForMs = SystemClock.elapsedRealtime() - lastChargingSeenElapsedMs
        if (holdsChargePause(DiLink5Platform.isDiLink5, telemetry.gear, pausedForMs)) {
            if (pausedSessionId != session.id) {
                pausedSessionId = session.id
                DiagLog.event(appContext, TAG,
                    "🔌 charge paused — session ${session.id} held up to ${CHARGE_PAUSE_HOLD_MS / 60_000} min " +
                        "(gear=${telemetry.gear} carOn=${telemetry.carOn} power=${telemetry.enginePower})")
            }
            // Still a charge in progress: an update installed now would cut it in two.
            _isCharging.value = true
            return
        }
        closeActiveSessionIfAny(carConfig, telemetry)
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
        lastChargingSeenElapsedMs = SystemClock.elapsedRealtime()
        DiagLog.event(appContext, TAG,
            "🔌 charge session $id started: soc=${telemetry.soc} panel=${telemetry.socPanel} " +
                "power=${telemetry.chargingPower} carOn=${telemetry.carOn} gear=${telemetry.gear}")
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
    ) {
        val session = activeSession ?: return
        val now = System.currentTimeMillis()
        val dataPoints = sessionDao.getDataPointsForSessionSync(session.id)
        val closed = closePersistedActiveSession(session, dataPoints, carConfig)

        // Arm post-charge settle top-up for a near-full off-state charge: the session is
        // already closed (activeSessionId clears immediately, as callers expect), but the
        // BMS keeps recomputing SoC upward for a few minutes after current stops
        // (e.g. 98.5% → 100%). applyPostChargeSettle() revises the closed row's end SoC
        // during this window. Bounded + capped so it can never absorb a drive or a
        // separate charge. Car-off only (off-state charging completion).
        // The settle runs from when current stopped, and a pause hold has already waited through
        // part of it: what the BMS settled meanwhile is caught by the first tick after this close.
        // Without the deduction a held session's message would go out after the service's
        // 5-minute self-stop.
        val sinceChargingMs = (SystemClock.elapsedRealtime() - lastChargingSeenElapsedMs).coerceAtLeast(0L)
        val settleWindowMs = (SETTLE_GRACE_MS - sinceChargingMs).coerceAtLeast(SETTLE_MIN_WINDOW_MS)
        val closedEndSoc = maxOf(dataPoints.lastOrNull()?.soc ?: 0.0, session.socEnd ?: 0.0)
        if (!telemetry.isCarOn && dataPoints.isNotEmpty() && closedEndSoc >= SETTLE_NEAR_FULL_PCT) {
            settleSessionId   = session.id
            settleBaselineSoc = closedEndSoc
            settleUntilMs     = now + settleWindowMs
        } else {
            settleSessionId = null
            settleUntilMs   = 0L
        }

        activeSession = null
        lastChargingSeenElapsedMs = 0L
        pausedSessionId = null
        _activeSessionId.value = null
        _isCharging.value = false

        // The live close: this one is news, so it reaches the push as well as the wire.
        // null means the row was dropped as a phantom or a charger-handshake flap.
        closed?.let {
            notifyChargingFinished(it.id, settleWaitMs = if (settleSessionId == it.id) settleWindowMs else 0L,
                origin = ChargeOrigin.LIVE)
        }
    }

    /**
     * Announces a closed session, after the post-charge settle window when one is armed: for a
     * near-full off-state charge the BMS keeps revising SoC upward for minutes after current
     * stops, and a message saying 98% about a session the app then stores (and shows) as 100%
     * is the kind of small disagreement that costs trust in every other number in it. The row
     * is re-read at the end of the wait so whatever settled is what gets sent.
     */
    private fun notifyChargingFinished(sessionId: Long, settleWaitMs: Long, origin: ChargeOrigin) {
        scope.launch {
            // The scope carries no exception handler, so anything thrown here would reach the
            // thread's default handler and take the process down. This now runs at cold start on
            // every DiLink-5 ignition, where the cost of that would be the app itself.
            try {
                if (settleWaitMs > 0L) delay(settleWaitMs + SETTLE_SEND_MARGIN_MS)
                val row = sessionDao.getSessionById(sessionId) ?: return@launch
                val prefs = PreferencesManager(appContext)
                VehicleEvents.getInstance(appContext).onChargingSessionClosed(
                    session = row,
                    // The charging screen's own rule: the session's price when it has one (0.0 is
                    // a valid "free"), otherwise the global tariff, and no cost without either.
                    ratePerKwh = row.pricePerKwh ?: prefs.getCachedElectricityPrice().takeIf { it > 0.0 },
                    origin = origin,
                )
            } catch (e: CancellationException) {
                // The settle wait is the long part of this coroutine, so a cancelled scope lands
                // here before anything else does. Cancellation is not a failure to log, and a
                // coroutine that swallows it reports itself as having completed normally.
                throw e
            } catch (t: Throwable) {
                Log.w(TAG, "Could not announce charging session $sessionId (${origin.wireName})", t)
            }
        }
    }

    /**
     * After a near-full off-state session closes, the BMS keeps recomputing SoC upward for
     * a few minutes (the CV/settle phase, e.g. 98.5% → 100%). While within the settle
     * window and the car stays parked, revise the closed session's end SoC (and energy)
     * to the higher value. Capped at [SETTLE_MAX_RISE_PCT] and gated so it can never absorb
     * a drive or a separate charge. Raising the pack figure can invalidate the session's
     * received figure, which is re-checked against it here rather than left to contradict it.
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
        val newKwhAdded = (newDelta / 100.0) * session.batteryKwh
        // Settling moves the pack figure, which is the received figure's only sanity check.
        // Less energy cannot have arrived than the pack ended up holding, so an integral the
        // revised figure catches up with was never a reading of this whole charge, and the
        // session goes back to reporting no measurement like any other unmeasured one.
        val received = session.energyReceivedKwh?.takeIf { ChargingEnergy.plausible(it, newKwhAdded) }
        if (received == null && session.energyReceivedKwh != null) {
            Log.i(TAG, "Post-charge settle: session $sid dropped its received figure — " +
                "${"%.2f".format(session.energyReceivedKwh)} kWh no longer clears a pack gain " +
                "of ${"%.2f".format(newKwhAdded)} kWh")
        }
        val updated = session.copy(
            socEnd      = cur,
            socEndPanel = maxOf(session.socEndPanel ?: 0.0, telemetry.socPanel.toDouble()),
            kwhAdded    = newKwhAdded,
            energyReceivedKwh = received,
        )
        sessionDao.updateSession(updated)
        settleBaselineSoc = cur
        Log.i(TAG, "Post-charge settle: session $sid end SoC revised to ${"%.1f".format(cur)}%")
    }

    /** @return the closed row, or null when the session was dropped as a phantom or a flap. */
    private suspend fun closePersistedActiveSession(
        session: ChargingSessionEntity,
        dataPoints: List<ChargingDataPointEntity>,
        carConfig: CarConfig?
    ): ChargingSessionEntity? {
        if (dataPoints.isEmpty()) {
            sessionDao.deleteSession(session)
            DiagLog.event(appContext, TAG, "🔌 charge session ${session.id} dropped: no data points")
            return null
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
        // What the car measured arriving, as opposed to what the pack kept. Computed here and
        // stored, because DatabaseTrimmer eventually deletes the points it is derived from. The
        // pack figure goes in as the sanity check: an integral that does not clear it did not
        // see the whole charge, and ChargingEnergy returns null rather than an understatement.
        val receivedKwh = ChargingEnergy.receivedKwh(dataPoints, kwhAdded)

        // peakKw is deliberately excluded: a DC charger handshake can briefly report
        // high power (5-60 kW) via capacity-activity inference before actual charging
        // starts, creating ghost sessions with real-looking peakKw but zero energy.
        val isMicroSession = durationMs in 0 until MIN_COMPLETED_SESSION_DURATION_MS &&
            socDelta < MIN_COMPLETED_SESSION_SOC_DELTA_PCT &&
            kwhAdded < MIN_COMPLETED_SESSION_KWH

        if (isMicroSession) {
            sessionDao.deleteDataPointsForSession(session.id)
            sessionDao.deleteSession(session)
            DiagLog.event(appContext, TAG,
                "🔌 charge session ${session.id} dropped as a flap: ${dataPoints.size} points over ${durationMs / 1000}s")
            return null
        }

        val closed = session.copy(
            endTime      = lastPoint.timestamp,
            socEnd       = endSoc,
            socEndPanel  = endSocPanel,
            kwhAdded     = kwhAdded,
            energyReceivedKwh = receivedKwh,
            peakKw       = peakKw,
            avgKw        = avgKw,
            batteryTempEnd = lastPoint.batteryTempAvg,
            voltageEnd   = lastPoint.batteryTotalVoltage,
            isActive     = false
        )
        sessionDao.updateSession(closed)
        val received = receivedKwh?.let { "%.2f".format(it) } ?: "unmeasured"
        DiagLog.event(appContext, TAG,
            "🔌 charge session ${session.id} closed: soc=${session.socStart}→$endSoc " +
                "${dataPoints.size} points over ${durationMs / 60_000} min, pack ${"%.2f".format(kwhAdded)} kWh, received $received")
        return closed
    }

    // ── SoC-delta reconstruction (car-off charging) ───────────────────────────

    private suspend fun tryReconstructChargingSession(
        previousShutdownState: ShutdownState?,
        telemetry : VehicleTelemetry,
        carConfig : CarConfig?
    ) {
        val now = System.currentTimeMillis()
        val saved = previousShutdownState
        if (saved == null || saved.soc < 0 || saved.timestamp < 0) {
            Log.i(TAG, "No shutdown state — skipping reconstruction (first ever run)")
            return
        }

        val elapsedSinceSavedMs = now - saved.timestamp
        if (elapsedSinceSavedMs in 0 until MIN_RECONSTRUCTION_GAP_MS) {
            Log.i(TAG, "Saved state is too recent (${elapsedSinceSavedMs}ms) — skipping reconstruction")
            return
        }

        // Duplicate guard: a session that started after the state was saved covers this window.
        val recent = sessionDao.getMostRecentSession()
        if (recent != null && recent.startTime >= saved.timestamp - OVERLAP_GUARD_MS) {
            Log.i(TAG, "Recent session (id=${recent.id}) covers this window — skipping")
            return
        }

        // A session that started before the state was saved but ran past it has already counted
        // what it added. The state is only saved with the car on, so a charge the car was switched
        // on during goes on long after the last save: on the dev Seal (2026-11-12) #332 ran
        // 12:21–13:48 with the state saved at 12:36, and the wake at 13:50 stored 12:36–13:50 again
        // as Reconstructed #334 (78.6 → 83.9 %, 4.37 kWh). Only what came after its end is unaccounted for.
        val recorded = sessionDao.getLatestSessionEndedAfter(saved.timestamp)
        val baseline = rebaseOnRecordedCharge(saved, recorded)
        val rebased = baseline !== saved

        val socDelta   = telemetry.soc - baseline.soc
        val batteryKwh = carConfig?.batteryKwh ?: FALLBACK_BATTERY_KWH
        val kwhAdded   = (socDelta / 100.0) * batteryKwh
        val elapsedSinceBaselineMs = now - baseline.timestamp

        Log.i(TAG, "Wake-up SoC: last=${"%.1f".format(baseline.soc)}% → " +
            "now=${"%.1f".format(telemetry.soc)}%  Δ=${"%.1f".format(socDelta)}%  " +
            "≈${"%.2f".format(kwhAdded)} kWh${if (rebased) "  (from the end of session ${recorded?.id})" else ""}")

        if (rebased && socDelta < MIN_SOC_RISE_AFTER_RECORDED_CHARGE_PCT) {
            DiagLog.event(
                appContext, TAG,
                "charge reconstruction skipped: session ${recorded?.id} recorded the charge until " +
                    "${elapsedSinceBaselineMs / 60_000} min ago (BMS ${"%.1f".format(baseline.soc)} %), " +
                    "now ${"%.1f".format(telemetry.soc)} % — nothing it didn't count"
            )
            return
        }

        if (socDelta < MIN_SOC_DELTA_PCT || kwhAdded < MIN_KWH_ADDED) {
            Log.i(TAG, "Delta below threshold — no car-off session to reconstruct")
            return
        }

        if (isBmsRestRebound(socDelta, baseline.socPanel, telemetry.socPanel)) {
            DiagLog.event(
                appContext, TAG,
                "charge reconstruction skipped: BMS ${"%.1f".format(baseline.soc)} → ${"%.1f".format(telemetry.soc)}% " +
                    "but panel ${baseline.socPanel} → ${telemetry.socPanel}% — rest rebound, not a charge"
            )
            return
        }

        implausibleReconstruction(
            kwhAdded, elapsedSinceBaselineMs,
            bmsBefore = baseline.soc, panelBefore = baseline.socPanel,
        )?.let { reason ->
            DiagLog.event(
                appContext, TAG,
                "charge reconstruction skipped: BMS ${"%.1f".format(baseline.soc)} → ${"%.1f".format(telemetry.soc)}% " +
                    "panel ${baseline.socPanel} → ${telemetry.socPanel}% " +
                    "${"%.2f".format(kwhAdded)} kWh over ${elapsedSinceBaselineMs / 1000}s — $reason"
            )
            return
        }

        val session = ChargingSessionEntity(
            startTime        = baseline.timestamp,
            endTime          = now,
            socStart         = baseline.soc,
            socEnd           = telemetry.soc,
            socStartPanel    = baseline.socPanel.toDouble(),
            socEndPanel      = telemetry.socPanel.toDouble(),
            kwhAdded         = kwhAdded,
            peakKw           = 0.0,   // not available — car was off
            avgKw            = 0.0,   // not available — car was off
            batteryTempStart = baseline.tempAvg,
            batteryTempEnd   = telemetry.batteryTempAvg,
            voltageStart     = baseline.voltage,
            voltageEnd       = telemetry.batteryTotalVoltage,
            batteryKwh       = batteryKwh,
            carConfigId      = carConfig?.id ?: "",
            isActive         = false,
            // The car was parked throughout an off-state charge, so the odometer
            // never moved — the wake-up reading is the charge's odometer anchor.
            startOdometer    = telemetry.odometer.takeIf { it > 0.0 }
        )
        val id = sessionDao.insertSession(session)
        DiagLog.event(appContext, TAG,
            "🔌 charge reconstructed: session $id BMS ${"%.1f".format(baseline.soc)} → ${"%.1f".format(telemetry.soc)}% " +
                "panel ${baseline.socPanel} → ${telemetry.socPanel}% ${"%.2f".format(kwhAdded)} kWh over " +
                "${elapsedSinceBaselineMs / 60_000} min${if (rebased) " (from the end of session ${recorded?.id})" else ""}")
        notifyChargingFinished(id, settleWaitMs = 0L, origin = ChargeOrigin.SOC_ESTIMATE)
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

        /**
         * The BMS reading drops further than the panel under load and creeps back up once the
         * pack rests — 1–2 % after a drive, with the panel unmoved (DiLink-3 Seal, 2026-09-26:
         * BMS 48.7 → 50.2 % over 4.5 h parked underground, panel 50 → 50). Taken as a charge,
         * that rebound stored phantom "Reconstructed" sessions of ~1.2–1.4 kWh. A real charge
         * moves the panel as well, so a BMS rise below [MAX_BMS_REST_REBOUND_PCT] with the panel
         * unchanged (or lower) is a rebound. Above it the BMS alone is trusted, in case the panel
         * still reads its pre-charge value on the first packet after wake; with either panel
         * reading missing (0) the check stands aside.
         */
        internal fun isBmsRestRebound(bmsRisePct: Double, panelBefore: Int, panelNow: Int): Boolean =
            panelBefore > 0 && panelNow > 0 && panelNow <= panelBefore && bmsRisePct < MAX_BMS_REST_REBOUND_PCT

        /**
         * Why a would-be reconstructed charge can't be one, or null when it may be.
         *
         * Two phantoms on a Sealion 7 (2026-10-10, 11:00 and 11:31) showed what slips past the
         * other guards: 0–1 minute long, panel 63 % → 63 %, yet 51 kWh — the saved BMS reading
         * was about 1 %, a bad reading taken while driving, and the 62-point "rise" from it was
         * far too big for [isBmsRestRebound]. Either rule below stops that, independently:
         * - the energy can't have arrived in the time since the state was saved — above
         *   [MAX_RECONSTRUCTED_CHARGE_KW] (51 kWh in a minute is over 3,000 kW);
         * - in the saved state, the BMS reading and the panel disagree by more than
         *   [MAX_BMS_PANEL_GAP_PCT] points, so the baseline the delta comes from is broken.
         *   Normally they sit within a few points of each other. Only the saved end is checked:
         *   it was taken with the car running, so its panel is live, while the panel at wake can
         *   still be stale — the reason [isBmsRestRebound] trusts a large rise with the panel
         *   unchanged, which this must not overturn. Skipped where the saved panel is missing (0).
         */
        internal fun implausibleReconstruction(
            kwhAdded: Double,
            elapsedMs: Long,
            bmsBefore: Double,
            panelBefore: Int,
        ): String? {
            val hours = elapsedMs / 3_600_000.0
            if (hours > 0 && kwhAdded / hours > MAX_RECONSTRUCTED_CHARGE_KW) {
                return "${"%.0f".format(kwhAdded / hours)} kW would be needed — not a charge"
            }
            // A saved BMS of ~0 with the panel above it is the first-frame 0 (isPlausibleDi5UsableKwh),
            // whatever the panel reads. The gap rule below misses it at a low SoC — 0 against a
            // panel of 16 % was one of the 11 frames on the Sealion 7 — which is just when people
            // plug in, and the power rule only trips within minutes of the saved state.
            if (panelBefore >= 2 && bmsBefore < ZERO_BASELINE_MAX_PCT) {
                return "the saved BMS reading is 0 while the panel read $panelBefore % — a bad reading, not a charge"
            }
            if (panelBefore > 0 && kotlin.math.abs(bmsBefore - panelBefore) > MAX_BMS_PANEL_GAP_PCT) {
                return "the saved BMS reading disagrees with the panel — a bad reading, not a charge"
            }
            return null
        }

        /**
         * Whether a not-charging packet is a pause the open session waits out ([CHARGE_PAUSE_HOLD_MS])
         * rather than its end. Gear alone decides "driving", for the reason computeChargingActive
         * gives: a parked head unit's speed (GPS fallback included) can read above 0.
         */
        internal fun holdsChargePause(isDiLink5: Boolean, gear: String, pausedForMs: Long): Boolean =
            !isDiLink5 && gear != "D" && gear != "R" && pausedForMs in 0 until CHARGE_PAUSE_HOLD_MS

        /**
         * The saved state a reconstruction measures from, moved to the end of [recorded] when that
         * session ran past it: from there on, the session counted the charge itself. [recorded] is
         * the latest closed session ending after the saved state (null when there is none).
         * Returns [saved] itself (same instance) when nothing moves it.
         */
        internal fun rebaseOnRecordedCharge(saved: ShutdownState, recorded: ChargingSessionEntity?): ShutdownState {
            if (recorded == null || recorded.isActive) return saved
            val end = recorded.endTime ?: return saved
            val socEnd = recorded.socEnd ?: return saved
            if (end <= saved.timestamp) return saved
            return ShutdownState(
                soc       = socEnd,
                timestamp = end,
                tempAvg   = recorded.batteryTempEnd ?: saved.tempAvg,
                voltage   = recorded.voltageEnd ?: saved.voltage,
                // 0 = unknown, which makes isBmsRestRebound and the panel-gap check stand aside.
                socPanel  = recorded.socEndPanel?.roundToInt() ?: 0,
            )
        }

        /**
         * How long a session is held through a pause in the charge (see endOrHoldActiveSession). The
         * pauses seen last seconds: ~10 s on switching a DiLink-3 Seal on, 15–30 s on unlocking a
         * DiLink-5 Sealion 7. It must stay well under the service's 5-minute "car off and not
         * charging" self-stop (VehicleTelemetryService), which counts from the same packet: a hold
         * still running when the service stops leaves the session open until the app next starts.
         */
        internal const val CHARGE_PAUSE_HOLD_MS = 3 * 60_000L

        /**
         * After a recorded charge, the BMS can still move the SoC up by itself — the post-charge
         * settle, up to [SETTLE_MAX_RISE_PCT] — and that rise belongs to the recorded charge, not to
         * a new one. A real charge after the recorded one is larger than that.
         */
        private const val MIN_SOC_RISE_AFTER_RECORDED_CHARGE_PCT = 4.0

        /** A real near-empty battery reads a few tenths with the panel at 0–1 %; below this it's the 0. */
        private const val ZERO_BASELINE_MAX_PCT = 0.5

        /** Above any charger the app counts (the same ceiling as DiLink-5's charge-power check). */
        private const val MAX_RECONSTRUCTED_CHARGE_KW = 400.0
        private const val MAX_BMS_PANEL_GAP_PCT = 20.0

        private const val MAX_BMS_REST_REBOUND_PCT = 3.0
        private const val MIN_SOC_DELTA_PCT   = 1.0
        private const val MIN_KWH_ADDED       = 0.3
        private const val MIN_RECONSTRUCTION_GAP_MS = 30_000L
        private const val OVERLAP_GUARD_MS    = 5 * 60 * 1000L
        private const val FALLBACK_BATTERY_KWH = 82.5
        // Post-charge settle: hold a near-full off-state session open this long after charge
        // current stops so the BMS's SoC recalibration to 100% is captured. Comfortably
        // within the service's 5-min car-off self-stop window.
        private const val SETTLE_GRACE_MS = 4 * 60_000L
        /** The shortest settle window after a close, however long a pause hold ran before it. */
        private const val SETTLE_MIN_WINDOW_MS = 60_000L
        /** Small extra wait so the last settle tick is stored before the row is read back. */
        private const val SETTLE_SEND_MARGIN_MS = 15_000L
        private const val SETTLE_NEAR_FULL_PCT = 90.0
        private const val SETTLE_MAX_RISE_PCT = 4.0
        private const val MIN_COMPLETED_SESSION_DURATION_MS = 90_000L
        private const val MIN_COMPLETED_SESSION_SOC_DELTA_PCT = 0.05
        private const val MIN_COMPLETED_SESSION_KWH = 0.05
    }
}