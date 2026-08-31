package com.byd.tripstats.data.repository

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.byd.tripstats.data.local.BydStatsDatabase
import com.byd.tripstats.data.local.dao.TripSohSummary
import com.byd.tripstats.data.local.entity.LatLng
import com.byd.tripstats.data.local.entity.TripDataPointEntity
import com.byd.tripstats.data.local.entity.TripEntity
import com.byd.tripstats.data.local.entity.TagEntity
import com.byd.tripstats.data.local.entity.TripSegmentEntity
import com.byd.tripstats.data.local.entity.TripStatsEntity
import com.byd.tripstats.data.local.entity.TripTagCrossRef
import com.byd.tripstats.data.local.entity.TAG_PALETTE_SIZE
import com.byd.tripstats.data.model.VehicleTelemetry
import com.byd.tripstats.data.preferences.PreferencesManager
import com.byd.tripstats.util.DiagLog
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs
import kotlin.math.sqrt

// ── Event bus ─────────────────────────────────────────────────────────────────

/**
 * Every operation that touches trip state enters the repository through one of
 * these events. The processor coroutine drains the channel sequentially, so no
 * mutex, no @Volatile, no concurrency reasoning anywhere in the business logic.
 */
sealed class TripEvent {
    /** Live telemetry packet arriving from the vehicle telemetry service. */
    data class Telemetry(val data: VehicleTelemetry) : TripEvent()

    /** User tapped "Start trip" in the UI. */
    object ManualStart : TripEvent()

    /** User tapped "Stop trip" in the UI. */
    object ManualStop : TripEvent()

    /**
     * User tapped "Keep recording" on the auto-stop confirmation prompt — hold the
     * current car-off window open so the trip is not auto-stopped (until they drive
     * again, tap Stop, or the kept-off safety cap is reached).
     */
    object KeepTripAcrossOff : TripEvent()

    /**
     * User tapped "Stop" on the auto-stop confirmation prompt — finalise the
     * pending car-off auto-stop now (ends exactly as the silent auto-stop would).
     */
    object ConfirmAutoStop : TripEvent()

    /**
     * Sent by the watchdog every 60 s. The handler decides whether silence
     * has been long enough to close the active trip.
     */
    object WatchdogTick : TripEvent()

    /**
     * Injected as the very first event on cold start. The handler checks for
     * an open trip in the DB and either resumes or closes it.
     */
    object Recover : TripEvent()
}

/** What to do with an active trip that has been car-off past its continuation window. */
internal enum class CarOffStopAction {
    /** Still inside the continuation window — keep waiting. */
    WITHIN_WINDOW,
    /** End the trip now (legacy auto-stop). */
    END,
    /** Hold the trip open and show the confirmation prompt. */
    HOLD_FOR_CONFIRM,
    /** User already chose to keep this off-window — stay open silently. */
    KEEP_HELD
}

/**
 * Pure decision for [TripRepository.evaluateCarOffStop], extracted so it can be
 * unit-tested without the DB/telemetry machinery. Order matters: the long-parked
 * safety cap wins over a user "keep" so a forgotten trip can't run forever; a
 * "keep" wins over the prompt; and with the feature off or no UI on screen we fall
 * straight through to the legacy auto-stop.
 */
internal fun decideCarOffStop(
    offDurationMs: Long,
    timeoutMs: Long,
    maxKeptMs: Long,
    userKept: Boolean,
    confirmEnabled: Boolean,
    uiVisible: Boolean
): CarOffStopAction {
    // The cap must always sit above the timeout — otherwise it fires on the same
    // tick the prompt would and ends the trip first, suppressing the dialog
    // entirely (e.g. a user-set car-off timeout ≥ the cap, or a test that lowers
    // the cap to the timeout). Floor it at timeout + 1 min so the prompt always
    // has a window.
    val effectiveCapMs = maxOf(maxKeptMs, timeoutMs + 60_000L)
    return when {
        offDurationMs <= timeoutMs       -> CarOffStopAction.WITHIN_WINDOW
        offDurationMs > effectiveCapMs   -> CarOffStopAction.END
        userKept                         -> CarOffStopAction.KEEP_HELD
        !confirmEnabled || !uiVisible    -> CarOffStopAction.END
        else                             -> CarOffStopAction.HOLD_FOR_CONFIRM
    }
}

// ── Trip merge ──────────────────────────────────────────────────────────────

/** Whether two trips may be merged, and — when not — a user-facing reason. */
data class MergeEligibility(val eligible: Boolean, val reason: String? = null)

/** Outcome of [TripRepository.mergeTrips]. */
sealed class MergeResult {
    /** The two trips were combined; [mergedTripId] is the surviving (earlier) trip. */
    data class Success(val mergedTripId: Long) : MergeResult()
    /** The merge did not happen; [reason] is suitable for display. */
    data class Failure(val reason: String) : MergeResult()
}

// ── Trip state machine ────────────────────────────────────────────────────────

private enum class TripState { IDLE, ACTIVE }

private const val KEY_LAST_TELEMETRY_JSON = "last_telemetry_json"

// ── Repository ────────────────────────────────────────────────────────────────

class TripRepository private constructor(context: Context) {

    private val TAG = "TripRepository"

    private val appContext   = context.applicationContext
    private val database     = BydStatsDatabase.getDatabase(context)
    private val tripDao      = database.tripDao()
    private val dataPointDao = database.tripDataPointDao()
    private val statsDao     = database.tripStatsDao()
    private val segmentDao   = database.tripSegmentDao()
    private val tagDao       = database.tagDao()

    private val prefs = context.getSharedPreferences("trip_prefs", Context.MODE_PRIVATE)
    private val telemetryCachePrefs = context.getSharedPreferences("telemetry_cache", Context.MODE_PRIVATE)
    private val telemetryJson = Json { ignoreUnknownKeys = true }
    private val prefsManager = PreferencesManager(appContext)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── Public state (read by UI) ─────────────────────────────────────────────

    private val _latestTelemetry = MutableStateFlow<VehicleTelemetry?>(null)
    val latestTelemetry: StateFlow<VehicleTelemetry?> = _latestTelemetry.asStateFlow()

    private val _isInTrip = MutableStateFlow(false)
    val isInTrip: StateFlow<Boolean> = _isInTrip.asStateFlow()

    private val _currentTripId = MutableStateFlow<Long?>(null)
    val currentTripId: StateFlow<Long?> = _currentTripId.asStateFlow()

    /**
     * True while an active trip has hit the car-off timeout but is being HELD open
     * awaiting the user's decision (only set when the app is in the foreground and
     * the confirm-before-auto-stop preference is on). The UI observes this to show
     * the "keep / stop" prompt; it clears when the trip resumes, is kept, or ends.
     */
    private val _pendingAutoStop = MutableStateFlow(false)
    val pendingAutoStop: StateFlow<Boolean> = _pendingAutoStop.asStateFlow()

    // Whether the dashboard UI is currently in the foreground. Set by the UI via
    // [setUiVisible]; gates whether a car-off auto-stop is held for confirmation
    // (no UI on screen → there's nobody to confirm, so auto-stop as before).
    @Volatile private var uiVisible: Boolean = false

    // ── Event channel ─────────────────────────────────────────────────────────

    /**
     * UNLIMITED so producers never block. At 1 Hz telemetry the queue depth will
     * never exceed a handful of items — memory is not a concern.
     * Control events (ManualStart, ManualStop, WatchdogTick, Recover) are rare,
     * and the processor drains them as fast as DB I/O allows.
     */
    private val tripEvents = Channel<TripEvent>(Channel.UNLIMITED)

    // ── Private state — ONLY touched from the processor coroutine ─────────────
    // No mutex, no @Volatile: serialisation is guaranteed by the single consumer.

    private var tripState             = TripState.IDLE
    private var cachedCurrentTrip: TripEntity? = null

    /**
     * Returns the active trip's currently-cached `startTotalDischarge` anchor,
     * or null if no trip is active. DashboardViewModel reads this every tick so
     * its live "energy consumed" delta stays in sync with the post-tick anchor
     * correction (see [correctStaleDischargeAnchorIfNeeded]) — without it the
     * live display would keep using the original stale-zero anchor for the rest
     * of the trip, defeating the purpose of the correction.
     */
    fun currentTripStartTotalDischarge(): Double? = cachedCurrentTrip?.startTotalDischarge

    /**
     * Sum of positive per-tick totalDischarge deltas accumulated across the active
     * trip. Robust against counter resets at every car-on cycle. Returns null when
     * no trip is active. See [tripCumulativeBmsDeltaKwh] for full rationale.
     */
    fun currentTripCumulativeBmsDeltaKwh(): Double? =
        if (cachedCurrentTrip != null) tripCumulativeBmsDeltaKwh else null

    private var lastTelemetry:        VehicleTelemetry? = null
    private var lastRecordedTelemetry: VehicleTelemetry? = null
    private var lastTelemetryTime     = 0L
    private var lastWriteTime         = 0L
    private var batteryTempSum        = 0.0
    private var batteryTempSamples    = 0

    // Running "best known" distance in km, computed per tick as the max of the
    // odometer delta and a speed-integrated distance. Survives periods where
    // car is offline and reports odometer=0 (commonly seen after the
    // car has parked and on the first telemetry ticks post-resume). Used as an
    // end-odometer fallback so a trip closed immediately after a resume does
    // not collapse to the pre-off distance.
    private var tripBestDistanceKm    = 0.0

    // Running max of telemetry.totalDischarge seen during the active trip. Same
    // motivation as tripBestDistanceKm but for energy accounting: when the final
    // telemetry/data-point reads for discharge come back stale (0 or still at
    // trip.startTotalDischarge), this gives doEndTrip a non-stale value that was
    // actually observed mid-trip. Absolute kWh value, not a delta.
    private var tripBestTotalDischarge = 0.0
    // Running sum of max(0, enginePower) × dt over the active trip, in kWh.
    // Serves as a power-integrated floor / fallback for end discharge: when
    // the car under-reports totalDischarge for long stretches, this integral
    // still reflects the energy the car actually pulled from the pack
    // tick-by-tick. Also used as the "energy actually consumed so far"
    // estimate for the per-tick stale-zero anchor correction below.
    private var tripIntegratedDischargeKwh = 0.0
    // Net energy consumed, accumulated tick-by-tick across the trip from BYD's
    // getTotalElecConValue. Required because that counter resets at every car-on
    // cycle on many firmwares (notably PHEV and the Seal Excellence) — so a trip
    // spanning an engine-off pause runs 0→4 in segment 1, 0→2 in segment 2, etc.,
    // and a naive end−start would only capture one segment.
    //
    // Within a segment the counter moves UP on discharge and DOWN on regen, so we
    // sum BOTH signs to get NET consumption (matching the car's own kWh/100km and
    // reference apps). We previously summed only positive deltas, which silently
    // discarded all regen and over-reported energy by the regen amount (large on
    // hilly trips). The car-on counter reset is handled separately by dropping the
    // anchor at the off→on transition (live path) / across segment-gap boundaries
    // (recovery path), so the only large negative deltas left are sensor glitches,
    // rejected by the ±50 kWh/tick cap.
    private var tripCumulativeBmsDeltaKwh = 0.0
    private var lastTripTotalDischarge: Double? = null
    // Running min of telemetry.soc seen during the active trip. Pairs with the
    // discharge running-max so endSoc can fall back to the lowest mid-trip SoC
    // when the final reads are stale.
    private var tripMinSoc             = Double.MAX_VALUE

    // Default TRUE — auto-recording works immediately after install without
    // requiring the user to open the app and toggle the switch first.
    // @Volatile: read from the event-processor coroutine, written from the UI thread
    // via setAutoTripDetection. Without volatile the processor may read a stale value.
    @Volatile private var autoTripDetection = prefs.getBoolean(PREF_AUTO_TRIP, true)

    // Manual stop grace window. After the user explicitly stops a trip, suppress
    // auto-start until the cooldown expires — otherwise the very next telemetry tick
    // (car still in D, still moving) would re-open a fresh trip and the user
    // perceives the stop as having had no effect.
    @Volatile private var manualStopCooldownUntilMs: Long = 0L
    private val MANUAL_STOP_COOLDOWN_MS = 60_000L

    // ── Car-off continuation window ────────────────────────────────────────────
    // When the car turns off we do NOT end the trip immediately — the driver may
    // just be stopped briefly (coffee stop, red light, parking briefly).
    // Instead we start a timer and keep the trip open for a continuation window.
    // Timestamp (ms) when the car turned off during an active trip.
    // 0L means the car is currently on (or no active trip).
    private var carOffSinceMs: Long = 0L

    // Set true when the user taps "Keep recording" on the auto-stop prompt: the
    // current car-off window is held open and won't auto-stop or re-prompt. Cleared
    // when the car comes back on (window over) or the trip ends. In-memory only —
    // a process death falls back to the normal stale-trip recovery on next start.
    private var keepCurrentOffWindow: Boolean = false

    // Last car-off stop action written to diag.log. The evaluation runs on every telemetry
    // tick, and a KEEP_HELD / HOLD_FOR_CONFIRM state can stand for the full 30-minute cap —
    // so only transitions are recorded, not one line per tick. Cleared when the off-window
    // closes (engine back on) and on trip reset, so the next park logs afresh.
    private var lastLoggedCarOffAction: CarOffStopAction? = null

    // Safety cap: even a "kept" trip is auto-ended once the car has been off this
    // long continuously, so a forgotten trip can't linger. Nobody parks with the
    // engine on for this long, so 30 min is a safe ceiling. The trip's end time is
    // still anchored at car-off + timeout, so the parked time isn't counted.
    // NOTE: the prompt fires at the car-off TIMEOUT (Settings, default 3 min), not
    // at this cap — to test the prompt quickly, lower the timeout, not this value.
    // decideCarOffStop floors the effective cap above the timeout regardless.
    private val MAX_KEPT_OFF_MS = 30 * 60 * 1000L   // 30 minutes

    // Hard timeout after engine-off before the trip is automatically ended.
    // User-configurable in Settings → Preferences (default 30 min). Read from
    // the synchronous SharedPreferences cache on every tick so a change applies
    // to the current trip without having to restart the service.
    private fun carOffTimeoutMs(): Long =
        prefsManager.getCachedCarOffTimeoutMinutes() * 60_000L

