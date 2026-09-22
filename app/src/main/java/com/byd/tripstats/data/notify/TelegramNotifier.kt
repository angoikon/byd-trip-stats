package com.byd.tripstats.data.notify

import android.content.Context
import android.util.Log
import com.byd.tripstats.data.backup.TelegramManager
import com.byd.tripstats.util.DiagLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * The Telegram delivery channel for vehicle events: the per-event switches, and the queue that
 * gets a message out even when the car had no connection at the moment it happened.
 *
 * It uses the same bot the database backup already does, so there is no new account, server or
 * token to set up. What an event *is* — and whether it is worth announcing at all — belongs to
 * [VehicleEvents], which calls [send] here once it has decided.
 *
 * ## When an event can reach this class at all
 *
 * Events fire from the telemetry service, so they follow the service's lifetime — and that
 * lifetime covers parked time in the default **Always On** background mode, where the service
 * keeps polling at 30 s after the car is switched off (see `VehicleTelemetryService`'s self-stop
 * branch, which is skipped for [com.byd.tripstats.data.preferences.OffStateMode.ENABLED]). The
 * MCU cuts **Wi-Fi** roughly 15 min after a park, but the head unit stays on **mobile data**, and
 * Telegram is an internet API — the same reason MQTT to a cloud broker keeps publishing from a
 * parked car while MQTT to a LAN broker does not.
 *
 * The honest limits are:
 *  - **Deep Sleep** mode — nothing runs while parked, so nothing fires until the next drive.
 *  - **Minimal** mode — the service is revived by the 90-minute keepalive alarm, so an off-state
 *    event arrives up to 90 min late.
 *  - **A long park** — once the head unit itself powers down (~10 h), nothing can run.
 *  - **DiLink-5** — the OEM force-stops the app at ignition-off, so trips and off-state charges
 *    are only closed on the *next* start, and a reconstructed close is deliberately not
 *    announced (see [VehicleEvents.onTripFinished]).
 *
 * ## Delivery
 *
 * A send that fails (no network in the window between the Wi-Fi cut and the mobile-data fallback,
 * a flaky tunnel, Telegram itself) is queued to [KEY_OUTBOX] and retried in the background, and
 * the queue survives process death — which matters precisely because the events that need it most
 * fire when nobody is looking at the car. Messages older than a day are dropped rather than
 * delivered as stale news.
 */
