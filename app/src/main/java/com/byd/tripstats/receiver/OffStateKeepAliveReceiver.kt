package com.byd.tripstats.receiver

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.util.Log
import com.byd.tripstats.data.preferences.OffStateMode
import com.byd.tripstats.data.preferences.PreferencesManager
import com.byd.tripstats.service.VehicleTelemetryService
import com.byd.tripstats.util.DiagLog
import com.byd.tripstats.util.McuWakeHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Dedicated off-state keepalive receiver.
 *
 * Fires every [INTERVAL_MS] (90 minutes) after ACC_OFF to:
 *   1. Call McuWakeHelper.keepAlive() — resets the MCU WiFi-cut countdown
 *   2. Start VehicleTelemetryService — reviving it samples BMS/charging state
 *      once per tick, so charging sessions that begin while the car is off
 *      (e.g. overnight AC charging) get captured as real-time data points
 *      rather than a lossy SoC-delta reconstruction.
 *   3. Reschedule itself for the next interval — unbounded, stopped only on
 *      ACC_ON by [cancel]. Overnight charging can run 4–8 hours, so a
 *      fixed iteration cap would silently drop mid-session sampling.
 *
 * Unlike the in-service 5-minute coroutine loop, this receiver survives
 * process death — AlarmManager fires the broadcast and Android recreates
 * the process to handle it, giving keepalive a chance even after the OS
 * kills our PID.
 *
 * Redundancy: two independent alarms (primary + backup) are kept live at all
 * times. If the primary is dropped by the OS, the backup fires [BACKUP_OFFSET_MS]
 * later and re-seeds both. Either alarm firing is sufficient to keep the chain
 * alive indefinitely.
 */
class OffStateKeepaliveReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // The headless-system-user copy on Android Automotive stays idle (AaosPlatform).
        if (com.byd.tripstats.sdk.AaosPlatform.isHeadlessSystemUserInstance) return
        val iteration = intent.getIntExtra(EXTRA_ITERATION, 0)
        val appContext = context.applicationContext
        DiagLog.event(appContext, TAG, "fired iteration=$iteration data=${intent.data}")

        // Safety net: if the user switched to deep sleep mode after this alarm was
        // already scheduled, cancel the chain and exit without doing any work.
        if (PreferencesManager(appContext).getCachedOffStateMode() == OffStateMode.DEEP_SLEEP) {
            DiagLog.event(appContext, TAG, "deep sleep mode — cancelling chain and returning")
            cancel(appContext)
            return
        }

        // Record that the keepalive actually fired. Surfaced in Settings so a user can
        // tell whether Minimal works on their vehicle: on head units that fully power
        // off at park (no drain in Deep Sleep), this alarm can't fire and this timestamp
        // never advances while parked — the signal to use Always On for parked data.
        recordFired(appContext)

        // Acquire a partial wake lock for the duration of the async work.
        // AlarmManager.RTC_WAKEUP holds a wake lock only until onReceive()
        // returns — the goAsync() coroutine runs unprotected after that.
        val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG).apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }

        // Reschedule both alarms synchronously before any async work so that
        // even a crash mid-execution doesn't break the chain.
        try {
            VehicleTelemetryService.start(appContext)
        } catch (e: Exception) {
            Log.w(TAG, "Service start error: ${e.message}")
        }
        schedule(appContext, iteration + 1, source = "self-reschedule")

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                withTimeout(10_000L) {
                    McuWakeHelper.keepAlive(appContext)
                }
                Log.i(TAG, "MCU keepalive sent (iteration=$iteration)")
            } catch (e: Exception) {
                Log.w(TAG, "MCU keepalive failed/timed out (iteration=$iteration): ${e.message}")
            } finally {
                pending.finish()
                if (wl.isHeld) wl.release()
            }
        }
    }

    companion object {
        private const val TAG = "OffStateKeepalive"
        const val ACTION = "com.byd.tripstats.action.OFF_STATE_KEEPALIVE"
        private const val EXTRA_ITERATION = "iteration"
        private const val WAKE_TAG = "bydstats:offstate_keepalive"
        private const val WAKE_LOCK_TIMEOUT_MS = 15_000L

        // 90 minutes — lets the MCU sleep between pokes (WiFi cuts after ~15 min,
        // system genuinely idles for ~75 min per cycle). NOTE: this alarm is the
        // ONLY reliable trigger for off-state EV charging. android.intent.action
        // .POWER_CONNECTED only reflects the head unit's own 12V/USB power, not the
        // traction battery being plugged in, so it does not fire when the EV starts
        // charging while parked. A charge session that begins off-state is therefore
        // detected at most ~90 min late here (Minimal mode); in Deep Sleep mode this
        // chain isn't scheduled at all, so off-state charging is only reconstructed
        // from the SoC delta on the next ACC_ON.
        private const val INTERVAL_MS = 90 * 60 * 1000L

        // Backup fires 5 min after the primary. At 90 min cadence, if the primary
        // alarm is dropped by the OS the backup re-seeds the chain before the next cycle.
        private const val BACKUP_OFFSET_MS = 5 * 60 * 1000L

        private val REQUEST_CODE = ACTION.hashCode()
        private val BACKUP_REQUEST_CODE = (ACTION + "_backup").hashCode()

        // ── Health stats (surfaced in Settings) ──────────────────────────────
        private const val STATS_PREFS = "offstate_keepalive_stats"
        private const val KEY_LAST_FIRED = "last_fired_ms"
        private const val KEY_FIRE_COUNT = "fire_count"

        private fun statsPrefs(context: Context) =
            context.getSharedPreferences(STATS_PREFS, Context.MODE_PRIVATE)

        /** Record a real keepalive fire (wall-clock + running count). */
        fun recordFired(context: Context) {
            val p = statsPrefs(context)
            p.edit()
                .putLong(KEY_LAST_FIRED, System.currentTimeMillis())
                .putInt(KEY_FIRE_COUNT, p.getInt(KEY_FIRE_COUNT, 0) + 1)
                .apply()
        }

        /** Wall-clock ms of the last keepalive fire, or 0 if it has never fired. */
        fun lastFiredMs(context: Context): Long = statsPrefs(context).getLong(KEY_LAST_FIRED, 0L)

        /** Total number of keepalive fires recorded since install. */
        fun fireCount(context: Context): Int = statsPrefs(context).getInt(KEY_FIRE_COUNT, 0)

        /** Whether a keepalive alarm is currently armed. */
        fun isScheduled(context: Context): Boolean = pendingIntent(
            context, 0, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) != null

        private fun pendingIntent(context: Context, iteration: Int, flags: Int): PendingIntent? {
            val intent = Intent(context, OffStateKeepaliveReceiver::class.java).apply {
                action = ACTION
                data = Uri.parse("bydstats://keepalive")
                putExtra(EXTRA_ITERATION, iteration)
            }
            return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
        }

        private fun pendingIntentBackup(context: Context, iteration: Int, flags: Int): PendingIntent? {
            val intent = Intent(context, OffStateKeepaliveReceiver::class.java).apply {
                action = ACTION
                data = Uri.parse("bydstats://keepalive-backup")
                putExtra(EXTRA_ITERATION, iteration)
            }
            return PendingIntent.getBroadcast(context, BACKUP_REQUEST_CODE, intent, flags)
        }

        fun schedule(context: Context, iteration: Int = 0, source: String = "unknown") {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                ?: run {
                    DiagLog.event(context, TAG, "schedule failed — AlarmManager unavailable (source=$source)")
                    return
                }
            val now = System.currentTimeMillis()

            pendingIntent(
                context, iteration,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )?.let { pi ->
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, now + INTERVAL_MS, pi)
            }

            pendingIntentBackup(
                context, iteration,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )?.let { pi ->
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    now + INTERVAL_MS + BACKUP_OFFSET_MS,
                    pi,
                )
            }
            DiagLog.event(
                context, TAG,
                "scheduled iteration=$iteration source=$source " +
                    "primary=+${INTERVAL_MS / 1000}s backup=+${(INTERVAL_MS + BACKUP_OFFSET_MS) / 1000}s",
            )
        }

        /**
         * Re-arm the keepalive chain only if no alarm is currently pending.
         * Cheap probe: `FLAG_NO_CREATE` returns null when no matching PendingIntent
         * exists, so we never accidentally upsert. Use this from any code path
         * that may run while the car is off and we want belt-and-braces coverage
         * without overwriting a healthy schedule.
         */
        fun ensureScheduled(context: Context, source: String) {
            val existing = pendingIntent(
                context, 0,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            )
            if (existing == null) {
                DiagLog.event(context, TAG, "ensureScheduled: alarm missing — arming (source=$source)")
                schedule(context, iteration = 0, source = "ensure:$source")
            } else {
                DiagLog.event(context, TAG, "ensureScheduled: alarm present — no-op (source=$source)")
            }
        }

        fun cancel(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                ?: return
            pendingIntent(
                context, 0,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            )?.let { alarmManager.cancel(it) }
            pendingIntentBackup(
                context, 0,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            )?.let { alarmManager.cancel(it) }
            DiagLog.event(context, TAG, "cancelled")
        }
    }
}