    /**
     * Decides what to do when an active trip has been car-off past the timeout.
     * Extracted so the live telemetry handler and the watchdog share identical
     * logic. End semantics (overrideEndTime = car-off + timeout) match the
     * pre-existing silent auto-stop exactly, so trip stats are unchanged.
     */
    private suspend fun evaluateCarOffStop(now: Long) {
        if (carOffSinceMs <= 0L) return
        val timeoutMs = carOffTimeoutMs()
        val confirmEnabled = prefsManager.getCachedConfirmBeforeAutoStop()
        val action = decideCarOffStop(
            offDurationMs  = now - carOffSinceMs,
            timeoutMs      = timeoutMs,
            maxKeptMs      = MAX_KEPT_OFF_MS,
            userKept       = keepCurrentOffWindow,
            confirmEnabled = confirmEnabled,
            uiVisible      = uiVisible
        )
        if (action != CarOffStopAction.WITHIN_WINDOW && action != lastLoggedCarOffAction) {
            lastLoggedCarOffAction = action
            // Persistent, not just logcat: on DiLink-5 this is the last thing the app manages to
            // record before the OEM force-stop, and its absence is itself the finding — it means
            // the app was already dead when the engine-off timeout came due.
            DiagLog.event(
                appContext, TAG,
                "car-off stop: $action (off=${(now - carOffSinceMs) / 1000}s " +
                    "timeout=${timeoutMs / 1000}s uiVisible=$uiVisible confirm=$confirmEnabled " +
                    "kept=$keepCurrentOffWindow)"
            )
        }
        when (action) {
            CarOffStopAction.WITHIN_WINDOW,
            CarOffStopAction.KEEP_HELD     -> _pendingAutoStop.value = false
            CarOffStopAction.HOLD_FOR_CONFIRM -> _pendingAutoStop.value = true
            CarOffStopAction.END -> {
                _pendingAutoStop.value = false
                // End where the silent auto-stop always did: car-off + timeout, so
                // the parked time isn't counted and stats are unchanged.
                doEndTrip(reason = "car-off-timeout", overrideEndTime = carOffSinceMs + timeoutMs)
            }
        }
    }

    // ── Timing constants ──────────────────────────────────────────────────────

    // 8 min — survives the ~8s service restart gap without closing the trip.
    private val TELEMETRY_TIMEOUT_MS = 8 * 60 * 1000L

    // Flush a segment every 30 s. At 50 km/h ≈ 417 m per endpoint pair —
    // accurate enough for route display; RDP compression smooths it at end.
    private val SEGMENT_FLUSH_MS = 30_000L

    // Data-point heartbeat raised to 10 s because GPS is owned by segments now.
    private val WRITE_INTERVAL_MS = 10_000L

    // ── Discharge-anchor sanity bounds ────────────────────────────────────────
    // A single trip cannot consume more than a couple of full battery's worth
    // of energy (no opportunity to recharge mid-trip), so any observed delta
    // of (telemetry.totalDischarge − trip.startTotalDischarge) above this
    // multiple of the pack capacity points at a corrupted anchor — typically
    // a stale 0 read from the BMS at the moment of trip start, later replaced
    // by the true lifetime-cumulative value (often in the thousands of kWh).
    private val MAX_PLAUSIBLE_TRIP_BATTERIES = 2.0
    // Fallback pack capacity used when no car is selected yet. Generous so
    // the sanity check is permissive rather than over-eager.
    private val FALLBACK_BATTERY_KWH = 100.0

    private val DRIVE_GEARS = setOf("D", "R")

    private fun validBatteryTemp(temp: Double): Double? =
        temp.takeIf { it > 0.0 }

    private fun validCellTemp(temp: Int): Int? =
        temp.takeIf { it > 0 }

    /**
     * Usable pack capacity in kWh for sanity bounds. For PHEVs the EV-only
     * portion is preferred since totalDischarge tracks only the HV-pack
     * traction draw. Returns a generous fallback when no car config is loaded.
     */
    private fun resolveBatteryKwh(): Double {
        val car = prefsManager.getCachedSelectedCarConfig() ?: return FALLBACK_BATTERY_KWH
        return if (car.isPhev) car.phevUsableBatteryKwh ?: car.batteryKwh else car.batteryKwh
    }

    /**
     * Detect and correct a corrupted [TripEntity.startTotalDischarge] anchor
     * the moment the BMS produces a plausible read.
     *
     * The known failure mode: at trip start the BMS may briefly report
     * `totalDischarge = 0` (stale) — the trip is created with
     * `startTotalDischarge = 0`, and as soon as the BMS publishes the real
     * lifetime-cumulative value (often several thousand kWh), every downstream
     * `end − start` calculation balloons by that amount. The same logic also
     * covers any other case where the stored anchor turns out to be
     * implausibly lower than what the BMS is now reporting.
     *
     * We re-anchor only when the observed delta exceeds both
     *   • [MAX_PLAUSIBLE_TRIP_BATTERIES] × pack capacity, and
     *   • the power-integrated estimate of what we've actually drawn so far
     *     (with a small absolute-kWh buffer)
     *
     * Using `t.totalDischarge − tripIntegratedDischargeKwh` as the corrected
     * anchor preserves the real trip-energy-so-far that we already integrated
     * tick-by-tick from enginePower × dt; it doesn't reset progress.
     */
    private suspend fun correctStaleDischargeAnchorIfNeeded(t: VehicleTelemetry) {
        val trip = cachedCurrentTrip ?: return
        if (!t.totalDischarge.isFinite() || t.totalDischarge <= 0.0) return

        val observedDelta = t.totalDischarge - trip.startTotalDischarge
        if (!observedDelta.isFinite() || observedDelta <= 0.0) return

        val batteryKwh = resolveBatteryKwh()
        val implausibleByCapacity = observedDelta > batteryKwh * MAX_PLAUSIBLE_TRIP_BATTERIES
        val implausibleVsIntegrated = observedDelta > tripIntegratedDischargeKwh + 5.0

        if (implausibleByCapacity && implausibleVsIntegrated) {
            val correctedAnchor = (t.totalDischarge - tripIntegratedDischargeKwh)
                .coerceAtLeast(0.0)
            val updated = trip.copy(startTotalDischarge = correctedAnchor)
            tripDao.updateTrip(updated)
            cachedCurrentTrip = updated
            // Re-seed the running-max so its "advanced past start" check stays
            // meaningful with the new anchor.
            if (t.totalDischarge > tripBestTotalDischarge) {
                tripBestTotalDischarge = t.totalDischarge
            }
            Log.w(
                TAG,
                "Corrected implausible startTotalDischarge: " +
                    "${trip.startTotalDischarge} → $correctedAnchor " +
                    "(observed=${t.totalDischarge}, integratedSoFar=$tripIntegratedDischargeKwh, " +
                    "batteryKwh=$batteryKwh)"
            )
        }
    }

    private fun effectiveSpeedKmh(t: VehicleTelemetry): Double =
        maxOf(t.speed, t.locationGpsSpeed ?: 0.0)

    private fun shouldBackAnchorTripStart(
        previousTelemetry: VehicleTelemetry?,
        currentTelemetry: VehicleTelemetry? = null
    ): Boolean {
        if (previousTelemetry == null) {
            // Fresh service start after idle: back-anchor if the first packet itself
            // shows active driving AND a non-trivial journey counter. This covers the
            // case where the service was idle while the user was already driving and
            // opens the app mid-trip. The staleness guard in doStartTrip is still the
            // safety net for wild counter values.
            val cur = currentTelemetry ?: return false
            val journeyKm = cur.currentJourneyDriveMileage ?: return false
            return journeyKm > 0.5 &&
                (cur.gear in DRIVE_GEARS ||
                    effectiveSpeedKmh(cur) > 2.0 ||
                    kotlin.math.abs(cur.enginePower) > 5)
        }
        return previousTelemetry.gear in DRIVE_GEARS ||
            effectiveSpeedKmh(previousTelemetry) > 2.0 ||
            kotlin.math.abs(previousTelemetry.enginePower) > 5 ||
            previousTelemetry.engineSpeedFront > 0 ||
            previousTelemetry.engineSpeedRear > 0
    }

    // ── Segment builder ───────────────────────────────────────────────────────

    private data class SegmentBuilder(
        val tripId:              Long,
        val startTime:           Long,
        val startLat:            Double?,
        val startLon:            Double?,
        val startOdometer:       Double,
        val startTotalDischarge: Double,
        // Snapshot of the trip-level integrated draw at the moment the segment
        // opened. Combined with the trip-level integral at flush time, this gives
        // a per-segment power-integrated kWh estimate that is robust to a stale
        // totalDischarge anchor on the segment itself (a 30-second window is too
        // short for the per-tick anchor correction to recover in time).
        val tripIntegratedKwhAtOpen: Double,
        var endTime:             Long,
        var endLat:              Double?,
        var endLon:              Double?,
        var speedSum:            Double = 0.0,
        var powerSum:            Double = 0.0,
        var samples:             Int    = 0
    )

    private var currentSegment: SegmentBuilder? = null

    // ── Initialisation ────────────────────────────────────────────────────────