class TelegramNotifier private constructor(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val telegram = TelegramManager.getInstance(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val outboxLock = Mutex()

    /** True while a backoff chain is draining the outbox; guards against one chain per event. */
    @Volatile private var retryRunning = false

    // ── Settings ──────────────────────────────────────────────────────────────
    // SharedPreferences rather than DataStore: every gate is read from the telemetry
    // thread at event time, where a suspending read would be the wrong shape.

    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, false))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _tripSummary = MutableStateFlow(prefs.getBoolean(KEY_TRIP_SUMMARY, true))
    val tripSummaryEnabled: StateFlow<Boolean> = _tripSummary.asStateFlow()

    // Default OFF, unlike the other two: a charge finishing is the one event whose timing the
    // user doesn't choose, and an overnight charge that completes at 03:00 sends at 03:00.
    // Opting in is a decision about being woken, so it is made deliberately.
    private val _chargingFinished = MutableStateFlow(prefs.getBoolean(KEY_CHARGING, false))
    val chargingFinishedEnabled: StateFlow<Boolean> = _chargingFinished.asStateFlow()

    private val _cellImbalance = MutableStateFlow(prefs.getBoolean(KEY_CELL_IMBALANCE, true))
    val cellImbalanceEnabled: StateFlow<Boolean> = _cellImbalance.asStateFlow()

    fun setEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        _enabled.value = value
        if (value) scope.launch { flushOutbox() }
    }

    fun setTripSummaryEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_TRIP_SUMMARY, value).apply()
        _tripSummary.value = value
    }

    fun setChargingFinishedEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_CHARGING, value).apply()
        _chargingFinished.value = value
    }

    fun setCellImbalanceEnabled(value: Boolean) {
        prefs.edit().putBoolean(KEY_CELL_IMBALANCE, value).apply()
        _cellImbalance.value = value
    }

    /** True when a bot is connected AND the master switch is on — nothing is queued otherwise. */
    private fun active(): Boolean = _enabled.value && telegram.config.value != null

    /**
     * Queues [event] for the chat. Called by [VehicleEvents], which owns what an event *is* and
     * whether its switch is on; this class owns only whether it can be delivered and what to do
     * when it can't.
     */
    fun send(event: VehicleEvent) {
        if (!active()) return
        enqueue(event.key, event.telegramHtml)
    }

    /** "Send test message" in Settings. Bypasses the per-event gates but not the bot check. */
    suspend fun sendTest(): Boolean =
        telegram.config.value != null && telegram.sendMessage(TelegramEventMessages.test())

    /**
     * Retries anything the queue still holds. Called when the telemetry service starts,
     * i.e. at the moment the car wakes and network usually comes back with it.
     */
    fun flushPending() {
        if (!active()) return
        scope.launch { flushOutbox() }
    }

    // ── Outbox ────────────────────────────────────────────────────────────────

    /**
     * [key] identifies the event, not the message, so the same trip or charging session
     * can never be announced twice — the close paths in both repositories can legitimately
     * run again for the same row (a recovery pass, a re-close after a service restart).
     * The last [RECENT_KEYS_MAX] keys are remembered, so an interleaved trip/charge/trip
     * sequence is deduplicated too, not just an immediate repeat.
     */
    private fun enqueue(key: String, text: String) {
        val recent = prefs.getString(KEY_RECENT_EVENTS, "")!!.split('\n').filter { it.isNotBlank() }
        if (key in recent) {
            Log.d(TAG, "Skipping duplicate event $key")
            return
        }
        val updated = (recent + key).takeLast(RECENT_KEYS_MAX).joinToString("\n")
        prefs.edit().putString(KEY_RECENT_EVENTS, updated).apply()
        scope.launch {
            outboxLock.withLock { appendToOutbox(key, text) }
            flushOutbox()
        }
    }

    private fun appendToOutbox(key: String, text: String) {
        val queue = readOutbox().toMutableList()
        queue += QueuedMessage(key, text, System.currentTimeMillis())
        // Oldest first out: a full queue means the link has been down for a while,
        // and the newest events are the ones still worth delivering.
        while (queue.size > OUTBOX_MAX_SIZE) queue.removeAt(0)
        writeOutbox(queue)
    }

    /**
     * Drains the queue oldest-first, stopping at the first failure so ordering is kept.
     * On failure the remaining messages stay on disk and a backoff chain retries them;
     * [retryRunning] keeps a burst of events from starting a chain each.
     */
    private suspend fun flushOutbox() {
        outboxLock.withLock {
            val now = System.currentTimeMillis()
            val stored = readOutbox()
            // Stale news is worse than no news: a trip summary from yesterday's drive
            // arriving at lunchtime today reads as a live event.
            val queue = stored.filter { now - it.timestamp <= OUTBOX_MAX_AGE_MS }
            if (queue.isEmpty()) {
                if (stored.isNotEmpty()) writeOutbox(emptyList())
                return@withLock
            }
            var failed = false
            val remaining = queue.toMutableList()
            for (message in queue) {
                if (!telegram.sendMessage(message.text)) {
                    failed = true
                    break
                }
                remaining.removeAt(0)
                DiagLog.event(context, TAG, "sent ${message.key}")
            }
            writeOutbox(remaining)
            // retryRunning is only ever set here and cleared by the chain itself, so a
            // burst of events queued while the link is down starts one chain, not one each.
            if (failed && remaining.isNotEmpty() && !retryRunning) {
                retryRunning = true
                scope.launch { runRetryChain() }
            }
        }
    }

    private suspend fun runRetryChain() {
        try {
            for (delayMs in RETRY_DELAYS_MS) {
                delay(delayMs)
                if (!active()) break
                flushOutbox()
                if (readOutbox().isEmpty()) break
            }
            val left = readOutbox().size
            if (left > 0) {
                DiagLog.event(
                    context, TAG,
                    "$left Telegram notification(s) still undelivered after retries — " +
                        "queued until the next event or service start",
                )
            }
        } finally {
            retryRunning = false
        }
    }

    private data class QueuedMessage(val key: String, val text: String, val timestamp: Long)

    private fun readOutbox(): List<QueuedMessage> = try {
        val raw = prefs.getString(KEY_OUTBOX, null)
        if (raw.isNullOrBlank()) emptyList() else {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                QueuedMessage(
                    key = o.optString("key"),
                    text = o.getString("text"),
                    timestamp = o.optLong("ts", System.currentTimeMillis()),
                )
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Outbox unreadable, dropping: ${e.message}")
        emptyList()
    }

    private fun writeOutbox(queue: List<QueuedMessage>) {
        val arr = JSONArray()
        queue.forEach { m ->
            arr.put(JSONObject().apply {
                put("key", m.key)
                put("text", m.text)
                put("ts", m.timestamp)
            })
        }
        // commit(), not apply(): these writes happen while the car is parking and the
        // process can be killed at any moment — an un-flushed queue is a lost alert.
        prefs.edit().putString(KEY_OUTBOX, arr.toString()).commit()
    }

    companion object {
        private const val TAG = "TelegramNotifier"
        private const val PREFS_NAME = "telegram_notify_prefs"

        private const val KEY_ENABLED = "notify_enabled"
        private const val KEY_TRIP_SUMMARY = "notify_trip_summary"
        private const val KEY_CHARGING = "notify_charging_finished"
        private const val KEY_CELL_IMBALANCE = "notify_cell_imbalance"
        private const val KEY_OUTBOX = "notify_outbox"
        private const val KEY_RECENT_EVENTS = "notify_recent_event_keys"

        /** How many event keys are remembered for deduplication. */
        private const val RECENT_KEYS_MAX = 10

        /** Shorter drives are parking manoeuvres, not trips worth a push. */
        private const val MIN_TRIP_DISTANCE_KM = 0.5

        /** Below this a "session" is a charger handshake flap, not a charge. */
        private const val MIN_CHARGE_KWH = 0.5

        /**
         * Backstop for a live-path close whose trip nonetheless ended long ago. Deliberately
         * loose: the car-off timeout is user-configurable with no upper bound, so this must
         * never be what decides an ordinary close — [onTripFinished]'s `liveClose` does that.
         */
        private const val TRIP_SUMMARY_MAX_AGE_MS = 6 * 60 * 60 * 1000L

        private const val OUTBOX_MAX_SIZE = 20
        private const val OUTBOX_MAX_AGE_MS = 24 * 60 * 60 * 1000L
        private val RETRY_DELAYS_MS = longArrayOf(60_000L, 5 * 60_000L, 20 * 60_000L)

        @Volatile private var INSTANCE: TelegramNotifier? = null

        fun getInstance(context: Context): TelegramNotifier =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: TelegramNotifier(context.applicationContext).also { INSTANCE = it }
            }
    }
}