    init {
        restoreCachedTelemetry()

        // Start the single processor coroutine — this is the only place that
        // ever reads or writes trip state.
        scope.launch {
            for (event in tripEvents) {
                try {
                    handleEvent(event)
                } catch (e: Exception) {
                    Log.e(TAG, "Unhandled error processing $event", e)
                    // Continue processing — a bad event should never kill the loop.
                }
            }
        }

        // Inject recovery as the first event so it runs before any telemetry.
        tripEvents.trySend(TripEvent.Recover)

        startWatchdog()

        // Backfill stats for any completed trips that are missing a trip_stats
        // row — these arise when calculateTripStats threw or returned early.
        scope.launch(Dispatchers.IO) {
            try {
                val missing = tripDao.getCompletedTripsWithoutStats()
                if (missing.isNotEmpty()) {
                    Log.i(TAG, "Backfilling stats for ${missing.size} trips without stats rows")
                    missing.forEach { calculateTripStats(it.id) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Stats backfill failed: ${e.message}")
            }
        }

        // One-shot backfill of offStateDurationMs for trips created before the
        // 2.5.0 schema bump. Walks every completed trip with offStateDurationMs=0
        // (the default after the v3→v4 migration) and recomputes from its data
        // points. Gated by a SharedPreferences flag so it only runs once.
        //
        // Deferred 30 s after start so the critical-path init work (TripEvent.Recover,
        // first telemetry processing, any auto-start of an in-progress trip) finishes
        // first. Otherwise, on a DB with many trips, this loop's writes could hold the
        // SQLite write lock long enough that doStartTrip's insert queued behind it,
        // delaying the visible "trip started" state by the duration of the backfill.
        scope.launch(Dispatchers.IO) {
            try {
                kotlinx.coroutines.delay(30_000L)
                if (!prefs.getBoolean(PREF_OFFSTATE_BACKFILL_DONE, false)) {
                    backfillOffStateDuration()
                    prefs.edit()
                        .putBoolean(PREF_OFFSTATE_BACKFILL_DONE, true)
                        .apply()
                }
                // One-shot repair of trips corrupted by an earlier thinning +
                // off-state-recompute interaction (impossible avg speeds). Runs once,
                // after the backfill, so any backfill output is included.
                if (!prefs.getBoolean(PREF_DURATION_REPAIR_DONE, false)) {
                    repairImplausibleDurations()
                    prefs.edit()
                        .putBoolean(PREF_DURATION_REPAIR_DONE, true)
                        .apply()
                }
                // One-shot repair of trips whose panel-SoC pair collapsed to a flat
                // "x% → x%" when they were closed from the cold-start recovery path
                // (routine on DiLink-5). Only rows with that exact signature are read.
                if (!prefs.getBoolean(PREF_FLAT_END_SOC_PANEL_REPAIR_DONE, false)) {
                    repairFlatEndSocPanel()
                    prefs.edit()
                        .putBoolean(PREF_FLAT_END_SOC_PANEL_REPAIR_DONE, true)
                        .apply()
                }
            } catch (e: Exception) {
                Log.w(TAG, "offStateDuration backfill / repair failed: ${e.message}")
            }
        }
    }

    /**
     * Walks every completed trip in the DB and back-fills [TripEntity.offStateDurationMs]
     * from the trip's data points using [computeOffStateDurationMs]. Idempotent: a
     * second run produces the same values.
     *
     * Important: this used to also call [calculateTripStats] on every updated trip so
     * the persisted avg-speed reflected the corrected duration. That made the backfill
     * heavy enough to monopolise the SQLite write lock for minutes on a large DB,
     * which blocked the trip event processor's [doStartTrip] insert and made the
     * dashboard show "Waiting for Trip..." for ~1.5 km after starting a new drive.
     *
     * Now: only update the `offStateDurationMs` column. [TripEntity.duration]'s getter
     * subtracts it on read, so every UI surface that reads `trip.duration` (Trip Detail,
     * exports, comparisons, sort) gets the corrected value automatically. The History
     * list's avg-speed column reads from [TripStatsEntity.avgSpeed] and will continue
     * to show the legacy wallclock-based value until the trip is opened (or stats are
     * manually rebuilt) — acceptable trade-off for a non-blocking app start.
     *
     * Also yields between iterations so a concurrent trip insert isn't starved if the
     * DB happens to be busy.
     */
    private suspend fun backfillOffStateDuration() {
        val all = tripDao.getCompletedTripsBefore(Long.MAX_VALUE)
        if (all.isEmpty()) return
        var updated = 0
        for (trip in all) {
            val points = dataPointDao.getDataPointsForTripSync(trip.id)
            val off = computeOffStateDurationMs(points, trip.endTime)
            // Guard against thinned trips: once a trip's data points have been
            // down-sampled (manual trimmer → 1 point/min), the gaps between
            // retained points exceed OFFSTATE_GAP_THRESHOLD_MS purely because of
            // reduced density, and computeOffStateDurationMs mistakes that thinned
            // driving for engine-off time. The result is an off-state spanning
            // almost the whole trip → duration collapses → avg speed explodes.
            // Reject any recompute that would imply a physically impossible average
            // speed (> 200 km/h); keep the trip's existing (close-time) value.
            val implausible = run {
                val end  = trip.endTime ?: return@run false
                val dist = trip.distance ?: return@run false
                if (dist <= 0.0) return@run false
                val durMs = (end - trip.startTime - off).coerceAtLeast(0L)
                durMs <= 0L || dist / (durMs / 3_600_000.0) > 200.0
            }
            if (off != trip.offStateDurationMs && !implausible) {
                val correctedTrip = trip.copy(offStateDurationMs = off)
                tripDao.updateTrip(correctedTrip)
                // Lightweight stats touch: just refresh totalDuration / avgSpeed so the
                // History list and Trip Detail show the corrected numbers. We do NOT
                // call calculateTripStats() here — that's what made the previous version
                // of this backfill monopolise the SQLite write lock and starve the trip
                // event processor's doStartTrip insert. A column-only update is cheap.
                val existing = statsDao.getStatsForTrip(trip.id)
                if (existing != null) {
                    val durationMs = correctedTrip.duration ?: 0L
                    val distance = correctedTrip.distance ?: 0.0
                    val newAvgSpeed = if (durationMs > 0L && distance > 0.0)
                        distance / (durationMs / 3_600_000.0)
                    else existing.avgSpeed
                    statsDao.updateStats(existing.copy(
                        totalDuration = durationMs,
                        avgSpeed = newAvgSpeed
                    ))
                }
                updated++
            }
            kotlinx.coroutines.yield()
        }
        Log.i(TAG, "offStateDuration backfill: updated $updated / ${all.size} trips")
    }

    /**
     * One-shot repair for trips whose stored duration was corrupted by the
     * thinning + off-state-recompute interaction (see [backfillOffStateDuration]'s
     * guard): an inflated offStateDurationMs collapsed the trip's duration toward
     * zero, producing an impossible average speed (e.g. 11 000 km/h).
     *
     * For each completed trip whose *stored* duration implies > 200 km/h, we
     * re-estimate the real driving time from the span of the retained data points
     * (first-to-last timestamp — this survives thinning and reflects actual elapsed
     * time), fall back to the wallclock span if needed, then rewrite
     * offStateDurationMs (so [TripEntity.duration] is correct) and the stored
     * avgSpeed. Trips that are already plausible are left untouched.
     */
    private suspend fun repairImplausibleDurations() {
        val all = tripDao.getCompletedTripsBefore(Long.MAX_VALUE)
        if (all.isEmpty()) return
        var fixed = 0
        for (trip in all) {
            val end  = trip.endTime ?: continue
            val dist = trip.distance ?: continue
            if (dist <= 0.0) continue

            val storedDurMs = (end - trip.startTime - trip.offStateDurationMs).coerceAtLeast(0L)
            val storedImplausible =
                storedDurMs <= 0L || dist / (storedDurMs / 3_600_000.0) > 200.0
            if (!storedImplausible) continue

            val points     = dataPointDao.getDataPointsForTripSync(trip.id)
            val wallclock  = (end - trip.startTime).coerceAtLeast(0L)
            val pointsSpan = if (points.size >= 2)
                (points.last().timestamp - points.first().timestamp).coerceAtLeast(0L) else 0L

            fun plausible(durMs: Long) = durMs > 0L && dist / (durMs / 3_600_000.0) <= 200.0
            val candidate = when {
                plausible(pointsSpan) -> pointsSpan      // best: real recorded span
                plausible(wallclock)  -> wallclock       // fallback: full wallclock
                else                  -> 0L              // unrecoverable — leave as-is
            }
            if (candidate <= 0L) continue

            val newOff = (wallclock - candidate).coerceAtLeast(0L)
            tripDao.updateTrip(trip.copy(offStateDurationMs = newOff))
            statsDao.getStatsForTrip(trip.id)?.let { stats ->
                statsDao.updateStats(stats.copy(
                    totalDuration = candidate,
                    avgSpeed      = dist / (candidate / 3_600_000.0)
                ))
            }
            fixed++
            kotlinx.coroutines.yield()
        }
        Log.i(TAG, "duration repair: fixed $fixed / ${all.size} trips")
    }

    /**
     * One-shot repair (2.15.1) for trips whose stored [TripEntity.endSocPanel] collapsed
     * onto [TripEntity.startSocPanel], showing a flat "80% → 80%" in the history list while
     * the trip's own data points held the real decline all along.
     *
     * Cause: when a trip was closed from the cold-start recovery path, [doEndTrip] had no
     * live telemetry (fresh process) and its data-point fallback was suppressed by the
     * caller's odometer override, so the panel-SoC chain had no source and fell through to
     * `?: trip.startSocPanel`. That is the *normal* close path on DiLink-5, where the OEM
     * force-stops the app at ignition-off, which is why the flat pair shows up there
     * routinely; DiLink-3 closes trips in-process and only lands here if it happened to be
     * killed mid-trip. Fixed forward by the `overrideSocPanel` argument.
     *
     * Safety — this must never rewrite a correct value, on either head unit:
     *  • only rows carrying the exact corruption signature are even read
     *    ([TripDao.getTripsWithFlatPanelSoc]: completed, endSocPanel non-null and equal to
     *    startSocPanel, panel data present);
     *  • a genuinely flat trip (a short drive where the integer panel SoC really didn't
     *    move) resolves to a candidate equal to startSocPanel and is skipped, so DiLink-3
     *    history — where flat pairs are usually real — is left untouched;
     *  • the candidate is the same value the fixed close path would now write: the last
     *    recorded non-zero panel reading of that trip.
     *
     * endSocPanel is display-only (history card, trip detail, comparison, web/export) — no
     * stats row or energy/cost ledger derives from it — so this is a plain column rewrite.
     * Idempotent: a second run finds candidate == stored value and writes nothing.
     */
    private suspend fun repairFlatEndSocPanel() {
        val candidates = tripDao.getTripsWithFlatPanelSoc()
        if (candidates.isEmpty()) return
        var fixed = 0
        for (trip in candidates) {
            val lastPanelSoc = dataPointDao.getLastPanelSocForTrip(trip.id)?.toDouble() ?: continue
            if (lastPanelSoc !in 1.0..100.0) continue
            if (lastPanelSoc == trip.startSocPanel) continue   // genuinely flat — leave alone
            tripDao.updateTrip(trip.copy(endSocPanel = lastPanelSoc))
            fixed++
            kotlinx.coroutines.yield()
        }
        DiagLog.event(
            appContext, TAG,
            "flat endSocPanel repair: fixed $fixed / ${candidates.size} candidate trips"
        )
    }

    // ── Event dispatcher ──────────────────────────────────────────────────────

    private suspend fun handleEvent(event: TripEvent) {
        when (event) {
            is TripEvent.Telemetry   -> handleTelemetry(event.data)
            TripEvent.ManualStart    -> handleManualStart()
            TripEvent.ManualStop     -> handleManualStop()
            TripEvent.KeepTripAcrossOff -> handleKeepTripAcrossOff()
            TripEvent.ConfirmAutoStop   -> handleConfirmAutoStop()
            TripEvent.WatchdogTick   -> handleWatchdogTick()
            TripEvent.Recover        -> recoverActiveTrip()
        }
    }

    // ── Handlers ──────────────────────────────────────────────────────────────

    private suspend fun handleTelemetry(t: VehicleTelemetry) {
        val now = System.currentTimeMillis()

        // Always broadcast for UI, regardless of trip state
        _latestTelemetry.value = t

        when (tripState) {

            TripState.IDLE -> {
                val inCooldown = now < manualStopCooldownUntilMs
                val previousTelemetry = lastTelemetry
                if (!inCooldown && autoTripDetection && shouldAutoStart(t, previousTelemetry)) {
                    val backAnchorToJourney = shouldBackAnchorTripStart(previousTelemetry, t)
                    // Assign lastTelemetry AFTER the check so odometer
                    // comparison uses the actual previous packet.
                    Log.i(TAG, "Auto-detect: movement → starting trip")
                    lastTelemetry     = t
                    lastTelemetryTime = now
                    doStartTrip(
                        telemetry = t,
                        isManual = false,
                        backAnchorToJourney = backAnchorToJourney
                    )
                } else {
                    lastTelemetry     = t
                    lastTelemetryTime = now
                }
            }

            TripState.ACTIVE -> {
                // ── Engine-off handling ───────────────────────────────────────
                // Requirements:
                //   • Engine on in P gear → keep recording (driver waiting/parked)
                //   • Driver exits with engine on → keep recording
                //   • Engine off, returns within the configured window → resume seamlessly
                //   • Engine off > configured window → end trip
                // The current implementation is intentionally simple:
                //   • first off packet starts a timer
                //   • any on packet before timeout resumes the same trip
                //   • off for longer than carOffTimeoutMs() ends the trip
                // Always update min/max stats — the car can be moving while
                // isCarOn is false in edge cases (slow speed, gear=P, etc.).
                updateTripMetrics(t)

                if (!t.isCarOn) {
                    if (carOffSinceMs == 0L) {
                        carOffSinceMs = resolveCarOffSince(now)
                        Log.i(TAG, "Engine OFF — will end trip in ${carOffTimeoutMs() / 60_000} min if not restarted")

                        // Persist the off-transition so recoverActiveTrip can preserve
                        // carOffSinceMs across process death. Without this, a process
                        // kill during the off window loses the already-accrued off time
                        // and the 30-min watchdog effectively restarts on the next off.
                        //
                        // Car often goes offline at the moment of
                        // car-off and returns odometer/discharge/soc as 0. Copy the
                        // last known-good values so this point doesn't skew
                        // calculateTripStats' per-pair deltas with a stale 0.
                        _currentTripId.value?.let { tripId ->
                            val lastGood = lastRecordedTelemetry
                            val startDischarge = cachedCurrentTrip?.startTotalDischarge ?: 0.0
                            val odoOverride = if (t.odometer > 0.0) null
                                              else lastGood?.odometer
                            val dischargeOverride = if (t.totalDischarge > 0.0 && t.totalDischarge >= startDischarge) null
                                                    else lastGood?.totalDischarge
                            val socOverride = if (t.soc > 0.0) null else lastGood?.soc
                            recordDataPoint(
                                tripId, t,
                                overrideOdometer       = odoOverride,
                                overrideTotalDischarge = dischargeOverride,
                                overrideSoc            = socOverride
                            )
                            lastRecordedTelemetry = t
                            lastWriteTime = now
                        }
                    }
                    // Decide whether to end now, hold for confirmation, or honour a
                    // user "keep". evaluateCarOffStop preserves the legacy end timing.
                    evaluateCarOffStop(now)
                    lastTelemetry     = t
                    lastTelemetryTime = now
                    return
                }

                // Engine came back on — continue trip seamlessly
                if (carOffSinceMs > 0L) {
                    Log.i(TAG, "Engine back ON after ${(now - carOffSinceMs) / 1000}s — continuing trip")
                    carOffSinceMs = 0L
                    // The car-off window is over — drop any held auto-stop prompt and the
                    // user's "keep" flag so a future stop is evaluated fresh.
                    keepCurrentOffWindow = false
                    lastLoggedCarOffAction = null
                    _pendingAutoStop.value = false
                    // getTotalElecConValue resets to ~0 at car-on on resetting firmwares,
                    // so the first post-resume delta would be a large negative jump. Drop
                    // the energy anchor so that one delta is skipped (the next tick re-anchors);
                    // in-segment regen is still credited by the net accumulator below.
                    lastTripTotalDischarge = null
                }

                // Extend the best-known distance. Odometer delta is ground truth
                // when fresh, but car can be slow to come back online
                // after a resume and return odometer=0 for several ticks while the
                // car is actually driving — speed integration bridges that gap.
                val startOdo = cachedCurrentTrip?.startOdometer
                if (startOdo != null) {
                    val dtSec = if (lastTelemetryTime > 0L)
                        (now - lastTelemetryTime) / 1000.0 else 0.0
                    if (dtSec in 0.1..10.0) {
                        val speedKmh = maxOf(t.speed, t.locationGpsSpeed ?: 0.0)
                        if (speedKmh > 0.0) {
                            tripBestDistanceKm += speedKmh * dtSec / 3600.0
                        }
                    }
                    val odoDelta = t.odometer - startOdo
                    if (odoDelta.isFinite() && odoDelta > tripBestDistanceKm) {
                        tripBestDistanceKm = odoDelta
                    }
                }

                // Running max/min for energy accounting. Only advance on readings
                // that are plausibly non-stale — a 0 is always stale, and discharge
                // must have advanced past the trip start.
                if (t.totalDischarge.isFinite() &&
                    t.totalDischarge > 0.0 &&
                    t.totalDischarge > tripBestTotalDischarge) {
                    tripBestTotalDischarge = t.totalDischarge
                }
                // Net per-segment BMS delta — see field declaration. Both signs are
                // summed: positive = discharge, negative = regen. The car-on counter
                // reset is handled by dropping the anchor in the off→on branch above,
                // so the only deltas reaching here are within-segment. The ±50 kWh/tick
                // cap rejects sensor glitches (a single tick can't physically move that).
                if (t.totalDischarge.isFinite() && t.totalDischarge >= 0.0) {
                    val prev = lastTripTotalDischarge
                    if (prev != null) {
                        val delta = t.totalDischarge - prev
                        if (delta > -50.0 && delta < 50.0) {
                            tripCumulativeBmsDeltaKwh += delta
                        }
                    }
                    lastTripTotalDischarge = t.totalDischarge
                }
                if (t.soc.isFinite() && t.soc > 0.0 && t.soc < tripMinSoc) {
                    tripMinSoc = t.soc
                }
                // Power × dt integrated draw. Same gap-guard as distance to reject
                // backgrounded/stalled ticks that would otherwise inflate the integral.
                if (lastTelemetryTime > 0L) {
                    val dtH = (now - lastTelemetryTime) / 3_600_000.0
                    if (dtH in 0.0..(10.0 / 3600.0) && t.enginePower > 0) {
                        tripIntegratedDischargeKwh += t.enginePower * dtH
                    }
                }

                // After updating the integrated estimate, check whether the BMS now
                // disagrees with us so strongly that the trip-start anchor must have
                // been a stale 0. Re-anchor in that case so the eventual
                // (end − start) delta and every downstream "energy consumed" value
                // stays bounded by physics.
                correctStaleDischargeAnchorIfNeeded(t)

                // Gap check: if car is now on but there was a long unexplained
                // telemetry gap (service crashed while car was driving), end the
                // stale trip and let shouldAutoStart open a new one.
                // Use carOffTimeoutMs() so a normal stop+restart within the configured
                // window is never falsely closed here.
                if (lastTelemetryTime > 0 && now - lastTelemetryTime > carOffTimeoutMs()) {
                    Log.w(TAG, "Long gap (${(now - lastTelemetryTime) / 60_000} min) while car on → ending stale trip")
                    doEndTrip(reason = "long-gap-while-on", overrideEndTime = lastTelemetryTime)
                    // Re-evaluate this same packet for a new auto-start rather than
                    // discarding it — avoids a one-packet hole after stale-trip close.
                    val inCooldown = now < manualStopCooldownUntilMs
                    if (!inCooldown && autoTripDetection && shouldAutoStart(t, null)) {
                        Log.i(TAG, "Immediately re-starting trip after stale close")
                        lastTelemetry     = t
                        lastTelemetryTime = now
                        doStartTrip(
                            telemetry = t,
                            isManual = false,
                            backAnchorToJourney = true,
                            maxBackdateMs = carOffTimeoutMs()
                        )
                        return
                    }
                    lastTelemetry     = t
                    lastTelemetryTime = now
                    return
                }

                // Feed into segment accumulator on every packet
                updateSegment(t)

                // Flush segment on gear change or time boundary
                val gearChanged = lastTelemetry?.gear != null && t.gear != lastTelemetry?.gear
                val segmentAge  = now - (currentSegment?.startTime ?: now)
                if (gearChanged || segmentAge >= SEGMENT_FLUSH_MS) {
                    flushSegment(endTelemetry = t)
                }

                // Throttled data-point write
                if (shouldRecordDataPoint(t, now)) {
                    _currentTripId.value?.let { tripId ->
                        recordDataPoint(tripId, t)
                        lastRecordedTelemetry = t
                        lastWriteTime = now
                    }
                }

                lastTelemetry     = t
                lastTelemetryTime = now
            }
        }
    }

    private suspend fun handleManualStart() {
        val t = lastTelemetry ?: run {
            Log.w(TAG, "ManualStart ignored — no telemetry yet")
            return
        }
        // If an auto-trip is already active when the user taps Manual Start,
        // close it and open a fresh manual trip. Previously we silently ignored
        // the request, which left the user with an auto trip (and a confusing
        // UX where the manual-stop button would end it, but the saved trip
        // looked like a manual one with stale bounds).
        if (tripState == TripState.ACTIVE) {
            Log.i(TAG, "Manual start requested while auto trip active — closing auto trip first")
            doEndTrip(reason = "manual-start-replaces-auto")
        }
        Log.i(TAG, "Manual start requested")
        doStartTrip(
            telemetry = t,
            isManual = true,
            backAnchorToJourney = false
        )
    }

    private suspend fun handleManualStop() {
        // Arm the cooldown regardless of state — a user Stop press means "don't
        // auto-open a new trip for the next minute", even if we were already IDLE.
        manualStopCooldownUntilMs = System.currentTimeMillis() + MANUAL_STOP_COOLDOWN_MS
        if (tripState == TripState.IDLE) {
            Log.w(TAG, "ManualStop ignored — no active trip (cooldown armed)")
            return
        }
        Log.i(TAG, "Manual stop requested")
        doEndTrip(reason = "manual-stop")
    }

    /** User tapped "Keep recording" on the auto-stop prompt. */
    private fun handleKeepTripAcrossOff() {
        if (tripState != TripState.ACTIVE || carOffSinceMs <= 0L) {
            _pendingAutoStop.value = false
            return
        }
        Log.i(TAG, "Auto-stop prompt: user kept the trip across the car-off window")
        keepCurrentOffWindow = true
        _pendingAutoStop.value = false
    }

    /** User tapped "Stop" on the auto-stop prompt — finalise the held auto-stop. */
    private suspend fun handleConfirmAutoStop() {
        _pendingAutoStop.value = false
        if (tripState != TripState.ACTIVE) return
        // End exactly where the silent auto-stop would have: car-off + timeout.
        val endAt = if (carOffSinceMs > 0L) carOffSinceMs + carOffTimeoutMs() else null
        Log.i(TAG, "Auto-stop prompt: user confirmed stop")
        manualStopCooldownUntilMs = System.currentTimeMillis() + MANUAL_STOP_COOLDOWN_MS
        doEndTrip(reason = "auto-stop-confirmed", overrideEndTime = endAt)
    }

    private suspend fun handleWatchdogTick() {
        if (tripState != TripState.ACTIVE) return
        val now = System.currentTimeMillis()
        if (carOffSinceMs > 0L) {
            evaluateCarOffStop(now)
            return
        }

        val silence = now - lastTelemetryTime
        if (silence > TELEMETRY_TIMEOUT_MS) {
            Log.w(TAG, "Watchdog: ${silence / 1000}s silence → ending trip")
            doEndTrip(reason = "watchdog-silence", overrideEndTime = lastTelemetryTime)
        }
    }

    /**
     * Runs as the first event on cold start. If a trip row is still marked
     * active, either resume it (last data point is recent) or close it using
     * the last DB-persisted values so distance / energy are not zeroed out.
     */
    private suspend fun recoverActiveTrip() {
        val activeTrip = tripDao.getActiveTrip() ?: return
        val dataPoints = dataPointDao.getDataPointsForTripSync(activeTrip.id)
        val lastPoint  = dataPoints.lastOrNull()

        val now  = System.currentTimeMillis()
        val gap  = if (lastPoint != null) now - lastPoint.timestamp
                   else                   now - activeTrip.startTime
        // Match TELEMETRY_TIMEOUT_MS exactly — a trip with a gap large enough
        // to trigger the in-loop timeout should be closed here upfront, not
        // resumed and then immediately closed on the first telemetry packet.
        // A recovered trip is stale only if the gap exceeds the configured
        // engine-off timeout. This matches the in-flight logic: engine-off
        // for less than the timeout means the trip continues.
        val STALE_THRESHOLD = carOffTimeoutMs()
        val storedOffSince = trailingStoredCarOffStart(dataPoints)
        val staleBecauseCarOff = storedOffSince != null && now - storedOffSince > STALE_THRESHOLD

        // Seed best-known distance / discharge / min-SoC from recorded data
        // points so a recovered trip doesn't lose ground already persisted.
        // Use the whole point list to compute running max/min — the last
        // valid-odometer point gives distance but mid-trip extremes matter
        // for discharge and SoC.
        val validPoint = dataPointDao
            .getLastValidOdometerPointForTrip(activeTrip.id, activeTrip.startOdometer)
        tripBestDistanceKm = validPoint?.odometer?.let {
            (it - activeTrip.startOdometer).coerceAtLeast(0.0)
        } ?: 0.0
        tripBestTotalDischarge = dataPoints
            .mapNotNull { it.totalDischarge.takeIf { v -> v > activeTrip.startTotalDischarge } }
            .maxOrNull()
            ?: activeTrip.startTotalDischarge
        tripMinSoc = dataPoints
            .mapNotNull { it.soc.takeIf { v -> v > 0.0 } }
            .minOrNull()
            ?: Double.MAX_VALUE
        // Re-integrate Σ max(0, power) × dt across the persisted data points so a
        // recovery resumes the power-integrated floor rather than restarting at 0.
        tripIntegratedDischargeKwh = run {
            var sum = 0.0
            dataPoints.zipWithNext { a, b ->
                val dtH = (b.timestamp - a.timestamp).coerceAtLeast(0L) / 3_600_000.0
                if (dtH in 0.0..(60.0 / 3600.0) && a.power > 0.0) sum += a.power * dtH
            }
            sum
        }
        // Re-sum per-tick totalDischarge deltas across the stored points (NET of regen)
        // so a recovery resumes the per-segment draw rather than restarting at 0. A large
        // time gap marks a segment boundary where the car-on counter reset, so the delta
        // across it is dropped; within a segment both signs are summed (discharge + regen).
        tripCumulativeBmsDeltaKwh = run {
            var sum = 0.0
            var last: Double? = null
            var lastTs: Long? = null
            for (dp in dataPoints) {
                val td = dp.totalDischarge
                if (td.isFinite() && td >= 0.0) {
                    val withinSegment = lastTs != null &&
                        (dp.timestamp - lastTs) < OFFSTATE_GAP_THRESHOLD_MS
                    if (last != null && withinSegment) {
                        val delta = td - last
                        if (delta > -50.0 && delta < 50.0) sum += delta
                    }
                    last = td
                    lastTs = dp.timestamp
                }
            }
            sum.coerceAtLeast(0.0)
        }
        lastTripTotalDischarge = dataPoints.lastOrNull()?.totalDischarge
            ?.takeIf { it.isFinite() && it >= 0.0 }

        // The resume-vs-close decision, recorded before it is acted on. On DiLink-5 this line
        // is the one that explains a trip's stored end values: the app is force-stopped at
        // ignition-off, so whether it lands on "resume" or "stale-close" comes down to whether
        // the background restarter woke it inside the engine-off timeout.
        DiagLog.event(
            appContext, TAG,
            "trip recover id=${activeTrip.id} " +
                "decision=${if (gap > STALE_THRESHOLD || staleBecauseCarOff) "stale-close" else "resume"} " +
                "gap=${gap / 1000}s threshold=${STALE_THRESHOLD / 1000}s " +
                "carOffFor=${storedOffSince?.let { (now - it) / 1000 }?.toString()?.plus("s") ?: "-"} " +
                "points=${dataPoints.size}"
        )

        if (gap > STALE_THRESHOLD || staleBecauseCarOff) {
            Log.w(TAG, "Stale trip ${activeTrip.id} — closing with last DB values")
            // Seed state so doEndTrip can find the trip
            _currentTripId.value = activeTrip.id
            cachedCurrentTrip    = activeTrip
            tripState            = TripState.ACTIVE

            // Prefer the last driving sample over the very last recorded point:
            // once the car parks and car goes offline, samples return
            // odometer=0 and would zero out trip distance/energy if used as-is.
            // Pass null rather than a stale zero so doEndTrip's fallback chain
            // lands on trip.startOdometer instead of storing a 0 endOdometer.
            //
            // Panel SoC: the last recorded non-zero panel reading. Taken from the whole
            // point list rather than validPoint alone so a single wedged (0) panel read on
            // the final driving sample doesn't collapse the trip back to startSocPanel.
            // A 0 soc on validPoint is likewise dropped so doEndTrip falls through to
            // tripMinSoc (seeded above) instead of storing a stale 0 as the end SoC.
            val lastPanelSoc = dataPoints.lastOrNull { it.socPanel > 0 }?.socPanel?.toDouble()
            doEndTrip(
                reason                 = "cold-start-recovery",
                overrideEndTime        = storedOffSince?.let { it + carOffTimeoutMs() }
                                            ?: validPoint?.timestamp
                                            ?: lastPoint?.timestamp,
                overrideOdometer       = validPoint?.odometer,
                overrideSoc            = validPoint?.soc?.takeIf { it > 0.0 },
                overrideSocPanel       = lastPanelSoc,
                overrideTotalDischarge = validPoint?.totalDischarge
            )
        } else {
            Log.i(TAG, "Resuming active trip ${activeTrip.id}")
            _currentTripId.value = activeTrip.id
            _isInTrip.value      = true
            cachedCurrentTrip    = activeTrip
            tripState            = TripState.ACTIVE
            lastTelemetryTime    = lastPoint?.timestamp ?: activeTrip.startTime
            carOffSinceMs        = storedOffSince ?: 0L
            // Restore lastTelemetry from the UI cache so the watchdog and
            // handleTelemetry have a non-null odometer until the first live packet.
            lastTelemetry        = _latestTelemetry.value
        }
    }

    private suspend fun resolveCarOffSince(now: Long): Long {
        val tripId = _currentTripId.value ?: return now
        val storedOffSince = trailingStoredCarOffStart(dataPointDao.getDataPointsForTripSync(tripId))
        if (storedOffSince != null && storedOffSince < now) {
            Log.i(TAG, "Restored engine-off timer from stored trip history (${(now - storedOffSince) / 60_000} min ago)")
            return storedOffSince
        }
        return now
    }

    private fun trailingStoredCarOffStart(points: List<TripDataPointEntity>): Long? {
        var offStart: Long? = null
        for (point in points.asReversed()) {
            if (storedPointLooksCarOff(point)) {
                offStart = point.timestamp
            } else {
                break
            }
        }
        return offStart
    }

    private fun storedPointLooksCarOff(point: TripDataPointEntity): Boolean {
        val rawCarOn = runCatching {
            telemetryJson.parseToJsonElement(point.rawJson)
                .jsonObject["car_on"]
                ?.jsonPrimitive
                ?.intOrNull
        }.getOrNull()

        // engineSpeedFront/Rear are intentionally excluded: on cars without an Engine or
        // Speed device the instrument cluster provides RPM, and the cluster may return
        // non-zero values while the car is parked/off. Use kinematic + carOn signals only.
        return rawCarOn == 0 &&
            point.gear == "P" &&
            point.speed <= 0.5 &&
            abs(point.power) <= 2.0
    }

    // ── Core trip operations ──────────────────────────────────────────────────

    private suspend fun doStartTrip(
        telemetry: VehicleTelemetry,
        isManual: Boolean,
        backAnchorToJourney: Boolean,
        maxBackdateMs: Long = 8 * 60 * 60 * 1000L
    ) {
        val now = System.currentTimeMillis()
        // Back-anchor using the car's journey counter so a trip opened mid-drive
        // (auto-start late, or user opened app after already driving) represents
        // the full drive from car-on, not from the moment the trip was opened.
        // Do not use it for manual starts or for the first moving packet seen
        // after a parked/off packet, otherwise the trip grows beyond the exact
        // timestamps the user expects.
        val journeyKmRaw = telemetry.currentJourneyDriveMileage
            ?.takeIf { backAnchorToJourney && it.isFinite() && it > 0.01 } ?: 0.0
        // Guard: same stale-counter check the ViewModel applies to its back-date logic.
        // If journeyDistance ÷ journeyTime > 200 km/h the mileage counter has not reset
        // across the engine cycle (e.g. fresh start with the previous run's counter still
        // showing 60 km). Using it would anchor startOdometer far in the past, inflating
        // the stored trip distance by the entire day's driving.
        val journeyTimeMin = telemetry.currentJourneyDriveTime
        val journeyCountersStale = journeyKmRaw > 0.0 && run {
            val timeMs = journeyTimeMin?.takeIf { it.isFinite() && it > 0.0 }
                ?.let { (it * 60_000.0).toLong() }
            timeMs != null && journeyKmRaw / (timeMs / 3_600_000.0) > 200.0
        }
        if (journeyCountersStale) {
            Log.w(TAG, "Trip start: journey counters stale (${journeyKmRaw}km implied >200km/h) — anchoring at current odometer")
        }
        val journeyKm = if (journeyCountersStale) 0.0 else journeyKmRaw
        val startOdometer = (telemetry.odometer - journeyKm).coerceAtLeast(0.0)
        val backdateMs = if (journeyKm > 0.0) {
            journeyTimeMin
                ?.takeIf { it.isFinite() && it > 0.0 }
                ?.let { (it * 60_000.0).toLong() }
                ?: run {
                    val effectiveSpeedKmh = effectiveSpeedKmh(telemetry).coerceAtLeast(1.0)
                    ((journeyKm / effectiveSpeedKmh) * 3_600_000.0).toLong()
                }
        } else 0L
        val clampedBackdateMs = backdateMs.coerceAtMost(maxBackdateMs)
        val startTime = now - clampedBackdateMs

        val startBatteryTemp = validBatteryTemp(telemetry.batteryTempAvg)
        val startCellTempMax = validCellTemp(telemetry.batteryCellTempMax)
        val startCellTempMin = validCellTemp(telemetry.batteryCellTempMin)
        val trip = TripEntity(
            startTime           = startTime,
            startOdometer       = startOdometer,
            startSoc            = telemetry.soc,
            startSocPanel       = telemetry.socPanel.toDouble(),
            startTotalDischarge = telemetry.totalDischarge,
            isActive            = true,
            isManual            = isManual,
            minSoc              = telemetry.soc,
            avgBatteryTemp      = startBatteryTemp ?: 0.0,
            maxBatteryCellTemp  = startCellTempMax ?: Int.MIN_VALUE,
            minBatteryCellTemp  = startCellTempMin ?: Int.MAX_VALUE
        )

        val tripId = tripDao.insertTrip(trip)

        _currentTripId.value = tripId
        _isInTrip.value      = true
        cachedCurrentTrip    = trip.copy(id = tripId)
        tripState            = TripState.ACTIVE

        batteryTempSum     = startBatteryTemp ?: 0.0
        batteryTempSamples = if (startBatteryTemp != null) 1 else 0

        // Seed the best-known distance from the back-anchored portion. Future
        // telemetry ticks extend this via speed integration or odometer delta.
        tripBestDistanceKm         = journeyKm
        tripBestTotalDischarge     = telemetry.totalDischarge
        tripIntegratedDischargeKwh = 0.0
        tripCumulativeBmsDeltaKwh  = 0.0
        lastTripTotalDischarge     = telemetry.totalDischarge.takeIf { it.isFinite() && it >= 0.0 }
        tripMinSoc                 = telemetry.soc

        currentSegment = openSegment(tripId, telemetry)

        // Synthetic anchor point at the back-calculated trip start. Carries current
        // telemetry values but odometer/timestamp reflect the real car-on moment so
        // Charts/Route/Analysis tabs span the full trip.
        if (clampedBackdateMs > 0L) {
            recordDataPoint(
                tripId = tripId,
                t = telemetry,
                overrideTimestamp = startTime,
                overrideOdometer = startOdometer
            )
        }

        // Write the opening datapoint immediately so short trips and
        // early sparse packets are always captured from the first moment.
        recordDataPoint(tripId, telemetry)
        lastRecordedTelemetry = telemetry
        lastWriteTime = System.currentTimeMillis()

        // Paired with the "trip close" line below, so a whole drive can be read out of
        // diag.log — including the SoC the trip was anchored at, which is what an end value
        // silently falls back to when the close path has no live reading.
        DiagLog.event(
            appContext, TAG,
            "trip start id=$tripId manual=$isManual " +
                "soc=${"%.1f".format(telemetry.soc)} panel=${telemetry.socPanel} " +
                "odo=${"%.1f".format(startOdometer)} backdate=${clampedBackdateMs / 1000}s " +
                "journey=$journeyKm backAnchor=$backAnchorToJourney"
        )
    }

    /**
     * Closes the active trip. Override params are only used by recoverActiveTrip
     * to supply DB-sourced values when lastTelemetry is null on cold start.
     */
    private suspend fun doEndTrip(
        /** Which trigger closed the trip. Recorded in diag.log — the close path is the
         *  difference between a trip finalised from live telemetry and one reconstructed
         *  from the DB on the next cold start, and after the fact there is no other way
         *  to tell which one a given trip took. */
        reason:                 String  = "unspecified",
        overrideEndTime:        Long?   = null,
        overrideOdometer:       Double? = null,
        overrideSoc:            Double? = null,
        overrideSocPanel:       Double? = null,
        overrideTotalDischarge: Double? = null
    ) {
        val tripId = _currentTripId.value ?: run {
            Log.w(TAG, "doEndTrip called with no currentTripId")
            tripState = TripState.IDLE
            return
        }

        val trip      = tripDao.getTripById(tripId) ?: run {
            Log.w(TAG, "doEndTrip: trip $tripId not found in DB")
            resetTripState()
            return
        }

        val endTelemetry = lastTelemetry

        // When lastTelemetry is null (service was restarted — in-memory variable is not
        // persisted) or reports an unusable odometer (car-off mode: car goes
        // offline and returns 0.0), fall back to the last saved data point so that
        // distance and energy are not zeroed out.
        val endPointFallback: TripDataPointEntity? = run {
            if (overrideOdometer != null) return@run null   // caller already provides correct values
            val odo        = endTelemetry?.odometer        ?: 0.0
            val discharge  = endTelemetry?.totalDischarge  ?: 0.0
            // Also fall back when totalDischarge hasn't advanced past the trip-start value —
            // this happens when the car is slow to initialise or the car-off
            // car path returns a stale read. Without this branch, distance would be correct
            // but energyConsumed would be computed as 0 because endTotalDischarge == startTotalDischarge.
            if (endTelemetry == null
                || odo       <= trip.startOdometer
                || discharge <= trip.startTotalDischarge) {
                // Skip data points recorded after car went offline
                // (odometer falls back to 0). Otherwise endOdometer collapses to
                // trip.startOdometer → distance=0 even after real driving.
                dataPointDao.getLastValidOdometerPointForTrip(tripId, trip.startOdometer)
                    ?: dataPointDao.getLastDataPointForTrip(tripId)
            } else null
        }

        // Floor endTime against the last data-point timestamp so that a service-restart
        // recovery with lastTelemetryTime ≈ trip.startTime cannot produce duration = 0.
        val endTime = (overrideEndTime ?: System.currentTimeMillis())
            .coerceAtLeast(endPointFallback?.timestamp ?: trip.startTime)

        flushSegment(endTelemetry = endTelemetry)

        val finalAvgTemp = if (batteryTempSamples > 0)
            batteryTempSum / batteryTempSamples
        else
            trip.avgBatteryTemp

        // End-odometer resolution: prefer the raw odometer reading when it advanced
        // past the trip start — this matches what the instrument cluster shows.
        // tripBestDistanceKm (speed-integration) is only a fallback for the car-offline
        // case where the odometer returns 0 for several ticks after a resume.
        val resolvedEndOdometer = when {
            overrideOdometer != null -> overrideOdometer
            endTelemetry?.odometer?.let { it > trip.startOdometer } == true ->
                endTelemetry.odometer
            else -> listOfNotNull(
                endPointFallback?.odometer?.takeIf { it > trip.startOdometer },
                (trip.startOdometer + tripBestDistanceKm).takeIf { tripBestDistanceKm > 0.0 }
            ).maxOrNull() ?: trip.startOdometer
        }

        // Guard totalDischarge fallbacks against stale 0 reads (car offline path)
        // AND counter resets across segments. On many BYD firmwares
        // getTotalElecConValue resets at every car-on cycle, so a trip that
        // spans an engine-off pause has the counter running 0→4 in segment 1
        // and 0→2 in segment 2; a naive end−start (or best−start) captures
        // only one segment's worth, halving (or worse) the recorded consumption.
        //
        // tripCumulativeBmsDeltaKwh sums NET per-tick deltas (discharge minus regen)
        // across all segments, dropping the counter reset at each car-on boundary. It
        // is the authoritative consumption: for a single segment it telescopes exactly
        // to end−start, and across resets it sums each segment correctly.
        val cumulativeBmsCandidate = (trip.startTotalDischarge + tripCumulativeBmsDeltaKwh)
            .takeIf { tripCumulativeBmsDeltaKwh > 0.0 }
        // Fallbacks only — used when the cumulative value is unavailable. NOTE:
        // tripBestTotalDischarge is the running PEAK; on a counter that nets out regen
        // it sits above the end value (regen after the peak), so it must never be
        // preferred over the cumulative net figure or it would re-introduce the
        // gross/over-count. It's kept here purely as a last-resort stale-end guard.
        val dischargeCandidates = listOfNotNull(
            endTelemetry?.totalDischarge?.takeIf { it > trip.startTotalDischarge },
            endPointFallback?.totalDischarge?.takeIf { it > trip.startTotalDischarge },
            tripBestTotalDischarge.takeIf { it > trip.startTotalDischarge }
        )
        val powerIntegratedFallback = (trip.startTotalDischarge + tripIntegratedDischargeKwh)
            .takeIf { cumulativeBmsCandidate == null && dischargeCandidates.isEmpty() && tripIntegratedDischargeKwh > 0.0 }
        // Prefer the cumulative NET value whenever it's available; only fall back to
        // the raw end/peak reads when no net total was accumulated (e.g. a trip that
        // never produced a usable per-tick delta).
        val candidateEndDischarge = overrideTotalDischarge
            ?: cumulativeBmsCandidate
            ?: dischargeCandidates.maxOrNull()
            ?: powerIntegratedFallback
            ?: trip.startTotalDischarge

        // Sanity-cap the final delta. The per-tick anchor correction above
        // catches the common stale-zero case, but if a trip is closed before
        // the BMS ever recovered (or the override path supplied a bad value),
        // we may still be holding a (end − start) that exceeds the laws of
        // physics. When that happens, swap the implausible reading for the
        // power-integrated estimate of energy actually consumed.
        val batteryKwh = resolveBatteryKwh()
        val reportedDeltaKwh = candidateEndDischarge - trip.startTotalDischarge
        val resolvedEndDischarge = if (
            reportedDeltaKwh > batteryKwh * MAX_PLAUSIBLE_TRIP_BATTERIES &&
            tripIntegratedDischargeKwh > 0.0
        ) {
            Log.w(
                TAG,
                "Clamping implausible trip discharge delta: reported=" +
                    "${"%.2f".format(reportedDeltaKwh)} kWh " +
                    "(start=${trip.startTotalDischarge}, end=$candidateEndDischarge) " +
                    "→ power-integrated ${"%.2f".format(tripIntegratedDischargeKwh)} kWh " +
                    "(batteryKwh=$batteryKwh)"
            )
            trip.startTotalDischarge + tripIntegratedDischargeKwh
        } else {
            candidateEndDischarge
        }

        // SoC: 0 is possible as a real reading when fully discharged, but in
        // practice a 0 here almost always means stale telemetry. Prefer the
        // lowest plausible reading (SoC only decreases while discharging).
        val socCandidates = listOfNotNull(
            endTelemetry?.soc?.takeIf { it > 0.0 },
            endPointFallback?.soc?.takeIf { it > 0.0 },
            tripMinSoc.takeIf { it != Double.MAX_VALUE }
        )
        val resolvedEndSoc = overrideSoc
            ?: socCandidates.minOrNull()
            ?: trip.startSoc
        val endSocSource = when {
            overrideSoc != null        -> "override"
            socCandidates.isNotEmpty() -> "min-of-${socCandidates.size}"
            else                       -> "START-FALLBACK"
        }

        // Panel SoC: take the last available non-zero reading (no "lowest wins" logic
        // since panel SoC doesn't monotonically decrease on PHEVs with engine assist).
        // overrideSocPanel comes first: on the cold-start recovery path lastTelemetry is
        // null AND endPointFallback is suppressed (the caller supplies overrideOdometer),
        // so without a DB-sourced panel value this chain had no source at all and every
        // recovered trip stored endSocPanel == startSocPanel — a flat "80% → 80%" in the
        // history list. That is the *normal* close path on DiLink-5, where the OEM
        // force-stops the app at ignition-off and the trip is only closed on next start.
        // Carried as (source, value) pairs purely so the close line in diag.log can name
        // which one won — a "START-FALLBACK" there is the flat-pair bug reproducing.
        val panelCandidates: List<Pair<String, Double>> = listOfNotNull(
            overrideSocPanel?.takeIf { it > 0.0 }?.let { "override" to it },
            endTelemetry?.socPanel?.toDouble()?.takeIf { it > 0.0 }?.let { "telemetry" to it },
            endPointFallback?.socPanel?.toDouble()?.takeIf { it > 0.0 }?.let { "point" to it }
        )
        val resolvedEndSocPanel = panelCandidates.firstOrNull()?.second ?: trip.startSocPanel
        val endSocPanelSource = panelCandidates.firstOrNull()?.first ?: "START-FALLBACK"

        // Time the car was off but the trip stayed open — subtracted from
        // trip.duration so avg speed / displayed duration reflect actual driving
        // time rather than driving + parked-with-trip-still-open.
        val offStateMs = computeOffStateDurationMs(
            points = dataPointDao.getDataPointsForTripSync(tripId),
            tripEndMs = endTime
        )

        tripDao.updateTrip(
            trip.copy(
                endTime             = endTime,
                endOdometer         = resolvedEndOdometer,
                endSoc              = resolvedEndSoc,
                endSocPanel         = resolvedEndSocPanel,
                endTotalDischarge   = resolvedEndDischarge,
                isActive            = false,
                avgBatteryTemp      = finalAvgTemp,
                offStateDurationMs  = offStateMs
            )
        )

        // Persistent record of HOW this trip was closed. The logcat ring buffer on these head
        // units is shredded within minutes, so without this there is no way — after the fact —
        // to tell a trip finalised from live telemetry apart from one reconstructed on the next
        // cold start, which is exactly the distinction that decides whether the stored end
        // values are real or fallbacks. `panel=…[START-FALLBACK]` means the flat
        // "x% → x%" pair got stored; on DiLink-5 that also implies the app was not alive at
        // car-off + the engine-off timeout, i.e. the background restarter didn't win.
        DiagLog.event(
            appContext, TAG,
            "trip close id=$tripId via=$reason " +
                "telemetry=${if (endTelemetry != null) "live" else "none"} " +
                "dbPoint=${if (endPointFallback != null) "yes" else "no"} " +
                "soc=${"%.1f".format(trip.startSoc)}→${"%.1f".format(resolvedEndSoc)}[$endSocSource] " +
                "panel=${trip.startSocPanel.toInt()}→${resolvedEndSocPanel.toInt()}[$endSocPanelSource] " +
                "odo=${"%.1f".format(trip.startOdometer)}→${"%.1f".format(resolvedEndOdometer)} " +
                "kwh=${"%.2f".format(resolvedEndDischarge - trip.startTotalDischarge)} " +
                "offState=${offStateMs / 1000}s"
        )

        // Drop trips shorter than the user-configured minimum. Done before stats
        // calculation to skip the (now wasted) compute. Threshold 0.0 disables.
        val tripDistanceKm = (resolvedEndOdometer - trip.startOdometer).coerceAtLeast(0.0)
        val minTripKm = prefsManager.getCachedMinTripDistanceKm()
        if (minTripKm > 0.0 && tripDistanceKm < minTripKm) {
            Log.i(TAG, "Trip $tripId distance ${"%.2f".format(tripDistanceKm)} km " +
                "< min ${"%.2f".format(minTripKm)} km → discarding")
            deleteTrip(tripId)
            resetTripState()
            return
        }

        try {
            calculateTripStats(tripId)
        } catch (e: Exception) {
            Log.e(TAG, "Stats calculation failed for trip $tripId", e)
        }

        resetTripState()
        Log.i(TAG, "Trip ended id=$tripId")
    }

    private fun resetTripState() {
        cachedCurrentTrip     = null
        lastRecordedTelemetry = null
        currentSegment        = null
        lastWriteTime         = 0L
        batteryTempSum        = 0.0
        batteryTempSamples    = 0
        carOffSinceMs         = 0L
        keepCurrentOffWindow  = false
        lastLoggedCarOffAction = null
        _pendingAutoStop.value = false
        tripBestDistanceKm    = 0.0
        tripBestTotalDischarge = 0.0
        tripIntegratedDischargeKwh = 0.0
        tripCumulativeBmsDeltaKwh  = 0.0
        lastTripTotalDischarge     = null
        tripMinSoc             = Double.MAX_VALUE
        tripState             = TripState.IDLE
        _currentTripId.value  = null
        _isInTrip.value       = false
    }

    // ── Watchdog ──────────────────────────────────────────────────────────────

    private fun startWatchdog() {
        scope.launch {
            while (true) {
                delay(60_000)
                tripEvents.trySend(TripEvent.WatchdogTick)
            }
        }
    }

    // ── Auto-start detection ──────────────────────────────────────────────────

    private fun shouldAutoStart(
        t: VehicleTelemetry,
        previousTelemetry: VehicleTelemetry?
    ): Boolean {
        // All available signals may not arrive on every packet.
        val movedByOdometer = previousTelemetry != null &&
            t.odometer > previousTelemetry.odometer + 0.01
        val effectiveSpeed = maxOf(t.speed, t.locationGpsSpeed ?: 0.0)
        val drivetrainAlive = t.engineSpeedFront > 0 ||
            t.engineSpeedRear > 0 ||
            kotlin.math.abs(t.enginePower) > 2
        val carLooksOn = t.isCarOn || drivetrainAlive

        // Primary: car on, in drive gear, any movement signal
        if (carLooksOn && t.gear in DRIVE_GEARS) {
            if (effectiveSpeed > 2.0 || drivetrainAlive || movedByOdometer) {
                Log.i(TAG, "shouldAutoStart: YES (primary) — carOn=${t.carOn} " +
                    "gear=${t.gear} speed=$effectiveSpeed power=${t.enginePower} " +
                    "frontRpm=${t.engineSpeedFront} rearRpm=${t.engineSpeedRear} " +
                    "odomMoved=$movedByOdometer")
                return true
            }
        }
        // Fallbacks: unambiguous movement even if carOn/gear not yet populated
        if (effectiveSpeed > 5.0) {
            Log.i(TAG, "shouldAutoStart: YES (speed fallback) speed=$effectiveSpeed")
            return true
        }
        if (movedByOdometer) {
            Log.i(TAG, "shouldAutoStart: YES (odometer moved)")
            return true
        }
        if (drivetrainAlive && t.gear in DRIVE_GEARS) {
            Log.i(TAG, "shouldAutoStart: YES (drivetrain alive in drive gear)")
            return true
        }
        return false
    }

    // ── Data-point throttle ───────────────────────────────────────────────────

    private fun shouldRecordDataPoint(t: VehicleTelemetry, now: Long): Boolean {
        val last = lastRecordedTelemetry ?: return true
        if (now - lastWriteTime >= WRITE_INTERVAL_MS)            return true
        if (abs(t.speed        - last.speed)        >= 5)        return true
        if (abs(t.soc          - last.soc)          >= 0.5)      return true
        if (abs(t.enginePower  - last.enginePower)  >= 10)       return true
        if (abs(t.engineSpeedFront - last.engineSpeedFront) >= 300) return true
        if (abs(t.engineSpeedRear  - last.engineSpeedRear)  >= 300) return true
        if (t.gear != last.gear)                                  return true
        return false
    }

    // ── Segment helpers ───────────────────────────────────────────────────────

    private fun hasValidGps(lat: Double?, lon: Double?) =
        lat != null && lat != 0.0 && lon != null && lon != 0.0

    private fun openSegment(tripId: Long, t: VehicleTelemetry): SegmentBuilder {
        val now = System.currentTimeMillis()
        val lat = t.locationLatitude.takeIf  { it != 0.0 }
        val lon = t.locationLongitude.takeIf { it != 0.0 }
        return SegmentBuilder(
            tripId                  = tripId,
            startTime               = now,
            startLat                = lat,
            startLon                = lon,
            startOdometer           = t.odometer,
            startTotalDischarge     = t.totalDischarge,
            tripIntegratedKwhAtOpen = tripIntegratedDischargeKwh,
            endTime                 = now,
            endLat                  = lat,
            endLon                  = lon
        )
    }

    private fun updateSegment(t: VehicleTelemetry) {
        val seg = currentSegment ?: return
        seg.speedSum += t.speed
        seg.powerSum += t.enginePower
        seg.samples++
        seg.endTime = System.currentTimeMillis()
        val lat = t.locationLatitude.takeIf  { it != 0.0 }
        val lon = t.locationLongitude.takeIf { it != 0.0 }
        if (lat != null) { seg.endLat = lat; seg.endLon = lon }
    }

    private suspend fun flushSegment(endTelemetry: VehicleTelemetry?) {
        val seg    = currentSegment ?: return
        val tripId = _currentTripId.value ?: return

        if (seg.samples == 0) {
            currentSegment = endTelemetry?.let { openSegment(tripId, it) }
            return
        }

        // Per-segment power-integrated kWh = trip integral now − snapshot at open.
        // This is the ground-truth fallback when the BMS discharge-counter delta
        // for this segment is physically impossible (typically because the
        // segment's startTotalDischarge anchor was a stale 0 — the same failure
        // mode that motivated the trip-level correctStaleDischargeAnchorIfNeeded
        // path, but inside a 30-second window the per-tick correction usually
        // doesn't fire in time to save the segment).
        val segIntegratedKwh = (tripIntegratedDischargeKwh - seg.tripIntegratedKwhAtOpen)
            .coerceAtLeast(0.0)
        val batteryKwh = resolveBatteryKwh()
        // A segment is at most SEGMENT_FLUSH_MS ≈ 30 s of driving. Even at peak
        // continuous output (~250 kW) that caps real consumption at ~2 kWh per
        // segment. We use 50% of the pack as the implausibility threshold —
        // anything above that is unambiguously a corrupt anchor.
        val segMaxPlausibleKwh = batteryKwh * 0.5
        val reportedSegEnergy = if (endTelemetry != null)
                                    maxOf(0.0, endTelemetry.totalDischarge - seg.startTotalDischarge)
                                else 0.0
        val resolvedSegEnergy = if (reportedSegEnergy > segMaxPlausibleKwh) {
            Log.w(
                TAG,
                "Clamping implausible segment energyUsed: " +
                    "${"%.2f".format(reportedSegEnergy)} kWh " +
                    "(start=${seg.startTotalDischarge}, " +
                    "end=${endTelemetry?.totalDischarge}) → " +
                    "power-integrated ${"%.2f".format(segIntegratedKwh)} kWh"
            )
            segIntegratedKwh
        } else {
            reportedSegEnergy
        }

        segmentDao.insertSegment(
            TripSegmentEntity(
                tripId     = seg.tripId,
                startTime  = seg.startTime,
                endTime    = seg.endTime,
                startLat   = seg.startLat,
                startLon   = seg.startLon,
                endLat     = seg.endLat,
                endLon     = seg.endLon,
                avgSpeed   = seg.speedSum  / seg.samples,
                avgPower   = seg.powerSum  / seg.samples,
                distance   = if (endTelemetry != null)
                                 maxOf(0.0, endTelemetry.odometer - seg.startOdometer)
                             else 0.0,
                energyUsed = resolvedSegEnergy
            )
        )

        currentSegment = endTelemetry?.let { openSegment(tripId, it) }
    }

    // ── Data point recording ──────────────────────────────────────────────────

    private suspend fun recordDataPoint(
        tripId: Long,
        t: VehicleTelemetry,
        overrideTimestamp: Long? = null,
        overrideOdometer: Double? = null,
        overrideTotalDischarge: Double? = null,
        overrideSoc: Double? = null,
    ) {
        try {
            val isPhevSample = prefsManager.getCachedSelectedCarConfig()?.isPhev ?: false

            // Guard against NaN/Infinity values that would either crash the JSON
            // encoder or produce an invalid rawJson that Room rejects.
            fun Double.safe() = if (isFinite()) this else 0.0

            // Schema JSON is best-effort: if serialization throws (e.g. an
            // unregistered type in probe_values) we must still persist the row —
            // first-class columns carry the essential telemetry, and losing the
            // rawJson blob is far better than losing the whole data point.
            val rawJson = try {
                t.toSchemaJson(isPhev = isPhevSample)
            } catch (e: Exception) {
                Log.w(TAG, "toSchemaJson failed for tripId=$tripId — writing empty rawJson: ${e.message}")
                "{}"
            }

            dataPointDao.insertDataPoint(
                TripDataPointEntity(
                    tripId                 = tripId,
                    timestamp              = overrideTimestamp ?: System.currentTimeMillis(),
                    latitude               = t.locationLatitude.safe(),
                    longitude              = t.locationLongitude.safe(),
                    altitude               = t.locationAltitude.safe(),
                    speed                  = t.speed.safe(),
                    power                  = t.enginePower.toDouble(),
                    soc                    = (overrideSoc ?: t.soc).safe(),
                    odometer               = (overrideOdometer ?: t.odometer).safe(),
                    batteryTemp            = t.batteryTempAvg.safe(),
                    totalDischarge         = (overrideTotalDischarge ?: t.totalDischarge).safe(),
                    gear                   = t.gear,
                    isRegenerating         = t.isRegenerating,
                    engineSpeedFront       = t.engineSpeedFront,
                    engineSpeedRear        = t.engineSpeedRear,
                    electricDrivingRangeKm = t.electricDrivingRangeKm,
                    tyrePressureLF         = t.tyrePressureLF.safe(),
                    tyrePressureRF         = t.tyrePressureRF.safe(),
                    tyrePressureLR         = t.tyrePressureLR.safe(),
                    tyrePressureRR         = t.tyrePressureRR.safe(),
                    socPanel               = t.socPanel,
                    tyreTempLF             = t.tyreTempLF,
                    tyreTempRF             = t.tyreTempRF,
                    tyreTempLR             = t.tyreTempLR,
                    tyreTempRR             = t.tyreTempRR,
                    soh                    = t.soh,
                    sohPrecise             = t.statisticBatterySoh ?: 0.0,
                    batteryTotalVoltage    = t.batteryTotalVoltage,
                    battery12vVoltage      = t.battery12vVoltage.safe(),
                    batteryCellVoltageMax  = t.batteryCellVoltageMax.safe(),
                    batteryCellVoltageMin  = t.batteryCellVoltageMin.safe(),
                    batteryRemainPowerEV   = t.batteryRemainPowerEV?.takeIf { it.isFinite() },
                    rawJson                = rawJson
                )
            )
            Log.v(TAG, "recordDataPoint: tripId=$tripId odo=${t.odometer} speed=${t.speed}")
        } catch (e: Exception) {
            Log.e(TAG, "recordDataPoint FAILED for tripId=$tripId: ${e.javaClass.simpleName}: ${e.message}", e)
        }
    }

    // ── Trip metrics ──────────────────────────────────────────────────────────

    // Max-Speed spike rejection state (see usage below). The BYD SpeedDevice can emit a lone
    // garbage reading; without this, one bad sample would pin the trip's Max Speed for the
    // whole trip (maxSpeed is a running max). Averages are distance/time based and unaffected.
    private val maxPlausibleSpeedKmh = 250.0   // absolute ceiling — above any production BYD top speed
    private val maxSpeedStepKmh = 80.0         // max believable change between consecutive samples
    private var lastAcceptedSpeedKmh: Double? = null

    private suspend fun updateTripMetrics(t: VehicleTelemetry) {
        val trip = cachedCurrentTrip ?: return

        val batteryTemp = validBatteryTemp(t.batteryTempAvg)
        if (batteryTemp != null) {
            batteryTempSum += batteryTemp
            batteryTempSamples++
        }

        val nextMaxCellTemp = validCellTemp(t.batteryCellTempMax)?.let {
            if (trip.maxBatteryCellTemp == Int.MIN_VALUE) it else maxOf(trip.maxBatteryCellTemp, it)
        } ?: trip.maxBatteryCellTemp
        val nextMinCellTemp = validCellTemp(t.batteryCellTempMin)?.let {
            if (trip.minBatteryCellTemp == Int.MAX_VALUE) it else minOf(trip.minBatteryCellTemp, it)
        } ?: trip.minBatteryCellTemp

        // Spike rejection: only fold this sample into maxSpeed if it's under the absolute
        // ceiling AND not an impossible jump from the last accepted sample (see fields above).
        val prevSpeed = lastAcceptedSpeedKmh
        val speedIsPlausible = t.speed in 0.0..maxPlausibleSpeedKmh &&
            (prevSpeed == null || t.speed <= prevSpeed + maxSpeedStepKmh)
        if (speedIsPlausible) lastAcceptedSpeedKmh = t.speed
        val nextMaxSpeed = if (speedIsPlausible && t.speed > trip.maxSpeed) t.speed else trip.maxSpeed

        val updated = trip.copy(
            maxSpeed           = nextMaxSpeed,
            maxPower           = maxOf(trip.maxPower, t.enginePower.toDouble()),
            maxRegenPower      = if (t.isRegenerating)
                                     minOf(trip.maxRegenPower, t.enginePower.toDouble())
                                 else trip.maxRegenPower,
            minSoc             = minOf(trip.minSoc, t.soc),
            avgBatteryTemp     = if (batteryTempSamples > 0) batteryTempSum / batteryTempSamples else trip.avgBatteryTemp,
            maxBatteryCellTemp = nextMaxCellTemp,
            minBatteryCellTemp = nextMinCellTemp
        )

        cachedCurrentTrip = updated
        tripDao.updateTrip(updated)
    }

    // ── Stats calculation ─────────────────────────────────────────────────────

    private fun compressRoute(points: List<LatLng>, epsilon: Double): List<LatLng> {
        if (points.size < 3) return points
        var maxDist = 0.0
        var index   = 0
        val start   = points.first()
        val end     = points.last()
        for (i in 1 until points.lastIndex) {
            val d = perpendicularDistance(points[i], start, end)
            if (d > maxDist) { index = i; maxDist = d }
        }
        return if (maxDist > epsilon) {
            compressRoute(points.subList(0, index + 1), epsilon).dropLast(1) +
            compressRoute(points.subList(index, points.size), epsilon)
        } else {
            listOf(start, end)
        }
    }

    private fun perpendicularDistance(p: LatLng, a: LatLng, b: LatLng): Double {
        val dx    = b.lon - a.lon
        val dy    = b.lat - a.lat
        val t     = ((p.lon - a.lon) * dx + (p.lat - a.lat) * dy) / (dx * dx + dy * dy)
        val diffX = p.lon - (a.lon + t * dx)
        val diffY = p.lat - (a.lat + t * dy)
        return sqrt(diffX * diffX + diffY * diffY)
    }

    private fun speedBin(speed: Double) = when {
        speed <  20 -> "0-20"
        speed <  40 -> "20-40"
        speed <  60 -> "40-60"
        speed <  80 -> "60-80"
        speed < 100 -> "80-100"
        else        -> "100+"
    }

    private suspend fun calculateTripStats(tripId: Long) {
        val dataPoints = dataPointDao.getDataPointsForTripSync(tripId)

        val trip = tripDao.getTripById(tripId) ?: return

        // When there are no data points we still write a minimal stats row so
        // the UI doesn't show blank fields. Use values already stored in the
        // trip entity (maxSpeed, maxPower, distance, duration, efficiency).
        if (dataPoints.isEmpty()) {
            val segments = segmentDao.getSegmentsForTripSync(tripId)
            val startLat = segments.firstOrNull()?.startLat ?: 0.0
            val startLon = segments.firstOrNull()?.startLon ?: 0.0
            val endLat   = segments.lastOrNull()?.endLat    ?: 0.0
            val endLon   = segments.lastOrNull()?.endLon    ?: 0.0
            statsDao.insertStats(
                TripStatsEntity(
                    tripId                   = tripId,
                    totalDistance            = trip.distance       ?: 0.0,
                    totalDuration            = trip.duration       ?: 0L,
                    totalEnergyConsumed      = trip.energyConsumed ?: 0.0,
                    totalRegenEnergy         = 0.0,
                    avgSpeed                 = 0.0,
                    avgEfficiency            = trip.efficiency ?: 0.0,
                    maxSpeed                 = trip.maxSpeed,
                    maxPower                 = trip.maxPower,
                    maxRegenPower            = trip.maxRegenPower,
                    powerDistribution        = emptyMap(),
                    speedDistribution        = emptyMap(),
                    startLatitude            = startLat,
                    startLongitude           = startLon,
                    endLatitude              = endLat,
                    endLongitude             = endLon,
                    matrixDistribution       = emptyMap(),
                    energyConsumptionBySpeed = emptyMap(),
                    regenEnergy              = 0.0,
                    mechanicalEnergy         = 0.0,
                    compressedRoute          = emptyList()
                )
            )
            return
        }

        val totalDistance       = trip.distance       ?: 0.0
        val totalDuration       = trip.duration       ?: 0L
        val totalEnergyConsumed = trip.energyConsumed ?: 0.0

        val totalRegenEnergy = if (dataPoints.size < 2) 0.0 else {
            dataPoints.zipWithNext { a, b ->
                val gap = b.timestamp - a.timestamp
                // Skip pairs that straddle an off-state / inter-segment boundary (also
                // the seam between two merged trips): the parked gap isn't regen time,
                // and integrating a.power across it would invent energy.
                if (gap > OFFSTATE_GAP_THRESHOLD_MS) 0.0
                else if (a.isRegenerating) abs(a.power) * gap / 3_600_000.0
                else 0.0
            }.sum()
        }

        val avgSpeed = if (totalDuration > 0L && totalDistance > 0.0) {
            totalDistance / (totalDuration / 3_600_000.0)
        } else {
            dataPoints.filter { it.speed > 0 }
                .takeIf { it.isNotEmpty() }?.map { it.speed }?.average() ?: 0.0
        }

        val energyBySpeed   = mutableMapOf<String, Double>()
        val distanceBySpeed = mutableMapOf<String, Double>()
        var regenEnergy      = 0.0
        var mechanicalEnergy = 0.0

        for (i in 1 until dataPoints.size) {
            val a  = dataPoints[i - 1]
            val b  = dataPoints[i]
            // Skip pairs that straddle an off-state / inter-segment boundary (also the
            // seam between two merged trips). Across such a gap the discharge counter
            // may have reset (b.totalDischarge − a.totalDischarge goes wildly negative)
            // and integrating power over the parked time would invent energy — both
            // would poison the per-speed-bin energy/efficiency and the mech/regen totals.
            if (b.timestamp - a.timestamp > OFFSTATE_GAP_THRESHOLD_MS) continue
            val dt = (b.timestamp - a.timestamp) / 3_600_000.0

            if (a.power > 0) mechanicalEnergy += a.power * dt
            if (a.power < 0) regenEnergy      += abs(a.power) * dt

            val bin = speedBin((a.speed + b.speed) / 2)
            energyBySpeed[bin]   = energyBySpeed.getOrDefault(bin, 0.0)   + (b.totalDischarge - a.totalDischarge)
            distanceBySpeed[bin] = distanceBySpeed.getOrDefault(bin, 0.0) + (b.odometer       - a.odometer)
        }

        val efficiencyBySpeed = mutableMapOf<String, Double>()
        energyBySpeed.forEach { (bin, e) ->
            val d = distanceBySpeed[bin] ?: return@forEach
            if (d > 0) efficiencyBySpeed[bin] = (e / d) * 100
        }

        // Route from segment endpoints — fallback to data points for old trips
        val segments = segmentDao.getSegmentsForTripSync(tripId)
        val routePoints: List<LatLng> = if (segments.isNotEmpty()) {
            segments.flatMap { seg ->
                listOfNotNull(
                    if (hasValidGps(seg.startLat, seg.startLon)) LatLng(seg.startLat!!, seg.startLon!!) else null,
                    if (hasValidGps(seg.endLat,   seg.endLon))   LatLng(seg.endLat!!,   seg.endLon!!)   else null
                )
            }.distinct()
        } else {
            dataPoints.filter { it.latitude != 0.0 && it.longitude != 0.0 }
                .map { LatLng(it.latitude, it.longitude) }
        }
        val compressedRoute = compressRoute(routePoints, 0.0001)

        val startLat = segments.firstOrNull()?.startLat?.takeIf { it != 0.0 }
            ?: dataPoints.firstOrNull { it.latitude != 0.0 }?.latitude  ?: 0.0
        val startLon = segments.firstOrNull()?.startLon?.takeIf { it != 0.0 }
            ?: dataPoints.firstOrNull { it.latitude != 0.0 }?.longitude ?: 0.0
        val endLat   = segments.lastOrNull()?.endLat?.takeIf { it != 0.0 }
            ?: dataPoints.lastOrNull  { it.latitude != 0.0 }?.latitude  ?: 0.0
        val endLon   = segments.lastOrNull()?.endLon?.takeIf { it != 0.0 }
            ?: dataPoints.lastOrNull  { it.latitude != 0.0 }?.longitude ?: 0.0

        // ── Speed × power heatmap ─────────────────────────────────────────────
        val matrixDistribution = mutableMapOf<String, Int>()
        dataPoints.forEach { dp ->
            val powerBin = when {
                dp.power < -30 -> "regen-high"
                dp.power <  -5 -> "regen"
                dp.power <  10 -> "idle"
                dp.power <  30 -> "low"
                dp.power <  60 -> "medium"
                else           -> "high"
            }
            val key = "${speedBin(dp.speed)}|$powerBin"
            matrixDistribution[key] = matrixDistribution.getOrDefault(key, 0) + 1
        }

        // ── Power distribution histogram ──────────────────────────────────────
        val powerRanges = mapOf(
            "regen_strong"      to dataPoints.count { it.power <  -30.0 }.toDouble(),
            "regen_medium"      to dataPoints.count { it.power >= -30.0 && it.power <  -10.0 }.toDouble(),
            "regen_light"       to dataPoints.count { it.power >= -10.0 && it.power <    0.0 }.toDouble(),
            "cruising"          to dataPoints.count { it.power >=   0.0 && it.power <   20.0 }.toDouble(),
            "acceleration"      to dataPoints.count { it.power >=  20.0 && it.power <   50.0 }.toDouble(),
            "hard_acceleration" to dataPoints.count { it.power >=  50.0 }.toDouble()
        )

        // ── Speed distribution histogram ──────────────────────────────────────
        val speedRanges = mapOf(
            "0-30"    to dataPoints.count { it.speed >=   0.0 && it.speed <  30.0 }.toDouble(),
            "30-70"   to dataPoints.count { it.speed >=  30.0 && it.speed <  70.0 }.toDouble(),
            "70-100"  to dataPoints.count { it.speed >=  70.0 && it.speed < 100.0 }.toDouble(),
            "100-130" to dataPoints.count { it.speed >= 100.0 && it.speed < 130.0 }.toDouble(),
            "130+"    to dataPoints.count { it.speed >= 130.0 }.toDouble()
        )

        statsDao.insertStats(
            TripStatsEntity(
                tripId                   = tripId,
                totalDistance            = totalDistance,
                totalDuration            = totalDuration,
                totalEnergyConsumed      = totalEnergyConsumed,
                totalRegenEnergy         = totalRegenEnergy,
                avgSpeed                 = avgSpeed,
                avgEfficiency            = trip.efficiency ?: 0.0,
                maxSpeed                 = trip.maxSpeed,
                maxPower                 = trip.maxPower,
                maxRegenPower            = trip.maxRegenPower,
                powerDistribution        = powerRanges,
                speedDistribution        = speedRanges,
                startLatitude            = startLat,
                startLongitude           = startLon,
                endLatitude              = endLat,
                endLongitude             = endLon,
                matrixDistribution       = matrixDistribution,
                energyConsumptionBySpeed = efficiencyBySpeed,
                regenEnergy              = regenEnergy,
                mechanicalEnergy         = mechanicalEnergy,
                compressedRoute          = compressedRoute
            )
        )
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Called by the foreground telemetry service on every incoming packet.
     * Non-suspend: just enqueues — never blocks the service thread.
     */
    fun processTelemetry(telemetry: VehicleTelemetry) {
        _latestTelemetry.value = telemetry   // immediate UI update, no wait for queue
        cacheLatestTelemetry(telemetry)
        tripEvents.trySend(TripEvent.Telemetry(telemetry))
    }

    private fun restoreCachedTelemetry() {
        val cachedJson = telemetryCachePrefs.getString(KEY_LAST_TELEMETRY_JSON, null) ?: return
        runCatching {
            telemetryJson.decodeFromString(VehicleTelemetry.serializer(), cachedJson)
        }.onSuccess { cached ->
            _latestTelemetry.value = cached
            Log.d(TAG, "Restored cached telemetry snapshot for faster startup")
        }.onFailure { error ->
            Log.w(TAG, "Failed to restore cached telemetry snapshot", error)
        }
    }

    private fun cacheLatestTelemetry(telemetry: VehicleTelemetry) {
        runCatching {
            telemetryCachePrefs.edit()
                .putString(KEY_LAST_TELEMETRY_JSON, telemetryJson.encodeToString(telemetry))
                .apply()
        }.onFailure { error ->
            Log.w(TAG, "Failed to cache latest telemetry snapshot", error)
        }
    }

    /** Called by the UI "Start trip" button. Non-suspend. */
    fun requestManualStart() {
        tripEvents.trySend(TripEvent.ManualStart)
    }

    /** Called by the UI "Stop trip" button. Non-suspend. */
    fun requestManualStop() {
        tripEvents.trySend(TripEvent.ManualStop)
    }

    /** "Keep recording" on the auto-stop prompt — hold the trip open. Non-suspend. */
    fun requestKeepTripAcrossOff() {
        tripEvents.trySend(TripEvent.KeepTripAcrossOff)
    }

    /** "Stop" on the auto-stop prompt — finalise the held auto-stop. Non-suspend. */
    fun confirmAutoStop() {
        tripEvents.trySend(TripEvent.ConfirmAutoStop)
    }

    /**
     * Called by the UI as the dashboard enters/leaves the foreground. Only when the
     * UI is visible is a car-off auto-stop held for confirmation; otherwise it ends
     * as before. If the UI disappears while a stop is pending (user walked away), a
     * watchdog tick is posted so the held trip is finalised promptly.
     */
    fun setUiVisible(visible: Boolean) {
        uiVisible = visible
        if (!visible && _pendingAutoStop.value && !keepCurrentOffWindow) {
            tripEvents.trySend(TripEvent.WatchdogTick)
        }
    }

    fun getAllTrips(): Flow<List<TripEntity>> = tripDao.getAllTrips()

    fun getTripById(tripId: Long): Flow<TripEntity?> = tripDao.getTripByIdFlow(tripId)

    fun getDataPointsForTrip(tripId: Long): Flow<List<TripDataPointEntity>> =
        dataPointDao.getDataPointsForTrip(tripId)

    fun getStatsForTrip(tripId: Long): Flow<TripStatsEntity?> =
        statsDao.getStatsForTripFlow(tripId)

    fun getAllTripStats(): Flow<List<TripStatsEntity>> = statsDao.getAllTripStats()

    fun getAvgSohPerTrip(): Flow<List<TripSohSummary>> = dataPointDao.getAvgSohPerTrip()

    /**
     * One-time (2.9.1): recover the precise statistic_battery_soh that older data points already
     * carry inside their rawJson into the new [sohPrecise] column, so the degradation chart/report
     * are decimal-accurate across all history and never show a false up-tick where rounded data
     * meets precise. Id-paginated + transactional so it scales to a large database without loading
     * it all at once. Caller guards this to run only once (see DashboardViewModel.init).
     */
    suspend fun backfillPreciseSoh() = withContext(Dispatchers.IO) {
        val regex = Regex("\"statistic_battery_soh\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)")
        var afterId = 0L
        var updated = 0
        while (true) {
            val rows = dataPointDao.pointsNeedingSohBackfill(afterId, 2000)
            if (rows.isEmpty()) break
            afterId = rows.last().id
            val parsed = rows.mapNotNull { r ->
                val v = regex.find(r.rawJson)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
                if (v != null && v > 0.0) r.id to v else null
            }
            if (parsed.isNotEmpty()) {
                database.withTransaction {
                    parsed.forEach { (id, v) -> dataPointDao.setSohPrecise(id, v) }
                }
                updated += parsed.size
            }
        }
        if (updated > 0) Log.i(TAG, "Precise-SoH backfill updated $updated data point(s)")
    }

    fun getSegmentsForTrip(tripId: Long) = segmentDao.getSegmentsForTrip(tripId)

    suspend fun deleteTrips(tripIds: List<Long>) {
        database.withTransaction {
            tripIds.forEach { tripId ->
                tripDao.deleteTripById(tripId)
                dataPointDao.deleteDataPointsForTrip(tripId)
                statsDao.deleteStatsForTrip(tripId)
                segmentDao.deleteSegmentsForTrip(tripId)
                tagDao.clearTagsForTrip(tripId)
            }
        }
    }

    suspend fun deleteTrip(tripId: Long) {
        database.withTransaction {
        tripDao.deleteTripById(tripId)
        dataPointDao.deleteDataPointsForTrip(tripId)
        statsDao.deleteStatsForTrip(tripId)
        segmentDao.deleteSegmentsForTrip(tripId)
        tagDao.clearTagsForTrip(tripId)
        }
    }

    // ── Tags ────────────────────────────────────────────────────────────────────

    fun getAllTags(): Flow<List<TagEntity>> = tagDao.getAllTags()

    fun getAllTripTagRefs(): Flow<List<TripTagCrossRef>> = tagDao.getAllTripTagRefs()

    fun getTagsForTrip(tripId: Long): Flow<List<TagEntity>> = tagDao.getTagsForTrip(tripId)

    /**
     * Returns the tag named [name] (case-insensitive), creating it if absent. New
     * tags get the next palette colour by tag count, so distinct tags get distinct
     * colours until the palette wraps.
     */
    suspend fun createOrGetTag(name: String): TagEntity? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        tagDao.getTagByName(trimmed)?.let { return it }
        val colorIndex = tagDao.getTagCount() % TAG_PALETTE_SIZE
        val id = tagDao.insertTag(TagEntity(name = trimmed, colorIndex = colorIndex))
        // insert(IGNORE) returns -1 on a unique-name race — re-read the winner.
        return if (id > 0) TagEntity(id = id, name = trimmed, colorIndex = colorIndex)
               else tagDao.getTagByName(trimmed)
    }

    suspend fun addTagToTrip(tripId: Long, tagId: Long) =
        tagDao.addTagToTrip(TripTagCrossRef(tripId, tagId))

    suspend fun removeTagFromTrip(tripId: Long, tagId: Long) =
        tagDao.removeTagFromTrip(tripId, tagId)

    /** Bulk-applies [tagId] to every trip in [tripIds] (History selection mode). */
    suspend fun applyTagToTrips(tagId: Long, tripIds: List<Long>) {
        database.withTransaction {
            tripIds.forEach { tagDao.addTagToTrip(TripTagCrossRef(it, tagId)) }
        }
    }

    /** Renames [tagId] to [newName] unless another tag already holds that name. */
    suspend fun renameTag(tagId: Long, newName: String): Boolean {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return false
        val existing = tagDao.getTagByName(trimmed)
        if (existing != null && existing.id != tagId) return false
        val current = tagDao.getAllTags().first().firstOrNull { it.id == tagId } ?: return false
        tagDao.updateTag(current.copy(name = trimmed))
        return true
    }

    suspend fun recolorTag(tagId: Long, colorIndex: Int) {
        val current = tagDao.getAllTags().first().firstOrNull { it.id == tagId } ?: return
        tagDao.updateTag(current.copy(colorIndex = colorIndex))
    }

    /** Deletes a tag and all its trip links. */
    suspend fun deleteTag(tagId: Long) {
        database.withTransaction {
            tagDao.clearTripsForTag(tagId)
            tagDao.deleteTagById(tagId)
        }
    }

    /**
     * Combines two recorded trips that were really one journey split by a brief
     * stop (petrol/charge/red-light timeout) into a single trip.
     *
     * The earlier trip (by start time) survives — its id and favourite flag are
     * kept; the later trip's data points and segments are re-pointed onto it and
     * the later trip row + its stats are deleted.
     *
     * Crucially, the merged trip carries *real* numbers, not timeline-derived ones:
     *   • boundary anchors come from the real endpoints — start of the earlier trip,
     *     end (SoC / SoC-panel / position) of the later trip;
     *   • cumulative quantities are the SUM of each trip's own recorded value, so a
     *     mid-merge discharge-counter reset (BYD resets it every car-on cycle) can't
     *     corrupt energy, and untracked driving in the gap can't inflate distance.
     *     The end odometer/discharge anchors are synthesised from those sums so the
     *     [TripEntity] getters stay self-consistent;
     *   • duration is dur1 + dur2 (active driving time), realised by inflating
     *     [TripEntity.offStateDurationMs] to absorb the inter-trip gap — exactly the
     *     "addition, not wallclock" rule the duration getter already follows.
     *
     * Only contiguous trips are accepted (see [checkMergeEligibility]); the call is
     * a no-op Failure otherwise.
     */
    suspend fun mergeTrips(tripIdA: Long, tripIdB: Long): MergeResult = withContext(Dispatchers.IO) {
        if (tripIdA == tripIdB) return@withContext MergeResult.Failure("Cannot merge a trip with itself")
        val a = tripDao.getTripById(tripIdA)
            ?: return@withContext MergeResult.Failure("Trip not found")
        val b = tripDao.getTripById(tripIdB)
            ?: return@withContext MergeResult.Failure("Trip not found")

        val eligibility = checkMergeEligibility(a, b)
        if (!eligibility.eligible) {
            return@withContext MergeResult.Failure(eligibility.reason ?: "Trips cannot be merged")
        }

        // Earlier trip survives; later trip is absorbed.
        val first  = if (a.startTime <= b.startTime) a else b
        val second = if (a.startTime <= b.startTime) b else a

        // Cumulative quantities: each trip's OWN recorded value, then summed.
        val dist1   = first.distance        ?: 0.0
        val dist2   = second.distance       ?: 0.0
        val energy1 = first.energyConsumed  ?: 0.0
        val energy2 = second.energyConsumed ?: 0.0
        val dur1    = first.duration        ?: 0L
        val dur2    = second.duration       ?: 0L

        val mergedEndTime   = second.endTime ?: first.endTime ?: first.startTime
        val mergedWallclock = (mergedEndTime - first.startTime).coerceAtLeast(0L)
        val mergedActiveMs  = (dur1 + dur2).coerceAtLeast(0L)
        // offState absorbs the inter-trip gap (and each trip's own off windows) so
        // the duration getter = dur1 + dur2 rather than the full wallclock span.
        val mergedOffStateMs = (mergedWallclock - mergedActiveMs).coerceAtLeast(0L)

        // Synthesise end anchors so the getters return the summed cumulative values
        // while the start anchors stay at the real start of the earlier trip.
        val mergedEndOdometer = first.startOdometer + dist1 + dist2
        val mergedEndDischarge = first.startTotalDischarge + energy1 + energy2

        // Aggregate min/max metrics across both trips.
        val mergedMaxSpeed      = maxOf(first.maxSpeed, second.maxSpeed)
        val mergedMaxPower      = maxOf(first.maxPower, second.maxPower)
        val mergedMaxRegenPower = maxOf(first.maxRegenPower, second.maxRegenPower)
        val mergedMinSoc        = minOf(first.minSoc, second.minSoc)
        val mergedMaxCellTemp   = maxOf(first.maxBatteryCellTemp, second.maxBatteryCellTemp)
        val mergedMinCellTemp   = minOf(first.minBatteryCellTemp, second.minBatteryCellTemp)
        // avgBatteryTemp: duration-weighted mean of whatever each trip recorded.
        val mergedAvgBatteryTemp = run {
            val t1 = first.avgBatteryTemp
            val t2 = second.avgBatteryTemp
            val w1 = dur1.coerceAtLeast(0L)
            val w2 = dur2.coerceAtLeast(0L)
            when {
                t1 > 0.0 && t2 > 0.0 && (w1 + w2) > 0L -> (t1 * w1 + t2 * w2) / (w1 + w2)
                t1 > 0.0 && t2 > 0.0                    -> (t1 + t2) / 2.0
                t1 > 0.0                                -> t1
                else                                    -> t2
            }
        }

        val merged = first.copy(
            endTime            = mergedEndTime,
            endOdometer        = mergedEndOdometer,
            endSoc             = second.endSoc,
            endSocPanel        = second.endSocPanel,
            endTotalDischarge  = mergedEndDischarge,
            isActive           = false,
            maxSpeed           = mergedMaxSpeed,
            maxPower           = mergedMaxPower,
            maxRegenPower      = mergedMaxRegenPower,
            avgBatteryTemp     = mergedAvgBatteryTemp,
            minSoc             = mergedMinSoc,
            maxBatteryCellTemp = mergedMaxCellTemp,
            minBatteryCellTemp = mergedMinCellTemp,
            offStateDurationMs = mergedOffStateMs,
            isFavourite        = first.isFavourite || second.isFavourite
        )

        database.withTransaction {
            // Move the later trip's points/segments/tags onto the survivor, then
            // update the survivor row and drop the now-empty later trip + its stats.
            dataPointDao.reassignTripId(second.id, first.id)
            segmentDao.reassignTripId(second.id, first.id)
            tagDao.reassignTripTags(second.id, first.id)  // union of both trips' tags
            tagDao.clearTagsForTrip(second.id)            // drop any leftover duplicates
            tripDao.updateTrip(merged)
            statsDao.deleteStatsForTrip(second.id)
            tripDao.deleteTripById(second.id)
        }

        // Rebuild the derived stats (route, histograms, avg speed) from the combined
        // points/segments. Reads trip.distance/duration/energyConsumed — now the
        // summed values — so headline figures stay consistent with the anchors above.
        try {
            calculateTripStats(first.id)
        } catch (e: Exception) {
            Log.e(TAG, "Stats calculation failed for merged trip ${first.id}", e)
        }

        Log.i(TAG, "Merged trip ${second.id} into ${first.id} " +
            "(dist=${"%.2f".format(dist1 + dist2)}km energy=${"%.2f".format(energy1 + energy2)}kWh " +
            "duration=${mergedActiveMs / 60_000}min)")
        MergeResult.Success(first.id)
    }

    /** Flags/unflags a trip as favourite — favourites are exempt from all trimming. */
    suspend fun setTripFavourite(tripId: Long, favourite: Boolean) {
        tripDao.setFavourite(tripId, favourite)
    }

    fun setAutoTripDetection(enabled: Boolean) {
        autoTripDetection = enabled
        prefs.edit().putBoolean(PREF_AUTO_TRIP, enabled).apply()
    }

    fun isAutoTripDetectionEnabled(): Boolean = autoTripDetection

    /**
     * Reduces storage for old trips by removing redundant data points.
     *
     * NOT auto-invoked. As of the favourites release, automatic thinning was
     * removed — point-thinning is now exclusively user-initiated via the manual
     * "Trim database" action (DatabaseTrimmer). This tiered thinner is retained
     * only for potential future use behind an explicit user action; nothing calls
     * it automatically.
     *
     * Tiered thinning — keeps recent trips at full resolution, progressively
     * decimates older trips. First and last points of each trip are always
     * preserved, favourited trips are skipped, and trip-level stats (distance,
     * energy, efficiency) live in TripEntity / TripStatsEntity and are unaffected.
     *
     * Tier policy (trip age → keep one point per N seconds):
     *   < 45 days   →  untouched (full resolution)
     *   45–90 days  →  1 point / 2 s
     *   90–180 days →  1 point / 4 s
     *   > 180 days  →  1 point / 10 s
     */
    suspend fun thinOldDataPoints() {
        val nowMs = System.currentTimeMillis()

        // Tier boundaries in milliseconds. Recent trips keep full fidelity — nothing
        // is thinned until a trip is older than 45 days. Favourited trips are never
        // thinned regardless of age.
        val day45Ms  =  45L * 24L * 3_600_000L
        val day90Ms  =  90L * 24L * 3_600_000L
        val day180Ms = 180L * 24L * 3_600_000L

        // Trips older than 45 days are candidates — anything newer is untouched
        val cutoffMs = nowMs - day45Ms
        val oldTrips = tripDao.getCompletedTripsBefore(cutoffMs)
            .filterNot { it.isFavourite }   // favourites keep full data-point density

        if (oldTrips.isEmpty()) {
            Log.i(TAG, "thinOldDataPoints: no non-favourite trips older than 45 days")
            return
        }

                var totalDeleted = 0

        oldTrips.forEach { trip ->
            val ageMs = nowMs - trip.startTime

            // Determine keep interval for this trip's age tier
            val keepIntervalMs = when {
                ageMs < day90Ms  -> 2_000L   //  45–90 days:  1 point/2 s
                ageMs < day180Ms -> 4_000L   //  90–180 days: 1 point/4 s
                else             -> 10_000L  //    > 180 days: 1 point/10 s
            }

            val points = dataPointDao.getDataPointsForTripSync(trip.id)
            if (points.size < 3) return@forEach

            val toDelete = mutableListOf<Long>()
            var lastKeptTimestamp = points.first().timestamp

            // Always keep index 0 (trip start) and last index (trip end).
            // For everything in between, keep only if far enough from last kept.
            for (i in 1 until points.lastIndex) {
                val pt = points[i]
                if (pt.timestamp - lastKeptTimestamp >= keepIntervalMs) {
                    lastKeptTimestamp = pt.timestamp
                } else {
                    toDelete.add(pt.id)
                }
            }

            if (toDelete.isNotEmpty()) {
                dataPointDao.deleteDataPointsByIds(toDelete)
                totalDeleted += toDelete.size
                Log.d(TAG, "Trip ${trip.id} (${ageMs/86_400_000}d old): " +
                    "removed ${toDelete.size}/${points.size} points " +
                    "(keep interval ${keepIntervalMs/1000}s)")
            }
        }

        Log.i(TAG, "thinOldDataPoints: removed $totalDeleted points across ${oldTrips.size} trips")
    }

    // ── Singleton ─────────────────────────────────────────────────────────────

    fun close() {
        scope.cancel()
        tripEvents.close()
    }

    companion object {
        private const val PREF_AUTO_TRIP = "auto_trip_detection"
        private const val PREF_OFFSTATE_BACKFILL_DONE = "offstate_duration_backfill_v4"
        private const val PREF_DURATION_REPAIR_DONE = "duration_repair_v1"
        private const val PREF_FLAT_END_SOC_PANEL_REPAIR_DONE = "flat_end_soc_panel_repair_v1"

        /**
         * Gap between consecutive recorded data points (or between the last point
         * and trip-end) that's considered an off-state window rather than normal
         * polling variance. Normal in-drive sampling is 1–5 s, so 20 s is well
         * above the noise floor and below any plausible mid-traffic event.
         */
        private const val OFFSTATE_GAP_THRESHOLD_MS = 20_000L

        /**
         * Largest time gap between the end of the earlier trip and the start of the
         * later one for the two to still count as the same journey. Covers petrol /
         * charging / meal stops while still rejecting an accidental merge of trips
         * from different parts of the day or different days.
         */
        private const val MERGE_MAX_TIME_GAP_MS = 6L * 60L * 60L * 1000L   // 6 hours

        /**
         * Largest odometer gap (km) between the earlier trip's end and the later
         * trip's start. A genuine brief stop leaves the odometer unchanged; a small
         * tolerance allows for repositioning / GPS-vs-odometer drift. A larger gap
         * means real untracked driving happened between them — not a clean merge.
         */
        private const val MERGE_MAX_ODO_GAP_KM = 5.0

        /**
         * Decides whether two completed trips may be merged. Pure so the UI can use
         * it to gate the merge action and surface a reason without touching the DB.
         * Order-independent: the earlier trip (by start time) is treated as first.
         */
        fun checkMergeEligibility(a: TripEntity, b: TripEntity): MergeEligibility {
            if (a.id == b.id) return MergeEligibility(false, "Cannot merge a trip with itself")
            if (a.isActive || b.isActive)
                return MergeEligibility(false, "Cannot merge an active trip")
            if (a.endTime == null || b.endTime == null)
                return MergeEligibility(false, "Both trips must be completed")

            val first  = if (a.startTime <= b.startTime) a else b
            val second = if (a.startTime <= b.startTime) b else a
            val firstEnd = first.endTime ?: return MergeEligibility(false, "Both trips must be completed")

            if (second.startTime < firstEnd)
                return MergeEligibility(false, "These trips overlap in time")
            if (second.startTime - firstEnd > MERGE_MAX_TIME_GAP_MS)
                return MergeEligibility(false, "These trips are too far apart to be one journey")

            val firstEndOdo = first.endOdometer
            if (firstEndOdo != null) {
                val odoGap = second.startOdometer - firstEndOdo
                if (odoGap < -0.5 || odoGap > MERGE_MAX_ODO_GAP_KM)
                    return MergeEligibility(false, "These trips aren't continuous on the odometer")
            }
            return MergeEligibility(true)
        }

        /**
         * Sum of gaps in the data-point stream — and the gap between the last
         * recorded point and [tripEndMs] — that exceed [OFFSTATE_GAP_THRESHOLD_MS].
         * These represent time when the car was off but the trip stayed open
         * (the configurable engine-off resume window, plus the trailing window
         * before the timeout closes the trip).
         */
        fun computeOffStateDurationMs(
            points: List<TripDataPointEntity>,
            tripEndMs: Long?
        ): Long {
            if (points.isEmpty()) return 0L
            var off = 0L
            for (i in 1 until points.size) {
                val gap = points[i].timestamp - points[i - 1].timestamp
                if (gap > OFFSTATE_GAP_THRESHOLD_MS) off += gap
            }
            if (tripEndMs != null) {
                val tail = tripEndMs - points.last().timestamp
                if (tail > OFFSTATE_GAP_THRESHOLD_MS) off += tail
            }
            return off
        }

        @Volatile private var INSTANCE: TripRepository? = null

        fun getInstance(context: Context): TripRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: TripRepository(context.applicationContext).also { INSTANCE = it }
            }

        /**
         * Creates a fresh TripRepository backed by [db] for integration testing.
         * Bypasses the singleton so each test gets an isolated instance.
         */
        @androidx.annotation.VisibleForTesting
        fun createForTesting(db: BydStatsDatabase, context: Context): TripRepository {
            val repo = TripRepository(context)
            // Inject the test database via reflection — avoids breaking the private constructor
            listOf(
                "database"     to db,
                "tripDao"      to db.tripDao(),
                "dataPointDao" to db.tripDataPointDao(),
                "statsDao"     to db.tripStatsDao(),
                "segmentDao"   to db.tripSegmentDao(),
                "tagDao"       to db.tagDao()
            ).forEach { (name, value) ->
                TripRepository::class.java.getDeclaredField(name)
                    .also { it.isAccessible = true }
                    .set(repo, value)
            }
            return repo
        }
    }
}
